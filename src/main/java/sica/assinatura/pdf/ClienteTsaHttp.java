package sica.assinatura.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.SecureRandom;
import java.time.Duration;

import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

/**
 * Cliente RFC 3161 por HTTP (ex.: FreeTSA, {@code https://freetsa.org/tsr}).
 *
 * <p>Envia {@code application/timestamp-query} com nonce aleatorio e pede o
 * certificado da TSA no token; a resposta e validada contra a requisicao
 * (status, resumo e nonce) antes de ser aceita.</p>
 */
public final class ClienteTsaHttp implements CarimboTempo {

    private static final int RESPOSTA_MAX = 128 * 1024;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final URI url;
    private final HttpClient http;

    public ClienteTsaHttp(String url) {
        this.url = URI.create(url);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] carimbar(byte[] sha256) throws IOException {
        TimeStampRequestGenerator gerador = new TimeStampRequestGenerator();
        gerador.setCertReq(true);
        TimeStampRequest pedido =
                gerador.generate(TSPAlgorithms.SHA256, sha256, new BigInteger(64, ALEATORIO));

        HttpRequest req = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/timestamp-query")
                .POST(BodyPublishers.ofByteArray(pedido.getEncoded()))
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Pedido de carimbo de tempo interrompido.", e);
        }
        byte[] corpo;
        try (InputStream in = resp.body()) {
            corpo = in.readNBytes(RESPOSTA_MAX + 1);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("TSA respondeu HTTP " + resp.statusCode() + ".");
        }
        if (corpo.length > RESPOSTA_MAX) {
            throw new IOException("Resposta da TSA grande demais.");
        }
        try {
            TimeStampResponse resposta = new TimeStampResponse(corpo);
            resposta.validate(pedido);
            TimeStampToken token = resposta.getTimeStampToken();
            if (token == null) {
                throw new IOException("TSA recusou o pedido: " + resposta.getStatusString());
            }
            return token.getEncoded();
        } catch (TSPException e) {
            throw new IOException("Resposta invalida da TSA: " + e.getMessage(), e);
        }
    }
}
