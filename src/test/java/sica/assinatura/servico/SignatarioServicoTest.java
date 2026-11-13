package sica.assinatura.servico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sica.assinatura.PdfsDeTeste;
import sica.assinatura.email.EmailFalso;
import sica.assinatura.email.Mensagem;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.modelo.StatusSignatario;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.seguranca.TokenServico;

class SignatarioServicoTest {

    static final CampoAssinatura CAMPO = new CampoAssinatura(1, 72, 100, 200, 60);

    @TempDir
    Path pasta;

    Banco banco;
    EnvelopeServico envelopes;
    SignatarioServico servico;
    EmailFalso email;
    Envelope envelope;

    @BeforeEach
    void preparar() throws Exception {
        banco = new Banco(pasta.resolve("t.db"));
        banco.inicializar();
        Path storage = pasta.resolve("storage");
        envelopes = new EnvelopeServico(banco, storage, 1024 * 1024);
        email = new EmailFalso();
        servico = new SignatarioServico(banco, storage, email, "https://app.exemplo", 7);
        envelope = envelopes.criar("Contrato <X>", PdfsDeTeste.simples("x"), "1.1.1.1", "ua");
    }

    private Signatario adicionar(String nome, String mail) throws Exception {
        return servico.adicionar(envelope.id(), nome, mail, CAMPO, "1.1.1.1", "ua");
    }

    private List<String> eventos() throws Exception {
        List<String> tipos = new ArrayList<>();
        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT tipo FROM audit_events ORDER BY id")) {
            while (rs.next()) {
                tipos.add(rs.getString(1));
            }
        }
        return tipos;
    }

    private String tokenHash(String signerId) throws Exception {
        try (Connection c = banco.abrir(); PreparedStatement ps = c.prepareStatement(
                "SELECT token_hash FROM signers WHERE id = ?")) {
            ps.setString(1, signerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ----- Adicionar -----

    @Test
    void adicionaSignatarioNormalizandoEmail() throws Exception {
        Signatario s = adicionar("  Fulano de Tal ", " Fulano@Exemplo.COM ");

        assertEquals("Fulano de Tal", s.nome());
        assertEquals("fulano@exemplo.com", s.email());
        assertEquals(StatusSignatario.PENDING, s.status());
        List<Signatario> lista = servico.listar(envelope.id());
        assertEquals(1, lista.size());
        assertEquals(CAMPO, lista.get(0).campo());
        assertNull(tokenHash(s.id()));
        assertEquals(List.of("envelope.created", "signer.added"), eventos());
    }

    @Test
    void rejeitaEmailDuplicadoIgnorandoMaiusculas() throws Exception {
        adicionar("A", "a@exemplo.com");
        assertThrows(ConflitoException.class, () -> adicionar("B", "A@EXEMPLO.com"));
    }

    @Test
    void validaNomeEEmail() {
        assertThrows(ValidacaoException.class, () -> adicionar(" ", "a@exemplo.com"));
        assertThrows(ValidacaoException.class, () -> adicionar("a\nb", "a@exemplo.com"));
        assertThrows(ValidacaoException.class, () -> adicionar("A", "sem-arroba"));
        assertThrows(ValidacaoException.class, () -> adicionar("A", "a@semtld"));
        assertThrows(ValidacaoException.class, () -> adicionar("A", "a b@exemplo.com"));
        assertThrows(ValidacaoException.class,
                () -> adicionar("A", "a@exemplo.com\r\nBcc: x@y.com"));
        assertThrows(ValidacaoException.class, () -> adicionar("A", null));
    }

    @Test
    void validaPosicaoContraOPdf() {
        String id = envelope.id();
        // A pagina de teste e Letter (612 x 792 pt) com uma pagina.
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(2, 10, 10, 10, 10), null, null));
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(0, 10, 10, 10, 10), null, null));
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(1, 500, 10, 200, 10), null, null));
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(1, -1, 10, 10, 10), null, null));
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(1, 10, 10, 0, 10), null, null));
        assertThrows(ValidacaoException.class, () -> servico.adicionar(id, "A", "a@e.com",
                new CampoAssinatura(1, Double.NaN, 10, 10, 10), null, null));
        assertThrows(ValidacaoException.class,
                () -> servico.adicionar(id, "A", "a@e.com", null, null, null));
    }

    @Test
    void envelopeInexistenteLancaNaoEncontrado() {
        assertThrows(NaoEncontradoException.class, () -> servico.adicionar(
                "00000000-0000-0000-0000-000000000000", "A", "a@e.com", CAMPO, null, null));
        assertThrows(NaoEncontradoException.class,
                () -> servico.enviar("00000000-0000-0000-0000-000000000000", null, null));
    }

    @Test
    void naoAdicionaDepoisDoEnvio() throws Exception {
        adicionar("A", "a@exemplo.com");
        servico.enviar(envelope.id(), null, null);
        assertThrows(ConflitoException.class, () -> adicionar("B", "b@exemplo.com"));
    }

    @Test
    void respeitaLimiteDeSignatarios() throws Exception {
        for (int i = 0; i < SignatarioServico.MAX_SIGNATARIOS; i++) {
            adicionar("S" + i, "s" + i + "@exemplo.com");
        }
        assertThrows(ConflitoException.class, () -> adicionar("X", "x@exemplo.com"));
    }

    @Test
    void adicoesConcorrentesDoMesmoEmailGravamApenasUma() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> futuros = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Boolean> tarefa = () -> {
                largada.await();
                try {
                    adicionar("Mesmo", "mesmo@exemplo.com");
                    return true;
                } catch (ConflitoException e) {
                    return false;
                }
            };
            futuros.add(pool.submit(tarefa));
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
        assertEquals(1, servico.listar(envelope.id()).size());
    }

    // ----- Enviar -----

    @Test
    void enviaConvitesComLinkETokenSoComoHash() throws Exception {
        Signatario a = adicionar("Ana", "ana@exemplo.com");
        Signatario b = adicionar("Bia", "bia@exemplo.com");
        Instant antes = Instant.now();

        List<SignatarioServico.ResultadoEnvio> r = servico.enviar(envelope.id(), "2.2.2.2", "ua");

        assertEquals(2, r.size());
        assertTrue(r.stream().allMatch(SignatarioServico.ResultadoEnvio::enviado));
        assertEquals(StatusEnvelope.SENT, envelopes.obter(envelope.id()).status());
        assertEquals(2, email.enviadas.size());

        for (Mensagem m : email.enviadas) {
            String token = EmailFalso.token(m);
            assertTrue(m.texto().contains("https://app.exemplo/assinar/" + token));
            assertTrue(m.texto().contains(envelope.sha256Original()));
            String id = m.para().equals("ana@exemplo.com") ? a.id() : b.id();
            assertEquals(TokenServico.hash(token), tokenHash(id));
            assertBancoNaoContem(token);
        }
        Signatario lido = servico.listar(envelope.id()).get(0);
        Instant expira = Instant.parse(lido.tokenExpiraEm());
        assertTrue(expira.isAfter(antes.plus(Duration.ofDays(7)).minusSeconds(5)));
        assertTrue(expira.isBefore(Instant.now().plus(Duration.ofDays(7)).plusSeconds(5)));

        assertEquals(List.of("envelope.created", "signer.added", "signer.added",
                "envelope.sent", "email.sent", "email.sent"), eventos());
    }

    @Test
    void htmlDoEmailEscapaTituloENome() throws Exception {
        adicionar("<script>", "a@exemplo.com");
        servico.enviar(envelope.id(), null, null);
        String html = email.enviadas.get(0).html();
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("Contrato &lt;X&gt;"));
    }

    @Test
    void reenvioGeraTokenNovoEInvalidaOAnterior() throws Exception {
        Signatario a = adicionar("Ana", "ana@exemplo.com");
        servico.enviar(envelope.id(), null, null);
        String primeiro = EmailFalso.token(email.enviadas.get(0));

        servico.enviar(envelope.id(), null, null);
        String segundo = EmailFalso.token(email.enviadas.get(1));

        assertNotEquals(primeiro, segundo);
        assertEquals(TokenServico.hash(segundo), tokenHash(a.id()));
        assertTrue(eventos().contains("envelope.resent"));
    }

    @Test
    void naoEnviaSemSignatarios() throws Exception {
        assertThrows(ConflitoException.class, () -> servico.enviar(envelope.id(), null, null));
        // A transacao foi desfeita: o envelope continua em DRAFT.
        assertEquals(StatusEnvelope.DRAFT, envelopes.obter(envelope.id()).status());
        assertFalse(eventos().contains("envelope.sent"));
    }

    @Test
    void naoEnviaEnvelopeConcluidoOuCancelado() throws Exception {
        adicionar("Ana", "ana@exemplo.com");
        try (Connection c = banco.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE envelopes SET status = 'CANCELED'");
        }
        assertThrows(ConflitoException.class, () -> servico.enviar(envelope.id(), null, null));
        assertTrue(email.enviadas.isEmpty());
    }

    @Test
    void naoReconvidaQuemJaAssinou() throws Exception {
        Signatario a = adicionar("Ana", "ana@exemplo.com");
        adicionar("Bia", "bia@exemplo.com");
        servico.enviar(envelope.id(), null, null);
        try (Connection c = banco.abrir(); PreparedStatement ps = c.prepareStatement(
                "UPDATE signers SET status = 'SIGNED' WHERE id = ?")) {
            ps.setString(1, a.id());
            ps.executeUpdate();
        }
        String hashAssinado = tokenHash(a.id());
        email.enviadas.clear();

        List<SignatarioServico.ResultadoEnvio> r = servico.enviar(envelope.id(), null, null);

        assertEquals(1, r.size());
        assertEquals("bia@exemplo.com", r.get(0).email());
        assertEquals(hashAssinado, tokenHash(a.id()));
    }

    @Test
    void falhaDeEmailEhRegistradaSemDesfazerOEnvio() throws Exception {
        adicionar("Ana", "ana@exemplo.com");
        adicionar("Bia", "bia@exemplo.com");
        email.falharPara.add("bia@exemplo.com");

        List<SignatarioServico.ResultadoEnvio> r = servico.enviar(envelope.id(), null, null);

        assertTrue(r.get(0).enviado());
        assertFalse(r.get(1).enviado());
        assertNotNull(r.get(1).erro());
        assertFalse(r.get(1).erro().contains("simulada"), "detalhe interno nao vaza na resposta");
        assertEquals(StatusEnvelope.SENT, envelopes.obter(envelope.id()).status());
        assertTrue(eventos().contains("email.failed"));

        // Reenvio resolve a falha.
        email.falharPara.clear();
        email.enviadas.clear();
        assertTrue(servico.enviar(envelope.id(), null, null).stream()
                .allMatch(SignatarioServico.ResultadoEnvio::enviado));
    }

    private void assertBancoNaoContem(String token) throws Exception {
        try (Connection c = banco.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(token_hash,'') || COALESCE(token_expira_em,'') FROM signers"
                             + " UNION ALL SELECT COALESCE(detalhe,'') FROM audit_events")) {
            while (rs.next()) {
                assertFalse(rs.getString(1).contains(token), "token em claro no banco");
            }
        }
    }
}
