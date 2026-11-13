package sica.assinatura.servico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import sica.assinatura.ImagensDeTeste;

class ImagemAssinaturaTest {

    @Test
    void aceitaAssinaturaValidaEReCodifica() throws Exception {
        byte[] original = ImagensDeTeste.assinatura(600, 200);
        byte[] normalizada = ImagemAssinatura.validarENormalizar(original);

        BufferedImage lida = ImageIO.read(new ByteArrayInputStream(normalizada));
        assertEquals(600, lida.getWidth());
        assertEquals(200, lida.getHeight());
    }

    @Test
    void descartaDadosExtrasAposOFimDoPng() throws Exception {
        byte[] original = ImagensDeTeste.assinatura(300, 100);
        byte[] comLixo = Arrays.copyOf(original, original.length + 100);
        Arrays.fill(comLixo, original.length, comLixo.length, (byte) 'A');

        byte[] normalizada = ImagemAssinatura.validarENormalizar(comLixo);

        assertTrue(normalizada.length < comLixo.length);
        assertTrue(!new String(normalizada, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("AAAAAAAAAA"));
    }

    @Test
    void rejeitaAssinaturaEmBranco() throws Exception {
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> ImagemAssinatura.validarENormalizar(ImagensDeTeste.vazia(600, 200)));
        assertTrue(e.getMessage().contains("branco"));
    }

    @Test
    void rejeitaNaoPngVazioENulo() {
        assertThrows(ValidacaoException.class,
                () -> ImagemAssinatura.validarENormalizar("GIF89a....".getBytes()));
        assertThrows(ValidacaoException.class,
                () -> ImagemAssinatura.validarENormalizar(new byte[0]));
        assertThrows(ValidacaoException.class, () -> ImagemAssinatura.validarENormalizar(null));
    }

    @Test
    void rejeitaPngCorrompido() throws Exception {
        byte[] png = ImagensDeTeste.assinatura(300, 100);
        byte[] truncado = Arrays.copyOf(png, 40);
        assertThrows(ValidacaoException.class, () -> ImagemAssinatura.validarENormalizar(truncado));
    }

    @Test
    void rejeitaDimensoesForaDosLimites() throws Exception {
        // Arquivo pequeno, mas dimensoes enormes: barrado antes de decodificar.
        assertThrows(ValidacaoException.class, () -> ImagemAssinatura.validarENormalizar(
                ImagensDeTeste.vazia(ImagemAssinatura.MAX_LARGURA + 1, 100)));
        assertThrows(ValidacaoException.class, () -> ImagemAssinatura.validarENormalizar(
                ImagensDeTeste.vazia(100, ImagemAssinatura.MAX_ALTURA + 1)));
        assertThrows(ValidacaoException.class, () -> ImagemAssinatura.validarENormalizar(
                ImagensDeTeste.assinatura(10, 10)));
    }

    @Test
    void rejeitaArquivoGrandeDemais() throws Exception {
        byte[] png = ImagensDeTeste.assinatura(300, 100);
        byte[] grande = Arrays.copyOf(png, ImagemAssinatura.MAX_BYTES + 1);
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> ImagemAssinatura.validarENormalizar(grande));
        assertTrue(e.getMessage().contains("grande"));
    }
}
