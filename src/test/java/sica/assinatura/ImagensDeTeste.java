package sica.assinatura;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Gera PNGs para os testes de assinatura. */
public final class ImagensDeTeste {

    private ImagensDeTeste() {
    }

    /** PNG transparente com um "rabisco" escuro, como o desenhado no canvas. */
    public static byte[] assinatura(int largura, int altura) throws IOException {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(13, 27, 76));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(10, altura - 10, largura - 10, 10);
        g.drawLine(10, 10, largura / 2, altura - 10);
        g.dispose();
        return png(img);
    }

    /** PNG totalmente transparente (assinatura em branco). */
    public static byte[] vazia(int largura, int altura) throws IOException {
        return png(new BufferedImage(largura, altura, BufferedImage.TYPE_INT_ARGB));
    }

    public static byte[] png(BufferedImage img) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }
    }
}
