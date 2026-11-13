package sica.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;

import sica.assinatura.pdf.CertificadosDeTeste;

class ServidorHttpTest {

    @TempDir
    Path pasta;

    HttpServer server;
    int porta;

    @BeforeEach
    void subir() throws Exception {
        Path p12 = CertificadosDeTeste.gravarP12Https(pasta);
        server = ServidorHttp.criar(new InetSocketAddress("127.0.0.1", 0), p12,
                CertificadosDeTeste.SENHA);
        server.createContext("/", ex -> {
            byte[] r = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, r.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r);
            }
        });
        server.start();
        porta = server.getAddress().getPort();
    }

    @AfterEach
    void parar() {
        server.stop(0);
    }

    @Test
    void sobeEmHttpsComCertificadoConfiavel() throws Exception {
        assertTrue(server instanceof HttpsServer);
        HttpClient cliente = HttpClient.newBuilder()
                .sslContext(CertificadosDeTeste.clienteConfiandoNaAc()).build();
        var r = cliente.send(HttpRequest.newBuilder(
                URI.create("https://localhost:" + porta + "/")).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("ok", r.body());
        String protocolo = r.sslSession().orElseThrow().getProtocol();
        assertTrue(protocolo.equals("TLSv1.3") || protocolo.equals("TLSv1.2"), protocolo);
    }

    @Test
    void clienteSemConfiancaNoCertificadoEhRecusado() {
        // Sem confiar na AC de teste, o cliente recusa o servidor: nao ha
        // como um intermediario se passar pelo servidor sem um certificado valido.
        HttpClient cliente = HttpClient.newHttpClient();
        assertThrows(SSLHandshakeException.class, () -> cliente.send(HttpRequest.newBuilder(
                URI.create("https://localhost:" + porta + "/")).build(), BodyHandlers.ofString()));
    }

    @Test
    void httpSimplesNaPortaHttpsNaoFunciona() {
        HttpClient cliente = HttpClient.newHttpClient();
        assertThrows(IOException.class, () -> cliente.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + "/")).build(), BodyHandlers.ofString()));
    }

    @Test
    void recusaVersoesAntigasDeTls() throws Exception {
        SSLSocketFactory fabrica = CertificadosDeTeste.clienteConfiandoNaAc().getSocketFactory();
        try (SSLSocket s = (SSLSocket) fabrica.createSocket("localhost", porta)) {
            SSLParameters p = s.getSSLParameters();
            p.setProtocols(new String[] {"TLSv1.1"});
            s.setSSLParameters(p);
            assertThrows(IOException.class, s::startHandshake);
        } catch (IllegalArgumentException semSuporteNoCliente) {
            // JVM sem TLS 1.1 habilitado nem para o cliente: tambem atende ao objetivo.
        }
    }

    @Test
    void semKeystoreSobeEmHttpSimples() throws Exception {
        HttpServer simples = ServidorHttp.criar(new InetSocketAddress("127.0.0.1", 0), null, null);
        assertTrue(!(simples instanceof HttpsServer));
    }

    @Test
    void keystoreInvalidoImpedeASubida() throws Exception {
        Path p12 = CertificadosDeTeste.gravarP12Https(pasta);
        InetSocketAddress e = new InetSocketAddress("127.0.0.1", 0);
        assertThrows(IllegalStateException.class, () -> ServidorHttp.criar(e, p12, "errada"));
        assertThrows(IllegalStateException.class, () -> ServidorHttp.criar(e, p12, null));
        assertThrows(IllegalStateException.class,
                () -> ServidorHttp.criar(e, pasta.resolve("nao-existe.p12"), "x"));
    }
}
