package sica.assinatura.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuracao do modulo de assinatura, lida de variaveis de ambiente.
 *
 * <p>Segredos (chave de administrador, senha do certificado) nunca ficam no
 * codigo nem no repositorio: vem sempre do ambiente. A classe recebe um
 * {@link Map} em vez de ler {@link System#getenv()} diretamente, o que permite
 * testa-la sem alterar o ambiente do processo.</p>
 *
 * <h2>Variaveis</h2>
 * <ul>
 *   <li>{@code SICA_DB_PATH} - arquivo do SQLite (padrao {@code data/assinatura.db})</li>
 *   <li>{@code SICA_STORAGE_DIR} - pasta dos PDFs (padrao {@code storage})</li>
 *   <li>{@code ADMIN_KEY} - chave das rotas do emissor (sem padrao)</li>
 *   <li>{@code SIGNING_CERT_PATH} - arquivo .p12 do certificado do servico</li>
 *   <li>{@code SIGNING_CERT_PASSWORD} - senha do .p12</li>
 *   <li>{@code TSA_URL} - servidor de carimbo de tempo RFC 3161
 *       (padrao {@code https://freetsa.org/tsr}; {@code none} desativa, so para testes)</li>
 *   <li>{@code SICA_SERVER_FILES_DIR} - pasta do servidor TCP onde o PDF final e
 *       publicado (padrao {@code server-files}; {@code none} desativa)</li>
 *   <li>{@code MAX_PDF_BYTES} - tamanho maximo do PDF (padrao 20 MB)</li>
 *   <li>{@code TOKEN_VALIDADE_DIAS} - validade do link de assinatura (padrao 7)</li>
 *   <li>{@code BASE_URL} - URL publica do frontend, base dos links
 *       (padrao {@code http://localhost:8080})</li>
 *   <li>{@code EMAIL_PROVIDER} - {@code console} (padrao, imprime o e-mail) ou {@code resend}</li>
 *   <li>{@code RESEND_API_KEY} - chave da API do Resend (obrigatoria se provider=resend)</li>
 *   <li>{@code EMAIL_FROM} - remetente (padrao {@code Angel Signature <onboarding@resend.dev>})</li>
 * </ul>
 */
public final class Configuracao {

    private static final long PADRAO_MAX_PDF_BYTES = 20L * 1024 * 1024;
    private static final int PADRAO_TOKEN_VALIDADE_DIAS = 7;

    private final Path caminhoBanco;
    private final Path diretorioStorage;
    private final String adminKey;
    private final String caminhoCertificado;
    private final String senhaCertificado;
    private final String urlTsa;
    private final long maxPdfBytes;
    private final int tokenValidadeDias;
    private final String baseUrl;
    private final String emailProvider;
    private final String resendApiKey;
    private final String emailFrom;
    private final Path diretorioPublicacao;

    /** Cria a configuracao a partir de um mapa (normalmente {@code System.getenv()}). */
    public Configuracao(Map<String, String> ambiente) {
        this.caminhoBanco = Path.of(valor(ambiente, "SICA_DB_PATH", "data/assinatura.db"));
        this.diretorioStorage = Path.of(valor(ambiente, "SICA_STORAGE_DIR", "storage"));
        this.adminKey = valor(ambiente, "ADMIN_KEY", null);
        this.caminhoCertificado = valor(ambiente, "SIGNING_CERT_PATH", null);
        this.senhaCertificado = valor(ambiente, "SIGNING_CERT_PASSWORD", null);
        this.urlTsa = valor(ambiente, "TSA_URL", "https://freetsa.org/tsr");
        this.maxPdfBytes = inteiroPositivo(ambiente, "MAX_PDF_BYTES", PADRAO_MAX_PDF_BYTES);
        this.tokenValidadeDias =
                (int) inteiroPositivo(ambiente, "TOKEN_VALIDADE_DIAS", PADRAO_TOKEN_VALIDADE_DIAS);
        this.baseUrl = urlBase(valor(ambiente, "BASE_URL", "http://localhost:8080"));
        this.emailProvider = provedorEmail(valor(ambiente, "EMAIL_PROVIDER", "console"));
        this.resendApiKey = valor(ambiente, "RESEND_API_KEY", null);
        this.emailFrom = valor(ambiente, "EMAIL_FROM", "Angel Signature <onboarding@resend.dev>");
        String publicacao = valor(ambiente, "SICA_SERVER_FILES_DIR", "server-files");
        this.diretorioPublicacao = "none".equalsIgnoreCase(publicacao) ? null : Path.of(publicacao);
    }

    /** Atalho que le as variaveis do ambiente real do processo. */
    public static Configuracao doAmbiente() {
        return new Configuracao(System.getenv());
    }

    public Path caminhoBanco() {
        return caminhoBanco;
    }

    public Path diretorioStorage() {
        return diretorioStorage;
    }

    /** Chave de administrador, ou {@code null} se nao configurada. */
    public String adminKey() {
        return adminKey;
    }

    /** Caminho do .p12, ou {@code null} se nao configurado. */
    public String caminhoCertificado() {
        return caminhoCertificado;
    }

    /** Senha do .p12, ou {@code null} se nao configurada. */
    public String senhaCertificado() {
        return senhaCertificado;
    }

    public String urlTsa() {
        return urlTsa;
    }

    /**
     * Indica se o carimbo de tempo esta ativo. {@code TSA_URL=none} o desativa
     * (apenas para desenvolvimento offline: o selo fica sem prova de tempo).
     */
    public boolean carimboAtivo() {
        return !"none".equalsIgnoreCase(urlTsa);
    }

    /**
     * Pasta do servidor TCP do SiCA onde o PDF final e publicado (para aparecer
     * no LIST/DOWNLOAD), ou {@code null} se {@code SICA_SERVER_FILES_DIR=none}.
     */
    public Path diretorioPublicacao() {
        return diretorioPublicacao;
    }

    public long maxPdfBytes() {
        return maxPdfBytes;
    }

    public int tokenValidadeDias() {
        return tokenValidadeDias;
    }

    /** URL publica do frontend, sem barra final (base dos links de assinatura). */
    public String baseUrl() {
        return baseUrl;
    }

    /** Provedor de e-mail: {@code console} (padrao) ou {@code resend}. */
    public String emailProvider() {
        return emailProvider;
    }

    /** API key do Resend, ou {@code null} se nao configurada. */
    public String resendApiKey() {
        return resendApiKey;
    }

    /** Remetente dos e-mails (ex.: {@code Angel Signature <contato@seudominio.com>}). */
    public String emailFrom() {
        return emailFrom;
    }

    private static String urlBase(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("BASE_URL deve comecar com http:// ou https://: " + url);
        }
        String limpa = url;
        while (limpa.endsWith("/")) {
            limpa = limpa.substring(0, limpa.length() - 1);
        }
        return limpa;
    }

    private static String provedorEmail(String provedor) {
        String p = provedor.toLowerCase();
        if (!p.equals("console") && !p.equals("resend")) {
            throw new IllegalArgumentException("EMAIL_PROVIDER deve ser 'console' ou 'resend': " + provedor);
        }
        return p;
    }

    /** Le uma variavel; vazia ou so com espacos conta como ausente. */
    private static String valor(Map<String, String> ambiente, String nome, String padrao) {
        String v = ambiente.get(nome);
        return (v == null || v.isBlank()) ? padrao : v.trim();
    }

    private static long inteiroPositivo(Map<String, String> ambiente, String nome, long padrao) {
        String v = valor(ambiente, nome, null);
        if (v == null) {
            return padrao;
        }
        try {
            long n = Long.parseLong(v);
            if (n <= 0) {
                throw new IllegalArgumentException(nome + " deve ser maior que zero: " + v);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(nome + " deve ser um numero inteiro: " + v, e);
        }
    }
}
