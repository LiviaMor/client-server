package sica.assinatura.email;

import java.io.PrintStream;

/**
 * Provedor para desenvolvimento: imprime o e-mail (inclusive o link de
 * assinatura) no console em vez de envia-lo. E o padrao, para que o fluxo
 * funcione sem conta em nenhum servico externo.
 *
 * <p><b>Atencao:</b> o link impresso e a credencial do signatario. Nao use este
 * provedor em producao.</p>
 */
public final class ConsoleEmailSender implements EmailSender {

    private final PrintStream saida;

    public ConsoleEmailSender() {
        this(System.out);
    }

    public ConsoleEmailSender(PrintStream saida) {
        this.saida = saida;
    }

    @Override
    public void enviar(Mensagem m) {
        saida.println("[Email:console] ----------------------------------------");
        saida.println("[Email:console] Para:    " + m.para());
        saida.println("[Email:console] Assunto: " + m.assunto());
        saida.println(m.texto());
        for (Mensagem.Anexo a : m.anexos()) {
            saida.println("[Email:console] Anexo:   " + a.nome() + " (" + a.conteudo().length
                    + " bytes)");
        }
        saida.println("[Email:console] ----------------------------------------");
    }
}
