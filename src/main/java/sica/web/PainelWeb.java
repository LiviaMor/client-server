package sica.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serve o painel do emissor (arquivos estaticos em {@code resources/painel}).
 *
 * <p>O painel e HTML, CSS e JavaScript puros. Ele nao tem segredo algum: toda
 * operacao passa pelas rotas protegidas por {@code X-Admin-Key}, que o usuario
 * informa na tela de entrada.</p>
 */
final class PainelWeb {

    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self';"
            + " img-src 'self' data:; connect-src 'self'; frame-ancestors 'none';"
            + " base-uri 'none'; form-action 'none'";

    private PainelWeb() {
    }

    static void registrar(HttpServer server) {
        Map<String, Recurso> recursos = Map.of(
                "/", new Recurso("index.html", "text/html; charset=utf-8"),
                "/index.html", new Recurso("index.html", "text/html; charset=utf-8"),
                "/painel/painel.css", new Recurso("painel.css", "text/css; charset=utf-8"),
                "/painel/painel.js", new Recurso("painel.js", "text/javascript; charset=utf-8"),
                "/painel/logo.png", new Recurso("logo.png", "image/png"),
                "/painel/angel-signature.png", new Recurso("angel-signature.png", "image/png"),
                "/termos", new Recurso("termos.html", "text/html; charset=utf-8"));
        server.createContext("/", ex -> tratar(ex, recursos));
    }

    private record Recurso(String arquivo, String tipo) {
        byte[] ler() throws IOException {
            try (InputStream in = PainelWeb.class.getResourceAsStream("/painel/" + arquivo)) {
                if (in == null) {
                    throw new IOException("Recurso ausente: " + arquivo);
                }
                return in.readAllBytes();
            }
        }
    }

    private static void tratar(HttpExchange ex, Map<String, Recurso> recursos) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                responder(ex, 405, "text/plain; charset=utf-8", "Metodo nao permitido.");
                return;
            }
            Recurso r = recursos.get(ex.getRequestURI().getPath());
            if (r == null) {
                responder(ex, 404, "text/plain; charset=utf-8", "Pagina nao encontrada.");
                return;
            }
            var h = ex.getResponseHeaders();
            h.set("Content-Security-Policy", CSP);
            h.set("X-Frame-Options", "DENY");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            enviar(ex, 200, r.tipo(), r.ler());
        } catch (IOException e) {
            System.err.println("[Painel] Erro: " + e.getMessage());
            responder(ex, 500, "text/plain; charset=utf-8", "Erro interno.");
        } finally {
            ex.close();
        }
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, String texto)
            throws IOException {
        enviar(ex, codigo, tipo, texto.getBytes(StandardCharsets.UTF_8));
    }

    private static void enviar(HttpExchange ex, int codigo, String tipo, byte[] corpo)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(codigo, corpo.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(corpo);
        }
    }
}
