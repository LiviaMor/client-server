package sica.assinatura.servico;

/**
 * Falha temporaria ao finalizar (ex.: TSA indisponivel). O envelope continua
 * em {@code SENT} com todas as assinaturas e a finalizacao pode ser repetida
 * (resulta em HTTP 503).
 */
public class FinalizacaoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FinalizacaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
