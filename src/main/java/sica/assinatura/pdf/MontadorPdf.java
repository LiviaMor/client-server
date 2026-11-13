package sica.assinatura.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import sica.assinatura.modelo.AssinaturaRegistrada;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.EventoAuditoria;
import sica.assinatura.modelo.Signatario;

/**
 * Monta o PDF final (antes do selo): insere a imagem de cada assinatura no seu
 * campo, com uma legenda, e anexa a pagina de auditoria (manifesto).
 *
 * <p>Tudo isso entra no documento <b>antes</b> do selo PAdES, portanto o selo
 * protege tambem as imagens e os dados da auditoria.</p>
 */
public final class MontadorPdf {

    private static final DateTimeFormatter DATA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final PDType1Font NORMAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font NEGRITO =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font MONO = new PDType1Font(Standard14Fonts.FontName.COURIER);

    private MontadorPdf() {
    }

    /**
     * @param original     bytes do PDF original
     * @param assinaturas  assinaturas registradas (todas SIGNED)
     * @param eventos      trilha de auditoria do envelope
     * @param urlVerificar endereco de verificacao exibido no manifesto
     * @param finalizadoEm instante da finalizacao
     */
    public static byte[] montar(byte[] original, Envelope envelope,
                                List<AssinaturaRegistrada> assinaturas,
                                List<EventoAuditoria> eventos, String urlVerificar,
                                Instant finalizadoEm) throws IOException {
        Map<String, EventoAuditoria> eventoAssinatura = new HashMap<>();
        for (EventoAuditoria e : eventos) {
            if ("signer.signed".equals(e.tipo()) && e.signerId() != null) {
                eventoAssinatura.put(e.signerId(), e);
            }
        }
        try (PDDocument doc = Loader.loadPDF(original);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (AssinaturaRegistrada a : assinaturas) {
                inserirAssinatura(doc, a, envelope.sha256Original());
            }
            new Manifesto(doc).escrever(envelope, assinaturas, eventoAssinatura, eventos,
                    urlVerificar, finalizadoEm);
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Desenha a imagem no retangulo do campo, com legenda na parte de baixo. */
    private static void inserirAssinatura(PDDocument doc, AssinaturaRegistrada a,
                                          String hash) throws IOException {
        CampoAssinatura f = a.signatario().campo();
        if (f == null || f.pagina() < 1 || f.pagina() > doc.getNumberOfPages()) {
            throw new IOException("Campo de assinatura invalido para o signatario "
                    + a.signatario().id());
        }
        PDPage pagina = doc.getPage(f.pagina() - 1);
        PDImageXObject imagem =
                PDImageXObject.createFromByteArray(doc, a.png(), "assinatura.png");

        float x = (float) f.x();
        float y = (float) f.y();
        float largura = (float) f.largura();
        float altura = (float) f.altura();
        float legenda = altura >= 30 ? Math.min(14f, altura * 0.3f) : 0f;

        // Imagem proporcional, centralizada na area acima da legenda.
        float areaAltura = altura - legenda;
        float escala = Math.min(largura / imagem.getWidth(), areaAltura / imagem.getHeight());
        float w = imagem.getWidth() * escala;
        float h = imagem.getHeight() * escala;
        float ix = x + (largura - w) / 2;
        float iy = y + legenda + (areaAltura - h) / 2;

        try (PDPageContentStream cs =
                     new PDPageContentStream(doc, pagina, AppendMode.APPEND, true, true)) {
            cs.drawImage(imagem, ix, iy, w, h);
            if (legenda > 0) {
                String linha1 = "Assinado eletronicamente por " + seguro(a.signatario().nome());
                String linha2 = DATA.format(Instant.parse(a.signatario().assinadoEm()))
                        + " - SHA-256 " + hash.substring(0, 16) + "...";
                float tamanho = legenda / 2.4f;
                texto(cs, NORMAL, ajustar(NORMAL, linha1, tamanho, largura), x,
                        y + legenda / 2 + 1, linha1);
                texto(cs, NORMAL, ajustar(NORMAL, linha2, tamanho, largura), x, y + 1, linha2);
            }
        }
    }

    /** Escreve a pagina (ou paginas) de auditoria ao final do documento. */
    private static final class Manifesto {

        private static final float MARGEM = 50f;
        private static final float LARGURA = PDRectangle.A4.getWidth() - 2 * MARGEM;

        private final PDDocument doc;
        private PDPageContentStream cs;
        private float y;

        Manifesto(PDDocument doc) {
            this.doc = doc;
        }

        void escrever(Envelope e, List<AssinaturaRegistrada> assinaturas,
                      Map<String, EventoAuditoria> eventoAssinatura,
                      List<EventoAuditoria> eventos, String urlVerificar,
                      Instant finalizadoEm) throws IOException {
            novaPagina();
            try {
                linha(NEGRITO, 15, "Manifesto de assinaturas eletrônicas", 22);
                linha(NORMAL, 9, "Página de auditoria gerada pelo serviço de assinatura Angel Signature"
                        + " (Anjos Tecnologia).", 18);

                secao("Documento");
                campo("Título", e.titulo());
                campo("Envelope", e.id());
                campo("Criado em", DATA.format(Instant.parse(e.criadoEm())));
                campo("Finalizado em", DATA.format(finalizadoEm));
                linha(NORMAL, 9, "SHA-256 do documento original:", 12);
                linha(MONO, 8.5f, e.sha256Original(), 16);

                secao("Signatários (" + assinaturas.size() + ")");
                int n = 1;
                for (AssinaturaRegistrada a : assinaturas) {
                    Signatario s = a.signatario();
                    EventoAuditoria ev = eventoAssinatura.get(s.id());
                    garantirEspaco(80);
                    linha(NEGRITO, 10, n++ + ". " + s.nome() + " <" + s.email() + ">", 14);
                    campo("Nome digitado", a.nomeConfirmado());
                    campo("Assinado em", DATA.format(Instant.parse(s.assinadoEm())));
                    if (ev != null) {
                        campo("IP", ev.ip() == null ? "-" : ev.ip());
                        campo("Navegador", ev.userAgent() == null ? "-" : ev.userAgent());
                        campo("Hash visto", ev.sha256Documento());
                    }
                    y -= 6;
                }

                secao("Trilha de auditoria");
                Map<String, String> emails = new HashMap<>();
                for (AssinaturaRegistrada a : assinaturas) {
                    emails.put(a.signatario().id(), a.signatario().email());
                }
                for (EventoAuditoria ev : eventos) {
                    String quem = ev.signerId() == null ? "emissor/sistema"
                            : emails.getOrDefault(ev.signerId(), ev.signerId());
                    String ip = ev.ip() == null ? "" : "  IP " + ev.ip();
                    paragrafo(NORMAL, 8, DATA.format(Instant.parse(ev.ocorridoEm())) + "  "
                            + ev.tipo() + "  " + quem + ip, 11);
                }

                secao("Validade e verificação");
                paragrafo(NORMAL, 8.5f, "Assinatura eletrônica nos termos da MP 2.200-2/2001 e da"
                        + " Lei 14.063/2020. A identificação do signatário baseia-se no acesso ao"
                        + " link único enviado ao seu e-mail, no nome digitado e nas evidências"
                        + " acima (data/hora, IP, navegador e hash do documento).", 11);
                y -= 4;
                paragrafo(NORMAL, 8.5f, "Este arquivo é selado digitalmente pelo serviço (PAdES)"
                        + " com carimbo de tempo RFC 3161. Qualquer alteração posterior invalida o"
                        + " selo. O hash do arquivo final fica registrado no serviço; para conferir"
                        + " a autenticidade, envie o PDF para: " + urlVerificar, 11);
            } finally {
                cs.close();
            }
        }

        private void novaPagina() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage pagina = new PDPage(PDRectangle.A4);
            doc.addPage(pagina);
            cs = new PDPageContentStream(doc, pagina);
            y = PDRectangle.A4.getHeight() - MARGEM;
        }

        private void garantirEspaco(float altura) throws IOException {
            if (y - altura < MARGEM) {
                novaPagina();
            }
        }

        private void secao(String titulo) throws IOException {
            garantirEspaco(40);
            y -= 8;
            linha(NEGRITO, 11, titulo, 16);
        }

        private void campo(String rotulo, String valor) throws IOException {
            paragrafo(NORMAL, 9, rotulo + ": " + (valor == null ? "-" : valor), 12);
        }

        private void linha(PDType1Font fonte, float tamanho, String texto, float avanco)
                throws IOException {
            garantirEspaco(avanco);
            texto(cs, fonte, tamanho, MARGEM, y - tamanho, texto);
            y -= avanco;
        }

        /** Texto com quebra de linha pela largura disponivel. */
        private void paragrafo(PDType1Font fonte, float tamanho, String texto, float entrelinha)
                throws IOException {
            for (String l : quebrar(fonte, tamanho, seguro(texto), LARGURA)) {
                linha(fonte, tamanho, l, entrelinha);
            }
        }
    }

    // ----- Texto -----

    private static void texto(PDPageContentStream cs, PDType1Font fonte, float tamanho,
                              float x, float y, String texto) throws IOException {
        cs.beginText();
        cs.setFont(fonte, tamanho);
        cs.newLineAtOffset(x, y);
        cs.showText(seguro(texto));
        cs.endText();
    }

    /** Reduz o tamanho da fonte ate o texto caber na largura (minimo 3 pt). */
    private static float ajustar(PDType1Font fonte, String texto, float tamanho, float largura)
            throws IOException {
        float t = tamanho;
        while (t > 3f && largura(fonte, seguro(texto), t) > largura) {
            t -= 0.25f;
        }
        return t;
    }

    private static float largura(PDType1Font fonte, String texto, float tamanho)
            throws IOException {
        return fonte.getStringWidth(texto) / 1000f * tamanho;
    }

    static List<String> quebrar(PDType1Font fonte, float tamanho, String texto, float max)
            throws IOException {
        List<String> linhas = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String palavra : texto.split(" ")) {
            String tentativa = atual.length() == 0 ? palavra : atual + " " + palavra;
            if (largura(fonte, tentativa, tamanho) <= max) {
                atual.setLength(0);
                atual.append(tentativa);
                continue;
            }
            if (atual.length() > 0) {
                linhas.add(atual.toString());
                atual.setLength(0);
            }
            // Palavra maior que a linha (ex.: user agent, hash): corta em pedacos.
            String resto = palavra;
            while (largura(fonte, resto, tamanho) > max) {
                int corte = resto.length() - 1;
                while (corte > 1 && largura(fonte, resto.substring(0, corte), tamanho) > max) {
                    corte--;
                }
                linhas.add(resto.substring(0, corte));
                resto = resto.substring(corte);
            }
            atual.append(resto);
        }
        if (atual.length() > 0) {
            linhas.add(atual.toString());
        }
        return linhas;
    }

    /**
     * Mantem so caracteres que as fontes padrao (WinAnsi) conseguem desenhar:
     * acentos do portugues passam; o resto vira '?'. Controles viram espaco.
     */
    static String seguro(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length());
        texto.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                sb.append(' ');
                return;
            }
            String ch = new String(Character.toChars(cp));
            try {
                NORMAL.encode(ch);
                sb.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }
}
