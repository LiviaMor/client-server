package sica.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sica.common.Protocolo;

/**
 * Gateway web leve do SiCA.
 *
 * <p>Sobe um servidor HTTP nativo do Java ({@link HttpServer}) que serve uma
 * interface web simples e traduz as acoes do navegador em requisicoes ao
 * servidor SiCA via socket TCP (usando {@link SicaTcpClient}).</p>
 *
 * <p>Fluxo: <b>Navegador &rarr; (HTTP) &rarr; Gateway &rarr; (socket TCP) &rarr; Servidor SiCA</b>.
 * O nucleo da aplicacao continua sendo TCP; a camada web e apenas uma "fachada".</p>
 *
 * <h2>Rotas</h2>
 * <ul>
 *   <li>{@code GET /}          &rarr; pagina HTML da interface</li>
 *   <li>{@code GET /api/list}  &rarr; JSON com a lista de arquivos</li>
 *   <li>{@code POST /api/upload} &rarr; recebe multipart/form-data e faz upload</li>
 *   <li>{@code GET /api/download?nome=...} &rarr; baixa o arquivo</li>
 * </ul>
 */
public class GatewayWeb {

    private final int portaHttp;
    private final SicaTcpClient tcp;

    public GatewayWeb(int portaHttp, String hostTcp, int portaTcp) {
        this.portaHttp = portaHttp;
        this.tcp = new SicaTcpClient(hostTcp, portaTcp);
    }

    /** Registra as rotas e inicia o servidor HTTP. */
    public void iniciar() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(portaHttp), 0);
        server.createContext("/", this::rotaHome);
        server.createContext("/api/list", this::rotaListar);
        server.createContext("/api/upload", this::rotaUpload);
        server.createContext("/api/download", this::rotaDownload);
        server.setExecutor(null); // executor padrao
        server.start();
        System.out.println("[Gateway] Interface web em http://localhost:" + portaHttp);
    }

    /** Serve a pagina HTML principal. */
    private void rotaHome(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 405, "text/plain", "Metodo nao permitido".getBytes());
            return;
        }
        byte[] html = PAGINA_HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        responder(ex, 200, null, html);
    }

    /** Retorna a listagem de arquivos em JSON (montado manualmente, sem libs). */
    private void rotaListar(HttpExchange ex) throws IOException {
        try {
            List<SicaTcpClient.ArquivoInfo> arquivos = tcp.listar();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < arquivos.size(); i++) {
                SicaTcpClient.ArquivoInfo a = arquivos.get(i);
                if (i > 0) {
                    json.append(",");
                }
                json.append("{\"nome\":\"").append(escaparJson(a.nome))
                        .append("\",\"tamanho\":").append(a.tamanho).append("}");
            }
            json.append("]");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            responder(ex, 200, null, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            responder(ex, 502, "text/plain",
                    ("Falha ao contatar o servidor SiCA: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Recebe um upload via multipart/form-data, extrai nome e conteudo do arquivo
     * e repassa ao servidor SiCA.
     */
    private void rotaUpload(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            responder(ex, 405, "text/plain", "Metodo nao permitido".getBytes());
            return;
        }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            responder(ex, 400, "text/plain", "Esperado multipart/form-data".getBytes());
            return;
        }

        String boundary = "--" + contentType.split("boundary=")[1];
        byte[] corpo = lerTudo(ex.getRequestBody());

        MultipartFile arquivo = extrairArquivo(corpo, boundary);
        if (arquivo == null) {
            responder(ex, 400, "text/plain", "Nenhum arquivo enviado".getBytes());
            return;
        }

        try {
            String resultado = tcp.upload(arquivo.nome, arquivo.conteudo);
            responder(ex, 200, "text/plain", resultado.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            responder(ex, 502, "text/plain",
                    ("Falha no upload: " + e.getMessage()).getBytes());
        }
    }

    /** Baixa um arquivo do servidor SiCA e o entrega ao navegador. */
    private void rotaDownload(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String nome = extrairParametro(query, "nome");
        if (nome == null || nome.isBlank()) {
            responder(ex, 400, "text/plain", "Parametro 'nome' ausente".getBytes());
            return;
        }

        try {
            byte[] conteudo = tcp.download(nome);
            if (conteudo == null) {
                responder(ex, 404, "text/plain", "Arquivo nao encontrado".getBytes());
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + nome + "\"");
            responder(ex, 200, null, conteudo);
        } catch (IOException e) {
            responder(ex, 502, "text/plain",
                    ("Falha no download: " + e.getMessage()).getBytes());
        }
    }

    // ------------------------------------------------------------------
    // Utilitarios
    // ------------------------------------------------------------------

    /** Estrutura simples para carregar nome + bytes de um arquivo de upload. */
    private static final class MultipartFile {
        final String nome;
        final byte[] conteudo;

        MultipartFile(String nome, byte[] conteudo) {
            this.nome = nome;
            this.conteudo = conteudo;
        }
    }

    /**
     * Parser minimo de multipart/form-data: localiza a primeira parte que contem
     * {@code filename=} e extrai o nome do arquivo e seu conteudo binario.
     *
     * <p>Nao e um parser completo de RFC 7578 (atende o caso de um unico arquivo),
     * o suficiente para uma interface de estudo sem dependencias externas.</p>
     */
    private MultipartFile extrairArquivo(byte[] corpo, String boundary) {
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
        int inicio = indexOf(corpo, boundaryBytes, 0);
        while (inicio >= 0) {
            int cabecalhoIni = inicio + boundaryBytes.length;
            // Cada parte separa cabecalho e conteudo por uma linha em branco (CRLFCRLF).
            byte[] separador = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            int fimCabecalho = indexOf(corpo, separador, cabecalhoIni);
            if (fimCabecalho < 0) {
                break;
            }
            String cabecalho = new String(corpo, cabecalhoIni,
                    fimCabecalho - cabecalhoIni, StandardCharsets.ISO_8859_1);

            int proximoBoundary = indexOf(corpo, boundaryBytes, fimCabecalho + separador.length);
            if (proximoBoundary < 0) {
                break;
            }

            if (cabecalho.contains("filename=")) {
                String nome = extrairFilename(cabecalho);
                int conteudoIni = fimCabecalho + separador.length;
                // O conteudo termina em CRLF logo antes do proximo boundary.
                int conteudoFim = proximoBoundary - 2;
                int tamanho = Math.max(0, conteudoFim - conteudoIni);
                byte[] conteudo = new byte[tamanho];
                System.arraycopy(corpo, conteudoIni, conteudo, 0, tamanho);
                if (nome != null && !nome.isBlank()) {
                    return new MultipartFile(nome, conteudo);
                }
            }
            inicio = proximoBoundary;
        }
        return null;
    }

    private String extrairFilename(String cabecalho) {
        int idx = cabecalho.indexOf("filename=\"");
        if (idx < 0) {
            return null;
        }
        int ini = idx + "filename=\"".length();
        int fim = cabecalho.indexOf('"', ini);
        if (fim < 0) {
            return null;
        }
        return cabecalho.substring(ini, fim);
    }

    /** Busca a posicao de um padrao de bytes dentro de um array, a partir de {@code de}. */
    private int indexOf(byte[] dados, byte[] padrao, int de) {
        outer:
        for (int i = de; i <= dados.length - padrao.length; i++) {
            for (int j = 0; j < padrao.length; j++) {
                if (dados[i + j] != padrao[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private byte[] lerTudo(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private String extrairParametro(String query, String chave) {
        if (query == null) {
            return null;
        }
        for (String par : query.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2 && kv[0].equals(chave)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String escaparJson(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escreve a resposta HTTP com o codigo e corpo informados. */
    private void responder(HttpExchange ex, int codigo, String contentType, byte[] corpo)
            throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        }
        ex.sendResponseHeaders(codigo, corpo.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(corpo);
        }
    }

    // Suprime aviso do compilador para o parametro nao usado em URLEncoder (mantido por clareza).
    @SuppressWarnings("unused")
    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Ponto de entrada do gateway.
     *
     * @param args {@code [portaHttp] [hostTcp] [portaTcp]} (todos opcionais)
     */
    public static void main(String[] args) throws IOException {
        int portaHttp = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String hostTcp = args.length > 1 ? args[1] : Protocolo.HOST_PADRAO;
        int portaTcp = args.length > 2 ? Integer.parseInt(args[2]) : Protocolo.PORTA_PADRAO;
        new GatewayWeb(portaHttp, hostTcp, portaTcp).iniciar();
    }

    /** Pagina HTML da interface web (embutida para dispensar arquivos estaticos). */
    private static final String PAGINA_HTML =
            "<!DOCTYPE html>\n"
            + "<html lang=\"pt-br\">\n"
            + "<head>\n"
            + "  <meta charset=\"utf-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "  <title>SiCA - Compartilhamento de Arquivos</title>\n"
            + "  <style>\n"
            + "    body { font-family: system-ui, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; color: #222; }\n"
            + "    h1 { font-size: 1.4rem; }\n"
            + "    .card { border: 1px solid #ddd; border-radius: 8px; padding: 1rem; margin-bottom: 1rem; }\n"
            + "    button { cursor: pointer; padding: .4rem .8rem; border: 1px solid #888; border-radius: 6px; background: #f7f7f7; }\n"
            + "    button:hover { background: #eee; }\n"
            + "    ul { list-style: none; padding: 0; }\n"
            + "    li { display: flex; justify-content: space-between; padding: .4rem 0; border-bottom: 1px solid #eee; }\n"
            + "    .tam { color: #666; font-size: .85rem; }\n"
            + "    #status { font-size: .9rem; color: #555; }\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <h1>SiCA &mdash; Sistema de Compartilhamento de Arquivos</h1>\n"
            + "  <div class=\"card\">\n"
            + "    <h2 style=\"font-size:1.1rem\">Enviar arquivo</h2>\n"
            + "    <form id=\"formUpload\">\n"
            + "      <input type=\"file\" name=\"arquivo\" id=\"arquivo\" required>\n"
            + "      <button type=\"submit\">Enviar</button>\n"
            + "    </form>\n"
            + "  </div>\n"
            + "  <div class=\"card\">\n"
            + "    <h2 style=\"font-size:1.1rem\">Arquivos no servidor <button onclick=\"carregar()\">Atualizar</button></h2>\n"
            + "    <ul id=\"lista\"></ul>\n"
            + "  </div>\n"
            + "  <p id=\"status\"></p>\n"
            + "  <script>\n"
            + "    async function carregar() {\n"
            + "      const r = await fetch('/api/list');\n"
            + "      const arquivos = await r.json();\n"
            + "      const ul = document.getElementById('lista');\n"
            + "      ul.innerHTML = '';\n"
            + "      if (arquivos.length === 0) { ul.innerHTML = '<li>Nenhum arquivo.</li>'; return; }\n"
            + "      for (const a of arquivos) {\n"
            + "        const li = document.createElement('li');\n"
            + "        const info = document.createElement('span');\n"
            + "        info.textContent = a.nome;\n"
            + "        const tam = document.createElement('span');\n"
            + "        tam.className = 'tam';\n"
            + "        tam.textContent = a.tamanho + ' bytes';\n"
            + "        const link = document.createElement('a');\n"
            + "        link.href = '/api/download?nome=' + encodeURIComponent(a.nome);\n"
            + "        link.textContent = 'baixar';\n"
            + "        const dir = document.createElement('span');\n"
            + "        dir.append(tam, ' \u00b7 ', link);\n"
            + "        li.append(info, dir);\n"
            + "        ul.appendChild(li);\n"
            + "      }\n"
            + "    }\n"
            + "    document.getElementById('formUpload').addEventListener('submit', async (e) => {\n"
            + "      e.preventDefault();\n"
            + "      const arquivo = document.getElementById('arquivo').files[0];\n"
            + "      if (!arquivo) return;\n"
            + "      const fd = new FormData();\n"
            + "      fd.append('arquivo', arquivo);\n"
            + "      document.getElementById('status').textContent = 'Enviando...';\n"
            + "      const r = await fetch('/api/upload', { method: 'POST', body: fd });\n"
            + "      document.getElementById('status').textContent = await r.text();\n"
            + "      carregar();\n"
            + "    });\n"
            + "    carregar();\n"
            + "  </script>\n"
            + "</body>\n"
            + "</html>\n";
}
