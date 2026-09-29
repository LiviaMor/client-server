package sica.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import sica.common.Protocolo;

/**
 * Servidor do SiCA.
 *
 * <p>Abre um {@link ServerSocket} numa porta TCP e fica em laco infinito aceitando
 * conexoes. Para cada cliente conectado cria uma nova <b>thread</b>
 * ({@link ClienteHandler}), de modo que varios clientes possam ser atendidos
 * simultaneamente (modelo "thread por conexao").</p>
 *
 * <p>Os arquivos enviados sao armazenados no diretorio {@code server-files/}
 * (criado automaticamente na inicializacao).</p>
 */
public class Servidor {

    /** Diretorio onde o servidor guarda fisicamente os arquivos. */
    private final Path diretorioArquivos;
    private final int porta;

    public Servidor(int porta, Path diretorioArquivos) {
        this.porta = porta;
        this.diretorioArquivos = diretorioArquivos;
    }

    /**
     * Inicializa o diretorio de arquivos e entra no laco de aceitacao de conexoes.
     * Cada conexao aceita gera uma thread dedicada.
     *
     * @throws IOException se o {@link ServerSocket} nao puder ser aberto
     */
    public void iniciar() throws IOException {
        Files.createDirectories(diretorioArquivos);

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            System.out.println("[Servidor] SiCA no ar na porta " + porta);
            System.out.println("[Servidor] Diretorio de arquivos: "
                    + diretorioArquivos.toAbsolutePath());

            while (true) {
                // accept() bloqueia ate que um cliente se conecte.
                Socket socket = serverSocket.accept();
                System.out.println("[Servidor] Cliente conectado: "
                        + socket.getRemoteSocketAddress());

                // Uma thread por cliente: permite atender varios ao mesmo tempo.
                ClienteHandler handler = new ClienteHandler(socket, diretorioArquivos);
                new Thread(handler).start();
            }
        }
    }

    /**
     * Ponto de entrada do servidor.
     *
     * @param args opcionalmente {@code args[0]} = porta a ser usada
     */
    public static void main(String[] args) throws IOException {
        int porta = Protocolo.PORTA_PADRAO;
        if (args.length > 0) {
            porta = Integer.parseInt(args[0]);
        }
        Path dir = Paths.get("server-files");
        new Servidor(porta, dir).iniciar();
    }
}
