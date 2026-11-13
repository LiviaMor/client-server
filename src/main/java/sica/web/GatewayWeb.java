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
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import sica.assinatura.config.Configuracao;
import sica.assinatura.seguranca.Hashes;
import sica.assinatura.web.AssinaturaApi;
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

    /** Registra as rotas e inicia o servidor HTTP (ou HTTPS, se configurado). */
    public void iniciar() throws IOException {
        Configuracao config = Configuracao.doAmbiente();
        HttpServer server = ServidorHttp.criar(new InetSocketAddress(portaHttp),
                config.httpsKeystore(), config.httpsSenha());
        boolean https = config.httpsKeystore() != null;
        PainelWeb.registrar(server);
        server.createContext("/api/list", protegida(config, this::rotaListar));
        server.createContext("/api/upload", protegida(config, this::rotaUpload));
        server.createContext("/api/download", protegida(config, this::rotaDownload));
        registrarAssinatura(server, config);
        server.setExecutor(null); // executor padrao
        server.start();
        if (!https) {
            System.out.println("[Gateway] AVISO: HTTPS desativado (HTTPS_KEYSTORE_PATH nao definido);"
                    + " a comunicacao nao e cifrada. Use apenas em desenvolvimento.");
        }
        System.out.println("[Gateway] Interface web em " + (https ? "https" : "http")
                + "://localhost:" + portaHttp);
    }

    /**
     * Exige o cabecalho {@code X-Admin-Key} nas rotas do SiCA quando {@code ADMIN_KEY}
     * esta definida. Sem a variavel, o comportamento original (aberto) e mantido,
     * somente para desenvolvimento local.
     */
    private HttpHandler protegida(Configuracao config, HttpHandler interno) {
        return ex -> {
            String chave = config.adminKey();
            if (chave != null
                    && !Hashes.iguaisConstante(ex.getRequestHeaders().getFirst("X-Admin-Key"), chave)) {
                responder(ex, 401, "text/plain", "Chave de administrador ausente ou invalida.".getBytes());
                ex.close();
                return;
            }
            interno.handle(ex);
        };
    }

    /**
     * Registra as rotas do modulo de assinatura (/envelopes...), somente se a
     * variavel de ambiente {@code ADMIN_KEY} estiver definida. Sem ela, o
     * gateway funciona exatamente como antes. Falhas do modulo nao derrubam o
     * SiCA: sao registradas e o modulo fica desativado.
     */
    private void registrarAssinatura(HttpServer server, Configuracao config) {
        try {
            if (config.adminKey() == null) {
                System.out.println("[Gateway] Modulo de assinatura desativado (ADMIN_KEY nao definida).");
                return;
            }
            AssinaturaApi.criar(config).registrar(server);
            System.out.println("[Gateway] Modulo de assinatura ativo (/envelopes, e-mail: "
                    + config.emailProvider() + ").");
        } catch (Exception e) {
            System.err.println("[Gateway] Modulo de assinatura desativado: " + e.getMessage());
        }
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
}
