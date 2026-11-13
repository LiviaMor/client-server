package sica.assinatura.servico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sica.assinatura.ImagensDeTeste;
import sica.assinatura.PdfsDeTeste;
import sica.assinatura.email.EmailFalso;
import sica.assinatura.email.Mensagem;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.modelo.StatusSignatario;
import sica.assinatura.repositorio.Banco;

class AssinaturaServicoTest {

    static final CampoAssinatura CAMPO = new CampoAssinatura(1, 72, 100, 200, 60);

    @TempDir
    Path pasta;

    Banco banco;
    Path storage;
    EnvelopeServico envelopes;
    SignatarioServico signatarios;
    AssinaturaServico servico;
    EmailFalso email;
    Envelope envelope;
    byte[] png;

    @BeforeEach
    void preparar() throws Exception {
        banco = new Banco(pasta.resolve("t.db"));
        banco.inicializar();
        storage = pasta.resolve("storage");
        envelopes = new EnvelopeServico(banco, storage, 1024 * 1024);
        email = new EmailFalso();
        signatarios = new SignatarioServico(banco, storage, email, "https://app", 7);
        servico = new AssinaturaServico(banco, storage);
        envelope = envelopes.criar("Contrato", PdfsDeTeste.simples("Contrato"), null, null);
        png = ImagensDeTeste.assinatura(600, 200);
    }

    /** Adiciona os signatarios, envia e devolve os tokens na mesma ordem. */
    private List<String> convidar(String... nomesEEmails) throws Exception {
        for (int i = 0; i < nomesEEmails.length; i += 2) {
            signatarios.adicionar(envelope.id(), nomesEEmails[i], nomesEEmails[i + 1], CAMPO,
                    null, null);
        }
        email.enviadas.clear();
        signatarios.enviar(envelope.id(), null, null);
        List<String> tokens = new ArrayList<>();
        for (int i = 1; i < nomesEEmails.length; i += 2) {
            String alvo = nomesEEmails[i].toLowerCase();
            Mensagem m = email.enviadas.stream().filter(x -> x.para().equals(alvo))
                    .findFirst().orElseThrow();
            tokens.add(EmailFalso.token(m));
        }
        return tokens;
    }

    private String token() throws Exception {
        return convidar("Fulano de Tal", "fulano@exemplo.com").get(0);
    }

    private AssinaturaServico.Resultado assinar(String token) throws Exception {
        return servico.assinar(token, "Fulano de Tal", envelope.sha256Original(), true, png,
                "9.9.9.9", "Navegador/1.0");
    }

    private List<String[]> eventos() throws Exception {
        List<String[]> lista = new ArrayList<>();
        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT tipo, ip, user_agent, sha256_documento,"
                     + " detalhe FROM audit_events ORDER BY id")) {
            while (rs.next()) {
                lista.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)});
            }
        }
        return lista;
    }

    private String[] evento(String tipo) throws Exception {
        return eventos().stream().filter(e -> e[0].equals(tipo)).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("evento ausente: " + tipo));
    }

    // ----- Abrir -----

    @Test
    void abrirMostraConviteEMarcaComoVisto() throws Exception {
        String t = token();

        AssinaturaServico.Convite c = servico.abrir(t, "9.9.9.9", "Navegador/1.0");

        assertTrue(c.podeAssinar());
        assertNull(c.motivo());
        assertEquals("Fulano de Tal", c.signatario().nome());
        assertEquals(StatusSignatario.VIEWED, c.signatario().status());
        assertEquals(1, c.paginas().size());
        assertEquals(612.0, c.paginas().get(0).largura(), 0.1);
        String[] ev = evento("signer.viewed");
        assertEquals("9.9.9.9", ev[1]);
        assertEquals("Navegador/1.0", ev[2]);
        assertEquals(envelope.sha256Original(), ev[3]);
    }

    @Test
    void tokenInvalidoOuDesconhecidoDa404() throws Exception {
        token();
        assertThrows(NaoEncontradoException.class, () -> servico.abrir("curto", null, null));
        assertThrows(NaoEncontradoException.class, () -> servico.abrir(null, null, null));
        assertThrows(NaoEncontradoException.class,
                () -> servico.abrir("A".repeat(43), null, null));
        assertThrows(NaoEncontradoException.class,
                () -> servico.abrir("../../../../etc/passwd/aaaaaaaaaaaaaaaaaaaaaa", null, null));
    }

    @Test
    void tokenVencidoDa410() throws Exception {
        String t = token();
        try (Connection c = banco.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE signers SET token_expira_em = '2020-01-01T00:00:00Z'");
        }
        assertThrows(LinkExpiradoException.class, () -> servico.abrir(t, null, null));
        assertThrows(LinkExpiradoException.class, () -> assinar(t));
    }

    @Test
    void reenvioInvalidaOLinkAnterior() throws Exception {
        String antigo = token();
        email.enviadas.clear();
        signatarios.enviar(envelope.id(), null, null);
        String novo = EmailFalso.token(email.enviadas.get(0));

        assertThrows(NaoEncontradoException.class, () -> servico.abrir(antigo, null, null));
        assertThrows(NaoEncontradoException.class, () -> assinar(antigo));
        assertTrue(servico.abrir(novo, null, null).podeAssinar());
    }

    @Test
    void envelopeCanceladoInvalidaOLink() throws Exception {
        String t = token();
        try (Connection c = banco.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE envelopes SET status = 'CANCELED'");
        }
        assertThrows(NaoEncontradoException.class, () -> servico.abrir(t, null, null));
    }

    @Test
    void documentoEPaginaRenderizada() throws Exception {
        String t = token();
        assertEquals(envelope.sha256Original(), sica.assinatura.seguranca.Hashes.sha256Hex(
                Files.readAllBytes(servico.documento(t))));

        byte[] imagem = servico.renderizarPagina(t, 1);
        BufferedImage lida = ImageIO.read(new ByteArrayInputStream(imagem));
        assertNotNull(lida);
        assertTrue(lida.getWidth() <= 1200 && lida.getHeight() <= 1800);

        assertThrows(NaoEncontradoException.class, () -> servico.renderizarPagina(t, 2));
        assertThrows(NaoEncontradoException.class, () -> servico.renderizarPagina(t, 0));
    }

    // ----- Assinar -----

    @Test
    void assinaRegistrandoEvidencias() throws Exception {
        String t = token();

        AssinaturaServico.Resultado r = assinar(t);

        assertEquals(StatusSignatario.SIGNED, r.signatario().status());
        assertNotNull(r.signatario().assinadoEm());
        assertTrue(r.todosAssinaram());

        String[] ev = evento("signer.signed");
        assertEquals("9.9.9.9", ev[1]);
        assertEquals("Navegador/1.0", ev[2]);
        assertEquals(envelope.sha256Original(), ev[3]);
        assertTrue(ev[4].contains("nome_confirmado=Fulano de Tal"));
        assertTrue(ev[4].contains("aceite=true"));
        assertTrue(ev[4].contains("png_sha256="));

        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT nome_confirmado, assinatura_png FROM signers")) {
            rs.next();
            assertEquals("Fulano de Tal", rs.getString(1));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(rs.getBytes(2))));
        }
        // Depois de assinado, o link continua mostrando o status.
        AssinaturaServico.Convite c = servico.abrir(t, null, null);
        assertFalse(c.podeAssinar());
        assertTrue(c.motivo().contains("ja assinou"));
    }

    @Test
    void naoAssinaDuasVezes() throws Exception {
        String t = token();
        assinar(t);
        ConflitoException e = assertThrows(ConflitoException.class, () -> assinar(t));
        assertTrue(e.getMessage().contains("ja assinou"));
        assertEquals(1, eventos().stream().filter(x -> x[0].equals("signer.signed")).count());
    }

    @Test
    void assinaturasSimultaneasDoMesmoLinkGravamApenasUma() throws Exception {
        String t = token();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> futuros = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                try {
                    assinar(t);
                    return true;
                } catch (ConflitoException e) {
                    return false;
                }
            }));
        }
        largada.countDown();
        int sucessos = 0;
        for (Future<Boolean> f : futuros) {
            if (f.get()) {
                sucessos++;
            }
        }
        pool.shutdown();
        assertEquals(1, sucessos);
        assertEquals(1, eventos().stream().filter(x -> x[0].equals("signer.signed")).count());
    }

    @Test
    void nomeConfereIgnorandoAcentosCaixaEEspacos() throws Exception {
        String t = convidar("José da Silva", "jose@exemplo.com").get(0);
        AssinaturaServico.Resultado r = servico.assinar(t, "  JOSE   DA silva ",
                envelope.sha256Original(), true, png, null, null);
        assertEquals(StatusSignatario.SIGNED, r.signatario().status());
    }

    @Test
    void nomeDiferenteEhRecusado() throws Exception {
        String t = token();
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> servico.assinar(t,
                "Outra Pessoa", envelope.sha256Original(), true, png, null, null));
        assertTrue(e.getMessage().contains("nao confere"));
        assertThrows(ValidacaoException.class, () -> servico.assinar(t, " ",
                envelope.sha256Original(), true, png, null, null));
    }

    @Test
    void hashDiferenteDoDocumentoAtualEhRecusado() throws Exception {
        String t = token();
        String outro = "0".repeat(64);
        assertThrows(ConflitoException.class,
                () -> servico.assinar(t, "Fulano de Tal", outro, true, png, null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.assinar(t, "Fulano de Tal", "abc", true, png, null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.assinar(t, "Fulano de Tal", null, true, png, null, null));
        // Maiusculas no hash sao aceitas.
        servico.assinar(t, "Fulano de Tal", envelope.sha256Original().toUpperCase(), true, png,
                null, null);
    }

    @Test
    void exigeAceiteEImagemValida() throws Exception {
        String t = token();
        assertThrows(ValidacaoException.class, () -> servico.assinar(t, "Fulano de Tal",
                envelope.sha256Original(), false, png, null, null));
        assertThrows(ValidacaoException.class, () -> servico.assinar(t, "Fulano de Tal",
                envelope.sha256Original(), true, ImagensDeTeste.vazia(600, 200), null, null));
        assertThrows(ValidacaoException.class, () -> servico.assinar(t, "Fulano de Tal",
                envelope.sha256Original(), true, null, null, null));
        assertFalse(eventos().stream().anyMatch(x -> x[0].equals("signer.signed")));
    }

    @Test
    void originalAdulteradoNoDiscoImpedeAssinatura() throws Exception {
        String t = token();
        Files.write(storage.resolve(envelope.caminhoOriginal()), new byte[] {'x'},
                StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class, () -> assinar(t));
    }

    @Test
    void todosAssinaramSoQuandoOUltimoAssina() throws Exception {
        List<String> t = convidar("Ana Lima", "ana@exemplo.com", "Bia Costa", "bia@exemplo.com");
        AssinaturaServico.Resultado r1 = servico.assinar(t.get(0), "Ana Lima",
                envelope.sha256Original(), true, png, null, null);
        assertFalse(r1.todosAssinaram());
        AssinaturaServico.Resultado r2 = servico.assinar(t.get(1), "Bia Costa",
                envelope.sha256Original(), true, png, null, null);
        assertTrue(r2.todosAssinaram());
    }

    // ----- Recusar -----

    @Test
    void recusaEncerraOEnvelopeEImpedeOsDemais() throws Exception {
        List<String> t = convidar("Ana Lima", "ana@exemplo.com", "Bia Costa", "bia@exemplo.com");

        servico.recusar(t.get(0), "  Valor\nerrado ", "9.9.9.9", "ua");

        assertEquals(StatusEnvelope.DECLINED, envelopes.obter(envelope.id()).status());
        String[] ev = evento("signer.declined");
        assertEquals("motivo=Valor errado", ev[4]);
        AssinaturaServico.Convite c = servico.abrir(t.get(0), null, null);
        assertFalse(c.podeAssinar());
        assertThrows(ConflitoException.class, () -> servico.assinar(t.get(1), "Bia Costa",
                envelope.sha256Original(), true, png, null, null));
        assertFalse(servico.abrir(t.get(1), null, null).podeAssinar());
    }

    @Test
    void recusaExigeMotivoENaoAconteceDepoisDeAssinar() throws Exception {
        String t = token();
        assertThrows(ValidacaoException.class, () -> servico.recusar(t, " ", null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.recusar(t, "x".repeat(501), null, null));
        assinar(t);
        assertThrows(ConflitoException.class, () -> servico.recusar(t, "mudei de ideia", null, null));
    }

    @Test
    void naoGuardaTokenEmClaroNemNaAuditoria() throws Exception {
        String t = token();
        servico.abrir(t, null, null);
        assinar(t);
        try (Connection c = banco.abrir(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM audit_events WHERE detalhe LIKE ? OR ip LIKE ?"
                        + " OR user_agent LIKE ?")) {
            String padrao = "%" + t + "%";
            ps.setString(1, padrao);
            ps.setString(2, padrao);
            ps.setString(3, padrao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
