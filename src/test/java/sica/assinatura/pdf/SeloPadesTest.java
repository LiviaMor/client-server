package sica.assinatura.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sica.assinatura.PdfsDeTeste;

class SeloPadesTest {

    @TempDir
    Path pasta;

    TsaFalsa tsa;
    byte[] pdf;

    @BeforeEach
    void preparar() throws Exception {
        tsa = new TsaFalsa();
        pdf = PdfsDeTeste.simples("Contrato a selar");
    }

    @AfterEach
    void parar() {
        tsa.close();
    }

    private SeloPades selo() {
        return new SeloPades(CertificadosDeTeste.servico(), new ClienteTsaHttp(tsa.url()));
    }

    @Test
    void selaComPadesECarimboDeTempoVerificaveis() throws Exception {
        Instant antes = Instant.now().minusSeconds(2);
        byte[] selado = selo().selar(pdf, "Teste");

        // Atualizacao incremental: o arquivo original fica intacto no inicio.
        assertArrayEquals(pdf, Arrays.copyOf(selado, pdf.length));

        List<VerificadorPdf.Assinatura> r = VerificadorPdf.verificar(selado);
        assertEquals(1, r.size());
        VerificadorPdf.Assinatura a = r.get(0);
        assertTrue(a.criptografiaValida(), a.erro());
        assertTrue(a.cobreDocumentoInteiro());
        assertTrue(a.temSigningCertificateV2());
        assertTrue(a.temCarimbo());
        assertTrue(a.carimboValido());
        assertTrue(a.integra());
        assertNull(a.erro());
        assertTrue(a.assinante().contains("SiCA Servico de Assinatura"));
        assertTrue(a.autoridadeCarimbo().contains("TSA de Teste"));
        assertTrue(a.dataCarimbo().isAfter(antes)
                && a.dataCarimbo().isBefore(Instant.now().plus(Duration.ofSeconds(2))));
        assertEquals(CertificadosDeTeste.SERVICO, a.cadeia().get(0));
        assertTrue(a.cadeia().contains(CertificadosDeTeste.AC));
        assertEquals(1, tsa.pedidos.get());
    }

    @Test
    void usaPerfilPadesSemSigningTimeEComSubFilterCades() throws Exception {
        byte[] selado = selo().selar(pdf, "Teste");
        try (PDDocument doc = Loader.loadPDF(selado)) {
            PDSignature sig = doc.getSignatureDictionaries().get(0);
            assertEquals(PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName(), sig.getSubFilter());
            assertEquals("Teste", sig.getReason());
            assertNotNull(sig.getSignDate());

            CMSSignedData cms = VerificadorPdf.lerCms(sig.getSignedContent(selado),
                    sig.getContents(selado));
            SignerInformation si = cms.getSignerInfos().getSigners().iterator().next();
            assertNull(si.getSignedAttributes().get(CMSAttributes.signingTime));

            // O carimbo cobre exatamente o valor da assinatura.
            TimeStampToken token = new TimeStampToken(org.bouncycastle.asn1.cms.ContentInfo.getInstance(
                    si.getUnsignedAttributes().get(
                            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken)
                            .getAttrValues().getObjectAt(0)));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(si.getSignature()),
                    token.getTimeStampInfo().getMessageImprintDigest());
        }
    }

    @Test
    void alteracaoNoConteudoInvalidaOSelo() throws Exception {
        byte[] selado = selo().selar(pdf, "Teste");
        // Linha 2 do PDF e um comentario binario ("%" + 4 bytes): alterar um byte
        // mantem o arquivo legivel, mas muda o conteudo coberto pelo selo.
        assertEquals('%', selado[9]);
        byte[] adulterado = selado.clone();
        adulterado[10] ^= 0x01;

        VerificadorPdf.Assinatura a = VerificadorPdf.verificar(adulterado).get(0);
        assertFalse(a.criptografiaValida());
        assertFalse(a.integra());
        assertNotNull(a.erro());
    }

    @Test
    void conteudoAcrescentadoDepoisDoSeloEhDetectado() throws Exception {
        byte[] selado = selo().selar(pdf, "Teste");
        byte[] extra = "\n% conteudo acrescentado depois\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] comExtra = Arrays.copyOf(selado, selado.length + extra.length);
        System.arraycopy(extra, 0, comExtra, selado.length, extra.length);

        VerificadorPdf.Assinatura a = VerificadorPdf.verificar(comExtra).get(0);
        assertTrue(a.criptografiaValida(), "os bytes cobertos continuam iguais");
        assertFalse(a.cobreDocumentoInteiro());
        assertFalse(a.integra());
    }

    @Test
    void semTsaSelaSemCarimbo() throws Exception {
        SeloPades semCarimbo = new SeloPades(CertificadosDeTeste.servico(), null);
        assertFalse(semCarimbo.temCarimbo());
        VerificadorPdf.Assinatura a =
                VerificadorPdf.verificar(semCarimbo.selar(pdf, "Teste")).get(0);
        assertTrue(a.integra());
        assertFalse(a.temCarimbo());
        assertNull(a.dataCarimbo());
    }

    @Test
    void falhaDaTsaImpedeOSelo() {
        tsa.falhar.set(true);
        IOException e = assertThrows(IOException.class, () -> selo().selar(pdf, "Teste"));
        assertTrue(e.getMessage().contains("500") || String.valueOf(e.getCause()).contains("500"),
                e.getMessage());
    }

    @Test
    void pdfSemAssinaturaRetornaListaVazia() throws Exception {
        assertTrue(VerificadorPdf.verificar(pdf).isEmpty());
    }

    @Test
    void carregaCertificadoDeArquivoP12() throws Exception {
        Path p12 = CertificadosDeTeste.gravarP12(pasta);
        CertificadoAssinatura c = CertificadoAssinatura.carregar(p12, CertificadosDeTeste.SENHA);
        assertEquals(CertificadosDeTeste.SERVICO, c.certificado());
        assertEquals(2, c.cadeia().length);
        assertEquals("SHA256withRSA", c.algoritmo());

        assertThrows(IllegalStateException.class, () -> CertificadoAssinatura.carregar(p12, "errada"));
        assertThrows(IllegalStateException.class, () -> CertificadoAssinatura.carregar(p12, null));
        assertThrows(IllegalStateException.class,
                () -> CertificadoAssinatura.carregar(pasta.resolve("nao-existe.p12"), "x"));
    }

    @Test
    void clienteTsaRejeitaRespostaInvalida() throws Exception {
        try (var falsa = new RespostaFixa("isto nao e uma resposta RFC 3161".getBytes())) {
            ClienteTsaHttp c = new ClienteTsaHttp(falsa.url());
            assertThrows(IOException.class, () -> c.carimbar(new byte[32]));
        }
    }

    /** Servidor que responde sempre o mesmo corpo. */
    private static final class RespostaFixa implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;

        RespostaFixa(byte[] corpo) throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(200, corpo.length);
                try (var os = ex.getResponseBody()) {
                    os.write(corpo);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
