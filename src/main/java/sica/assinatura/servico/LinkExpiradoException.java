package sica.assinatura.servico;

/** Link de assinatura vencido (resulta em HTTP 410). */
public class LinkExpiradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LinkExpiradoException(String mensagem) {
        super(mensagem);
    }
}
