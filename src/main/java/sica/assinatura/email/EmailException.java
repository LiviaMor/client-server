package sica.assinatura.email;

/** Falha ao enviar um e-mail. A mensagem nunca deve conter o link secreto. */
public class EmailException extends Exception {

    private static final long serialVersionUID = 1L;

    public EmailException(String mensagem) {
        super(mensagem);
    }

    public EmailException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
