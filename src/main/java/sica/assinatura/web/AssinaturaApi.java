package sica.assinatura.web;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sica.assinatura.config.Configuracao;
import sica.assinatura.email.EmailSender;
import sica.assinatura.email.EmailSenders;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.EventoAuditoria;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.pdf.CarimboTempo;
import sica.assinatura.pdf.CertificadoAssinatura;
import sica.assinatura.pdf.ClienteTsaHttp;
import sica.assinatura.pdf.SeloPades;
import sica.assinatura.repositorio.Banco;
import sica.assinatura.seguranca.Hashes;
import sica.assinatura.servico.AssinaturaServico;
import sica.assinatura.servico.ConflitoException;
import sica.assinatura.servico.EnvelopeServico;
import sica.assinatura.servico.FinalizacaoException;
import sica.assinatura.servico.FinalizacaoServico;
import sica.assinatura.servico.NaoEncontradoException;
import sica.assinatura.servico.SignatarioServico;
import sica.assinatura.servico.ValidacaoException;
import sica.assinatura.util.Json;

/**
 * Rotas HTTP do modulo de assinatura, registradas em um {@link HttpServer}.
 *
 * <h2>Rotas do emissor (exigem o cabecalho {@code X-Admin-Key})</h2>
 * <ul>
 *   <li>{@code POST /envelopes} &rarr; multipart com {@code titulo} e {@code arquivo} (PDF);
 *       responde 201 com o envelope e seu SHA-256.</li>
 *   <li>{@code GET /envelopes/{id}} &rarr; dados do envelope e de seus signatarios.</li>
 *   <li>{@code POST /envelopes/{id}/signers} &rarr; formulario (urlencoded ou multipart) com
 *       {@code nome}, {@code email}, {@code pagina}, {@code x}, {@code y}, {@code largura},
 *       {@code altura}; responde 201 com o signatario.</li>
 *   <li>{@code POST /envelopes/{id}/send} &rarr; envia (ou reenvia) os convites; responde 200
 *       com o resultado por signatario.</li>
 * </ul>
 *
 * <p>As rotas publicas do signatario ({@code /assinar/...} e {@code /sign/...})
 * ficam em {@link SignatarioApi} e sao registradas junto.</p>
 *
 * <p>Erros sao devolvidos como {@code {"erro":"..."}}; falhas internas nunca
 * expoem detalhes ao cliente (apenas ao log do servidor).</p>
 */
public final class AssinaturaApi {

    /** Margem para cabecalhos/campos do multipart alem do tamanho maximo do PDF. */
    private static final long MARGEM_MULTIPART = 64L * 1024;
    /** Limite do corpo de requisicoes sem arquivo (formularios simples). */
    private static final long LIMITE_FORMULARIO = 16L * 1024;
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ROTA_ENVELOPE =
            Pattern.compile("^/envelopes/(" + UUID + ")(?:/(signers|send|finalize|download|audit))?$");
    /** Limites por IP nas rotas publicas do signatario (por minuto). */
    private static final int LIMITE_GERAL_POR_MINUTO = 120;
    private static final int LIMITE_ESCRITA_POR_MINUTO = 10;

    private final Configuracao config;
    private final EnvelopeServico envelopes;
    private final SignatarioServico signatarios;
    private final FinalizacaoServico finalizacao;
    private final SignatarioApi signatarioApi;

    AssinaturaApi(Configuracao config, EnvelopeServico envelopes, SignatarioServico signatarios,
                  FinalizacaoServico finalizacao, SignatarioApi signatarioApi) {
        this.config = config;
        this.envelopes = envelopes;
        this.signatarios = signatarios;
        this.finalizacao = finalizacao;
        this.signatarioApi = signatarioApi;
    }

    /**
     * Prepara o modulo com o provedor de e-mail definido na configuracao.
     *
     * @throws IllegalStateException se {@code ADMIN_KEY} nao estiver configurada, ou
     *                               se o provedor de e-mail estiver incompleto
     */
    public static AssinaturaApi criar(Configuracao config) throws IOException, SQLException {
        return criar(config, EmailSenders.criar(config));
    }

    /** Variante que recebe o {@link EmailSender} (usada nos testes). */
    public static AssinaturaApi criar(Configuracao config, EmailSender email)
            throws IOException, SQLException {
        return criar(config, email, LIMITE_GERAL_POR_MINUTO, LIMITE_ESCRITA_POR_MINUTO);
    }

    /** Variante que permite ajustar os limites de taxa (usada nos testes). */
    static AssinaturaApi criar(Configuracao config, EmailSender email, int limiteGeral,
                               int limiteEscrita) throws IOException, SQLException {
        if (config.adminKey() == null) {
            throw new IllegalStateException("ADMIN_KEY nao configurada.");
        }
        Banco banco = new Banco(config.caminhoBanco());
        banco.inicializar();
        java.nio.file.Files.createDirectories(config.diretorioStorage());
        EnvelopeServico envelopes =
                new EnvelopeServico(banco, config.diretorioStorage(), config.maxPdfBytes());
        SignatarioServico signatarios = new SignatarioServico(banco, config.diretorioStorage(),
                email, config.baseUrl(), config.tokenValidadeDias());
        FinalizacaoServico finalizacao = new FinalizacaoServico(banco, config.diretorioStorage(),
                criarSelo(config), email, config.baseUrl(), config.diretorioPublicacao());
        SignatarioApi signatarioApi = new SignatarioApi(
                new AssinaturaServico(banco, config.diretorioStorage()), finalizacao,
                new LimitadorTaxa(limiteGeral, 60_000),
                new LimitadorTaxa(limiteEscrita, 60_000));
        return new AssinaturaApi(config, envelopes, signatarios, finalizacao, signatarioApi);
    }

    /**
     * Carrega o certificado e monta o selo. Sem {@code SIGNING_CERT_PATH} a
     * finalizacao fica desativada (com aviso); com o caminho configurado, um
     * certificado invalido impede a inicializacao do modulo.
     */
    private static SeloPades criarSelo(Configuracao config) {
        if (config.caminhoCertificado() == null) {
            System.out.println("[Assinatura] AVISO: SIGNING_CERT_PATH nao definido;"
                    + " a finalizacao (selo PAdES) esta desativada.");
            return null;
        }
        CertificadoAssinatura cert = CertificadoAssinatura.carregar(
                java.nio.file.Path.of(config.caminhoCertificado()), config.senhaCertificado());
        CarimboTempo carimbo = null;
        if (config.carimboAtivo()) {
            carimbo = new ClienteTsaHttp(config.urlTsa());
        } else {
            System.out.println("[Assinatura] AVISO: TSA_URL=none; o selo sera aplicado sem"
                    + " carimbo de tempo (use apenas em desenvolvimento).");
        }
        System.out.println("[Assinatura] Selo PAdES: "
                + cert.certificado().getSubjectX500Principal().getName()
                + (carimbo == null ? "" : " | TSA: " + config.urlTsa()));
        return new SeloPades(cert, carimbo);
    }

    /** Registra as rotas do emissor e do signatario no servidor HTTP. */
    public void registrar(HttpServer server) {
        server.createContext("/envelopes", this::tratarEnvelopes);
        signatarioApi.registrar(server);
    }

    private void tratarEnvelopes(HttpExchange ex) throws IOException {
        try {
            String caminho = ex.getRequestURI().getPath();
            // O contexto casa por prefixo ("/envelopesfoo"); aqui exigimos a rota exata.
            if (!caminho.equals("/envelopes") && !caminho.startsWith("/envelopes/")) {
                Http.erro(ex, 404, "Rota nao encontrada.");
                return;
            }
            if (!autorizado(ex)) {
                Http.erro(ex, 401, "Chave de administrador ausente ou invalida.");
                return;
            }

            if (caminho.equals("/envelopes")) {
                if ("GET".equals(ex.getRequestMethod())) {
                    listarEnvelopes(ex);
                    return;
                }
                Http.exigirMetodo(ex, "POST");
                criarEnvelope(ex);
                return;
            }
            Matcher m = ROTA_ENVELOPE.matcher(caminho);
            if (!m.matches()) {
                Http.erro(ex, 404, "Rota nao encontrada.");
                return;
            }
            String id = m.group(1).toLowerCase(Locale.ROOT);
            String acao = m.group(2);
            if (acao == null) {
                Http.exigirMetodo(ex, "GET");
                consultarEnvelope(ex, id);
            } else if (acao.equals("signers")) {
                Http.exigirMetodo(ex, "POST");
                adicionarSignatario(ex, id);
            } else if (acao.equals("send")) {
                Http.exigirMetodo(ex, "POST");
                enviarConvites(ex, id);
            } else if (acao.equals("finalize")) {
                Http.exigirMetodo(ex, "POST");
                Http.lerCorpo(ex, LIMITE_FORMULARIO);
                Envelope e = finalizacao.finalizar(id, Http.ipOrigem(ex), Http.userAgent(ex));
                Http.json(ex, 200, paraJson(e, signatarios.listar(id)));
            } else if (acao.equals("download")) {
                Http.exigirMetodo(ex, "GET");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + id + "-assinado.pdf\"");
                Http.bytes(ex, 200, "application/pdf", finalizacao.pdfFinal(id));
            } else {
                Http.exigirMetodo(ex, "GET");
                auditoria(ex, id);
            }
        } catch (Http.MetodoNaoPermitidoException e) {
            Http.erro(ex, 405, "Metodo nao permitido.");
        } catch (ValidacaoException e) {
            Http.erro(ex, 400, e.getMessage());
        } catch (NaoEncontradoException e) {
            Http.erro(ex, 404, e.getMessage());
        } catch (ConflitoException e) {
            Http.erro(ex, 409, e.getMessage());
        } catch (FinalizacaoException e) {
            Http.erro(ex, 503, e.getMessage());
        } catch (Http.CorpoGrandeException e) {
            Http.erro(ex, 413, "Requisicao maior que o limite permitido.");
        } catch (Exception e) {
            System.err.println("[Assinatura] Erro interno: " + e);
            Http.erro(ex, 500, "Erro interno.");
        } finally {
            ex.close();
        }
    }

    // ----- Rotas -----

    private void criarEnvelope(HttpExchange ex) throws IOException, SQLException {
        byte[] corpo = Http.lerCorpo(ex, config.maxPdfBytes() + MARGEM_MULTIPART);
        List<Multipart.Parte> partes =
                Multipart.analisar(ex.getRequestHeaders().getFirst("Content-Type"), corpo);
        String titulo = Multipart.campo(partes, "titulo");
        Multipart.Parte arquivo = Multipart.arquivo(partes, "arquivo");
        byte[] pdf = arquivo == null ? null : arquivo.conteudo();

        Envelope envelope = envelopes.criar(titulo, pdf, Http.ipOrigem(ex), Http.userAgent(ex));
        ex.getResponseHeaders().set("Location", "/envelopes/" + envelope.id());
        Http.json(ex, 201, paraJson(envelope, List.of()));
    }

    /** Lista os envelopes (mais recentes primeiro) com seus signatarios. */
    private void listarEnvelopes(HttpExchange ex) throws SQLException, IOException {
        List<String> itens = new ArrayList<>();
        for (Envelope e : envelopes.listar()) {
            itens.add(paraJson(e, signatarios.listar(e.id())));
        }
        Http.json(ex, 200, Json.objeto("envelopes", Json.lista(itens)));
    }

    private void consultarEnvelope(HttpExchange ex, String id) throws SQLException, IOException {
        Envelope envelope = envelopes.obter(id);
        Http.json(ex, 200, paraJson(envelope, signatarios.listar(id)));
    }

    private void adicionarSignatario(HttpExchange ex, String envelopeId)
            throws IOException, SQLException {
        Map<String, String> f = Http.lerFormulario(ex, LIMITE_FORMULARIO, null);
        CampoAssinatura campo = new CampoAssinatura(
                inteiro(f, "pagina"), decimal(f, "x"), decimal(f, "y"),
                decimal(f, "largura"), decimal(f, "altura"));
        Signatario s = signatarios.adicionar(envelopeId, f.get("nome"), f.get("email"), campo,
                Http.ipOrigem(ex), Http.userAgent(ex));
        Http.json(ex, 201, paraJson(s));
    }

    private void enviarConvites(HttpExchange ex, String envelopeId)
            throws IOException, SQLException {
        Http.lerCorpo(ex, LIMITE_FORMULARIO); // corpo ignorado, mas consumido com limite
        List<SignatarioServico.ResultadoEnvio> resultados =
                signatarios.enviar(envelopeId, Http.ipOrigem(ex), Http.userAgent(ex));
        List<String> itens = new ArrayList<>();
        boolean todos = true;
        for (SignatarioServico.ResultadoEnvio r : resultados) {
            todos &= r.enviado();
            itens.add(Json.objeto(
                    "signerId", Json.texto(r.signerId()),
                    "email", Json.texto(r.email()),
                    "enviado", String.valueOf(r.enviado()),
                    "erro", Json.texto(r.erro())));
        }
        Http.json(ex, 200, Json.objeto(
                "envelopeId", Json.texto(envelopeId),
                "status", Json.texto(envelopes.obter(envelopeId).status().name()),
                "todosEnviados", String.valueOf(todos),
                "convites", Json.lista(itens)));
    }

    private void auditoria(HttpExchange ex, String id) throws SQLException, IOException {
        envelopes.obter(id); // 404 se nao existir
        List<String> itens = new ArrayList<>();
        for (EventoAuditoria e : envelopes.auditoria(id)) {
            itens.add(Json.objeto(
                    "id", String.valueOf(e.id()),
                    "tipo", Json.texto(e.tipo()),
                    "signerId", Json.texto(e.signerId()),
                    "ip", Json.texto(e.ip()),
                    "userAgent", Json.texto(e.userAgent()),
                    "sha256Documento", Json.texto(e.sha256Documento()),
                    "detalhe", Json.texto(e.detalhe()),
                    "ocorridoEm", Json.texto(e.ocorridoEm())));
        }
        Http.json(ex, 200, Json.objeto("envelopeId", Json.texto(id), "eventos", Json.lista(itens)));
    }

    // ----- Seguranca -----

    private boolean autorizado(HttpExchange ex) {
        String enviada = ex.getRequestHeaders().getFirst("X-Admin-Key");
        return Hashes.iguaisConstante(enviada, config.adminKey());
    }

    // ----- Entrada -----

    private static int inteiro(Map<String, String> f, String nome) {
        String v = obrigatorio(f, nome);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ValidacaoException("O campo '" + nome + "' deve ser um numero inteiro.");
        }
    }

    private static double decimal(Map<String, String> f, String nome) {
        String v = obrigatorio(f, nome);
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new ValidacaoException("O campo '" + nome + "' deve ser numerico.");
        }
    }

    private static String obrigatorio(Map<String, String> f, String nome) {
        String v = f.get(nome);
        if (v == null || v.isBlank()) {
            throw new ValidacaoException("O campo '" + nome + "' e obrigatorio.");
        }
        return v;
    }

    // ----- Saida -----

    private static String paraJson(Envelope e, List<Signatario> lista) {
        List<String> itens = new ArrayList<>();
        for (Signatario s : lista) {
            itens.add(paraJson(s));
        }
        return Json.objeto(
                "id", Json.texto(e.id()),
                "titulo", Json.texto(e.titulo()),
                "status", Json.texto(e.status().name()),
                "sha256Original", Json.texto(e.sha256Original()),
                "sha256Final", Json.texto(e.sha256Final()),
                "criadoEm", Json.texto(e.criadoEm()),
                "expiraEm", Json.texto(e.expiraEm()),
                "concluidoEm", Json.texto(e.concluidoEm()),
                "signatarios", Json.lista(itens));
    }

    /** Signatario em JSON. Nunca inclui o token nem seu hash. */
    private static String paraJson(Signatario s) {
        CampoAssinatura f = s.campo();
        String campo = f == null ? "null" : Json.objeto(
                "pagina", String.valueOf(f.pagina()),
                "x", numero(f.x()),
                "y", numero(f.y()),
                "largura", numero(f.largura()),
                "altura", numero(f.altura()));
        return Json.objeto(
                "id", Json.texto(s.id()),
                "nome", Json.texto(s.nome()),
                "email", Json.texto(s.email()),
                "status", Json.texto(s.status().name()),
                "tokenExpiraEm", Json.texto(s.tokenExpiraEm()),
                "assinadoEm", Json.texto(s.assinadoEm()),
                "campo", campo);
    }

    private static String numero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
