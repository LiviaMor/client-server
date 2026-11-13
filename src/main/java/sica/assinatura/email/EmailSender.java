package sica.assinatura.email;

/**
 * Abstracao do envio de e-mail. Permite trocar o provedor (console, Resend,
 * SMTP...) por configuracao, e testar o fluxo sem enviar e-mails reais.
 */
public interface EmailSender {

    /**
     * Envia a mensagem.
     *
     * @throws EmailException se o provedor recusar ou estiver indisponivel
     */
    void enviar(Mensagem mensagem) throws EmailException;
}
