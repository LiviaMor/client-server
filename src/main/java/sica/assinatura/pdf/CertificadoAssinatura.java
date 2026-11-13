package sica.assinatura.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Certificado do servico (chave privada + cadeia), carregado de um arquivo
 * PKCS#12 ({@code .p12}/{@code .pfx}).
 *
 * <p>Trocar o certificado de teste por um A1 ICP-Brasil e so apontar
 * {@code SIGNING_CERT_PATH}/{@code SIGNING_CERT_PASSWORD} para o novo arquivo.</p>
 *
 * @param chave  chave privada do servico
 * @param cadeia certificado do servico na posicao 0, seguido dos emissores
 */
public record CertificadoAssinatura(PrivateKey chave, X509Certificate[] cadeia) {

    /** Certificado do servico (folha da cadeia). */
    public X509Certificate certificado() {
        return cadeia[0];
    }

    /** Algoritmo de assinatura adequado a chave (RSA ou EC). */
    public String algoritmo() {
        return "EC".equals(chave.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
    }

    /**
     * Carrega a primeira entrada com chave privada do arquivo PKCS#12.
     *
     * @throws IllegalStateException se o arquivo, a senha ou o conteudo forem invalidos
     */
    public static CertificadoAssinatura carregar(Path arquivo, String senha) {
        if (senha == null) {
            throw new IllegalStateException("SIGNING_CERT_PASSWORD nao configurada.");
        }
        char[] s = senha.toCharArray();
        try (InputStream in = Files.newInputStream(arquivo)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, s);
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) {
                    continue;
                }
                PrivateKey chave = (PrivateKey) ks.getKey(alias, s);
                Certificate[] cadeia = ks.getCertificateChain(alias);
                if (chave == null || cadeia == null || cadeia.length == 0) {
                    continue;
                }
                X509Certificate[] x509 = new X509Certificate[cadeia.length];
                for (int i = 0; i < cadeia.length; i++) {
                    x509[i] = (X509Certificate) cadeia[i];
                }
                x509[0].checkValidity();
                return new CertificadoAssinatura(chave, x509);
            }
            throw new IllegalStateException("Nenhuma chave privada encontrada em " + arquivo);
        } catch (IOException | GeneralSecurityException e) {
            // Nao inclui a senha; a mensagem do KeyStore ja e generica.
            throw new IllegalStateException("Falha ao carregar o certificado " + arquivo + ": "
                    + e.getMessage(), e);
        } finally {
            java.util.Arrays.fill(s, '\0');
        }
    }
}
