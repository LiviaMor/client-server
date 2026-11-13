package sica.assinatura.servico;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
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
import sica.assinatura.pdf.CertificadosDeTeste;
import sica.assinatura.pdf.ClienteTsaHttp;
import sica.assinatura.pdf.SeloPades;
import sica.assinatura.pdf.TsaFalsa;
import sica.assinatura.pdf.VerificadorPdf;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.seguranca.Hashes;

class FinalizacaoServicoTest {

    static final CampoAssinatura CAMPO = new CampoAssinatura(1, 72, 100, 200, 60);

    @TempDir
    Path pasta;

    TsaFalsa tsa;
    Banco banco;
    Path storage;
    Path publicacao;
    EmailFalso email;
    EnvelopeServico envelopes;
    SignatarioServico signatarios;
    AssinaturaServico assinaturas;
    FinalizacaoServico finalizacao;
    Envelope envelope;

    @BeforeEach
    void preparar() throws Exception {
        tsa = new TsaFalsa();
        banco = new Banco(pasta.resolve("t.db"));
        banco.inicializar();
        storage = pasta.resolve("storage");
        publicacao = pasta.resolve("server-files");
        email = new EmailFalso();
        envelopes = new EnvelopeServico(banco, storage, 1024 * 1024);
        signatarios = new SignatarioServico(banco, storage, email, "https://app", 7);
        assinaturas = new AssinaturaServico(banco, storage);
        finalizacao = new FinalizacaoServico(banco, storage,
                new SeloPades(CertificadosDeTeste.servico(), new ClienteTsaHttp(tsa.url())),
                email, "https://app", publicacao);
        envelope = envelopes.criar("Contrato de Locação", PdfsDeTeste.simples("Contrato"), null, null);
    }

    @AfterEach
    void parar() {
        tsa.close();
    }

    /** Convida e faz todos assinarem (sem finalizar). */
    private void todosAssinam(String... nomesEEmails) throws Exception {
        for (int i = 0; i < nomesEEmails.length; i += 2) {
            signatarios.adicionar(envelope.id(), nomesEEmails[i], nomesEEmails[i + 1], CAMPO,
                    null, null);
        }
        signatarios.enviar(envelope.id(), null, null);
        List<Mensagem> convites = new ArrayList<>(email.enviadas);
        for (int i = 0; i < nomesEEmails.length; i += 2) {
            String alvo = nomesEEmails[i + 1];
            Mensagem m = convites.stream().filter(x -> x.para().equals(alvo)).findFirst().orElseThrow();
            assinaturas.assinar(EmailFalso.token(m), nomesEEmails[i], envelope.sha256Original(),
                    true, ImagensDeTeste.assinatura(600, 200), "9.9.9.9", "ua");
        }
        email.enviadas.clear();
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

    @Test
    void finalizaComSeloCarimboPublicacaoEEmail() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com", "Bia Costa", "bia@exemplo.com");

        Envelope e = finalizacao.finalizar(envelope.id(), "1.2.3.4", "admin");

        assertEquals(StatusEnvelope.COMPLETED, e.status());
        assertNotNull(e.concluidoEm());
        byte[] fin = Files.readAllBytes(storage.resolve(e.id()).resolve("final.pdf"));
        assertEquals(e.sha256Final(), Hashes.sha256Hex(fin));
        assertArrayEquals(fin, finalizacao.pdfFinal(e.id()));
        // O original continua intacto.
        assertEquals(envelope.sha256Original(),
                Hashes.sha256Hex(Files.readAllBytes(storage.resolve(envelope.caminhoOriginal()))));

        VerificadorPdf.Assinatura a = VerificadorPdf.verificar(fin).get(0);
        assertTrue(a.integra(), a.erro());
        assertTrue(a.temCarimbo());

        Path publicado = publicacao.resolve(FinalizacaoServico.nomePublicado(e));
        assertTrue(publicado.getFileName().toString().startsWith("contrato-de-locacao-"));
        assertArrayEquals(fin, Files.readAllBytes(publicado));

        assertEquals(2, email.enviadas.size());
        for (Mensagem m : email.enviadas) {
            assertEquals(1, m.anexos().size());
            assertArrayEquals(fin, m.anexos().get(0).conteudo());
            assertTrue(m.texto().contains(e.sha256Final()));
        }
        List<String> tipos = eventos();
        assertTrue(tipos.containsAll(List.of("envelope.completed", "envelope.published",
                "email.final_sent")));
        assertFalse(Files.exists(storage.resolve(e.id()).resolve("final.pdf.tmp")));
    }

    @Test
    void ehIdempotente() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com");
        Envelope primeiro = finalizacao.finalizar(envelope.id(), null, null);
        int pedidos = tsa.pedidos.get();

        Envelope segundo = finalizacao.finalizar(envelope.id(), null, null);

        assertEquals(primeiro, segundo);
        assertEquals(pedidos, tsa.pedidos.get(), "nao selou de novo");
        assertEquals(1, eventos().stream().filter("envelope.completed"::equals).count());
    }

    @Test
    void finalizacoesSimultaneasConcluemUmaVez() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com");
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Envelope>> futuros = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return finalizacao.finalizar(envelope.id(), null, null);
            }));
        }
        largada.countDown();
        String hash = null;
        for (Future<Envelope> f : futuros) {
            Envelope e = f.get();
            hash = hash == null ? e.sha256Final() : hash;
            assertEquals(hash, e.sha256Final());
        }
        pool.shutdown();
        assertEquals(1, eventos().stream().filter("envelope.completed"::equals).count());
        assertEquals(1, tsa.pedidos.get());
    }

    @Test
    void naoFinalizaComPendentes() throws Exception {
        signatarios.adicionar(envelope.id(), "Ana Lima", "ana@exemplo.com", CAMPO, null, null);
        signatarios.enviar(envelope.id(), null, null);
        assertThrows(ConflitoException.class, () -> finalizacao.finalizar(envelope.id(), null, null));
        assertEquals(0, tsa.pedidos.get());
    }

    @Test
    void naoFinalizaRascunhoNemRecusado() throws Exception {
        assertThrows(ConflitoException.class, () -> finalizacao.finalizar(envelope.id(), null, null));
        try (Connection c = banco.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE envelopes SET status = 'DECLINED'");
        }
        assertThrows(ConflitoException.class, () -> finalizacao.finalizar(envelope.id(), null, null));
        assertThrows(ConflitoException.class, () -> finalizacao.pdfFinal(envelope.id()));
        assertThrows(NaoEncontradoException.class,
                () -> finalizacao.finalizar("00000000-0000-0000-0000-000000000000", null, null));
    }

    @Test
    void falhaDaTsaMantemEnvelopeAbertoEPermiteRepetir() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com");
        tsa.falhar.set(true);

        assertThrows(FinalizacaoException.class, () -> finalizacao.finalizar(envelope.id(), null, null));

        assertEquals(StatusEnvelope.SENT, envelopes.obter(envelope.id()).status());
        assertFalse(Files.exists(storage.resolve(envelope.id()).resolve("final.pdf")));
        assertTrue(eventos().contains("envelope.finalize_failed"));
        assertTrue(email.enviadas.isEmpty());

        tsa.falhar.set(false);
        assertEquals(StatusEnvelope.COMPLETED,
                finalizacao.finalizar(envelope.id(), null, null).status());
    }

    @Test
    void semCertificadoAFinalizacaoFicaIndisponivel() throws Exception {
        FinalizacaoServico semCert =
                new FinalizacaoServico(banco, storage, null, email, "https://app", null);
        assertFalse(semCert.disponivel());
        todosAssinam("Ana Lima", "ana@exemplo.com");
        ConflitoException e = assertThrows(ConflitoException.class,
                () -> semCert.finalizar(envelope.id(), null, null));
        assertTrue(e.getMessage().contains("Certificado"));
    }

    @Test
    void originalAdulteradoImpedeAFinalizacao() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com");
        Files.write(storage.resolve(envelope.caminhoOriginal()), PdfsDeTeste.simples("outro"));
        assertThrows(IllegalStateException.class, () -> finalizacao.finalizar(envelope.id(), null, null));
        assertEquals(StatusEnvelope.SENT, envelopes.obter(envelope.id()).status());
    }

    @Test
    void falhaDeEmailNaoDesfazAConclusao() throws Exception {
        todosAssinam("Ana Lima", "ana@exemplo.com");
        email.falharPara.add("ana@exemplo.com");
        assertEquals(StatusEnvelope.COMPLETED,
                finalizacao.finalizar(envelope.id(), null, null).status());
        assertTrue(eventos().contains("email.final_failed"));
    }

    @Test
    void signatarioBaixaOFinalPeloLink() throws Exception {
        signatarios.adicionar(envelope.id(), "Ana Lima", "ana@exemplo.com", CAMPO, null, null);
        signatarios.enviar(envelope.id(), null, null);
        String token = EmailFalso.token(email.enviadas.get(0));
        assinaturas.assinar(token, "Ana Lima", envelope.sha256Original(), true,
                ImagensDeTeste.assinatura(600, 200), null, null);
        assertThrows(ConflitoException.class, () -> assinaturas.documentoFinal(token));

        Envelope e = finalizacao.finalizar(envelope.id(), null, null);

        assertEquals(e.sha256Final(),
                Hashes.sha256Hex(Files.readAllBytes(assinaturas.documentoFinal(token))));
    }

    @Test
    void nomePublicadoSemAcentosNemCaracteresEspeciais() {
        Envelope e = new Envelope("abcdef12-0000-0000-0000-000000000000", " Contrato: Ação/../X ",
                StatusEnvelope.COMPLETED, "h", null, "p", "t", null, null);
        assertEquals("contrato-acao-x-abcdef12-assinado.pdf", FinalizacaoServico.nomePublicado(e));
        Envelope vazio = new Envelope("abcdef12-0000-0000-0000-000000000000", "!!!",
                StatusEnvelope.COMPLETED, "h", null, "p", "t", null, null);
        assertEquals("documento-abcdef12-assinado.pdf", FinalizacaoServico.nomePublicado(vazio));
    }
}
