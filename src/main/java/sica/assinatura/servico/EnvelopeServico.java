package sica.assinatura.servico;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.StatusEnvelope;
import sica.assinatura.repositorio.AuditoriaRepositorio;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.repositorio.EnvelopeRepositorio;
import sica.assinatura.seguranca.Hashes;

/**
 * Regras de criacao e consulta de envelopes.
 *
 * <p>Ao criar um envelope o servico valida o PDF, calcula o SHA-256, grava o
 * original em {@code storage/<id>/original.pdf} e registra o envelope e o evento
 * de auditoria {@code envelope.created} na <b>mesma transacao</b>. O nome do
 * arquivo em disco e fixo e o diretorio e um UUID gerado aqui, entao nada
 * fornecido pelo cliente participa do caminho (sem <i>path traversal</i>).</p>
 *
 * <p>O PDF original nunca e sobrescrito.</p>
 */
public final class EnvelopeServico {

    /** Nome fixo do arquivo original dentro da pasta do envelope. */
    static final String NOME_ORIGINAL = "original.pdf";

    private static final int TITULO_MAX = 200;
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");

    private final Banco banco;
    private final Path storage;
    private final long maxPdfBytes;

    public EnvelopeServico(Banco banco, Path storage, long maxPdfBytes) {
        this.banco = banco;
        this.storage = storage.toAbsolutePath().normalize();
        this.maxPdfBytes = maxPdfBytes;
    }

    /**
     * Cria um envelope em estado {@code DRAFT} a partir de um PDF.
     *
     * @param titulo    titulo do documento (1 a 200 caracteres, sem controle)
     * @param pdf       conteudo do PDF
     * @param ip        IP de origem da requisicao (auditoria)
     * @param userAgent user agent da requisicao (auditoria)
     * @throws ValidacaoException se o titulo ou o PDF forem invalidos
     */
    public Envelope criar(String titulo, byte[] pdf, String ip, String userAgent)
            throws IOException, SQLException {
        String tituloLimpo = validarTitulo(titulo);
        validarPdf(pdf);

        String id = UUID.randomUUID().toString();
        String hash = Hashes.sha256Hex(pdf);
        String relativo = id + "/" + NOME_ORIGINAL;
        Path pasta = storage.resolve(id);
        Path arquivo = pasta.resolve(NOME_ORIGINAL);

        Files.createDirectories(pasta);
        // CREATE_NEW: falha se o arquivo ja existir, garantindo que nada e sobrescrito.
        Files.write(arquivo, pdf, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Envelope envelope = new Envelope(id, tituloLimpo, StatusEnvelope.DRAFT, hash, null,
                relativo, Instant.now().toString(), null, null);

        try (Connection c = banco.abrir()) {
            c.setAutoCommit(false);
            try {
                EnvelopeRepositorio.inserir(c, envelope);
                AuditoriaRepositorio.registrar(c, id, null, "envelope.created", ip, userAgent,
                        hash, "tamanho=" + pdf.length);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            // Banco falhou: nao deixa arquivo orfao no disco.
            removerSilenciosamente(arquivo);
            removerSilenciosamente(pasta);
            throw e;
        }
        return envelope;
    }

    /**
     * Busca um envelope.
     *
     * @throws NaoEncontradoException se o id nao existir
     */
    public Envelope obter(String id) throws SQLException {
        try (Connection c = banco.abrir()) {
            return EnvelopeRepositorio.buscar(c, id)
                    .orElseThrow(() -> new NaoEncontradoException("Envelope nao encontrado."));
        }
    }

    /** Trilha de auditoria do envelope, em ordem de registro. */
    public java.util.List<sica.assinatura.modelo.EventoAuditoria> auditoria(String id)
            throws SQLException {
        try (Connection c = banco.abrir()) {
            return AuditoriaRepositorio.listar(c, id);
        }
    }

    // ----- Validacoes -----

    private static String validarTitulo(String titulo) {
        if (titulo == null || titulo.isBlank()) {
            throw new ValidacaoException("O campo 'titulo' e obrigatorio.");
        }
        String limpo = titulo.strip();
        if (limpo.length() > TITULO_MAX) {
            throw new ValidacaoException("O titulo deve ter no maximo " + TITULO_MAX
                    + " caracteres.");
        }
        if (CONTROLE.matcher(limpo).find()) {
            throw new ValidacaoException("O titulo contem caracteres invalidos.");
        }
        return limpo;
    }

    /**
     * Garante que o conteudo e um PDF legivel: tamanho dentro do limite,
     * assinatura {@code %PDF-} e carregamento bem-sucedido pelo PDFBox. PDFs
     * protegidos por senha sao recusados (nao poderiam receber o selo depois).
     */
    private void validarPdf(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new ValidacaoException("O campo 'arquivo' (PDF) e obrigatorio.");
        }
        if (pdf.length > maxPdfBytes) {
            throw new ValidacaoException("O PDF excede o tamanho maximo de " + maxPdfBytes
                    + " bytes.");
        }
        if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D'
                || pdf[3] != 'F' || pdf[4] != '-') {
            throw new ValidacaoException("O arquivo enviado nao e um PDF.");
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.isEncrypted()) {
                throw new ValidacaoException("PDFs protegidos por senha nao sao aceitos.");
            }
            if (doc.getNumberOfPages() < 1) {
                throw new ValidacaoException("O PDF nao possui paginas.");
            }
        } catch (IOException e) {
            throw new ValidacaoException("PDF invalido, corrompido ou protegido por senha.");
        }
    }

    private static void removerSilenciosamente(Path caminho) {
        try {
            Files.deleteIfExists(caminho);
        } catch (IOException ignorado) {
            // Melhor esforco: a falha original e a que importa.
        }
    }
}
