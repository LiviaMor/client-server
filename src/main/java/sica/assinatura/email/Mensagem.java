package sica.assinatura.email;

import java.util.List;

/**
 * Mensagem de e-mail a enviar.
 *
 * @param para    destinatario unico
 * @param assunto assunto (sem quebras de linha)
 * @param texto   versao em texto puro
 * @param html    versao HTML (conteudo ja escapado pelo chamador)
 * @param anexos  arquivos anexados (pode ser vazio)
 */
public record Mensagem(String para, String assunto, String texto, String html,
                       List<Anexo> anexos) {

    /** Arquivo anexado. */
    public record Anexo(String nome, byte[] conteudo) {

        @Override
        public String toString() {
            return "Anexo[" + nome + ", " + conteudo.length + " bytes]";
        }
    }

    public Mensagem {
        anexos = anexos == null ? List.of() : List.copyOf(anexos);
    }

    /** Mensagem sem anexos. */
    public Mensagem(String para, String assunto, String texto, String html) {
        this(para, assunto, texto, html, List.of());
    }

    /** Evita imprimir o corpo (que pode conter o link secreto) em logs acidentais. */
    @Override
    public String toString() {
        return "Mensagem[para=" + para + ", assunto=" + assunto + ", anexos=" + anexos.size() + "]";
    }
}
