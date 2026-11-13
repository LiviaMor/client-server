package sica.assinatura.util;

import java.util.List;

/** Utilitario minimo para montar JSON (somente saida) sem biblioteca externa. */
public final class Json {

    private Json() {
    }

    /** Retorna o texto como literal JSON (com aspas e escapes) ou {@code null} se nulo. */
    public static String texto(String valor) {
        if (valor == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(valor.length() + 2).append('"');
        for (int i = 0; i < valor.length(); i++) {
            char ch = valor.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Monta um objeto JSON a partir de pares chave/valor ja serializados. */
    public static String objeto(String... paresChaveValorJson) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < paresChaveValorJson.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(texto(paresChaveValorJson[i])).append(':').append(paresChaveValorJson[i + 1]);
        }
        return sb.append('}').toString();
    }

    /** Monta um array JSON a partir de elementos ja serializados. */
    public static String lista(List<String> elementosJson) {
        return "[" + String.join(",", elementosJson) + "]";
    }
}
