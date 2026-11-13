package sica.assinatura.email;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import sica.assinatura.util.Json;

/**
 * Envia e-mails pela API HTTP do Resend ({@code POST /emails}), sem SDK nem
 * MCP: e uma chamada HTTPS direta autenticada por {@code Authorization: Bearer}.
 *
 * <p>Sem dominio verificado, o Resend so entrega ao e-mail da propria conta
 * (remetente {@code onboarding@resend.dev}); outros destinatarios recebem
 * HTTP 403. A mensagem de erro do Resend e propagada na {@link EmailException}
 * para facilitar o diagnostico. Consulte a documentacao oficial do Resend para
 * os limites do plano gratuito.</p>
 */
public final class ResendEmailSender implements EmailSender {

    public static final URI ENDPOINT_PADRAO = URI.create("https://api.resend.com/emails");
    private static final int ERRO_MAX = 300;

    private final String apiKey;
    private final String remetente;
    private final URI endpoint;
    private final HttpClient http;

    public ResendEmailSender(String apiKey, String remetente) {
        this(apiKey, remetente, ENDPOINT_PADRAO);
    }

    /** Permite apontar para outro endpoint (usado nos testes). */
    public ResendEmailSender(String apiKey, String remetente, URI endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("RESEND_API_KEY ausente.");
        }
        this.apiKey = apiKey;
        this.remetente = remetente;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public void enviar(Mensagem m) throws EmailException {
        List<String> anexos = new java.util.ArrayList<>();
        for (Mensagem.Anexo a : m.anexos()) {
            anexos.add(Json.objeto(
                    "filename", Json.texto(a.nome()),
                    "content", Json.texto(Base64.getEncoder().encodeToString(a.conteudo()))));
        }
        String corpo = Json.objeto(
                "from", Json.texto(remetente),
                "to", Json.lista(List.of(Json.texto(m.para()))),
                "subject", Json.texto(m.assunto()),
                "text", Json.texto(m.texto()),
                "html", Json.texto(m.html()),
                "attachments", Json.lista(anexos));
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String detalhe = resp.body() == null ? "" : resp.body();
                if (detalhe.length() > ERRO_MAX) {
                    detalhe = detalhe.substring(0, ERRO_MAX);
                }
                throw new EmailException("Resend respondeu HTTP " + resp.statusCode() + ": "
                        + detalhe);
            }
        } catch (IOException e) {
            throw new EmailException("Falha de comunicacao com o Resend: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailException("Envio interrompido.", e);
        }
    }
}
