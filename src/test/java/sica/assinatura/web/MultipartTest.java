package sica.assinatura.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import sica.assinatura.servico.ValidacaoException;

class MultipartTest {

    /** Monta um corpo multipart com um campo de texto e um arquivo binario. */
    static byte[] corpo(String boundary, String titulo, String nomeArquivo, byte[] conteudo)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (titulo != null) {
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"titulo\"\r\n\r\n"
                    + titulo + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (conteudo != null) {
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"arquivo\"; filename=\""
                    + nomeArquivo + "\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(conteudo);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @Test
    void extraiCampoEArquivoBinarioIntacto() throws Exception {
        // Conteudo com CRLF, hifens e todos os valores de byte: nao pode ser truncado.
        byte[] binario = new byte[300];
        for (int i = 0; i < binario.length; i++) {
            binario[i] = (byte) i;
        }
        byte[] extra = "\r\n--meio\r\n--".getBytes(StandardCharsets.ISO_8859_1);
        byte[] conteudo = new byte[binario.length + extra.length];
        System.arraycopy(binario, 0, conteudo, 0, binario.length);
        System.arraycopy(extra, 0, conteudo, binario.length, extra.length);

        byte[] corpo = corpo("XyZ123", "Contrato de Prestação", "a.pdf", conteudo);
        List<Multipart.Parte> partes =
                Multipart.analisar("multipart/form-data; boundary=XyZ123", corpo);

        assertEquals("Contrato de Prestação", Multipart.campo(partes, "titulo"));
        Multipart.Parte arq = Multipart.arquivo(partes, "arquivo");
        assertEquals("a.pdf", arq.nomeArquivo());
        assertArrayEquals(conteudo, arq.conteudo());
    }

    @Test
    void aceitaBoundaryEntreAspas() throws Exception {
        byte[] corpo = corpo("abc", "t", "a.pdf", new byte[] {1, 2, 3});
        List<Multipart.Parte> partes =
                Multipart.analisar("multipart/form-data; boundary=\"abc\"", corpo);
        assertEquals("t", Multipart.campo(partes, "titulo"));
    }

    @Test
    void campoOuArquivoAusenteRetornaNulo() throws Exception {
        byte[] corpo = corpo("abc", "t", null, null);
        List<Multipart.Parte> partes =
                Multipart.analisar("multipart/form-data; boundary=abc", corpo);
        assertNull(Multipart.arquivo(partes, "arquivo"));
        assertNull(Multipart.campo(partes, "inexistente"));
    }

    @Test
    void nomeDeCampoNaoConfundeComFilename() throws Exception {
        // 'filename="titulo"' nao pode ser lido como name="titulo".
        String corpo = "--b\r\nContent-Disposition: form-data; name=\"arquivo\"; "
                + "filename=\"titulo\"\r\n\r\nX\r\n--b--\r\n";
        List<Multipart.Parte> partes = Multipart.analisar("multipart/form-data; boundary=b",
                corpo.getBytes(StandardCharsets.UTF_8));
        assertEquals("arquivo", partes.get(0).nome());
        assertEquals("titulo", partes.get(0).nomeArquivo());
    }

    @Test
    void rejeitaContentTypeIncorreto() {
        assertThrows(ValidacaoException.class,
                () -> Multipart.analisar("application/json", new byte[0]));
        assertThrows(ValidacaoException.class, () -> Multipart.analisar(null, new byte[0]));
        assertThrows(ValidacaoException.class,
                () -> Multipart.analisar("multipart/form-data", new byte[0]));
    }

    @Test
    void rejeitaCorpoMalformado() {
        String ct = "multipart/form-data; boundary=b";
        assertThrows(ValidacaoException.class,
                () -> Multipart.analisar(ct, "lixo sem delimitador".getBytes()));
        // Parte sem delimitador de fechamento.
        assertThrows(ValidacaoException.class, () -> Multipart.analisar(ct,
                "--b\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nvalor".getBytes()));
        // Parte sem Content-Disposition com name.
        assertThrows(ValidacaoException.class, () -> Multipart.analisar(ct,
                "--b\r\nX-Outro: 1\r\n\r\nv\r\n--b--\r\n".getBytes()));
    }

    @Test
    void limitaNumeroDePartes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("--b\r\nContent-Disposition: form-data; name=\"c").append(i)
                    .append("\"\r\n\r\nv\r\n");
        }
        sb.append("--b--\r\n");
        ValidacaoException e = assertThrows(ValidacaoException.class, () -> Multipart.analisar(
                "multipart/form-data; boundary=b", sb.toString().getBytes()));
        assertTrue(e.getMessage().contains("demais"));
    }
}
