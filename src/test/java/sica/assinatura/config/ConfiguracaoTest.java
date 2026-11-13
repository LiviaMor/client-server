package sica.assinatura.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfiguracaoTest {

    @Test
    void usaPadroesQuandoAmbienteVazio() {
        Configuracao c = new Configuracao(Map.of());

        assertEquals(Path.of("data/assinatura.db"), c.caminhoBanco());
        assertEquals(Path.of("storage"), c.diretorioStorage());
        assertEquals("https://freetsa.org/tsr", c.urlTsa());
        assertEquals(20L * 1024 * 1024, c.maxPdfBytes());
        assertEquals(7, c.tokenValidadeDias());
        assertNull(c.adminKey());
        assertNull(c.caminhoCertificado());
        assertNull(c.senhaCertificado());
        assertEquals("http://localhost:8080", c.baseUrl());
        assertEquals("console", c.emailProvider());
        assertNull(c.resendApiKey());
        assertEquals("SiCA <onboarding@resend.dev>", c.emailFrom());
    }

    @Test
    void normalizaEValidaBaseUrlEProvedor() {
        Configuracao c = new Configuracao(Map.of(
                "BASE_URL", "https://app.exemplo.com//", "EMAIL_PROVIDER", "Resend"));
        assertEquals("https://app.exemplo.com", c.baseUrl());
        assertEquals("resend", c.emailProvider());

        assertThrows(IllegalArgumentException.class,
                () -> new Configuracao(Map.of("BASE_URL", "javascript:alert(1)")));
        assertThrows(IllegalArgumentException.class,
                () -> new Configuracao(Map.of("EMAIL_PROVIDER", "smtp")));
    }

    @Test
    void leValoresDoAmbiente() {
        Configuracao c = new Configuracao(Map.of(
                "SICA_DB_PATH", "/tmp/x.db",
                "ADMIN_KEY", "segredo",
                "SIGNING_CERT_PATH", "/tmp/s.p12",
                "SIGNING_CERT_PASSWORD", "senha",
                "MAX_PDF_BYTES", "1000",
                "TOKEN_VALIDADE_DIAS", "3"));

        assertEquals(Path.of("/tmp/x.db"), c.caminhoBanco());
        assertEquals("segredo", c.adminKey());
        assertEquals("/tmp/s.p12", c.caminhoCertificado());
        assertEquals("senha", c.senhaCertificado());
        assertEquals(1000L, c.maxPdfBytes());
        assertEquals(3, c.tokenValidadeDias());
    }

    @Test
    void valorEmBrancoContaComoAusente() {
        Configuracao c = new Configuracao(Map.of("ADMIN_KEY", "   ", "TSA_URL", ""));

        assertNull(c.adminKey());
        assertEquals("https://freetsa.org/tsr", c.urlTsa());
    }

    @Test
    void rejeitaNumeroInvalidoOuNaoPositivo() {
        assertThrows(IllegalArgumentException.class,
                () -> new Configuracao(Map.of("MAX_PDF_BYTES", "abc")));
        assertThrows(IllegalArgumentException.class,
                () -> new Configuracao(Map.of("MAX_PDF_BYTES", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> new Configuracao(Map.of("TOKEN_VALIDADE_DIAS", "-1")));
    }
}
