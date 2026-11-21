package sica.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sica.assinatura.pdf.CertificadosDeTeste;

/** Testa o TLS do canal TCP do SiCA: cifra, valida o servidor e recusa texto puro. */
class TlsTest {

    @TempDir
    Path pasta;

    ServerSocket servidor;
    Path acConfiavel;

    @BeforeEach
    void subirServidor() throws Exception {
        Path p12 = CertificadosDeTeste.gravarP12Https(pasta);
        acConfiavel = gravarPem(pasta.resolve("ac.crt"), CertificadosDeTeste.AC);
        servidor = Tls.criarServidor(0, p12, CertificadosDeTeste.SENHA);

        // Servidor de eco: le uma string UTF e devolve "eco:" + string.
        Thread t = new Thread(() -> {
            while (!servidor.isClosed()) {
                try (Socket s = servidor.accept();
                     DataInputStream in = new DataInputStream(s.getInputStream());
                     DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                    out.writeUTF("eco:" + in.readUTF());
                    out.flush();
                } catch (IOException ignorado) {
                    // handshake recusado ou servidor fechado
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void fechar() throws IOException {
        servidor.close();
    }

    @Test
    void clienteConfiavelConversaPorTlsModerno() throws Exception {
        try (Socket s = Tls.conectar("localhost", servidor.getLocalPort(), acConfiavel)) {
            String protocolo = ((SSLSocket) s).getSession().getProtocol();
            assertTrue(protocolo.equals("TLSv1.3") || protocolo.equals("TLSv1.2"), protocolo);

            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.writeUTF("oi");
            out.flush();
            assertEquals("eco:oi", in.readUTF());
        }
    }

    @Test
    void clienteRecusaServidorDeOutraAc() throws Exception {
        // Confia numa AC diferente da que emitiu o certificado do servidor.
        Path outraAc = gravarPem(pasta.resolve("outra.crt"), CertificadosDeTeste.TSA);
        assertThrows(IOException.class,
                () -> Tls.conectar("localhost", servidor.getLocalPort(), outraAc));
    }

    @Test
    void clienteRecusaNomeDeHostDiferenteDoCertificado() {
        // O certificado vale para localhost/127.0.0.1, nao para este nome.
        assertThrows(IOException.class,
                () -> Tls.conectar("127.0.0.2", servidor.getLocalPort(), acConfiavel));
    }

    @Test
    void conexaoEmTextoPuroNaoFunciona() throws Exception {
        try (Socket s = new Socket("localhost", servidor.getLocalPort())) {
            s.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeUTF("oi");
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            // O servidor responde com alerta TLS ou fecha: nunca com "eco:oi".
            String resposta = null;
            try {
                resposta = in.readUTF();
            } catch (IOException esperado) {
                // conexao encerrada pelo servidor
            }
            assertTrue(resposta == null || !resposta.equals("eco:oi"));
        }
    }

    private static Path gravarPem(Path destino, X509Certificate cert) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded());
        Files.writeString(destino,
                "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n");
        return destino;
    }
}
