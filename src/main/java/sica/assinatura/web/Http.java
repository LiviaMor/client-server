package sica.assinatura.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import sica.assinatura.servico.ValidacaoException;
import sica.assinatura.util.Json;

/** Utilitarios HTTP compartilhados pelas rotas do modulo de assinatura. */
final class Http {

    private static final int UA_MAX = 255;

    private Http() {
    }

    /** Corpo da requisicao acima do limite (HTTP 413). */
    static final class CorpoGrandeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Metodo HTTP incompativel com a rota (HTTP 405). */
    static final class MetodoNaoPermitidoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static void exigirMetodo(HttpExchange ex, String esperado) {
        if (!esperado.equals(ex.getRequestMethod())) {
            throw new MetodoNaoPermitidoException();
        }
    }

    /**
     * Le o corpo respeitando o limite: recusa pelo {@code Content-Length}
     * declarado e tambem ao ler (caso o cabecalho minta ou esteja ausente).
     */
    static byte[] lerCorpo(HttpExchange ex, long limite) throws IOException {
        String declarado = ex.getRequestHeaders().getFirst("Content-Length");
        if (declarado != null) {
            try {
                if (Long.parseLong(declarado.trim()) > limite) {
                    throw new CorpoGrandeException();
                }
            } catch (NumberFormatException e) {
                throw new ValidacaoException("Content-Length invalido.");
            }
        }
        InputStream in = ex.getRequestBody();
        int max = (int) Math.min(limite + 1, Integer.MAX_VALUE - 8);
        byte[] dados = in.readNBytes(max);
        if (dados.length > limite) {
            throw new CorpoGrandeException();
        }
        return dados;
    }

    /**
     * Le um formulario {@code application/x-www-form-urlencoded} ou multipart.
     * Campos de texto vao para o mapa; no multipart, arquivos sao devolvidos em
     * {@code arquivos}.
     */
    static Map<String, String> lerFormulario(HttpExchange ex, long limite,
                                             Map<String, byte[]> arquivos) throws IOException {
        String tipo = ex.getRequestHeaders().getFirst("Content-Type");
        byte[] corpo = lerCorpo(ex, limite);
        Map<String, String> campos = new HashMap<>();
        if (tipo != null && tipo.toLowerCase(Locale.ROOT)
                .startsWith("application/x-www-form-urlencoded")) {
            String texto = new String(corpo, StandardCharsets.UTF_8);
            for (String par : texto.split("&")) {
                if (par.isEmpty()) {
                    continue;
                }
                String[] kv = par.split("=", 2);
                try {
                    String chave = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                    String valor = kv.length > 1
                            ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                    campos.putIfAbsent(chave, valor);
                } catch (IllegalArgumentException e) {
                    throw new ValidacaoException("Formulario mal codificado.");
                }
            }
            return campos;
        }
        List<Multipart.Parte> partes = Multipart.analisar(tipo, corpo);
        for (Multipart.Parte p : partes) {
            if (p.ehArquivo()) {
                if (arquivos != null) {
                    arquivos.putIfAbsent(p.nome(), p.conteudo());
                }
            } else {
                campos.putIfAbsent(p.nome(), p.texto());
            }
        }
        return campos;
    }

    static String ipOrigem(HttpExchange ex) {
        // Nao confiamos em X-Forwarded-For: so o endereco real da conexao.
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static String userAgent(HttpExchange ex) {
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > UA_MAX ? ua.substring(0, UA_MAX) : ua;
    }

    static void erro(HttpExchange ex, int codigo, String mensagem) throws IOException {
        json(ex, codigo, Json.objeto("erro", Json.texto(mensagem)));
    }

    static void json(HttpExchange ex, int codigo, String corpo) throws IOException {
        bytes(ex, codigo, "application/json; charset=utf-8",
                corpo.getBytes(StandardCharsets.UTF_8));
    }

    /** Envia bytes com cabecalhos de seguranca basicos e sem cache. */
    static void bytes(HttpExchange ex, int codigo, String contentType, byte[] corpo)
            throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(codigo, corpo.length == 0 ? -1 : corpo.length);
        if (corpo.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(corpo);
            }
        }
    }
}
