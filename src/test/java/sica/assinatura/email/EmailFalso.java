package sica.assinatura.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@link EmailSender} de teste: guarda as mensagens e falha para e-mails escolhidos. */
public final class EmailFalso implements EmailSender {

    public final List<Mensagem> enviadas = Collections.synchronizedList(new ArrayList<>());
    public final Set<String> falharPara = new HashSet<>();

    @Override
    public void enviar(Mensagem m) throws EmailException {
        if (falharPara.contains(m.para())) {
            throw new EmailException("falha simulada");
        }
        enviadas.add(m);
    }

    /** Extrai o token do link contido no texto da mensagem. */
    public static String token(Mensagem m) {
        int i = m.texto().indexOf("/assinar/");
        if (i < 0) {
            throw new IllegalStateException("mensagem sem link");
        }
        int ini = i + "/assinar/".length();
        int fim = ini;
        while (fim < m.texto().length() && !Character.isWhitespace(m.texto().charAt(fim))) {
            fim++;
        }
        return m.texto().substring(ini, fim);
    }
}
