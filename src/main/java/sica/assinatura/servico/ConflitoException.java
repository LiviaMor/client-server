package sica.assinatura.servico;

/** Operacao incompativel com o estado atual do recurso (resulta em HTTP 409). */
public class ConflitoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflitoException(String mensagem) {
        super(mensagem);
    }
}
