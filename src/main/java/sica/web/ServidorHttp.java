package sica.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/**
 * Cria o servidor HTTP do gateway, com ou sem TLS.
 *
 * <p>Com um keystore PKCS#12 configurado, o gateway sobe em <b>HTTPS</b>
 * ({@link HttpsServer}, do proprio JDK), aceitando apenas TLS 1.3 e TLS 1.2.
 * Assim toda a comunicacao entre o navegador (cliente) e o servidor e
 * cifrada e autenticada pelo certificado do servidor.</p>
 *
 * <p>Qualquer certificado em PKCS#12 serve: o gerado por
 * {@code scripts/gerar-certificado-https.sh}, um gerado pelo {@code mkcert}
 * (confiavel no navegador local) ou um certificado real.</p>
 */
public final class ServidorHttp {

    /** Versoes de TLS aceitas (TLS 1.0/1.1 sao inseguras e ficam de fora). */
    static final String[] PROTOCOLOS = {"TLSv1.3", "TLSv1.2"};

    private ServidorHttp() {
    }

    /**
     * Cria (sem iniciar) o servidor na porta indicada.
     *
     * @param keystore keystore PKCS#12 do certificado, ou {@code null} para HTTP simples
     * @param senha    senha do keystore
     * @throws IllegalStateException se o keystore nao puder ser carregado
     */
    public static HttpServer criar(InetSocketAddress endereco, Path keystore, String senha)
            throws IOException {
        if (keystore == null) {
            return HttpServer.create(endereco, 0);
        }
        SSLContext contexto = contextoTls(keystore, senha);
        HttpsServer servidor = HttpsServer.create(endereco, 0);
        servidor.setHttpsConfigurator(new HttpsConfigurator(contexto) {
            @Override
            public void configure(HttpsParameters parametros) {
                SSLParameters p = contexto.getDefaultSSLParameters();
                p.setProtocols(PROTOCOLOS);
                parametros.setSSLParameters(p);
            }
        });
        return servidor;
    }

    /** Monta o contexto TLS a partir do keystore PKCS#12. */
    static SSLContext contextoTls(Path keystore, String senha) {
        if (senha == null) {
            throw new IllegalStateException("HTTPS_KEYSTORE_PASSWORD nao configurada.");
        }
        char[] s = senha.toCharArray();
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, s);
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, s);
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(kmf.getKeyManagers(), null, null);
            return contexto;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao carregar o certificado HTTPS " + keystore
                    + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(s, '\0');
        }
    }
}
