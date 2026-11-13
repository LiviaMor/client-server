package sica.assinatura.modelo;

/**
 * Ciclo de vida de um envelope de assinatura.
 *
 * <p>{@code DRAFT -> SENT -> COMPLETED}; desvios: {@code DECLINED} (um signatario
 * recusou), {@code EXPIRED} (prazo vencido) e {@code CANCELED} (emissor cancelou).</p>
 */
public enum StatusEnvelope {
    DRAFT,
    SENT,
    COMPLETED,
    DECLINED,
    EXPIRED,
    CANCELED
}
