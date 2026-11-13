package sica.assinatura.pdf;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import com.sun.net.httpserver.HttpServer;

/** TSA RFC 3161 local para testes (no lugar do FreeTSA). */
public final class TsaFalsa implements AutoCloseable {

    /** Faz a TSA responder HTTP 500. */
    public final AtomicBoolean falhar = new AtomicBoolean(false);
    /** Quantos pedidos foram atendidos. */
    public final AtomicInteger pedidos = new AtomicInteger();

    private final HttpServer server;
    private final TimeStampResponseGenerator gerador;
    private final AtomicLong serie = new AtomicLong(1);

    public TsaFalsa() throws Exception {
        TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA",
                        CertificadosDeTeste.CHAVE_TSA.getPrivate(), CertificadosDeTeste.TSA),
                new JcaDigestCalculatorProviderBuilder().build()
                        .get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokens.addCertificates(new JcaCertStore(List.of(CertificadosDeTeste.TSA)));
        gerador = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tsr", ex -> {
            try {
                byte[] corpo = ex.getRequestBody().readAllBytes();
                if (falhar.get()) {
                    ex.sendResponseHeaders(500, -1);
                    return;
                }
                TimeStampResponse r = gerador.generate(new TimeStampRequest(corpo),
                        BigInteger.valueOf(serie.getAndIncrement()), new Date());
                byte[] bytes = r.getEncoded();
                pedidos.incrementAndGet();
                ex.getResponseHeaders().set("Content-Type", "application/timestamp-reply");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                ex.sendResponseHeaders(500, -1);
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tsr";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
