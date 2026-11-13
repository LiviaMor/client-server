package sica.assinatura.seguranca;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Geracao e hash dos tokens dos links de assinatura.
 *
 * <p>O token tem 32 bytes aleatorios de {@link SecureRandom} (256 bits),
 * codificados em Base64 URL-safe sem padding (43 caracteres). Como a entropia
 * e alta, um SHA-256 simples basta para armazena-lo: no banco fica apenas o
 * hash, e o valor em claro existe somente no e-mail enviado.</p>
 */
public final class TokenServico {

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final int BYTES = 32;

    private TokenServico() {
    }

    /**
     * Token gerado.
     *
     * @param valor valor em claro (vai so no link do e-mail; nunca persistir)
     * @param hash  SHA-256 hex do valor (o que vai para o banco)
     */
    public record Token(String valor, String hash) {

        /** Nao expoe o valor em claro em logs acidentais. */
        @Override
        public String toString() {
            return "Token[hash=" + hash + "]";
        }
    }

    public static Token gerar() {
        byte[] bytes = new byte[BYTES];
        ALEATORIO.nextBytes(bytes);
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Token(valor, hash(valor));
    }

    /** Hash (SHA-256 hex) de um token em claro, usado para localiza-lo no banco. */
    public static String hash(String valor) {
        return Hashes.sha256Hex(valor.getBytes(StandardCharsets.UTF_8));
    }
}
