package sica.assinatura.servico;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import sica.assinatura.email.EmailException;
import sica.assinatura.email.EmailSender;
import sica.assinatura.email.Mensagem;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.modelo.StatusSignatario;
import sica.assinatura.repositorio.AuditoriaRepositorio;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.repositorio.EnvelopeRepositorio;
import sica.assinatura.repositorio.SignatarioRepositorio;
import sica.assinatura.seguranca.TokenServico;

/**
 * Cadastro de signatarios e envio dos convites de assinatura.
 *
 * <h2>Envio</h2>
 * <p>{@link #enviar} roda em duas fases:</p>
 * <ol>
 *   <li><b>Transacao:</b> muda o envelope para {@code SENT}, gera um token novo
 *       para cada signatario pendente (gravando apenas o hash) e registra
 *       {@code envelope.sent}. Tudo ou nada.</li>
 *   <li><b>E-mails:</b> enviados depois do commit, fora da transacao (uma chamada
 *       de rede nao deve segurar o bloqueio do banco). Cada resultado vira um
 *       evento de auditoria ({@code email.sent} ou {@code email.failed}).</li>
 * </ol>
 * <p>Chamar {@code enviar} de novo com o envelope em {@code SENT} reenvia os
 * convites aos signatarios que ainda nao assinaram, gerando tokens novos; os
 * links anteriores deixam de valer. Isso cobre falhas de entrega.</p>
 */
public final class SignatarioServico {

    /** Limite de signatarios por envelope (MVP). */
    public static final int MAX_SIGNATARIOS = 20;

    private static final int NOME_MAX = 200;
    private static final int EMAIL_MAX = 254;
    /** Formato pragmatico de e-mail: local@dominio.tld, sem espacos nem controle. */
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");

    private final Banco banco;
    private final Path storage;
    private final EmailSender email;
    private final String baseUrl;
    private final int tokenValidadeDias;

    public SignatarioServico(Banco banco, Path storage, EmailSender email, String baseUrl,
                             int tokenValidadeDias) {
        this.banco = banco;
        this.storage = storage.toAbsolutePath().normalize();
        this.email = email;
        this.baseUrl = baseUrl;
        this.tokenValidadeDias = tokenValidadeDias;
    }

    /** Resultado do envio de um convite. */
    public record ResultadoEnvio(String signerId, String email, boolean enviado, String erro) {
    }

    /**
     * Adiciona um signatario a um envelope em {@code DRAFT}.
     *
     * @throws NaoEncontradoException se o envelope nao existir
     * @throws ConflitoException      se o envelope nao estiver em DRAFT, o e-mail
     *                                ja estiver cadastrado ou o limite for atingido
     * @throws ValidacaoException     se nome, e-mail ou posicao forem invalidos
     */
    public Signatario adicionar(String envelopeId, String nome, String emailBruto,
                                CampoAssinatura campo, String ip, String userAgent)
            throws IOException, SQLException {
        String nomeLimpo = validarNome(nome);
        String emailLimpo = validarEmail(emailBruto);

        Envelope envelope = carregar(envelopeId);
        if (envelope.status() != StatusEnvelope.DRAFT) {
            throw new ConflitoException("So e possivel adicionar signatarios em envelopes DRAFT.");
        }
        validarCampo(campo, storage.resolve(envelope.caminhoOriginal()));

        Signatario s = new Signatario(UUID.randomUUID().toString(), envelopeId, nomeLimpo,
                emailLimpo, StatusSignatario.PENDING, null, null, campo);

        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                // Bloqueia e revalida dentro da transacao (o status pode ter mudado).
                if (!EnvelopeRepositorio.reservar(c, envelopeId, StatusEnvelope.DRAFT)) {
                    throw new ConflitoException(
                            "So e possivel adicionar signatarios em envelopes DRAFT.");
                }
                if (SignatarioRepositorio.contar(c, envelopeId) >= MAX_SIGNATARIOS) {
                    throw new ConflitoException("Limite de " + MAX_SIGNATARIOS
                            + " signatarios por envelope atingido.");
                }
                if (SignatarioRepositorio.emailExiste(c, envelopeId, emailLimpo)) {
                    throw new ConflitoException("Este e-mail ja e signatario do envelope.");
                }
                SignatarioRepositorio.inserir(c, s);
                AuditoriaRepositorio.registrar(c, envelopeId, s.id(), "signer.added", ip,
                        userAgent, envelope.sha256Original(), "email=" + emailLimpo);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return s;
    }

    /** Lista os signatarios de um envelope existente. */
    public List<Signatario> listar(String envelopeId) throws SQLException {
        try (Connection c = banco.abrir()) {
            return SignatarioRepositorio.listar(c, envelopeId);
        }
    }

    /**
     * Envia (ou reenvia) os convites de assinatura.
     *
     * @return o resultado por signatario convidado
     * @throws ConflitoException se o envelope nao tiver signatarios, nao estiver
     *                           em DRAFT/SENT, ou nao houver ninguem pendente
     */
    public List<ResultadoEnvio> enviar(String envelopeId, String ip, String userAgent)
            throws SQLException {
        Envelope envelope = carregar(envelopeId);
        Instant expira = Instant.now().plus(Duration.ofDays(tokenValidadeDias));
        List<Convite> convites = new ArrayList<>();

        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                boolean primeiroEnvio =
                        EnvelopeRepositorio.transitar(c, envelopeId, StatusEnvelope.DRAFT,
                                StatusEnvelope.SENT);
                if (!primeiroEnvio
                        && !EnvelopeRepositorio.reservar(c, envelopeId, StatusEnvelope.SENT)) {
                    throw new ConflitoException(
                            "O envelope precisa estar em DRAFT ou SENT para enviar convites.");
                }
                List<Signatario> signatarios = SignatarioRepositorio.listar(c, envelopeId);
                if (signatarios.isEmpty()) {
                    throw new ConflitoException("Adicione ao menos um signatario antes de enviar.");
                }
                for (Signatario s : signatarios) {
                    if (s.status() != StatusSignatario.PENDING
                            && s.status() != StatusSignatario.VIEWED) {
                        continue;
                    }
                    TokenServico.Token token = TokenServico.gerar();
                    if (SignatarioRepositorio.definirToken(c, s.id(), token.hash(),
                            expira.toString())) {
                        convites.add(new Convite(s, token.valor()));
                    }
                }
                if (convites.isEmpty()) {
                    throw new ConflitoException("Nenhum signatario pendente para convidar.");
                }
                AuditoriaRepositorio.registrar(c, envelopeId, null,
                        primeiroEnvio ? "envelope.sent" : "envelope.resent", ip, userAgent,
                        envelope.sha256Original(), "convites=" + convites.size());
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }

        // Fase 2: e-mails fora da transacao.
        List<ResultadoEnvio> resultados = new ArrayList<>();
        for (Convite convite : convites) {
            resultados.add(enviarConvite(envelope, convite, expira));
        }
        return resultados;
    }

    // ----- Internos -----

    private record Convite(Signatario signatario, String token) {
    }

    private ResultadoEnvio enviarConvite(Envelope envelope, Convite convite, Instant expira)
            throws SQLException {
        Signatario s = convite.signatario();
        String link = baseUrl + "/assinar/" + convite.token();
        String erro = null;
        try {
            email.enviar(montarMensagem(envelope, s, link, expira));
        } catch (EmailException | RuntimeException e) {
            erro = e.getMessage();
            System.err.println("[Assinatura] Falha ao enviar convite para " + s.email()
                    + ": " + erro);
        }
        try (Connection c = banco.abrir()) {
            AuditoriaRepositorio.registrar(c, envelope.id(), s.id(),
                    erro == null ? "email.sent" : "email.failed", null, null,
                    envelope.sha256Original(), erro == null ? null : limitar(erro, 300));
        }
        return new ResultadoEnvio(s.id(), s.email(), erro == null,
                erro == null ? null : "Falha ao enviar o e-mail.");
    }

    private static Mensagem montarMensagem(Envelope e, Signatario s, String link,
                                           Instant expira) {
        String assunto = "Assinatura solicitada: " + e.titulo();
        String texto = "Ola, " + s.nome() + ".\n\n"
                + "Voce foi convidado(a) a assinar o documento \"" + e.titulo() + "\".\n\n"
                + "Acesse o link abaixo para ler e assinar:\n" + link + "\n\n"
                + "Este link e pessoal e intransferivel; nao o encaminhe.\n"
                + "Valido ate " + expira + " (UTC).\n"
                + "Hash SHA-256 do documento: " + e.sha256Original() + "\n";
        String html = "<p>Ol&aacute;, " + html(s.nome()) + ".</p>"
                + "<p>Voc&ecirc; foi convidado(a) a assinar o documento <b>"
                + html(e.titulo()) + "</b>.</p>"
                + "<p><a href=\"" + html(link) + "\">Ler e assinar o documento</a></p>"
                + "<p>Este link &eacute; pessoal e intransfer&iacute;vel; n&atilde;o o encaminhe."
                + "<br>V&aacute;lido at&eacute; " + html(expira.toString()) + " (UTC).</p>"
                + "<p style=\"color:#666;font-size:12px\">Hash SHA-256 do documento: "
                + html(e.sha256Original()) + "</p>";
        return new Mensagem(s.email(), assunto, texto, html);
    }

    private Envelope carregar(String envelopeId) throws SQLException {
        try (Connection c = banco.abrir()) {
            return EnvelopeRepositorio.buscar(c, envelopeId)
                    .orElseThrow(() -> new NaoEncontradoException("Envelope nao encontrado."));
        }
    }

    private static String validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new ValidacaoException("O campo 'nome' e obrigatorio.");
        }
        String limpo = nome.strip();
        if (limpo.length() > NOME_MAX || CONTROLE.matcher(limpo).find()) {
            throw new ValidacaoException("Nome invalido.");
        }
        return limpo;
    }

    private static String validarEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ValidacaoException("O campo 'email' e obrigatorio.");
        }
        String limpo = email.strip().toLowerCase(Locale.ROOT);
        if (limpo.length() > EMAIL_MAX || !EMAIL.matcher(limpo).matches()) {
            throw new ValidacaoException("E-mail invalido.");
        }
        return limpo;
    }

    /** Confere se o retangulo existe na pagina indicada do PDF original. */
    private static void validarCampo(CampoAssinatura f, Path pdf) throws IOException {
        if (f == null) {
            throw new ValidacaoException("A posicao da assinatura e obrigatoria.");
        }
        if (!finito(f.x()) || !finito(f.y()) || !finito(f.largura()) || !finito(f.altura())
                || f.x() < 0 || f.y() < 0 || f.largura() <= 0 || f.altura() <= 0) {
            throw new ValidacaoException("Posicao da assinatura invalida.");
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (f.pagina() < 1 || f.pagina() > doc.getNumberOfPages()) {
                throw new ValidacaoException("Pagina inexistente: o documento tem "
                        + doc.getNumberOfPages() + " pagina(s).");
            }
            PDRectangle caixa = doc.getPage(f.pagina() - 1).getMediaBox();
            if (f.x() + f.largura() > caixa.getWidth() || f.y() + f.altura() > caixa.getHeight()) {
                throw new ValidacaoException(String.format(Locale.ROOT,
                        "A assinatura ultrapassa a pagina (%.0f x %.0f pt).",
                        caixa.getWidth(), caixa.getHeight()));
            }
        }
    }

    private static boolean finito(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String limitar(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Escapa texto para HTML. */
    static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
