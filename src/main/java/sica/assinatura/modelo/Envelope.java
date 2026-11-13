package sica.assinatura.modelo;

/**
 * Envelope de assinatura: um documento PDF e seu estado.
 *
 * @param id              identificador (UUID em texto)
 * @param titulo          titulo informado pelo emissor
 * @param status          estado atual
 * @param sha256Original  hash SHA-256 (hex) do PDF original
 * @param sha256Final     hash do PDF final assinado; {@code null} ate a conclusao
 * @param caminhoOriginal caminho do original, relativo a pasta de storage
 * @param criadoEm        instante de criacao (ISO-8601, UTC)
 * @param expiraEm        instante de expiracao; {@code null} se nao houver
 * @param concluidoEm     instante de conclusao; {@code null} ate la
 */
public record Envelope(
        String id,
        String titulo,
        StatusEnvelope status,
        String sha256Original,
        String sha256Final,
        String caminhoOriginal,
        String criadoEm,
        String expiraEm,
        String concluidoEm) {
}
