package sica.assinatura.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sica.assinatura.servico.ValidacaoException;

/**
 * Parser de {@code multipart/form-data} (RFC 7578) para campos de texto e
 * arquivos, sem dependencias externas.
 *
 * <p>Trabalha sobre o corpo ja lido em memoria (o chamador impoe o limite de
 * tamanho). Rejeita corpos malformados e limita o numero de partes.</p>
 */
final class Multipart {

    private static final int MAX_PARTES = 20;
    private static final Pattern BOUNDARY =
            Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOME =
            Pattern.compile("[;\\s]name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARQUIVO =
            Pattern.compile("filename=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLFCRLF = {'\r', '\n', '\r', '\n'};

    /** Uma parte do formulario: campo de texto ou arquivo. */
    record Parte(String nome, String nomeArquivo, byte[] conteudo) {

        boolean ehArquivo() {
            return nomeArquivo != null;
        }

        String texto() {
            return new String(conteudo, StandardCharsets.UTF_8);
        }
    }

    private Multipart() {
    }

    /**
     * Interpreta o corpo.
     *
     * @param contentType valor do cabecalho {@code Content-Type} da requisicao
     * @param corpo       corpo completo
     * @throws ValidacaoException se o conteudo nao for um multipart valido
     */
    static List<Parte> analisar(String contentType, byte[] corpo) {
        if (contentType == null
                || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            throw new ValidacaoException("Esperado Content-Type multipart/form-data.");
        }
        Matcher m = BOUNDARY.matcher(contentType);
        if (!m.find()) {
            throw new ValidacaoException("Multipart sem 'boundary'.");
        }
        String boundary = m.group(1) != null ? m.group(1) : m.group(2);
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] delimInterno = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        List<Parte> partes = new ArrayList<>();
        int pos = indexOf(corpo, delim, 0);
        if (pos < 0) {
            throw malformado();
        }
        while (true) {
            pos += delim.length;
            if (comecaCom(corpo, pos, new byte[] {'-', '-'})) {
                break; // delimitador final
            }
            if (!comecaCom(corpo, pos, CRLF)) {
                throw malformado();
            }
            pos += CRLF.length;

            int fimCabecalho = indexOf(corpo, CRLFCRLF, pos);
            if (fimCabecalho < 0) {
                throw malformado();
            }
            String cabecalho = new String(corpo, pos, fimCabecalho - pos, StandardCharsets.UTF_8);
            int inicioDados = fimCabecalho + CRLFCRLF.length;

            int proximo = indexOf(corpo, delimInterno, inicioDados);
            if (proximo < 0) {
                throw malformado();
            }
            if (partes.size() >= MAX_PARTES) {
                throw new ValidacaoException("Partes demais no formulario.");
            }
            partes.add(criarParte(cabecalho, Arrays.copyOfRange(corpo, inicioDados, proximo)));
            pos = proximo + CRLF.length; // aponta para o inicio do proximo delimitador
        }
        return partes;
    }

    /** Retorna o texto do primeiro campo (nao arquivo) com o nome dado, ou {@code null}. */
    static String campo(List<Parte> partes, String nome) {
        return partes.stream()
                .filter(p -> !p.ehArquivo() && nome.equals(p.nome()))
                .map(Parte::texto)
                .findFirst().orElse(null);
    }

    /** Retorna a primeira parte de arquivo com o nome de campo dado, ou {@code null}. */
    static Parte arquivo(List<Parte> partes, String nome) {
        return partes.stream()
                .filter(p -> p.ehArquivo() && nome.equals(p.nome()))
                .findFirst().orElse(null);
    }

    private static Parte criarParte(String cabecalho, byte[] conteudo) {
        Matcher nome = NOME.matcher(cabecalho);
        if (!nome.find()) {
            throw malformado();
        }
        Matcher arquivo = ARQUIVO.matcher(cabecalho);
        return new Parte(nome.group(1), arquivo.find() ? arquivo.group(1) : null, conteudo);
    }

    private static ValidacaoException malformado() {
        return new ValidacaoException("Corpo multipart malformado.");
    }

    private static boolean comecaCom(byte[] dados, int pos, byte[] padrao) {
        if (pos < 0 || pos + padrao.length > dados.length) {
            return false;
        }
        for (int i = 0; i < padrao.length; i++) {
            if (dados[pos + i] != padrao[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] dados, byte[] padrao, int de) {
        int limite = dados.length - padrao.length;
        for (int i = Math.max(0, de); i <= limite; i++) {
            if (dados[i] == padrao[0] && comecaCom(dados, i, padrao)) {
                return i;
            }
        }
        return -1;
    }
}
