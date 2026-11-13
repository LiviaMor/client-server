package sica.assinatura.pdf;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Selector;

/**
 * Verificacao tecnica das assinaturas PAdES de um PDF.
 *
 * <p>Para cada assinatura: confere a criptografia do CMS sobre os bytes do
 * {@code /ByteRange}, se o intervalo cobre o arquivo inteiro (nada foi
 * acrescentado depois), a presenca de {@code signing-certificate-v2} e, se
 * houver, o carimbo de tempo (assinatura da TSA e resumo do valor assinado).</p>
 *
 * <p>A confianca na cadeia (se o certificado foi emitido por uma AC aceita) e
 * avaliada por quem chama, comparando {@link Assinatura#cadeia()} com as ACs
 * configuradas.</p>
 */
public final class VerificadorPdf {

    private VerificadorPdf() {
    }

    /** Resultado de uma assinatura encontrada no PDF. */
    public record Assinatura(
            boolean criptografiaValida,
            boolean cobreDocumentoInteiro,
            boolean temSigningCertificateV2,
            boolean temCarimbo,
            boolean carimboValido,
            Instant dataCarimbo,
            String autoridadeCarimbo,
            String assinante,
            List<X509Certificate> cadeia,
            String erro) {

        /** Integra: criptografia valida e nada alterado ou acrescentado depois. */
        public boolean integra() {
            return criptografiaValida && cobreDocumentoInteiro && (!temCarimbo || carimboValido);
        }
    }

    /**
     * Verifica todas as assinaturas do PDF.
     *
     * @return lista vazia se o PDF nao tiver assinaturas
     * @throws IOException se o arquivo nao for um PDF legivel
     */
    public static List<Assinatura> verificar(byte[] pdf) throws IOException {
        List<Assinatura> resultados = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDSignature sig : doc.getSignatureDictionaries()) {
                resultados.add(verificarUma(sig, pdf));
            }
        }
        return resultados;
    }

    private static Assinatura verificarUma(PDSignature sig, byte[] pdf) {
        boolean cobre = false;
        try {
            int[] faixa = sig.getByteRange();
            cobre = faixa != null && faixa.length == 4 && faixa[0] == 0
                    && (long) faixa[2] + faixa[3] == pdf.length;

            byte[] conteudo = sig.getSignedContent(pdf);
            byte[] cmsBytes = sig.getContents(pdf);
            CMSSignedData cms = lerCms(conteudo, cmsBytes);
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();

            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> candidatos = cms.getCertificates()
                    .getMatches((Selector<X509CertificateHolder>) signer.getSID());
            if (candidatos.isEmpty()) {
                return falha(cobre, "Certificado do assinante ausente no CMS.");
            }
            X509CertificateHolder holder = candidatos.iterator().next();
            boolean cripto = signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder));

            AttributeTable assinados = signer.getSignedAttributes();
            boolean scv2 = assinados != null
                    && assinados.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2) != null;

            List<X509Certificate> cadeia = new ArrayList<>();
            JcaX509CertificateConverter conversor = new JcaX509CertificateConverter();
            cadeia.add(conversor.getCertificate(holder));
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> todos = cms.getCertificates().getMatches(null);
            for (X509CertificateHolder h : todos) {
                if (!h.equals(holder)) {
                    cadeia.add(conversor.getCertificate(h));
                }
            }

            boolean temCarimbo = false;
            boolean carimboValido = false;
            Instant data = null;
            String tsa = null;
            AttributeTable naoAssinados = signer.getUnsignedAttributes();
            Attribute attr = naoAssinados == null ? null
                    : naoAssinados.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            if (attr != null) {
                temCarimbo = true;
                TimeStampToken token = new TimeStampToken(
                        ContentInfo.getInstance(attr.getAttrValues().getObjectAt(0)));
                data = token.getTimeStampInfo().getGenTime().toInstant();
                carimboValido = carimboConfere(token, signer.getSignature());
                @SuppressWarnings("unchecked")
                Collection<X509CertificateHolder> tsaCerts = token.getCertificates()
                        .getMatches(token.getSID());
                if (!tsaCerts.isEmpty()) {
                    tsa = tsaCerts.iterator().next().getSubject().toString();
                }
            }
            String erro = null;
            if (!cripto) {
                erro = "Assinatura criptografica invalida.";
            } else if (!cobre) {
                erro = "O documento foi alterado ou recebeu conteudo depois do selo.";
            } else if (temCarimbo && !carimboValido) {
                erro = "Carimbo de tempo invalido.";
            }
            return new Assinatura(cripto, cobre, scv2, temCarimbo, carimboValido, data, tsa,
                    holder.getSubject().toString(), cadeia, erro);
        } catch (Exception e) {
            return falha(cobre, "Assinatura ilegivel ou corrompida: " + e.getMessage());
        }
    }

    /**
     * Le o CMS do campo {@code /Contents}. O campo tem tamanho fixo e e
     * completado com zeros; le-se apenas o primeiro objeto DER, ignorando o
     * preenchimento.
     */
    public static CMSSignedData lerCms(byte[] conteudoAssinado, byte[] contents)
            throws IOException, org.bouncycastle.cms.CMSException {
        try (ASN1InputStream in = new ASN1InputStream(contents)) {
            ASN1Primitive objeto = in.readObject();
            if (objeto == null) {
                throw new IOException("Campo /Contents vazio.");
            }
            return new CMSSignedData(new CMSProcessableByteArray(conteudoAssinado),
                    ContentInfo.getInstance(objeto));
        }
    }

    /** O token da TSA e autentico e carimba exatamente o valor desta assinatura? */
    private static boolean carimboConfere(TimeStampToken token, byte[] valorAssinatura) {
        try {
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> certs =
                    token.getCertificates().getMatches(token.getSID());
            if (certs.isEmpty()) {
                return false;
            }
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(certs.iterator().next()));
            String oid = token.getTimeStampInfo().getMessageImprintAlgOID().getId();
            if (!"2.16.840.1.101.3.4.2.1".equals(oid)) {
                return false; // so SHA-256
            }
            byte[] esperado = MessageDigest.getInstance("SHA-256").digest(valorAssinatura);
            return MessageDigest.isEqual(esperado,
                    token.getTimeStampInfo().getMessageImprintDigest());
        } catch (Exception e) {
            return false;
        }
    }

    private static Assinatura falha(boolean cobre, String erro) {
        return new Assinatura(false, cobre, false, false, false, null, null, null, List.of(), erro);
    }
}
