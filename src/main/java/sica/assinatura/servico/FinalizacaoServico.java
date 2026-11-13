package sica.assinatura.servico;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import sica.assinatura.email.EmailException;
import sica.assinatura.email.EmailSender;
import sica.assinatura.email.Mensagem;
import sica.assinatura.modelo.AssinaturaRegistrada;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.EventoAuditoria;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.pdf.MontadorPdf;
import sica.assinatura.pdf.SeloPades;
import sica.assinatura.repositorio.AuditoriaRepositorio;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.repositorio.EnvelopeRepositorio;
import sica.assinatura.repositorio.SignatarioRepositorio;
import sica.assinatura.seguranca.Hashes;

/**
 * Gera o PDF final quando todos assinaram.
 *
 * <ol>
 *   <li>confere a integridade do original (hash registrado na criacao);</li>
 *   <li>insere as imagens das assinaturas e anexa a pagina de auditoria;</li>
 *   <li>aplica o selo PAdES com o certificado do servico e o carimbo de tempo;</li>
 *   <li>grava {@code storage/<id>/final.pdf} e, na mesma transacao, muda o
 *       envelope para {@code COMPLETED} com o hash final e registra
 *       {@code envelope.completed};</li>
 *   <li>publica uma copia na pasta do servidor TCP do SiCA e envia o PDF final
 *       por e-mail a todos os signatarios (melhor esforco, auditado).</li>
 * </ol>
 *
 * <p>E idempotente: chamar de novo um envelope ja concluido devolve o mesmo
 * resultado. Chamadas simultaneas para o mesmo envelope sao serializadas. Se a
 * TSA falhar, nada e gravado como concluido; o evento
 * {@code envelope.finalize_failed} fica na auditoria e a operacao pode ser
 * repetida pelo emissor.</p>
 */
public final class FinalizacaoServico {

    static final String NOME_FINAL = "final.pdf";
    private static final DateTimeFormatter DATA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Banco banco;
    private final Path storage;
    private final SeloPades selo;
    private final EmailSender email;
    private final String baseUrl;
    private final Path publicacao;
    private final ConcurrentHashMap<String, Object> travas = new ConcurrentHashMap<>();

    /**
     * @param selo       selo PAdES, ou {@code null} se o certificado nao estiver
     *                   configurado (a finalizacao fica indisponivel)
     * @param publicacao pasta do servidor TCP do SiCA, ou {@code null} para nao publicar
     */
    public FinalizacaoServico(Banco banco, Path storage, SeloPades selo, EmailSender email,
                              String baseUrl, Path publicacao) {
        this.banco = banco;
        this.storage = storage.toAbsolutePath().normalize();
        this.selo = selo;
        this.email = email;
        this.baseUrl = baseUrl;
        this.publicacao = publicacao == null ? null : publicacao.toAbsolutePath().normalize();
    }

    /** A finalizacao esta disponivel (certificado configurado)? */
    public boolean disponivel() {
        return selo != null;
    }

    /**
     * Finaliza o envelope.
     *
     * @throws ConflitoException     se ainda houver pendentes, o envelope nao
     *                               estiver em SENT, ou nao houver certificado
     * @throws FinalizacaoException  se o selo ou o carimbo falharem (repetivel)
     */
    public Envelope finalizar(String envelopeId, String ip, String userAgent)
            throws SQLException, IOException {
        Object trava = travas.computeIfAbsent(envelopeId, k -> new Object());
        synchronized (trava) {
            try {
                return finalizarComTrava(envelopeId, ip, userAgent);
            } finally {
                travas.remove(envelopeId, trava);
            }
        }
    }

    private Envelope finalizarComTrava(String envelopeId, String ip, String userAgent)
            throws SQLException, IOException {
        Envelope envelope;
        List<AssinaturaRegistrada> assinaturas;
        List<EventoAuditoria> eventos;
        try (Connection c = banco.abrir()) {
            envelope = EnvelopeRepositorio.buscar(c, envelopeId)
                    .orElseThrow(() -> new NaoEncontradoException("Envelope nao encontrado."));
            if (envelope.status() == StatusEnvelope.COMPLETED) {
                return envelope;
            }
            if (envelope.status() != StatusEnvelope.SENT) {
                throw new ConflitoException("So envelopes enviados podem ser finalizados.");
            }
            if (selo == null) {
                throw new ConflitoException("Certificado de assinatura nao configurado"
                        + " (SIGNING_CERT_PATH).");
            }
            if (SignatarioRepositorio.contarNaoAssinados(c, envelopeId) > 0) {
                throw new ConflitoException("Ainda ha signatarios que nao assinaram.");
            }
            assinaturas = SignatarioRepositorio.assinaturas(c, envelopeId);
            eventos = AuditoriaRepositorio.listar(c, envelopeId);
        }
        if (assinaturas.isEmpty()) {
            throw new ConflitoException("O envelope nao possui assinaturas.");
        }

        Path pasta = storage.resolve(envelope.id()).normalize();
        Path original = storage.resolve(envelope.caminhoOriginal()).normalize();
        if (!original.startsWith(storage) || !pasta.startsWith(storage)) {
            throw new IllegalStateException("Caminho fora do storage.");
        }
        byte[] bytesOriginal = Files.readAllBytes(original);
        if (!Hashes.sha256Hex(bytesOriginal).equals(envelope.sha256Original())) {
            throw new IllegalStateException("Integridade do original violada no envelope "
                    + envelope.id());
        }

        Instant agora = Instant.now();
        byte[] selado;
        try {
            byte[] montado = MontadorPdf.montar(bytesOriginal, envelope, assinaturas, eventos,
                    baseUrl + "/verify", agora);
            Calendar data = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            data.setTimeInMillis(agora.toEpochMilli());
            selado = selo.selar(montado, "Selo do serviço de assinatura Angel Signature - envelope "
                    + envelope.id(), data);
        } catch (IOException | RuntimeException e) {
            registrarFalha(envelope, e);
            throw new FinalizacaoException("Nao foi possivel selar o documento agora"
                    + " (carimbo de tempo ou certificado indisponivel). Tente novamente.", e);
        }

        String hashFinal = Hashes.sha256Hex(selado);
        String relativo = envelope.id() + "/" + NOME_FINAL;
        Path destino = pasta.resolve(NOME_FINAL);
        Path temporario = pasta.resolve(NOME_FINAL + ".tmp");
        Files.write(temporario, selado);

        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                if (!EnvelopeRepositorio.concluir(c, envelope.id(), hashFinal, relativo,
                        agora.toString())) {
                    throw new ConflitoException("O envelope mudou de estado durante a finalizacao.");
                }
                AuditoriaRepositorio.registrar(c, envelope.id(), null, "envelope.completed", ip,
                        userAgent, hashFinal, "sha256_original=" + envelope.sha256Original()
                                + "; carimbo_tempo=" + (selo.temCarimbo() ? "sim" : "nao")
                                + "; tamanho=" + selado.length);
                // Move antes do commit: se o commit falhar, o arquivo e removido abaixo.
                Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                c.commit();
            } catch (SQLException | RuntimeException | IOException e) {
                c.rollback();
                Files.deleteIfExists(destino);
                throw e;
            }
        } finally {
            Files.deleteIfExists(temporario);
        }

        Envelope concluido;
        try (Connection c = banco.abrir()) {
            concluido = EnvelopeRepositorio.buscar(c, envelope.id()).orElseThrow();
        }
        publicar(concluido, selado);
        notificar(concluido, assinaturas, selado);
        return concluido;
    }

    /** PDF final de um envelope concluido. */
    public byte[] pdfFinal(String envelopeId) throws SQLException, IOException {
        String relativo;
        try (Connection c = banco.abrir()) {
            Envelope e = EnvelopeRepositorio.buscar(c, envelopeId)
                    .orElseThrow(() -> new NaoEncontradoException("Envelope nao encontrado."));
            if (e.status() != StatusEnvelope.COMPLETED) {
                throw new ConflitoException("O envelope ainda nao foi concluido.");
            }
            relativo = EnvelopeRepositorio.caminhoFinal(c, envelopeId).orElseThrow();
        }
        Path arquivo = storage.resolve(relativo).normalize();
        if (!arquivo.startsWith(storage)) {
            throw new IllegalStateException("Caminho fora do storage.");
        }
        return Files.readAllBytes(arquivo);
    }

    /** Nome do arquivo publicado no SiCA (sem acentos nem caracteres especiais). */
    static String nomePublicado(Envelope e) {
        String base = Normalizer.normalize(e.titulo(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "")
                .toLowerCase(Locale.ROOT);
        if (base.length() > 50) {
            base = base.substring(0, 50).replaceAll("-+$", "");
        }
        if (base.isEmpty()) {
            base = "documento";
        }
        return base + "-" + e.id().substring(0, 8) + "-assinado.pdf";
    }

    // ----- Internos -----

    private void registrarFalha(Envelope e, Exception erro) {
        String msg = String.valueOf(erro.getMessage());
        System.err.println("[Assinatura] Falha ao finalizar o envelope " + e.id() + ": " + msg);
        try (Connection c = banco.abrir()) {
            AuditoriaRepositorio.registrar(c, e.id(), null, "envelope.finalize_failed", null,
                    null, e.sha256Original(), msg.length() > 300 ? msg.substring(0, 300) : msg);
        } catch (SQLException ignorado) {
            // A falha original e a que importa.
        }
    }

    /** Copia o PDF final para a pasta do servidor TCP (aparece no LIST/DOWNLOAD). */
    private void publicar(Envelope e, byte[] pdf) {
        if (publicacao == null) {
            return;
        }
        try {
            Files.createDirectories(publicacao);
            Path destino = publicacao.resolve(nomePublicado(e)).normalize();
            if (!destino.startsWith(publicacao)) {
                throw new IOException("Nome de publicacao invalido.");
            }
            Files.write(destino, pdf);
            try (Connection c = banco.abrir()) {
                AuditoriaRepositorio.registrar(c, e.id(), null, "envelope.published", null, null,
                        e.sha256Final(), "arquivo=" + destino.getFileName());
            }
        } catch (IOException | SQLException ex) {
            System.err.println("[Assinatura] Falha ao publicar o envelope " + e.id() + ": "
                    + ex.getMessage());
        }
    }

    /** Envia o PDF final a todos os signatarios, auditando cada envio. */
    private void notificar(Envelope e, List<AssinaturaRegistrada> assinaturas, byte[] pdf) {
        Mensagem.Anexo anexo = new Mensagem.Anexo(nomePublicado(e), pdf);
        for (AssinaturaRegistrada a : assinaturas) {
            String para = a.signatario().email();
            String texto = "Olá, " + a.signatario().nome() + ".\n\n"
                    + "O documento \"" + e.titulo() + "\" foi assinado por todos os signatários"
                    + " e finalizado em " + DATA.format(Instant.parse(e.concluidoEm())) + ".\n\n"
                    + "O PDF final, selado digitalmente, segue em anexo.\n"
                    + "SHA-256 do arquivo final: " + e.sha256Final() + "\n"
                    + "Para verificar a autenticidade: " + baseUrl + "/verify\n\n"
                    + "Angel Signature — Anjos Tecnologia\n";
            String html = "<p>Ol&aacute;, " + SignatarioServico.html(a.signatario().nome())
                    + ".</p><p>O documento <b>" + SignatarioServico.html(e.titulo())
                    + "</b> foi assinado por todos os signat&aacute;rios e finalizado em "
                    + SignatarioServico.html(DATA.format(Instant.parse(e.concluidoEm())))
                    + ".</p><p>O PDF final, selado digitalmente, segue em anexo.</p>"
                    + "<p style=\"color:#666;font-size:12px\">SHA-256 do arquivo final: "
                    + SignatarioServico.html(e.sha256Final()) + "</p>"
                    + "<p style=\"color:#666;font-size:12px\">Angel Signature &mdash; Anjos Tecnologia</p>";
            String erro = null;
            try {
                email.enviar(new Mensagem(para, "Documento assinado: " + e.titulo(), texto, html,
                        List.of(anexo)));
            } catch (EmailException | RuntimeException ex) {
                erro = ex.getMessage();
                System.err.println("[Assinatura] Falha ao enviar o PDF final para " + para
                        + ": " + erro);
            }
            try (Connection c = banco.abrir()) {
                AuditoriaRepositorio.registrar(c, e.id(), a.signatario().id(),
                        erro == null ? "email.final_sent" : "email.final_failed", null, null,
                        e.sha256Final(), erro == null ? null
                                : (erro.length() > 300 ? erro.substring(0, 300) : erro));
            } catch (SQLException ex) {
                System.err.println("[Assinatura] Falha ao auditar envio final: " + ex.getMessage());
            }
        }
    }
}
