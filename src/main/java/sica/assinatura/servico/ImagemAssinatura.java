package sica.assinatura.servico;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Validacao e normalizacao da imagem (PNG) da assinatura desenhada.
 *
 * <p>Protecoes:</p>
 * <ul>
 *   <li>assinatura de bytes PNG e limite de tamanho do arquivo;</li>
 *   <li>dimensoes lidas do cabecalho <b>antes</b> de decodificar, evitando
 *       "bombas de descompressao" (PNG pequeno que expande para gigabytes);</li>
 *   <li>exige tracos visiveis (rejeita assinatura em branco);</li>
 *   <li>re-codifica a imagem, descartando metadados e chunks arbitrarios.</li>
 * </ul>
 */
public final class ImagemAssinatura {

    public static final int MAX_BYTES = 256 * 1024;
    static final int MAX_LARGURA = 2000;
    static final int MAX_ALTURA = 1000;
    static final int MIN_LADO = 20;
    /** Minimo de pixels "de tinta" para considerar que ha uma assinatura. */
    static final int MIN_PIXELS_TINTA = 30;

    private static final byte[] ASSINATURA_PNG =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private ImagemAssinatura() {
    }

    /**
     * Valida o PNG e devolve uma versao re-codificada e limpa.
     *
     * @throws ValidacaoException se a imagem for invalida, grande demais ou vazia
     */
    public static byte[] validarENormalizar(byte[] png) {
        if (png == null || png.length == 0) {
            throw new ValidacaoException("A imagem da assinatura e obrigatoria.");
        }
        if (png.length > MAX_BYTES) {
            throw new ValidacaoException("A imagem da assinatura e grande demais.");
        }
        if (png.length < ASSINATURA_PNG.length) {
            throw invalida();
        }
        for (int i = 0; i < ASSINATURA_PNG.length; i++) {
            if (png[i] != ASSINATURA_PNG[i]) {
                throw invalida();
            }
        }

        BufferedImage imagem = decodificar(png);
        if (!temTinta(imagem)) {
            throw new ValidacaoException("A assinatura esta em branco.");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(imagem, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw invalida();
        }
    }

    private static BufferedImage decodificar(byte[] png) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> leitores = ImageIO.getImageReadersByFormatName("png");
            if (in == null || !leitores.hasNext()) {
                throw invalida();
            }
            ImageReader leitor = leitores.next();
            try {
                leitor.setInput(in, true, true);
                int largura = leitor.getWidth(0);
                int altura = leitor.getHeight(0);
                if (largura > MAX_LARGURA || altura > MAX_ALTURA) {
                    throw new ValidacaoException("Dimensoes da assinatura acima do limite ("
                            + MAX_LARGURA + "x" + MAX_ALTURA + ").");
                }
                if (largura < MIN_LADO || altura < MIN_LADO) {
                    throw new ValidacaoException("A imagem da assinatura e pequena demais.");
                }
                return leitor.read(0);
            } finally {
                leitor.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidacaoException v) {
                throw v;
            }
            throw invalida();
        }
    }

    /** Ha pixels opacos e escuros o suficiente para formar um traco? */
    private static boolean temTinta(BufferedImage img) {
        int contagem = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int alfa = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (alfa > 64 && (r + g + b) / 3 < 200 && ++contagem >= MIN_PIXELS_TINTA) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ValidacaoException invalida() {
        return new ValidacaoException("A assinatura deve ser uma imagem PNG valida.");
    }
}
