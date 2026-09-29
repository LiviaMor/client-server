package sica.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import sica.common.Protocolo;

/**
 * Cliente TCP programatico do SiCA, usado pelo gateway web.
 *
 * <p>Diferente do cliente de linha de comando, esta classe nao interage com o
 * usuario: cada metodo abre uma conexao TCP com o servidor SiCA, executa uma
 * operacao do protocolo e devolve o resultado em memoria, para que o gateway
 * HTTP possa repassa-lo ao navegador.</p>
 */
public class SicaTcpClient {

    private final String host;
    private final int porta;

    public SicaTcpClient(String host, int porta) {
        this.host = host;
        this.porta = porta;
    }

    /** Representa uma entrada da listagem: nome do arquivo e tamanho em bytes. */
    public static final class ArquivoInfo {
        public final String nome;
        public final long tamanho;

        public ArquivoInfo(String nome, long tamanho) {
            this.nome = nome;
            this.tamanho = tamanho;
        }
    }

    /**
     * Consulta o servidor (comando LIST) e devolve a lista de arquivos disponiveis.
     */
    public List<ArquivoInfo> listar() throws IOException {
        try (Socket socket = new Socket(host, porta);
             DataInputStream in =
                     new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out =
                     new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            out.writeUTF(Protocolo.CMD_LISTAR);
            out.flush();

            int quantidade = in.readInt();
            List<ArquivoInfo> arquivos = new ArrayList<>(quantidade);
            for (int i = 0; i < quantidade; i++) {
                String nome = in.readUTF();
                long tamanho = in.readLong();
                arquivos.add(new ArquivoInfo(nome, tamanho));
            }
            encerrar(out);
            return arquivos;
        }
    }

    /**
     * Envia um arquivo (comando UPLOAD) a partir de bytes ja carregados em memoria.
     *
     * @param nome     nome do arquivo a criar no servidor
     * @param conteudo bytes do arquivo
     * @return mensagem de status retornada pelo servidor
     */
    public String upload(String nome, byte[] conteudo) throws IOException {
        try (Socket socket = new Socket(host, porta);
             DataInputStream in =
                     new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out =
                     new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            out.writeUTF(Protocolo.CMD_UPLOAD);
            out.writeUTF(nome);
            out.writeLong(conteudo.length);
            Protocolo.transferir(new ByteArrayInputStream(conteudo), out, conteudo.length);
            out.flush();

            String status = in.readUTF();
            String mensagem = in.readUTF();
            encerrar(out);
            return status + ": " + mensagem;
        }
    }

    /**
     * Baixa um arquivo do servidor (comando DOWNLOAD) para a memoria.
     *
     * @param nome nome do arquivo desejado
     * @return os bytes do arquivo, ou {@code null} se o servidor nao o encontrou
     */
    public byte[] download(String nome) throws IOException {
        try (Socket socket = new Socket(host, porta);
             DataInputStream in =
                     new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out =
                     new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            out.writeUTF(Protocolo.CMD_DOWNLOAD);
            out.writeUTF(nome);
            out.flush();

            String status = in.readUTF();
            if (!Protocolo.OK.equals(status)) {
                in.readUTF(); // consome a mensagem de erro
                encerrar(out);
                return null;
            }

            long tamanho = in.readLong();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Protocolo.transferir(in, buffer, tamanho);
            encerrar(out);
            return buffer.toByteArray();
        }
    }

    /** Envia o comando SAIR de forma cordial antes de fechar o socket. */
    private void encerrar(DataOutputStream out) {
        try {
            out.writeUTF(Protocolo.CMD_SAIR);
            out.flush();
        } catch (IOException ignorado) {
            // Conexao ja sera fechada pelo try-with-resources.
        }
    }
}
