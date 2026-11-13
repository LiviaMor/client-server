package sica.assinatura.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import sica.assinatura.config.Configuracao;

class ResendEmailSenderTest {

    HttpServer server;
    URI endpoint;
    final AtomicReference<String> corpo = new AtomicReference<>();
    final AtomicReference<String> autorizacao = new AtomicReference<>();
    final AtomicReference<String> tipo = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> resposta = new AtomicReference<>("{\"id\":\"abc\"}");

    @BeforeEach
    void subir() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", ex -> {
            corpo.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacao.set(ex.getRequestHeaders().getFirst("Authorization"));
            tipo.set(ex.getRequestHeaders().getFirst("Content-Type"));
            byte[] r = resposta.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), r.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r);
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
    }

    @AfterEach
    void parar() {
        server.stop(0);
    }

    @Test
    void enviaRequisicaoNoFormatoDaApi() throws Exception {
        ResendEmailSender s = new ResendEmailSender("re_teste", "SiCA <a@b.dev>", endpoint);

        s.enviar(new Mensagem("fulano@exemplo.com", "Assunto \"x\"", "texto\nlinha", "<p>oi</p>"));

        assertEquals("Bearer re_teste", autorizacao.get());
        assertTrue(tipo.get().startsWith("application/json"));
        String json = corpo.get();
        assertTrue(json.contains("\"from\":\"SiCA <a@b.dev>\""), json);
        assertTrue(json.contains("\"to\":[\"fulano@exemplo.com\"]"), json);
        assertTrue(json.contains("\"subject\":\"Assunto \\\"x\\\"\""), json);
        assertTrue(json.contains("\"text\":\"texto\\nlinha\""), json);
        assertTrue(json.contains("\"html\":\"<p>oi</p>\""), json);
    }

    @Test
    void respostaDeErroViraEmailExceptionComStatus() {
        status.set(403);
        resposta.set("{\"message\":\"You can only send testing emails to your own email address\"}");
        ResendEmailSender s = new ResendEmailSender("re_teste", "a@b.dev", endpoint);

        EmailException e = assertThrows(EmailException.class,
                () -> s.enviar(new Mensagem("x@y.com", "a", "b", "c")));
        assertTrue(e.getMessage().contains("403"));
        assertTrue(e.getMessage().contains("own email address"));
    }

    @Test
    void falhaDeRedeViraEmailException() {
        server.stop(0);
        ResendEmailSender s = new ResendEmailSender("re_teste", "a@b.dev", endpoint);
        assertThrows(EmailException.class,
                () -> s.enviar(new Mensagem("x@y.com", "a", "b", "c")));
    }

    @Test
    void exigeApiKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResendEmailSender(" ", "a@b.dev", endpoint));
    }

    @Test
    void fabricaEscolheProvedorPelaConfiguracao() {
        assertTrue(EmailSenders.criar(new Configuracao(Map.of())) instanceof ConsoleEmailSender);
        assertTrue(EmailSenders.criar(new Configuracao(Map.of(
                "EMAIL_PROVIDER", "resend", "RESEND_API_KEY", "re_x"))) instanceof ResendEmailSender);
        assertThrows(IllegalStateException.class,
                () -> EmailSenders.criar(new Configuracao(Map.of("EMAIL_PROVIDER", "resend"))));
    }

    @Test
    void mensagemNaoExpoeCorpoNoToString() {
        Mensagem m = new Mensagem("x@y.com", "a", "link secreto", "link secreto");
        assertTrue(!m.toString().contains("secreto"));
    }
}
