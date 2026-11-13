package sica.assinatura.modelo;

/**
 * Posicao do campo de assinatura no PDF, em pontos (1/72 pol) do espaco do
 * PDF: origem no canto inferior esquerdo da pagina; {@code x}/{@code y} e o
 * canto inferior esquerdo do retangulo.
 *
 * @param pagina  numero da pagina, a partir de 1
 */
public record CampoAssinatura(int pagina, double x, double y, double largura, double altura) {
}
