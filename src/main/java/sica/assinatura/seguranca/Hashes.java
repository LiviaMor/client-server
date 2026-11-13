package sica.assinatura.seguranca;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Utilitarios de hash usados pelo modulo de assinatura. */
public final class Hashes {

    private Hashes() {
    }

    /**
     * Calcula o SHA-256 de um conteudo.
     *
     * @return o hash em hexadecimal minusculo (64 caracteres)
     */
    public static String sha256Hex(byte[] dados) {
        return HexFormat.of().formatHex(sha256(dados));
    }

    /** Calcula o SHA-256 de um conteudo (bytes brutos). */
    public static byte[] sha256(byte[] dados) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(dados);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em toda JVM; nao deve ocorrer.
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    /**
     * Compara dois textos secretos em tempo constante, sem vazar o tamanho:
     * compara os SHA-256 de ambos com {@link MessageDigest#isEqual}.
     */
    public static boolean iguaisConstante(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256(a.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sha256(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
