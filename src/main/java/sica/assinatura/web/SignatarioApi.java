package sica.assinatura.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.servico.AssinaturaServico;
import sica.assinatura.servico.ConflitoException;
import sica.assinatura.servico.FinalizacaoServico;
import sica.assinatura.servico.ImagemAssinatura;
import sica.assinatura.servico.LinkExpiradoException;
import sica.assinatura.servico.NaoEncontradoException;
import sica.assinatura.servico.ValidacaoException;
import sica.assinatura.util.Json;

/**
 * Rotas publicas do signatario (sem login: o token do link e a credencial).
 *
 * <h2>Pagina (deep link)</h2>
 * <ul>
 *   <li>{@code GET /assinar/{token}} &rarr; pagina HTML de assinatura</li>
 *   <li>{@code GET /assinar/app.js}, {@code /assinar/estilo.css} &rarr; recursos da pagina</li>
 * </ul>
 * <h2>API</h2>
 * <ul>
 *   <li>{@code GET /sign/{token}} &rarr; dados do convite (registra a visualizacao)</li>
 *   <li>{@code GET /sign/{token}/documento} &rarr; PDF original</li>
 *   <li>{@code GET /sign/{token}/final} &rarr; PDF final assinado (apos a conclusao)</li>
 *   <li>{@code GET /sign/{token}/paginas/{n}} &rarr; pagina renderizada em PNG</li>
 *   <li>{@code POST /sign/{token}} &rarr; multipart: {@code nome}, {@code sha256},
 *       {@code aceite=true} e o arquivo {@code assinatura} (PNG)</li>
 *   <li>{@code POST /sign/{token}/decline} &rarr; formulario com {@code motivo}</li>
 * </ul>
 *
 * <p>O token nunca e registrado em log. Todas as respostas levam
 * {@code Referrer-Policy: no-referrer} (o token esta na URL) e
 * {@code Cache-Control: no-store}; a pagina leva CSP restritiva e bloqueio de
 * enquadramento (contra <i>clickjacking</i>).</p>
 */
final class SignatarioApi {

    private static final String TOKEN = "([A-Za-z0-9_-]{43})";
    private static final Pattern ROTA_SIGN =
            Pattern.compile("^/sign/" + TOKEN + "(?:/(documento|final|decline|paginas/(\\d{1,4})))?$");
    private static final Pattern ROTA_PAGINA = Pattern.compile("^/assinar/" + TOKEN + "$");
    private static final long LIMITE_ASSINATURA = ImagemAssinatura.MAX_BYTES + 16L * 1024;
    private static final long LIMITE_RECUSA = 8L * 1024;
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self';"
            + " img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none';"
            + " base-uri 'none'; form-action 'none'";

    private final AssinaturaServico servico;
    private final FinalizacaoServico finalizacao;
    private final LimitadorTaxa geral;
    private final LimitadorTaxa escrita;
    private final byte[] html;
    private final byte[] js;
    private final byte[] css;

    SignatarioApi(AssinaturaServico servico, FinalizacaoServico finalizacao, LimitadorTaxa geral,
                  LimitadorTaxa escrita) {
        this.servico = servico;
        this.finalizacao = finalizacao;
        this.geral = geral;
        this.escrita = escrita;
        this.html = recurso("assinar.html");
        this.js = recurso("app.js");
        this.css = recurso("estilo.css");
    }

    void registrar(HttpServer server) {
        server.createContext("/sign/", this::tratarApi);
        server.createContext("/assinar/", this::tratarPagina);
    }

    // ----- Pagina -----

    private void tratarPagina(HttpExchange ex) throws IOException {
        try {
            String ip = Http.ipOrigem(ex);
            if (!geral.permitir(ip)) {
                limiteExcedido(ex, geral, ip);
                return;
            }
            Http.exigirMetodo(ex, "GET");
            String caminho = ex.getRequestURI().getPath();
            if (caminho.equals("/assinar/app.js")) {
                Http.bytes(ex, 200, "text/javascript; charset=utf-8", js);
            } else if (caminho.equals("/assinar/estilo.css")) {
                Http.bytes(ex, 200, "text/css; charset=utf-8", css);
            } else if (ROTA_PAGINA.matcher(caminho).matches()) {
                // A pagina e estatica: o JS le o token da URL e chama a API.
                ex.getResponseHeaders().set("Content-Security-Policy", CSP);
                ex.getResponseHeaders().set("X-Frame-Options", "DENY");
                Http.bytes(ex, 200, "text/html; charset=utf-8", html);
            } else {
                Http.bytes(ex, 404, "text/plain; charset=utf-8",
                        "Link de assinatura invalido.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Http.MetodoNaoPermitidoException e) {
            Http.erro(ex, 405, "Metodo nao permitido.");
        } catch (Exception e) {
            System.err.println("[Assinatura] Erro interno na pagina: " + e);
            Http.erro(ex, 500, "Erro interno.");
        } finally {
            ex.close();
        }
    }

    // ----- API -----

    private void tratarApi(HttpExchange ex) throws IOException {
        try {
            String ip = Http.ipOrigem(ex);
            boolean ehEscrita = "POST".equals(ex.getRequestMethod());
            if (!geral.permitir(ip)) {
                limiteExcedido(ex, geral, ip);
                return;
            }
            if (ehEscrita && !escrita.permitir(ip)) {
                limiteExcedido(ex, escrita, ip);
                return;
            }
            Matcher m = ROTA_SIGN.matcher(ex.getRequestURI().getPath());
            if (!m.matches()) {
                Http.erro(ex, 404, "Link de assinatura invalido.");
                return;
            }
            String token = m.group(1);
            String acao = m.group(2);
            if (acao == null) {
                if (ehEscrita) {
                    assinar(ex, token);
                } else {
                    Http.exigirMetodo(ex, "GET");
                    abrir(ex, token);
                }
            } else if (acao.equals("documento")) {
                Http.exigirMetodo(ex, "GET");
                ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"documento.pdf\"");
                Http.bytes(ex, 200, "application/pdf", Files.readAllBytes(servico.documento(token)));
            } else if (acao.equals("final")) {
                Http.exigirMetodo(ex, "GET");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"documento-assinado.pdf\"");
                Http.bytes(ex, 200, "application/pdf",
                        Files.readAllBytes(servico.documentoFinal(token)));
            } else if (acao.equals("decline")) {
                Http.exigirMetodo(ex, "POST");
                recusar(ex, token);
            } else {
                Http.exigirMetodo(ex, "GET");
                int pagina = Integer.parseInt(m.group(3));
                Http.bytes(ex, 200, "image/png", servico.renderizarPagina(token, pagina));
            }
        } catch (Http.MetodoNaoPermitidoException e) {
            Http.erro(ex, 405, "Metodo nao permitido.");
        } catch (ValidacaoException e) {
            Http.erro(ex, 400, e.getMessage());
        } catch (NaoEncontradoException e) {
            Http.erro(ex, 404, e.getMessage());
        } catch (ConflitoException e) {
            Http.erro(ex, 409, e.getMessage());
        } catch (LinkExpiradoException e) {
            Http.erro(ex, 410, e.getMessage());
        } catch (Http.CorpoGrandeException e) {
            Http.erro(ex, 413, "Requisicao maior que o limite permitido.");
        } catch (Exception e) {
            // Nunca registrar a URL: ela contem o token.
            System.err.println("[Assinatura] Erro interno na API do signatario: " + e);
            Http.erro(ex, 500, "Erro interno.");
        } finally {
            ex.close();
        }
    }

    private void abrir(HttpExchange ex, String token) throws SQLException, IOException {
        AssinaturaServico.Convite c =
                servico.abrir(token, Http.ipOrigem(ex), Http.userAgent(ex));
        List<String> paginas = new ArrayList<>();
        for (AssinaturaServico.Pagina p : c.paginas()) {
            paginas.add(Json.objeto("largura", numero(p.largura()), "altura", numero(p.altura())));
        }
        Http.json(ex, 200, Json.objeto(
                "envelope", Json.objeto(
                        "titulo", Json.texto(c.envelope().titulo()),
                        "status", Json.texto(c.envelope().status().name()),
                        "sha256", Json.texto(c.envelope().sha256Original()),
                        "paginas", Json.lista(paginas)),
                "signatario", signatario(c.signatario()),
                "podeAssinar", String.valueOf(c.podeAssinar()),
                "motivo", Json.texto(c.motivo())));
    }

    private void assinar(HttpExchange ex, String token) throws SQLException, IOException {
        Map<String, byte[]> arquivos = new HashMap<>();
        Map<String, String> f = Http.lerFormulario(ex, LIMITE_ASSINATURA, arquivos);
        AssinaturaServico.Resultado r = servico.assinar(token, f.get("nome"), f.get("sha256"),
                "true".equals(f.get("aceite")), arquivos.get("assinatura"),
                Http.ipOrigem(ex), Http.userAgent(ex));
        boolean finalizado = false;
        if (r.todosAssinaram() && finalizacao.disponivel()) {
            // A assinatura ja esta gravada; uma falha aqui (ex.: TSA fora do ar) nao a desfaz
            // e o emissor pode repetir pela rota /envelopes/{id}/finalize.
            try {
                finalizacao.finalizar(r.signatario().envelopeId(), null, null);
                finalizado = true;
            } catch (Exception e) {
                System.err.println("[Assinatura] Finalizacao automatica falhou: " + e.getMessage());
            }
        }
        Http.json(ex, 200, Json.objeto(
                "signatario", signatario(r.signatario()),
                "todosAssinaram", String.valueOf(r.todosAssinaram()),
                "finalizado", String.valueOf(finalizado)));
    }

    private void recusar(HttpExchange ex, String token) throws SQLException, IOException {
        Map<String, String> f = Http.lerFormulario(ex, LIMITE_RECUSA, null);
        Signatario s = servico.recusar(token, f.get("motivo"), Http.ipOrigem(ex),
                Http.userAgent(ex));
        Http.json(ex, 200, Json.objeto("signatario", signatario(s)));
    }

    // ----- Auxiliares -----

    /** Dados do signatario visiveis a ele mesmo (sem token, sem imagem). */
    private static String signatario(Signatario s) {
        CampoAssinatura f = s.campo();
        String campo = f == null ? "null" : Json.objeto(
                "pagina", String.valueOf(f.pagina()),
                "x", numero(f.x()), "y", numero(f.y()),
                "largura", numero(f.largura()), "altura", numero(f.altura()));
        return Json.objeto(
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

    private static void limiteExcedido(HttpExchange ex, LimitadorTaxa l, String ip)
            throws IOException {
        ex.getResponseHeaders().set("Retry-After", String.valueOf(l.segundosParaLiberar(ip)));
        Http.erro(ex, 429, "Muitas requisicoes. Tente novamente em instantes.");
    }

    private static byte[] recurso(String nome) {
        try (InputStream in = SignatarioApi.class.getResourceAsStream("/assinatura-web/" + nome)) {
            if (in == null) {
                throw new IllegalStateException("Recurso ausente: " + nome);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler recurso " + nome, e);
        }
    }
}
