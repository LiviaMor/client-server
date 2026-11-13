package sica.assinatura.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import sica.assinatura.ImagensDeTeste;
import sica.assinatura.PdfsDeTeste;
import sica.assinatura.modelo.AssinaturaRegistrada;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.EventoAuditoria;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.modelo.StatusSignatario;

class MontadorPdfTest {

    static final String HASH = "ab".repeat(32);

    private static Envelope envelope() {
        return new Envelope("11111111-2222-3333-4444-555555555555", "Contrato de Locação",
                StatusEnvelope.SENT, HASH, null, "x/original.pdf", "2026-11-01T10:00:00Z", null, null);
    }

    private static AssinaturaRegistrada assinatura(String id, String nome, String email)
            throws Exception {
        Signatario s = new Signatario(id, "env", nome, email, StatusSignatario.SIGNED,
                "2026-12-01T00:00:00Z", "2026-11-02T15:30:00Z",
                new CampoAssinatura(1, 72, 100, 200, 60));
        return new AssinaturaRegistrada(s, nome, ImagensDeTeste.assinatura(600, 200));
    }

    private static String texto(PDDocument doc, int de, int ate) throws Exception {
        PDFTextStripper t = new PDFTextStripper();
        t.setStartPage(de);
        t.setEndPage(ate);
        return t.getText(doc);
    }

    @Test
    void insereImagensEAnexaManifesto() throws Exception {
        List<AssinaturaRegistrada> as = List.of(
                assinatura("s1", "José da Silva", "jose@exemplo.com"),
                assinatura("s2", "Maria Souza", "maria@exemplo.com"));
        List<EventoAuditoria> ev = List.of(
                new EventoAuditoria(1, "env", null, "envelope.created", "10.0.0.1", "ua", HASH,
                        null, "2026-11-01T10:00:00Z"),
                new EventoAuditoria(2, "env", "s1", "signer.signed", "200.1.2.3",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile",
                        HASH, "nome_confirmado=José da Silva", "2026-11-02T15:30:00Z"));

        byte[] montado = MontadorPdf.montar(PdfsDeTeste.simples("Clausula"), envelope(), as, ev,
                "https://app.exemplo/verify", Instant.parse("2026-11-02T16:00:00Z"));

        try (PDDocument doc = Loader.loadPDF(montado)) {
            assertTrue(doc.getNumberOfPages() >= 2);
            // Pagina 1: as duas imagens de assinatura foram adicionadas.
            int imagens = 0;
            for (COSName n : doc.getPage(0).getResources().getXObjectNames()) {
                if (doc.getPage(0).getResources().getXObject(n) instanceof PDImageXObject) {
                    imagens++;
                }
            }
            assertEquals(2, imagens);
            String p1 = texto(doc, 1, 1);
            assertTrue(p1.contains("Assinado eletronicamente por José da Silva"), p1);

            String manifesto = texto(doc, 2, doc.getNumberOfPages());
            assertTrue(manifesto.contains("Manifesto de assinaturas eletrônicas"), manifesto);
            assertTrue(manifesto.contains("Contrato de Locação"));
            assertTrue(manifesto.contains(HASH));
            assertTrue(manifesto.contains("José da Silva <jose@exemplo.com>"));
            assertTrue(manifesto.contains("Maria Souza <maria@exemplo.com>"));
            assertTrue(manifesto.contains("200.1.2.3"));
            assertTrue(manifesto.contains("envelope.created"));
            assertTrue(manifesto.contains("02/11/2026 16:00:00 UTC"));
            assertTrue(manifesto.contains("https://app.exemplo/verify"));
            assertTrue(manifesto.contains("Lei 14.063/2020"));
        }
    }

    @Test
    void caracteresForaDaFonteViramInterrogacao() throws Exception {
        List<AssinaturaRegistrada> as = List.of(assinatura("s1", "李小龍 Ana", "ana@exemplo.com"));
        byte[] montado = MontadorPdf.montar(PdfsDeTeste.simples("x"), envelope(), as, List.of(),
                "https://x/verify", Instant.now());
        try (PDDocument doc = Loader.loadPDF(montado)) {
            assertTrue(texto(doc, 2, 2).contains("??? Ana"));
        }
        assertEquals("Ação ok", MontadorPdf.seguro("Ação\tok").replace(' ', ' ').replace("\t", " "));
    }

    @Test
    void trilhaLongaQuebraEmVariasPaginas() throws Exception {
        List<EventoAuditoria> ev = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ev.add(new EventoAuditoria(i, "env", null, "signer.viewed", "10.0.0." + (i % 250),
                    null, HASH, null, "2026-11-01T10:00:00Z"));
        }
        byte[] montado = MontadorPdf.montar(PdfsDeTeste.simples("x"), envelope(),
                List.of(assinatura("s1", "Ana", "ana@exemplo.com")), ev, "https://x/verify",
                Instant.now());
        try (PDDocument doc = Loader.loadPDF(montado)) {
            assertTrue(doc.getNumberOfPages() >= 4, "paginas: " + doc.getNumberOfPages());
        }
    }

    @Test
    void quebraPalavrasMaioresQueALinha() throws Exception {
        var fonte = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
        List<String> linhas = MontadorPdf.quebrar(fonte, 9, "x".repeat(500), 200);
        assertTrue(linhas.size() > 1);
        assertEquals(500, String.join("", linhas).length());
    }
}
