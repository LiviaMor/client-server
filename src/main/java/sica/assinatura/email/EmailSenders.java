package sica.assinatura.email;

import sica.assinatura.config.Configuracao;

/** Escolhe o {@link EmailSender} conforme a configuracao. */
public final class EmailSenders {

    private EmailSenders() {
    }

    /**
     * @throws IllegalStateException se o provedor for {@code resend} sem
     *                               {@code RESEND_API_KEY}
     */
    public static EmailSender criar(Configuracao config) {
        if ("resend".equals(config.emailProvider())) {
            if (config.resendApiKey() == null) {
                throw new IllegalStateException(
                        "EMAIL_PROVIDER=resend exige RESEND_API_KEY.");
            }
            return new ResendEmailSender(config.resendApiKey(), config.emailFrom());
        }
        return new ConsoleEmailSender();
    }
}
