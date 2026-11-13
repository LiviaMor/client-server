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
import java.nio.file.Files;
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
import sica.assinatura.pdf.CertificadosDeTeste;
import sica.assinatura.pdf.TsaFalsa;
import sica.assinatura.pdf.VerificadorPdf;

/** Fluxo HTTP completo com certificado (.p12) e TSA configurados. */
class FinalizacaoApiTest {

    static final String CHAVE = "chave";
    static final String BOUNDARY = "----F";

    @TempDir
    Path pasta;

    TsaFalsa tsa;
    HttpServer server;
    HttpClient http = HttpClient.newHttpClient();
    EmailFalso email = new EmailFalso();
    String base;
    String id;
    String sha256;
    String token;

    @BeforeEach
    void subir() throws Exception {
        tsa = new TsaFalsa();
        Path p12 = CertificadosDeTeste.gravarP12(pasta);
        Configuracao cfg = new Configuracao(Map.of(
                "SICA_DB_PATH", pasta.resolve("api.db").toString(),
                "SICA_STORAGE_DIR", pasta.resolve("storage").toString(),
                "SICA_SERVER_FILES_DIR", pasta.resolve("server-files").toString(),
                "ADMIN_KEY", CHAVE,
                "SIGNING_CERT_PATH", p12.toString(),
                "SIGNING_CERT_PASSWORD", CertificadosDeTeste.SENHA,
                "TSA_URL", tsa.url()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AssinaturaApi.criar(cfg, email, 1000, 100).registrar(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        HttpResponse<String> env = admin("POST", "/envelopes",
                "multipart/form-data; boundary=" + BOUNDARY,
                MultipartTest.corpo(BOUNDARY, "Contrato", "a.pdf", PdfsDeTeste.simples("Contrato")));
        id = campo(env.body(), "id");
        sha256 = campo(env.body(), "sha256Original");
        admin("POST", "/envelopes/" + id + "/signers", "application/x-www-form-urlencoded",
                ("nome=Ana+Lima&email=ana%40e.com&pagina=1&x=72&y=100&largura=200&altura=60")
                        .getBytes(StandardCharsets.UTF_8));
        admin("POST", "/envelopes/" + id + "/send", "application/x-www-form-urlencoded", new byte[0]);
        token = EmailFalso.token(email.enviadas.get(0));
    }

    @AfterEach
    void parar() {
        server.stop(0);
        tsa.close();
    }

    private HttpResponse<String> admin(String metodo, String caminho, String tipo, byte[] corpo)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + caminho))
                .header("X-Admin-Key", CHAVE).header("Content-Type", tipo)
                .method(metodo, BodyPublishers.ofByteArray(corpo)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> get(String caminho, boolean comChave) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho)).GET();
        if (comChave) {
            b.header("X-Admin-Key", CHAVE);
        }
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    private static String campo(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\":(?:\"([^\"]*)\"|null)").matcher(json);
        assertTrue(m.find(), "campo ausente: " + nome + " em " + json);
        return m.group(1);
    }

    private HttpResponse<String> assinar() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] c : new String[][] {{"nome", "Ana Lima"}, {"sha256", sha256}, {"aceite", "true"}}) {
            out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + c[0]
                    + "\"\r\n\r\n" + c[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"assinatura\";"
                + " filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(ImagensDeTeste.assinatura(600, 200));
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base + "/sign/" + token))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(out.toByteArray())).build(), BodyHandlers.ofString());
    }

    @Test
    void ultimaAssinaturaFinalizaAutomaticamente() throws Exception {
        assertEquals(409, get("/envelopes/" + id + "/download", true).statusCode());
        assertEquals(409, get("/sign/" + token + "/final", false).statusCode());

        HttpResponse<String> r = assinar();
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"finalizado\":true"), r.body());

        HttpResponse<String> env = admin("GET", "/envelopes/" + id, "text/plain", new byte[0]);
        assertEquals("COMPLETED", campo(env.body(), "status"));
        String hashFinal = campo(env.body(), "sha256Final");

        HttpResponse<byte[]> download = get("/envelopes/" + id + "/download", true);
        assertEquals(200, download.statusCode());
        assertEquals("application/pdf", download.headers().firstValue("Content-Type").orElse(null));
        assertEquals(hashFinal, sica.assinatura.seguranca.Hashes.sha256Hex(download.body()));
        assertTrue(VerificadorPdf.verificar(download.body()).get(0).integra());

        HttpResponse<byte[]> doSignatario = get("/sign/" + token + "/final", false);
        assertEquals(200, doSignatario.statusCode());
        assertArrayEquals(download.body(), doSignatario.body());

        String audit = new String(get("/envelopes/" + id + "/audit", true).body(), StandardCharsets.UTF_8);
        assertTrue(audit.contains("\"tipo\":\"envelope.completed\""), audit);
        assertTrue(audit.contains("\"tipo\":\"signer.signed\""), audit);

        // Publicado na pasta do servidor TCP do SiCA.
        try (var arquivos = Files.list(pasta.resolve("server-files"))) {
            assertEquals(1, arquivos.filter(p -> p.toString().endsWith("-assinado.pdf")).count());
        }

        // Repetir a finalizacao e seguro (idempotente).
        assertEquals(200, admin("POST", "/envelopes/" + id + "/finalize", "text/plain", new byte[0])
                .statusCode());
    }

    @Test
    void tsaForaDoArNaoPerdeAAssinaturaEPermiteRepetir() throws Exception {
        tsa.falhar.set(true);
        HttpResponse<String> r = assinar();
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\":\"SIGNED\""));
        assertTrue(r.body().contains("\"finalizado\":false"), r.body());

        assertEquals(503, admin("POST", "/envelopes/" + id + "/finalize", "text/plain", new byte[0])
                .statusCode());

        tsa.falhar.set(false);
        HttpResponse<String> f = admin("POST", "/envelopes/" + id + "/finalize", "text/plain", new byte[0]);
        assertEquals(200, f.statusCode(), f.body());
        assertEquals("COMPLETED", campo(f.body(), "status"));
    }

    @Test
    void rotasNovasExigemChave() throws Exception {
        assertEquals(401, get("/envelopes/" + id + "/download", false).statusCode());
        assertEquals(401, get("/envelopes/" + id + "/audit", false).statusCode());
        assertEquals(405, get("/envelopes/" + id + "/finalize", true).statusCode());
    }
}
