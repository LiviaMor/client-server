package sica.assinatura.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import sica.assinatura.PdfsDeTeste;
import sica.assinatura.config.Configuracao;
import sica.assinatura.email.EmailFalso;

class AssinaturaApiTest {

    static final String CHAVE = "chave-de-teste";
    static final String BOUNDARY = "----TesteBoundary";

    @TempDir
    Path pasta;

    HttpServer server;
    EmailFalso email;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void subir() throws Exception {
        Configuracao cfg = new Configuracao(Map.of(
                "SICA_DB_PATH", pasta.resolve("api.db").toString(),
                "SICA_STORAGE_DIR", pasta.resolve("storage").toString(),
                "ADMIN_KEY", CHAVE,
                "MAX_PDF_BYTES", "200000"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        email = new EmailFalso();
        AssinaturaApi.criar(cfg, email).registrar(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void parar() {
        server.stop(0);
    }

    private HttpResponse<String> postar(String chave, byte[] corpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/envelopes"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("User-Agent", "JUnit-API")
                .POST(BodyPublishers.ofByteArray(corpo));
        if (chave != null) {
            b.header("X-Admin-Key", chave);
        }
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> obter(String caminho, String chave) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho)).GET();
        if (chave != null) {
            b.header("X-Admin-Key", chave);
        }
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private static String campo(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\":(?:\"([^\"]*)\"|null)").matcher(json);
        assertTrue(m.find(), "campo ausente: " + nome + " em " + json);
        return m.group(1);
    }

    @Test
    void criaEConsultaEnvelopeComHashCorreto() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("Contrato via API");
        String esperado = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));

        HttpResponse<String> r = postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "Meu contrato", "qualquer.pdf", pdf));

        assertEquals(201, r.statusCode(), r.body());
        assertEquals(esperado, campo(r.body(), "sha256Original"));
        assertEquals("DRAFT", campo(r.body(), "status"));
        String id = campo(r.body(), "id");
        assertEquals("/envelopes/" + id, r.headers().firstValue("Location").orElse(null));

        HttpResponse<String> g = obter("/envelopes/" + id, CHAVE);
        assertEquals(200, g.statusCode());
        assertEquals("Meu contrato", campo(g.body(), "titulo"));
        assertEquals(esperado, campo(g.body(), "sha256Original"));

        // O original em disco e identico ao enviado.
        byte[] salvo = Files.readAllBytes(pasta.resolve("storage").resolve(id).resolve("original.pdf"));
        assertEquals(esperado, HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(salvo)));
    }

    @Test
    void nomeDoArquivoEnviadoNaoInfluenciaOCaminho() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("x");
        HttpResponse<String> r = postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "t", "../../../etc/passwd", pdf));
        assertEquals(201, r.statusCode(), r.body());
        String id = campo(r.body(), "id");
        assertTrue(Files.exists(pasta.resolve("storage").resolve(id).resolve("original.pdf")));
        assertFalse(Files.exists(pasta.resolve("etc")));
    }

    @Test
    void semChaveOuComChaveErradaRetorna401() throws Exception {
        byte[] corpo = MultipartTest.corpo(BOUNDARY, "t", "a.pdf", PdfsDeTeste.simples("x"));
        assertEquals(401, postar(null, corpo).statusCode());
        assertEquals(401, postar("errada", corpo).statusCode());
        assertEquals(401, obter("/envelopes/00000000-0000-0000-0000-000000000000", null).statusCode());
        assertEquals(401, obter("/envelopes/00000000-0000-0000-0000-000000000000", "errada").statusCode());
    }

    @Test
    void naoPdfRetorna400() throws Exception {
        HttpResponse<String> r = postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "t", "a.pdf", "isto nao e pdf".getBytes()));
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("\"erro\""));
    }

    @Test
    void camposAusentesRetornam400() throws Exception {
        assertEquals(400, postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, null, "a.pdf", PdfsDeTeste.simples("x"))).statusCode());
        assertEquals(400, postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "titulo", null, null)).statusCode());
    }

    @Test
    void corpoNaoMultipartRetorna400() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/envelopes"))
                .header("X-Admin-Key", CHAVE)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{}")).build();
        assertEquals(400, http.send(req, BodyHandlers.ofString()).statusCode());
    }

    @Test
    void corpoAcimaDoLimiteRetorna413() throws Exception {
        byte[] enorme = new byte[400_000];
        System.arraycopy("%PDF-".getBytes(), 0, enorme, 0, 5);
        HttpResponse<String> r = postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "t", "a.pdf", enorme));
        assertEquals(413, r.statusCode());
    }

    @Test
    void envelopeInexistenteRetorna404() throws Exception {
        assertEquals(404,
                obter("/envelopes/00000000-0000-0000-0000-000000000000", CHAVE).statusCode());
    }

    @Test
    void idMalFormadoOuRotaDesconhecidaRetorna404() throws Exception {
        assertEquals(404, obter("/envelopes/nao-e-uuid", CHAVE).statusCode());
        assertEquals(404, obter("/envelopes/../x", CHAVE).statusCode());
        assertEquals(404, obter("/envelopesfoo", CHAVE).statusCode());
    }

    @Test
    void metodoIncorretoRetorna405() throws Exception {
        assertEquals(405, obter("/envelopes", CHAVE).statusCode());
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base + "/envelopes/00000000-0000-0000-0000-000000000000"))
                .header("X-Admin-Key", CHAVE)
                .DELETE().build();
        assertEquals(405, http.send(req, BodyHandlers.ofString()).statusCode());
    }

    @Test
    void semAdminKeyOModuloNaoSobe() {
        Configuracao semChave = new Configuracao(Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> AssinaturaApi.criar(semChave));
    }

    // ----- Etapa 2: signatarios e envio -----

    private String criarEnvelope() throws Exception {
        HttpResponse<String> r = postar(CHAVE,
                MultipartTest.corpo(BOUNDARY, "Contrato", "a.pdf", PdfsDeTeste.simples("x")));
        assertEquals(201, r.statusCode(), r.body());
        return campo(r.body(), "id");
    }

    private HttpResponse<String> form(String caminho, String chave, String corpo)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(corpo));
        if (chave != null) {
            b.header("X-Admin-Key", chave);
        }
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private static final String SIGNATARIO =
            "nome=Fulano+de+Tal&email=Fulano%40Exemplo.com&pagina=1&x=72&y=100&largura=200&altura=60";

    @Test
    void adicionaSignatarioEEnviaConvite() throws Exception {
        String id = criarEnvelope();

        HttpResponse<String> s = form("/envelopes/" + id + "/signers", CHAVE, SIGNATARIO);
        assertEquals(201, s.statusCode(), s.body());
        assertEquals("fulano@exemplo.com", campo(s.body(), "email"));
        assertEquals("PENDING", campo(s.body(), "status"));
        assertTrue(s.body().contains("\"campo\":{\"pagina\":1,\"x\":72,\"y\":100,"
                + "\"largura\":200,\"altura\":60}"), s.body());

        HttpResponse<String> env = form("/envelopes/" + id + "/send", CHAVE, "");
        assertEquals(200, env.statusCode(), env.body());
        assertEquals("SENT", campo(env.body(), "status"));
        assertTrue(env.body().contains("\"todosEnviados\":true"), env.body());
        assertEquals(1, email.enviadas.size());
        String token = EmailFalso.token(email.enviadas.get(0));

        HttpResponse<String> g = obter("/envelopes/" + id, CHAVE);
        assertEquals(200, g.statusCode());
        assertTrue(g.body().contains("\"signatarios\":[{"), g.body());
        assertTrue(g.body().contains("\"tokenExpiraEm\":\""), g.body());
        // Nem o token nem seu hash aparecem na API.
        assertFalse(g.body().contains(token));
        assertFalse(g.body().contains(sica.assinatura.seguranca.TokenServico.hash(token)));
        assertFalse(g.body().contains("token_hash") || g.body().contains("tokenHash"));
    }

    @Test
    void errosDeSignatarioTemCodigosCorretos() throws Exception {
        String id = criarEnvelope();
        String rota = "/envelopes/" + id + "/signers";

        assertEquals(401, form(rota, null, SIGNATARIO).statusCode());
        assertEquals(400, form(rota, CHAVE, "nome=A&email=a%40e.com").statusCode());
        assertEquals(400, form(rota, CHAVE, SIGNATARIO.replace("x=72", "x=abc")).statusCode());
        assertEquals(400, form(rota, CHAVE, SIGNATARIO.replace("pagina=1", "pagina=9")).statusCode());
        assertEquals(400, form(rota, CHAVE, "nome=%ZZ").statusCode());
        assertEquals(201, form(rota, CHAVE, SIGNATARIO).statusCode());
        assertEquals(409, form(rota, CHAVE, SIGNATARIO).statusCode());
        assertEquals(405, obter(rota, CHAVE).statusCode());
        assertEquals(404, form("/envelopes/00000000-0000-0000-0000-000000000000/signers",
                CHAVE, SIGNATARIO).statusCode());
        assertEquals(404, form("/envelopes/" + id + "/outra", CHAVE, "").statusCode());
    }

    @Test
    void aceitaSignatarioViaMultipart() throws Exception {
        String id = criarEnvelope();
        String b = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"";
        StringBuilder corpo = new StringBuilder();
        String[][] campos = {{"nome", "Ana"}, {"email", "ana@e.com"}, {"pagina", "1"},
                {"x", "10.5"}, {"y", "10"}, {"largura", "100"}, {"altura", "40"}};
        for (String[] c : campos) {
            corpo.append(b).append(c[0]).append("\"\r\n\r\n").append(c[1]).append("\r\n");
        }
        corpo.append("--").append(BOUNDARY).append("--\r\n");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/envelopes/" + id + "/signers"))
                .header("X-Admin-Key", CHAVE)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofString(corpo.toString())).build(), BodyHandlers.ofString());
        assertEquals(201, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"x\":10.5"), r.body());
    }

    @Test
    void envioSemSignatariosRetorna409() throws Exception {
        String id = criarEnvelope();
        assertEquals(409, form("/envelopes/" + id + "/send", CHAVE, "").statusCode());
        assertEquals(405, obter("/envelopes/" + id + "/send", CHAVE).statusCode());
        assertEquals(401, form("/envelopes/" + id + "/send", null, "").statusCode());
    }

    @Test
    void falhaDeEmailApareceNaRespostaDoEnvio() throws Exception {
        String id = criarEnvelope();
        form("/envelopes/" + id + "/signers", CHAVE, SIGNATARIO);
        email.falharPara.add("fulano@exemplo.com");

        HttpResponse<String> r = form("/envelopes/" + id + "/send", CHAVE, "");

        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"todosEnviados\":false"), r.body());
        assertTrue(r.body().contains("\"enviado\":false"), r.body());
    }
}
