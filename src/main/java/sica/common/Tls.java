package sica.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Cria sockets TCP protegidos por TLS para o SiCA.
 *
 * <p>Papeis:</p>
 * <ul>
 *   <li><b>Servidor:</b> apresenta seu certificado (keystore PKCS#12, o mesmo
 *       {@code https.p12} do gateway web) para provar sua identidade.</li>
 *   <li><b>Cliente:</b> so confia em servidores cujo certificado foi emitido pela
 *       AC informada (a AC de teste do projeto) e confere se o nome do servidor
 *       bate com o certificado.</li>
 * </ul>
 *
 * <p>Depois do handshake o protocolo do SiCA continua igual: o TLS fica por
 * baixo dos streams, cifrando e protegendo contra alteracao tudo o que passa.</p>
 *
 * <p>Variaveis de ambiente: {@code HTTPS_KEYSTORE_PATH} e
 * {@code HTTPS_KEYSTORE_PASSWORD} (servidor) e {@code SICA_TLS_CA}
 * (cliente; padrao {@code certs/ac-teste.crt}).</p>
 */
public final class Tls {

    private static final String[] PROTOCOLOS = {"TLSv1.3", "TLSv1.2"};
    private static final String CA_PADRAO = "certs/ac-teste.crt";

    private Tls() {
    }

    /** Abre o socket de escuta TLS usando as variaveis de ambiente. */
    public static ServerSocket criarServidor(int porta) throws IOException {
        String caminho = System.getenv("HTTPS_KEYSTORE_PATH");
        String senha = System.getenv("HTTPS_KEYSTORE_PASSWORD");
        if (caminho == null || caminho.isBlank() || senha == null) {
            throw new IOException("TLS obrigatorio: defina HTTPS_KEYSTORE_PATH e "
                    + "HTTPS_KEYSTORE_PASSWORD (rode 'source .env').");
        }
        return criarServidor(porta, Paths.get(caminho), senha);
    }

    /** Abre o socket de escuta TLS com o keystore informado. */
    public static ServerSocket criarServidor(int porta, Path keystore, String senha)
            throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, senha.toCharArray());
            }
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, senha.toCharArray());

            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(kmf.getKeyManagers(), null, null);

            SSLServerSocket servidor = (SSLServerSocket)
                    contexto.getServerSocketFactory().createServerSocket(porta);
            servidor.setEnabledProtocols(PROTOCOLOS);
            return servidor;
        } catch (GeneralSecurityException e) {
            throw new IOException("Falha ao configurar o TLS do servidor: " + e.getMessage(), e);
        }
    }

    /** Conecta ao servidor por TLS, confiando na AC indicada em {@code SICA_TLS_CA}. */
    public static Socket conectar(String host, int porta) throws IOException {
        String ca = System.getenv("SICA_TLS_CA");
        Path caminho = Paths.get(ca == null || ca.isBlank() ? CA_PADRAO : ca);
        return conectar(host, porta, caminho);
    }

    /** Conecta ao servidor por TLS, confiando apenas na AC do arquivo informado. */
    public static Socket conectar(String host, int porta, Path certificadoAc)
            throws IOException {
        try {
            KeyStore confiaveis = KeyStore.getInstance(KeyStore.getDefaultType());
            confiaveis.load(null, null);
            try (InputStream in = Files.newInputStream(certificadoAc)) {
                Certificate ac = CertificateFactory.getInstance("X.509").generateCertificate(in);
                confiaveis.setCertificateEntry("ac-sica", ac);
            }
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(confiaveis);

            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, tmf.getTrustManagers(), null);

            SSLSocket socket = (SSLSocket) contexto.getSocketFactory().createSocket(host, porta);
            SSLParameters parametros = socket.getSSLParameters();
            parametros.setProtocols(PROTOCOLOS);
            // Confere se o nome do servidor (host) consta no certificado.
            parametros.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parametros);
            socket.startHandshake();
            return socket;
        } catch (GeneralSecurityException e) {
            throw new IOException("Falha ao configurar o TLS do cliente: " + e.getMessage(), e);
        }
    }
}
