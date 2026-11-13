package sica.assinatura.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import sica.assinatura.ImagensDeTeste;
import sica.assinatura.PdfsDeTeste;
import sica.assinatura.config.Configuracao;
import sica.assinatura.email.EmailFalso;

class SignatarioApiTest {

    static final String CHAVE = "chave";
    static final String BOUNDARY = "----B";

    @TempDir
    Path pasta;

    HttpServer server;
    HttpClient http = HttpClient.newHttpClient();
    EmailFalso email = new EmailFalso();
    String base;
    byte[] pdf;
    String sha256;
    String token;

    @BeforeEach
    void subir() throws Exception {
        Configuracao cfg = new Configuracao(Map.of(
                "SICA_DB_PATH", pasta.resolve("api.db").toString(),
                "SICA_STORAGE_DIR", pasta.resolve("storage").toString(),
                "ADMIN_KEY", CHAVE));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AssinaturaApi.criar(cfg, email, 1000, 6).registrar(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        pdf = PdfsDeTeste.simples("Contrato");
        HttpResponse<String> env = http.send(HttpRequest.newBuilder(URI.create(base + "/envelopes"))
                .header("X-Admin-Key", CHAVE)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(MultipartTest.corpo(BOUNDARY, "Contrato", "a.pdf", pdf)))
                .build(), BodyHandlers.ofString());
        String id = campo(env.body(), "id");
        sha256 = campo(env.body(), "sha256Original");
        http.send(HttpRequest.newBuilder(URI.create(base + "/envelopes/" + id + "/signers"))
                .header("X-Admin-Key", CHAVE)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString("nome=Fulano+de+Tal&email=f%40e.com&pagina=1"
                        + "&x=72&y=100&largura=200&altura=60")).build(), BodyHandlers.ofString());
        http.send(HttpRequest.newBuilder(URI.create(base + "/envelopes/" + id + "/send"))
                .header("X-Admin-Key", CHAVE).POST(BodyPublishers.noBody()).build(),
                BodyHandlers.ofString());
        token = EmailFalso.token(email.enviadas.get(0));
    }

    @AfterEach
    void parar() {
        server.stop(0);
    }

    private static String campo(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\":(?:\"([^\"]*)\"|null)").matcher(json);
        assertTrue(m.find(), "campo ausente: " + nome + " em " + json);
        return m.group(1);
    }

    private HttpResponse<byte[]> get(String caminho) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + caminho)).GET().build(),
                BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> assinar(String nome, String hash, byte[] png) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[][] campos = {{"nome", nome}, {"sha256", hash}, {"aceite", "true"}};
        for (String[] c : campos) {
            out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + c[0]
                    + "\"\r\n\r\n" + c[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"assinatura\";"
                + " filename=\"assinatura.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base + "/sign/" + token))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("User-Agent", "Teste/1.0")
                .POST(BodyPublishers.ofByteArray(out.toByteArray())).build(),
                BodyHandlers.ofString());
    }

    @Test
    void paginaDeAssinaturaTemCabecalhosDeSeguranca() throws Exception {
        HttpResponse<byte[]> r = get("/assinar/" + token);

        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String csp = r.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("frame-ancestors 'none'") && csp.contains("script-src 'self'"));
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(null));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("/assinar/app.js"));

        assertEquals(200, get("/assinar/app.js").statusCode());
        assertEquals(200, get("/assinar/estilo.css").statusCode());
        assertEquals(404, get("/assinar/invalido").statusCode());
    }

    @Test
    void fluxoCompletoDeAssinatura() throws Exception {
        HttpResponse<byte[]> c = get("/sign/" + token);
        assertEquals(200, c.statusCode());
        String json = new String(c.body(), StandardCharsets.UTF_8);
        assertEquals(sha256, campo(json, "sha256"));
        assertEquals("SENT", campo(json, "status"), "primeiro status e o do envelope");
        assertTrue(json.contains("\"signatario\":{\"nome\":\"Fulano de Tal\",\"email\":\"f@e.com\","
                + "\"status\":\"VIEWED\""), json);
        assertTrue(json.contains("\"podeAssinar\":true"), json);
        assertTrue(json.contains("\"paginas\":[{\"largura\":612,\"altura\":792}]"), json);
        assertTrue(!json.contains(token), "token nao volta na resposta");

        HttpResponse<byte[]> doc = get("/sign/" + token + "/documento");
        assertEquals(200, doc.statusCode());
        assertEquals("application/pdf", doc.headers().firstValue("Content-Type").orElse(null));
        assertArrayEquals(pdf, doc.body());

        HttpResponse<byte[]> pag = get("/sign/" + token + "/paginas/1");
        assertEquals(200, pag.statusCode());
        assertEquals("image/png", pag.headers().firstValue("Content-Type").orElse(null));
        assertEquals(404, get("/sign/" + token + "/paginas/2").statusCode());

        HttpResponse<String> a = assinar("Fulano de Tal", sha256, ImagensDeTeste.assinatura(600, 200));
        assertEquals(200, a.statusCode(), a.body());
        assertTrue(a.body().contains("\"status\":\"SIGNED\""), a.body());
        assertTrue(a.body().contains("\"todosAssinaram\":true"), a.body());

        HttpResponse<String> deNovo = assinar("Fulano de Tal", sha256,
                ImagensDeTeste.assinatura(600, 200));
        assertEquals(409, deNovo.statusCode());

        String depois = new String(get("/sign/" + token).body(), StandardCharsets.UTF_8);
        assertTrue(depois.contains("\"podeAssinar\":false"), depois);
    }

    @Test
    void errosDaAssinaturaTemCodigosCorretos() throws Exception {
        assertEquals(400, assinar("Outro Nome", sha256, ImagensDeTeste.assinatura(600, 200)).statusCode());
        assertEquals(409, assinar("Fulano de Tal", "f".repeat(64), ImagensDeTeste.assinatura(600, 200)).statusCode());
        assertEquals(400, assinar("Fulano de Tal", sha256, ImagensDeTeste.vazia(600, 200)).statusCode());
        assertEquals(404, get("/sign/" + "A".repeat(43)).statusCode());
        assertEquals(404, get("/sign/curto").statusCode());
        assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(base + "/sign/" + token + "/documento"))
                .POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString()).statusCode());
    }

    @Test
    void recusaPelaApi() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/sign/" + token + "/decline"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString("motivo=Clausula+3+incorreta")).build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\":\"DECLINED\""));
    }

    @Test
    void limitaRequisicoesDeEscritaPorIp() throws Exception {
        int status = 0;
        for (int i = 0; i < 7; i++) {
            status = assinar("Nome Errado", sha256, ImagensDeTeste.assinatura(600, 200)).statusCode();
        }
        assertEquals(429, status);
    }

    @Test
    void rotasDoEmissorContinuamProtegidas() throws Exception {
        assertEquals(401, get("/envelopes/00000000-0000-0000-0000-000000000000").statusCode());
    }
}
