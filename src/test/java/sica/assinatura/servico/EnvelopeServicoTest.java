package sica.assinatura.servico;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sica.assinatura.PdfsDeTeste;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.repositorio.Banco;

class EnvelopeServicoTest {

    static final long LIMITE = 1024 * 1024;

    @TempDir
    Path pasta;

    Banco banco;
    Path storage;
    EnvelopeServico servico;

    @BeforeEach
    void preparar() throws Exception {
        banco = new Banco(pasta.resolve("t.db"));
        banco.inicializar();
        storage = pasta.resolve("storage");
        servico = new EnvelopeServico(banco, storage, LIMITE);
    }

    private static String sha256(byte[] dados) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
    }

    private long contar(String tabela) throws Exception {
        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void criaEnvelopeComHashIgualAoDoArquivo() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("Contrato");

        Envelope e = servico.criar("  Contrato de Teste ", pdf, "10.0.0.1", "JUnit");

        assertEquals(sha256(pdf), e.sha256Original());
        assertEquals("Contrato de Teste", e.titulo());
        assertEquals(StatusEnvelope.DRAFT, e.status());
        assertNull(e.sha256Final());
        Path salvo = storage.resolve(e.caminhoOriginal());
        assertArrayEquals(pdf, Files.readAllBytes(salvo));
        assertEquals(storage.resolve(e.id()).resolve("original.pdf"), salvo);
    }

    @Test
    void registraEventoDeAuditoriaComHashEOrigem() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("x");
        Envelope e = servico.criar("t", pdf, "10.0.0.1", "JUnit");

        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tipo, ip, user_agent, sha256_documento, detalhe, envelope_id"
                             + " FROM audit_events")) {
            assertTrue(rs.next());
            assertEquals("envelope.created", rs.getString("tipo"));
            assertEquals("10.0.0.1", rs.getString("ip"));
            assertEquals("JUnit", rs.getString("user_agent"));
            assertEquals(e.sha256Original(), rs.getString("sha256_documento"));
            assertEquals("tamanho=" + pdf.length, rs.getString("detalhe"));
            assertEquals(e.id(), rs.getString("envelope_id"));
            assertFalse(rs.next());
        }
    }

    @Test
    void obterDevolveOEnvelopeGravado() throws Exception {
        Envelope criado = servico.criar("t", PdfsDeTeste.simples("x"), null, null);
        assertEquals(criado, servico.obter(criado.id()));
    }

    @Test
    void obterInexistenteLancaNaoEncontrado() {
        assertThrows(NaoEncontradoException.class,
                () -> servico.obter("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void duasCriacoesGeramIdsEPastasDistintas() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("x");
        Envelope a = servico.criar("t", pdf, null, null);
        Envelope b = servico.criar("t", pdf, null, null);
        assertFalse(a.id().equals(b.id()));
        assertEquals(a.sha256Original(), b.sha256Original());
    }

    @Test
    void rejeitaArquivoQueNaoEPdf() throws Exception {
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> servico.criar("t", "texto qualquer".getBytes(), null, null));
        assertTrue(e.getMessage().contains("nao e um PDF"));
        assertNenhumRastro();
    }

    @Test
    void rejeitaPdfCorrompidoComCabecalhoValido() throws Exception {
        assertThrows(ValidacaoException.class,
                () -> servico.criar("t", "%PDF-1.7\nlixo sem estrutura".getBytes(), null, null));
        assertNenhumRastro();
    }

    @Test
    void rejeitaPdfProtegidoPorSenha() throws Exception {
        byte[] original = PdfsDeTeste.simples("x");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(original)) {
            StandardProtectionPolicy politica =
                    new StandardProtectionPolicy("dono-123", "usuario-123", new AccessPermission());
            politica.setEncryptionKeyLength(128);
            doc.protect(politica);
            doc.save(out);
        }
        assertThrows(ValidacaoException.class,
                () -> servico.criar("t", out.toByteArray(), null, null));
        assertNenhumRastro();
    }

    @Test
    void rejeitaVazioNuloEMuitoGrande() throws Exception {
        assertThrows(ValidacaoException.class, () -> servico.criar("t", new byte[0], null, null));
        assertThrows(ValidacaoException.class, () -> servico.criar("t", null, null, null));

        byte[] grande = new byte[(int) LIMITE + 1];
        System.arraycopy("%PDF-".getBytes(), 0, grande, 0, 5);
        ValidacaoException e = assertThrows(ValidacaoException.class,
                () -> servico.criar("t", grande, null, null));
        assertTrue(e.getMessage().contains("tamanho maximo"));
        assertNenhumRastro();
    }

    @Test
    void validaTitulo() throws Exception {
        byte[] pdf = PdfsDeTeste.simples("x");
        assertThrows(ValidacaoException.class, () -> servico.criar(null, pdf, null, null));
        assertThrows(ValidacaoException.class, () -> servico.criar("   ", pdf, null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.criar("a".repeat(201), pdf, null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.criar("linha1\nlinha2", pdf, null, null));
        assertNenhumRastro();
    }

    @Test
    void falhaNoBancoNaoDeixaArquivoOrfao() throws Exception {
        // Banco sem esquema: o INSERT falha depois que o arquivo ja foi gravado.
        Banco semEsquema = new Banco(pasta.resolve("vazio.db"));
        EnvelopeServico s = new EnvelopeServico(semEsquema, storage, LIMITE);

        assertThrows(java.sql.SQLException.class,
                () -> s.criar("t", PdfsDeTeste.simples("x"), null, null));

        assertNenhumRastro();
    }

    /** Nada foi gravado: nem linhas no banco, nem arquivos no storage. */
    private void assertNenhumRastro() throws Exception {
        assertEquals(0, contar("envelopes"));
        assertEquals(0, contar("audit_events"));
        if (Files.exists(storage)) {
            try (Stream<Path> s = Files.walk(storage)) {
                assertEquals(0, s.filter(Files::isRegularFile).count(),
                        "arquivos orfaos no storage");
            }
        }
    }
}
