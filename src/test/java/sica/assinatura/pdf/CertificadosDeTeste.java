package sica.assinatura.pdf;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Certificados de teste gerados em memoria (AC raiz, certificado do servico e
 * certificado de TSA). Gerados uma unica vez por execucao.
 */
public final class CertificadosDeTeste {

    public static final String SENHA = "senha-de-teste";

    public static final KeyPair CHAVE_AC;
    public static final X509Certificate AC;
    public static final KeyPair CHAVE_SERVICO;
    public static final X509Certificate SERVICO;
    public static final KeyPair CHAVE_TSA;
    public static final X509Certificate TSA;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            CHAVE_AC = g.generateKeyPair();
            CHAVE_SERVICO = g.generateKeyPair();
            CHAVE_TSA = g.generateKeyPair();
            X500Name ac = new X500Name("CN=AC Raiz de Teste JUnit, O=Angel Signature (TESTE), C=BR");
            AC = emitir(ac, CHAVE_AC.getPrivate(), ac, CHAVE_AC.getPublic(), 1, true, null);
            SERVICO = emitir(ac, CHAVE_AC.getPrivate(),
                    new X500Name("CN=Angel Signature Servico de Assinatura (TESTE),"
                            + " O=Angel Signature (TESTE), C=BR"),
                    CHAVE_SERVICO.getPublic(), 2, false, null);
            TSA = emitir(ac, CHAVE_AC.getPrivate(),
                    new X500Name("CN=TSA de Teste, O=Angel Signature (TESTE)"),
                    CHAVE_TSA.getPublic(), 3, false, KeyPurposeId.id_kp_timeStamping);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CertificadosDeTeste() {
    }

    /** Certificado do servico carregado como em producao. */
    public static CertificadoAssinatura servico() {
        return new CertificadoAssinatura(CHAVE_SERVICO.getPrivate(),
                new X509Certificate[] {SERVICO, AC});
    }

    /** Grava o certificado do servico em um .p12 (com a cadeia) e devolve o caminho. */
    public static Path gravarP12(Path pasta) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("angel-signature", CHAVE_SERVICO.getPrivate(), SENHA.toCharArray(),
                new Certificate[] {SERVICO, AC});
        Path arquivo = pasta.resolve("servico.p12");
        try (OutputStream out = Files.newOutputStream(arquivo)) {
            ks.store(out, SENHA.toCharArray());
        }
        return arquivo;
    }

    private static X509Certificate emitir(X500Name emissor, PrivateKey chaveEmissor,
                                          X500Name sujeito, PublicKey chave, long serie,
                                          boolean ac, KeyPurposeId uso) throws Exception {
        Instant agora = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(emissor,
                BigInteger.valueOf(serie), Date.from(agora.minus(Duration.ofDays(1))),
                Date.from(agora.plus(Duration.ofDays(365))), sujeito, chave);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ac));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(ac
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        if (uso != null) {
            b.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(uso));
        }
        return new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withRSA").build(chaveEmissor)));
    }
}
