package sica.assinatura.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

import javax.security.auth.x500.X500Principal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Aplica o selo digital PAdES ao PDF, com o certificado do servico.
 *
 * <p>Formato: assinatura CMS destacada, {@code SubFilter=ETSI.CAdES.detached},
 * gravada como atualizacao incremental do PDF. Atributos assinados:
 * {@code content-type}, {@code message-digest} e {@code signing-certificate-v2}
 * (sem {@code signing-time}, que no PAdES fica no dicionario {@code /M}). Com
 * TSA configurada, o token RFC 3161 sobre o valor da assinatura e adicionado
 * como atributo nao assinado {@code signature-time-stamp} (nivel PAdES B-T).</p>
 *
 * <p>O {@code /ByteRange} da assinatura cobre o arquivo inteiro exceto o
 * proprio valor da assinatura; qualquer alteracao posterior nesses bytes torna
 * o selo invalido.</p>
 */
public final class SeloPades {

    /** Espaco reservado para o CMS (certificados + carimbo de tempo). */
    private static final int TAMANHO_RESERVADO = 32 * 1024;

    private final CertificadoAssinatura certificado;
    private final CarimboTempo carimbo;

    /**
     * @param carimbo TSA, ou {@code null} para selar sem carimbo de tempo
     */
    public SeloPades(CertificadoAssinatura certificado, CarimboTempo carimbo) {
        this.certificado = certificado;
        this.carimbo = carimbo;
    }

    /** Indica se o selo inclui carimbo de tempo. */
    public boolean temCarimbo() {
        return carimbo != null;
    }

    /**
     * Sela o PDF.
     *
     * @param pdf    documento a selar (nao e alterado)
     * @param motivo texto do campo {@code /Reason}
     * @return o PDF selado (o original seguido da atualizacao incremental)
     */
    public byte[] selar(byte[] pdf, String motivo, Calendar data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf);
             SignatureOptions opcoes = new SignatureOptions();
             ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDSignature assinatura = new PDSignature();
            assinatura.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            assinatura.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            assinatura.setName(nomeComum(certificado.certificado().getSubjectX500Principal()));
            assinatura.setReason(motivo);
            assinatura.setLocation("Brasil");
            assinatura.setSignDate(data);

            opcoes.setPreferredSignatureSize(TAMANHO_RESERVADO);
            doc.addSignature(assinatura, this::assinarCms, opcoes);
            doc.saveIncremental(saida);
            return saida.toByteArray();
        }
    }

    /** Atalho com a data atual (UTC). */
    public byte[] selar(byte[] pdf, String motivo) throws IOException {
        return selar(pdf, motivo, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
    }

    /** Gera o CMS sobre os bytes cobertos pelo /ByteRange. */
    private byte[] assinarCms(InputStream conteudo) throws IOException {
        try {
            byte[] dados = conteudo.readAllBytes();
            ContentSigner signer = new JcaContentSignerBuilder(certificado.algoritmo())
                    .build(certificado.chave());
            CMSSignedDataGenerator gerador = new CMSSignedDataGenerator();
            gerador.addSignerInfoGenerator(
                    new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                            .setSignedAttributeGenerator(atributosPades())
                            .build(signer, certificado.certificado()));
            gerador.addCertificates(new JcaCertStore(Arrays.asList(certificado.cadeia())));
            CMSSignedData cms = gerador.generate(new CMSProcessableByteArray(dados), false);

            if (carimbo != null) {
                cms = adicionarCarimbo(cms);
            }
            return cms.getEncoded(ASN1Encoding.DER);
        } catch (GeneralSecurityException | OperatorCreationException | CMSException e) {
            throw new IOException("Falha ao gerar a assinatura CMS: " + e.getMessage(), e);
        }
    }

    /**
     * Atributos assinados: os padroes do BouncyCastle (content-type,
     * message-digest, protecao do algoritmo) mais signing-certificate-v2, sem
     * signing-time.
     */
    private CMSAttributeTableGenerator atributosPades()
            throws GeneralSecurityException {
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                new DERSet(signingCertificateV2())));
        DefaultSignedAttributeTableGenerator padrao =
                new DefaultSignedAttributeTableGenerator(new AttributeTable(v));
        return parametros -> padrao.getAttributes(parametros).remove(CMSAttributes.signingTime);
    }

    private SigningCertificateV2 signingCertificateV2() throws GeneralSecurityException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(certificado.certificado().getEncoded());
            X500Name emissor = X500Name.getInstance(
                    certificado.certificado().getIssuerX500Principal().getEncoded());
            IssuerSerial is = new IssuerSerial(new GeneralNames(new GeneralName(emissor)),
                    certificado.certificado().getSerialNumber());
            return new SigningCertificateV2(new ESSCertIDv2[] {new ESSCertIDv2(hash, is)});
        } catch (CertificateEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** Carimbo RFC 3161 sobre o valor da assinatura, como atributo nao assinado. */
    private CMSSignedData adicionarCarimbo(CMSSignedData cms) throws IOException,
            GeneralSecurityException {
        SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
        byte[] resumo = MessageDigest.getInstance("SHA-256").digest(signer.getSignature());
        byte[] token = carimbo.carimbar(resumo);

        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(new Attribute(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                new DERSet(ASN1Primitive.fromByteArray(token))));
        SignerInformation comCarimbo =
                SignerInformation.replaceUnsignedAttributes(signer, new AttributeTable(v));
        return CMSSignedData.replaceSigners(cms, new SignerInformationStore(comCarimbo));
    }

    private static String nomeComum(X500Principal principal) {
        X500Name nome = X500Name.getInstance(principal.getEncoded());
        var rdns = nome.getRDNs(BCStyle.CN);
        return rdns.length == 0 ? principal.getName()
                : IETFUtils.valueToString(rdns[0].getFirst().getValue());
    }
}
