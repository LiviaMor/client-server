package sica.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import sica.common.Protocolo;

/**
 * Handler responsavel por atender <b>um</b> cliente TCP.
 *
 * <p>Roda na propria thread (implementa {@link Runnable}). Le comandos do cliente
 * em laco e executa a operacao correspondente: listar, upload ou download.</p>
 *
 * <p>Cada instancia atende uma unica conexao e encerra quando o cliente envia
 * {@link Protocolo#CMD_SAIR} ou fecha o socket.</p>
 */
public class ClienteHandler implements Runnable {

    private final Socket socket;
    private final Path diretorioArquivos;

    public ClienteHandler(Socket socket, Path diretorioArquivos) {
        this.socket = socket;
        this.diretorioArquivos = diretorioArquivos;
    }

    /**
     * Laco principal de atendimento: le um comando por vez e o despacha para o
     * metodo adequado. Fecha os recursos ao final, independentemente de erro.
     */
    @Override
    public void run() {
        try (Socket s = socket;
             DataInputStream entrada =
                     new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream saida =
                     new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {

            boolean ativo = true;
            while (ativo) {
                String comando;
                try {
                    comando = entrada.readUTF();
                } catch (IOException fimDeConexao) {
                    // Cliente fechou a conexao sem enviar SAIR.
                    break;
                }

                switch (comando) {
                    case Protocolo.CMD_LISTAR:
                        listar(saida);
                        break;
                    case Protocolo.CMD_UPLOAD:
                        receberUpload(entrada, saida);
                        break;
                    case Protocolo.CMD_DOWNLOAD:
                        enviarDownload(entrada, saida);
                        break;
                    case Protocolo.CMD_SAIR:
                        ativo = false;
                        break;
                    default:
                        // Comando desconhecido: ignora para nao travar o protocolo.
                        break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Servidor] Erro ao atender cliente: " + e.getMessage());
        } finally {
            System.out.println("[Servidor] Cliente desconectado.");
        }
    }

    /**
     * Responde ao comando LIST: envia a quantidade de arquivos e, para cada um,
     * seu nome e tamanho em bytes.
     *
     * @param saida fluxo de controle para o cliente
     * @throws IOException em caso de falha de escrita
     */
    private void listar(DataOutputStream saida) throws IOException {
        List<Path> arquivos = new ArrayList<>();
        try (Stream<Path> stream = Files.list(diretorioArquivos)) {
            stream.filter(Files::isRegularFile).forEach(arquivos::add);
        }

        saida.writeInt(arquivos.size());
        for (Path arquivo : arquivos) {
            saida.writeUTF(arquivo.getFileName().toString());
            saida.writeLong(Files.size(arquivo));
        }
        saida.flush();
    }

    /**
     * Recebe um arquivo enviado pelo cliente (UPLOAD).
     *
     * <p>Le nome e tamanho, entao grava os bytes em disco em blocos. Responde
     * {@link Protocolo#OK} em sucesso ou {@link Protocolo#ERRO} + mensagem em falha.</p>
     */
    private void receberUpload(DataInputStream entrada, DataOutputStream saida)
            throws IOException {
        String nome = entrada.readUTF();
        long tamanho = entrada.readLong();

        Path destino = resolverSeguro(nome);
        if (destino == null) {
            // Consome os bytes para nao desalinhar o protocolo, mas descarta.
            descartar(entrada, tamanho);
            saida.writeUTF(Protocolo.ERRO);
            saida.writeUTF("Nome de arquivo invalido.");
            saida.flush();
            return;
        }

        try (OutputStream out =
                     new BufferedOutputStream(Files.newOutputStream(destino))) {
            Protocolo.transferir(entrada, out, tamanho);
        }

        saida.writeUTF(Protocolo.OK);
        saida.writeUTF("Arquivo '" + nome + "' recebido (" + tamanho + " bytes).");
        saida.flush();
        System.out.println("[Servidor] Upload concluido: " + nome);
    }

    /**
     * Envia um arquivo solicitado pelo cliente (DOWNLOAD).
     *
     * <p>Le o nome pedido; se o arquivo existir, responde {@link Protocolo#OK},
     * o tamanho e os bytes. Caso contrario, responde {@link Protocolo#ERRO}.</p>
     */
    private void enviarDownload(DataInputStream entrada, DataOutputStream saida)
            throws IOException {
        String nome = entrada.readUTF();
        Path origem = resolverSeguro(nome);

        if (origem == null || !Files.isRegularFile(origem)) {
            saida.writeUTF(Protocolo.ERRO);
            saida.writeUTF("Arquivo nao encontrado no servidor.");
            saida.flush();
            return;
        }

        saida.writeUTF(Protocolo.OK);
        saida.writeLong(Files.size(origem));
        try (var in = new BufferedInputStream(Files.newInputStream(origem))) {
            Protocolo.transferir(in, saida, Files.size(origem));
        }
        System.out.println("[Servidor] Download enviado: " + nome);
    }

    /**
     * Resolve o nome recebido para um caminho dentro do diretorio de arquivos,
     * impedindo <i>path traversal</i> (ex.: nomes com {@code ../} ou caminhos
     * absolutos que escapariam do diretorio do servidor).
     *
     * @return o caminho seguro, ou {@code null} se o nome for invalido
     */
    private Path resolverSeguro(String nome) {
        if (nome == null || nome.isBlank()) {
            return null;
        }
        // Usa apenas o nome final do arquivo, descartando qualquer componente de caminho.
        Path apenasNome = Path.of(nome).getFileName();
        if (apenasNome == null) {
            return null;
        }
        Path resolvido = diretorioArquivos.resolve(apenasNome).normalize();
        // Garante que o caminho final continua dentro do diretorio de arquivos.
        if (!resolvido.startsWith(diretorioArquivos.normalize())) {
            return null;
        }
        return resolvido;
    }

    /** Le e descarta {@code tamanho} bytes do fluxo, mantendo o protocolo alinhado. */
    private void descartar(DataInputStream entrada, long tamanho) throws IOException {
        byte[] buffer = new byte[Protocolo.TAMANHO_BUFFER];
        long restante = tamanho;
        while (restante > 0) {
            int aLer = (int) Math.min(buffer.length, restante);
            int lidos = entrada.read(buffer, 0, aLer);
            if (lidos == -1) {
                break;
            }
            restante -= lidos;
        }
    }
}
