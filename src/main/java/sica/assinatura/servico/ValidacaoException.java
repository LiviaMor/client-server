package sica.assinatura.servico;

/** Entrada invalida do cliente (resulta em HTTP 400). A mensagem e segura para exibir. */
public class ValidacaoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidacaoException(String mensagem) {
        super(mensagem);
    }
}
