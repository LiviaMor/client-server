package sica.assinatura.modelo;

/**
 * Signatario de um envelope. O token do link <b>nunca</b> faz parte deste
 * modelo: so o hash fica no banco e o valor em claro existe apenas no e-mail.
 *
 * @param tokenExpiraEm validade do link atual; {@code null} antes do envio
 * @param assinadoEm    instante da assinatura; {@code null} ate la
 * @param campo         posicao da assinatura no PDF
 */
public record Signatario(
        String id,
        String envelopeId,
        String nome,
        String email,
        StatusSignatario status,
        String tokenExpiraEm,
        String assinadoEm,
        CampoAssinatura campo) {
}
