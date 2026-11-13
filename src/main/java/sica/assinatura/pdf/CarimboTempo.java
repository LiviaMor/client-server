package sica.assinatura.pdf;

import java.io.IOException;

/** Autoridade de carimbo de tempo (TSA, RFC 3161). */
public interface CarimboTempo {

    /**
     * Obtem um carimbo de tempo para o resumo informado.
     *
     * @param sha256 resumo SHA-256 dos dados a carimbar (32 bytes)
     * @return o {@code TimeStampToken} codificado (DER)
     * @throws IOException se a TSA estiver indisponivel ou responder algo invalido
     */
    byte[] carimbar(byte[] sha256) throws IOException;
}
