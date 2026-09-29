package sica.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import sica.common.Protocolo;

/**
 * Cliente do SiCA (interface por linha de comando).
 *
 * <p>Conecta-se ao servidor via socket TCP e oferece um menu interativo com as
 * operacoes exigidas: enviar (upload), listar e baixar (download) arquivos.</p>
 *
 * <p>Os arquivos baixados sao salvos no diretorio {@code client-downloads/}.</p>
 */
public class Cliente {

    private final String host;
    private final int porta;
    /** Diretorio local onde os downloads sao gravados. */
    private final Path diretorioDownloads = Paths.get("client-downloads");

    public Cliente(String host, int porta) {
        this.host = host;
        this.porta = porta;
    }

    /**
     * Abre a conexao com o servidor e executa o laco de menu ate o usuario sair.
     * A conexao permanece aberta durante toda a sessao (varias operacoes).
     */
    public void executar() throws IOException {
        Files.createDirectories(diretorioDownloads);

        try (Socket socket = new Socket(host, porta);
             DataInputStream entrada =
                     new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream saida =
                     new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             Scanner teclado = new Scanner(System.in)) {

            System.out.println("Conectado ao SiCA em " + host + ":" + porta);

            boolean rodando = true;
            while (rodando) {
                exibirMenu();
                String opcao = teclado.nextLine().trim();
                switch (opcao) {
                    case "1":
                        enviarArquivo(teclado, entrada, saida);
                        break;
                    case "2":
                        listarArquivos(entrada, saida);
                        break;
                    case "3":
                        baixarArquivo(teclado, entrada, saida);
                        break;
                    case "4":
                        saida.writeUTF(Protocolo.CMD_SAIR);
                        saida.flush();
                        rodando = false;
                        System.out.println("Encerrando. Ate mais!");
                        break;
                    default:
                        System.out.println("Opcao invalida.");
                }
            }
        }
    }

    private void exibirMenu() {
        System.out.println();
        System.out.println("===== SiCA - Menu =====");
        System.out.println("1 - Enviar arquivo (upload)");
        System.out.println("2 - Listar arquivos do servidor");
        System.out.println("3 - Baixar arquivo (download)");
        System.out.println("4 - Sair");
        System.out.print("Escolha uma opcao: ");
    }

    /**
     * Realiza o upload: pede o caminho do arquivo local, envia o comando UPLOAD,
     * o nome, o tamanho e os bytes. Depois le a resposta de status do servidor.
     */
    private void enviarArquivo(Scanner teclado, DataInputStream entrada,
                               DataOutputStream saida) throws IOException {
        System.out.print("Caminho do arquivo local: ");
        String caminho = teclado.nextLine().trim();
        Path arquivo = Paths.get(caminho);

        if (!Files.isRegularFile(arquivo)) {
            System.out.println("Arquivo nao encontrado: " + caminho);
            return;
        }

        long tamanho = Files.size(arquivo);
        saida.writeUTF(Protocolo.CMD_UPLOAD);
        saida.writeUTF(arquivo.getFileName().toString());
        saida.writeLong(tamanho);
        try (var in = new BufferedInputStream(Files.newInputStream(arquivo))) {
            Protocolo.transferir(in, saida, tamanho);
        }
        saida.flush();

        String status = entrada.readUTF();
        String mensagem = entrada.readUTF();
        System.out.println("[" + status + "] " + mensagem);
    }

    /**
     * Solicita e imprime a listagem de arquivos do servidor (nome e tamanho).
     */
    private void listarArquivos(DataInputStream entrada, DataOutputStream saida)
            throws IOException {
        saida.writeUTF(Protocolo.CMD_LISTAR);
        saida.flush();

        int quantidade = entrada.readInt();
        if (quantidade == 0) {
            System.out.println("Nenhum arquivo no servidor.");
            return;
        }
        System.out.println("Arquivos no servidor (" + quantidade + "):");
        for (int i = 0; i < quantidade; i++) {
            String nome = entrada.readUTF();
            long tamanho = entrada.readLong();
            System.out.printf("  - %s (%d bytes)%n", nome, tamanho);
        }
    }

    /**
     * Realiza o download: envia o comando DOWNLOAD e o nome desejado; se o servidor
     * responder OK, le o tamanho e grava os bytes em {@code client-downloads/}.
     */
    private void baixarArquivo(Scanner teclado, DataInputStream entrada,
                               DataOutputStream saida) throws IOException {
        System.out.print("Nome do arquivo a baixar: ");
        String nome = teclado.nextLine().trim();

        saida.writeUTF(Protocolo.CMD_DOWNLOAD);
        saida.writeUTF(nome);
        saida.flush();

        String status = entrada.readUTF();
        if (!Protocolo.OK.equals(status)) {
            String mensagem = entrada.readUTF();
            System.out.println("[" + status + "] " + mensagem);
            return;
        }

        long tamanho = entrada.readLong();
        Path destino = diretorioDownloads.resolve(Path.of(nome).getFileName());
        try (OutputStream out =
                     new BufferedOutputStream(Files.newOutputStream(destino))) {
            Protocolo.transferir(entrada, out, tamanho);
        }
        System.out.println("Download concluido: " + destino.toAbsolutePath()
                + " (" + tamanho + " bytes)");
    }

    /**
     * Ponto de entrada do cliente.
     *
     * @param args {@code args[0]} = host (opcional), {@code args[1]} = porta (opcional)
     */
    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : Protocolo.HOST_PADRAO;
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : Protocolo.PORTA_PADRAO;
        new Cliente(host, porta).executar();
    }
}
