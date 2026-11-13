package sica.assinatura.modelo;

/**
 * Assinatura ja feita por um signatario: dados usados na montagem do PDF final.
 *
 * @param nomeConfirmado nome digitado pelo signatario
 * @param png            imagem (PNG normalizado) da assinatura desenhada
 */
public record AssinaturaRegistrada(Signatario signatario, String nomeConfirmado, byte[] png) {
}
