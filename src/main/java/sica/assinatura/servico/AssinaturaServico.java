package sica.assinatura.servico;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.modelo.StatusSignatario;
import sica.assinatura.repositorio.AuditoriaRepositorio;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.repositorio.EnvelopeRepositorio;
import sica.assinatura.repositorio.SignatarioRepositorio;
import sica.assinatura.seguranca.Hashes;
import sica.assinatura.seguranca.TokenServico;

/**
 * Lado do signatario: abrir o link, ver o documento, assinar ou recusar.
 *
 * <p>O token do link e a unica credencial. Ele chega em claro, e transformado
 * em hash e procurado no banco; um token desconhecido ou substituido por um
 * reenvio resulta em {@link NaoEncontradoException}, e um token vencido em
 * {@link LinkExpiradoException}.</p>
 *
 * <h2>Garantias da assinatura</h2>
 * <ul>
 *   <li><b>Assinou o que viu:</b> o hash informado pelo navegador precisa ser
 *       igual ao hash do original, e o arquivo em disco e re-hasheado na hora.</li>
 *   <li><b>Uma unica vez:</b> {@code UPDATE ... WHERE status IN (PENDING, VIEWED)
 *       AND token_hash = ?} dentro de transacao com bloqueio de escrita; zero
 *       linhas afetadas = outra requisicao ja assinou.</li>
 *   <li><b>Evidencias:</b> nome digitado, IP, user agent, hash e data/hora no
 *       evento {@code signer.signed}.</li>
 * </ul>
 */
public final class AssinaturaServico {

    private static final int MOTIVO_MAX = 500;
    private static final int NOME_MAX = 200;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");
    /** Limites da renderizacao de pagina (pixels), para conter uso de memoria. */
    private static final float ESCALA_MAX = 1.5f;
    private static final int LARGURA_MAX_PX = 1200;
    private static final int ALTURA_MAX_PX = 1800;

    private final Banco banco;
    private final Path storage;

    public AssinaturaServico(Banco banco, Path storage) {
        this.banco = banco;
        this.storage = storage.toAbsolutePath().normalize();
    }

    /** Dimensoes de uma pagina, em pontos. */
    public record Pagina(double largura, double altura) {
    }

    /**
     * O que o signatario ve ao abrir o link.
     *
     * @param podeAssinar {@code true} se ainda e possivel assinar ou recusar
     * @param motivo      por que nao pode assinar (ou {@code null})
     */
    public record Convite(Envelope envelope, Signatario signatario, List<Pagina> paginas,
                          boolean podeAssinar, String motivo) {
    }

    /** Resultado da assinatura. */
    public record Resultado(Signatario signatario, boolean todosAssinaram) {
    }

    /**
     * Abre o convite e registra a visualizacao ({@code signer.viewed}).
     */
    public Convite abrir(String token, String ip, String userAgent)
            throws SQLException, IOException {
        Acesso a = localizar(token);
        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                SignatarioRepositorio.marcarVisto(c, a.signatario().id());
                AuditoriaRepositorio.registrar(c, a.envelope().id(), a.signatario().id(),
                        "signer.viewed", ip, userAgent, a.envelope().sha256Original(), null);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        Acesso atual = localizar(token);
        String motivo = motivoBloqueio(atual);
        return new Convite(atual.envelope(), atual.signatario(), paginas(atual.envelope()),
                motivo == null, motivo);
    }

    /** Caminho do PDF original, para exibicao ao signatario. */
    public Path documento(String token) throws SQLException {
        Acesso a = localizar(token);
        return arquivoOriginal(a.envelope());
    }

    /**
     * Caminho do PDF final assinado, disponivel ao signatario depois que o
     * envelope e concluido.
     *
     * @throws ConflitoException se o envelope ainda nao foi concluido
     */
    public Path documentoFinal(String token) throws SQLException {
        Acesso a = localizar(token);
        if (a.envelope().status() != StatusEnvelope.COMPLETED) {
            throw new ConflitoException("O documento final ainda nao esta disponivel.");
        }
        try (Connection c = banco.abrir()) {
            String relativo = EnvelopeRepositorio.caminhoFinal(c, a.envelope().id())
                    .orElseThrow(() -> new ConflitoException(
                            "O documento final ainda nao esta disponivel."));
            Path p = storage.resolve(relativo).normalize();
            if (!p.startsWith(storage)) {
                throw new IllegalStateException("Caminho de documento fora do storage.");
            }
            return p;
        }
    }

    /**
     * Renderiza uma pagina do documento em PNG (para exibir no navegador sem
     * depender do leitor de PDF do dispositivo).
     *
     * @param pagina numero da pagina, a partir de 1
     */
    public byte[] renderizarPagina(String token, int pagina) throws SQLException, IOException {
        Acesso a = localizar(token);
        try (PDDocument doc = Loader.loadPDF(arquivoOriginal(a.envelope()).toFile())) {
            if (pagina < 1 || pagina > doc.getNumberOfPages()) {
                throw new NaoEncontradoException("Pagina inexistente.");
            }
            PDRectangle caixa = doc.getPage(pagina - 1).getCropBox();
            float escala = Math.min(ESCALA_MAX, Math.min(
                    LARGURA_MAX_PX / Math.max(1f, caixa.getWidth()),
                    ALTURA_MAX_PX / Math.max(1f, caixa.getHeight())));
            BufferedImage img = new PDFRenderer(doc)
                    .renderImage(pagina - 1, escala, ImageType.RGB);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            }
        }
    }

    /**
     * Assina o documento.
     *
     * @param nomeDigitado nome completo digitado pelo signatario (deve conferir
     *                     com o cadastrado, ignorando acentos/maiusculas/espacos)
     * @param sha256Visto  hash do documento que o navegador exibiu
     * @param aceite       aceite dos termos (obrigatorio)
     * @param png          imagem da assinatura desenhada
     * @throws ConflitoException se ja tiver assinado/recusado, ou o envelope nao
     *                           estiver aberto para assinatura
     */
    public Resultado assinar(String token, String nomeDigitado, String sha256Visto,
                             boolean aceite, byte[] png, String ip, String userAgent)
            throws SQLException, IOException {
        Acesso a = localizar(token);
        exigirAberto(a);

        if (!aceite) {
            throw new ValidacaoException("E necessario aceitar os termos para assinar.");
        }
        String nome = validarNomeDigitado(nomeDigitado, a.signatario().nome());
        String hash = sha256Visto == null ? "" : sha256Visto.strip().toLowerCase(Locale.ROOT);
        if (!HASH.matcher(hash).matches()) {
            throw new ValidacaoException("Hash do documento ausente ou invalido.");
        }
        if (!hash.equals(a.envelope().sha256Original())) {
            throw new ConflitoException("O documento exibido nao corresponde ao documento atual."
                    + " Recarregue a pagina.");
        }
        conferirIntegridade(a.envelope());
        byte[] imagem = ImagemAssinatura.validarENormalizar(png);

        String agora = Instant.now().toString();
        boolean todos;
        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                if (!EnvelopeRepositorio.reservar(c, a.envelope().id(), StatusEnvelope.SENT)) {
                    throw new ConflitoException("Este envelope nao esta mais aberto para assinatura.");
                }
                if (!SignatarioRepositorio.assinar(c, a.signatario().id(), a.tokenHash(), nome,
                        imagem, agora)) {
                    throw new ConflitoException("Este convite ja foi utilizado.");
                }
                AuditoriaRepositorio.registrar(c, a.envelope().id(), a.signatario().id(),
                        "signer.signed", ip, userAgent, hash,
                        "nome_confirmado=" + nome + "; aceite=true; png_sha256="
                                + Hashes.sha256Hex(imagem));
                todos = SignatarioRepositorio.contarNaoAssinados(c, a.envelope().id()) == 0;
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return new Resultado(localizar(token).signatario(), todos);
    }

    /**
     * Recusa a assinatura. O envelope passa a {@code DECLINED} e nao pode mais
     * ser concluido.
     */
    public Signatario recusar(String token, String motivo, String ip, String userAgent)
            throws SQLException {
        Acesso a = localizar(token);
        exigirAberto(a);
        String motivoLimpo = motivo == null ? "" : motivo.strip();
        if (motivoLimpo.isEmpty()) {
            throw new ValidacaoException("Informe o motivo da recusa.");
        }
        if (motivoLimpo.length() > MOTIVO_MAX) {
            throw new ValidacaoException("O motivo deve ter no maximo " + MOTIVO_MAX
                    + " caracteres.");
        }
        motivoLimpo = motivoLimpo.replaceAll("\\p{Cntrl}+", " ");

        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                if (!EnvelopeRepositorio.transitar(c, a.envelope().id(), StatusEnvelope.SENT,
                        StatusEnvelope.DECLINED)) {
                    throw new ConflitoException("Este envelope nao esta mais aberto para assinatura.");
                }
                if (!SignatarioRepositorio.recusar(c, a.signatario().id(), a.tokenHash())) {
                    throw new ConflitoException("Este convite ja foi utilizado.");
                }
                AuditoriaRepositorio.registrar(c, a.envelope().id(), a.signatario().id(),
                        "signer.declined", ip, userAgent, a.envelope().sha256Original(),
                        "motivo=" + motivoLimpo);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return localizar(token).signatario();
    }

    // ----- Internos -----

    private record Acesso(Envelope envelope, Signatario signatario, String tokenHash) {
    }

    /**
     * Resolve o token. Token mal formado ou desconhecido vira 404 (mesma
     * mensagem, para nao revelar qual caso ocorreu); vencido vira 410.
     */
    private Acesso localizar(String token) throws SQLException {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw linkInvalido();
        }
        String hash = TokenServico.hash(token);
        try (Connection c = banco.abrir()) {
            Signatario s = SignatarioRepositorio.buscarPorTokenHash(c, hash)
                    .orElseThrow(AssinaturaServico::linkInvalido);
            Envelope e = EnvelopeRepositorio.buscar(c, s.envelopeId())
                    .orElseThrow(AssinaturaServico::linkInvalido);
            if (e.status() == StatusEnvelope.CANCELED) {
                throw linkInvalido();
            }
            boolean concluido = s.status() == StatusSignatario.SIGNED
                    || s.status() == StatusSignatario.DECLINED;
            // Depois de assinar, o link continua mostrando o status (nao expira para leitura).
            if (!concluido && (s.tokenExpiraEm() == null
                    || !Instant.parse(s.tokenExpiraEm()).isAfter(Instant.now()))) {
                throw new LinkExpiradoException("Este link de assinatura expirou."
                        + " Solicite um novo convite ao emissor.");
            }
            return new Acesso(e, s, hash);
        }
    }

    private static String motivoBloqueio(Acesso a) {
        return switch (a.signatario().status()) {
            case SIGNED -> "Voce ja assinou este documento.";
            case DECLINED -> "Voce recusou a assinatura deste documento.";
            default -> a.envelope().status() == StatusEnvelope.SENT
                    ? null
                    : "Este envelope nao esta mais aberto para assinatura.";
        };
    }

    private static void exigirAberto(Acesso a) {
        String motivo = motivoBloqueio(a);
        if (motivo != null) {
            throw new ConflitoException(motivo);
        }
    }

    private static String validarNomeDigitado(String digitado, String cadastrado) {
        if (digitado == null || digitado.isBlank()) {
            throw new ValidacaoException("Digite seu nome completo.");
        }
        String limpo = digitado.strip().replaceAll("\\s+", " ");
        if (limpo.length() > NOME_MAX || CONTROLE.matcher(limpo).find()) {
            throw new ValidacaoException("Nome invalido.");
        }
        if (!normalizar(limpo).equals(normalizar(cadastrado))) {
            throw new ValidacaoException(
                    "O nome digitado nao confere com o nome cadastrado para este convite.");
        }
        return limpo;
    }

    /** Remove acentos, caixa e espacos repetidos, para comparar nomes. */
    static String normalizar(String nome) {
        String semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcento.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Path arquivoOriginal(Envelope e) {
        Path p = storage.resolve(e.caminhoOriginal()).normalize();
        if (!p.startsWith(storage)) {
            throw new IllegalStateException("Caminho de documento fora do storage.");
        }
        return p;
    }

    /** O arquivo em disco ainda tem o hash registrado na criacao? */
    private void conferirIntegridade(Envelope e) throws IOException {
        String atual = Hashes.sha256Hex(Files.readAllBytes(arquivoOriginal(e)));
        if (!atual.equals(e.sha256Original())) {
            throw new IllegalStateException("Integridade do original violada no envelope "
                    + e.id());
        }
    }

    private List<Pagina> paginas(Envelope e) throws IOException {
        List<Pagina> lista = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(arquivoOriginal(e).toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle caixa = doc.getPage(i).getCropBox();
                lista.add(new Pagina(caixa.getWidth(), caixa.getHeight()));
            }
        }
        return lista;
    }

    private static NaoEncontradoException linkInvalido() {
        return new NaoEncontradoException("Link de assinatura invalido.");
    }
}
