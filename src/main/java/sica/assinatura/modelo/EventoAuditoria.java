package sica.assinatura.modelo;

/** Evento da trilha de auditoria (somente leitura). */
public record EventoAuditoria(
        long id,
        String envelopeId,
        String signerId,
        String tipo,
        String ip,
        String userAgent,
        String sha256Documento,
        String detalhe,
        String ocorridoEm) {
}
