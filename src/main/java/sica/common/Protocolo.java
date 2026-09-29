package sica.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Protocolo de comunicacao do SiCA (Sistema de Compartilhamento de Arquivos).
 *
 * <p>O TCP entrega um fluxo continuo de bytes, sem nocao de "mensagem". Por isso
 * definimos aqui um protocolo de aplicacao proprio, que estabelece como cliente e
 * servidor delimitam e interpretam os dados trocados.</p>
 *
 * <h2>Formato geral</h2>
 * Toda a comunicacao de controle usa {@link DataInputStream}/{@link DataOutputStream},
 * que permitem enviar Strings com {@code writeUTF} (prefixadas pelo tamanho) e numeros
 * com {@code writeLong}/{@code writeInt}. Isso elimina ambiguidade na leitura.
 *
 * <h2>Comandos (enviados pelo cliente)</h2>
 * <ul>
 *   <li><b>LIST</b> &rarr; servidor responde: {@code int qtd} seguido de {@code qtd} pares
 *       ({@code String nome}, {@code long tamanho}).</li>
 *   <li><b>UPLOAD</b> &rarr; cliente envia {@code String nome}, {@code long tamanho} e, em
 *       seguida, os {@code tamanho} bytes do arquivo. Servidor responde
 *       {@code String status} ({@link #OK} ou {@link #ERRO}).</li>
 *   <li><b>DOWNLOAD</b> &rarr; cliente envia {@code String nome}. Servidor responde
 *       {@code String status}; se {@link #OK}, envia {@code long tamanho} + bytes.</li>
 *   <li><b>SAIR</b> &rarr; encerra a conexao.</li>
 * </ul>
 */
public final class Protocolo {

    /** Porta padrao em que o servidor TCP escuta. */
    public static final int PORTA_PADRAO = 5000;

    /** Host padrao do servidor. */
    public static final String HOST_PADRAO = "localhost";

    // ----- Comandos do cliente para o servidor -----
    public static final String CMD_LISTAR = "LIST";
    public static final String CMD_UPLOAD = "UPLOAD";
    public static final String CMD_DOWNLOAD = "DOWNLOAD";
    public static final String CMD_SAIR = "SAIR";

    // ----- Respostas de status do servidor -----
    public static final String OK = "OK";
    public static final String ERRO = "ERRO";

    /** Tamanho do buffer usado na transferencia em blocos (8 KB). */
    public static final int TAMANHO_BUFFER = 8192;

    // Classe utilitaria: nao deve ser instanciada.
    private Protocolo() {
    }

    /**
     * Envia um arquivo (sequencia de bytes) por um fluxo de saida, lendo o conteudo
     * em blocos para nao carregar o arquivo inteiro na memoria.
     *
     * <p>Pre-condicao: o {@code tamanho} (long) ja deve ter sido escrito no fluxo de
     * controle antes desta chamada, para que o receptor saiba quantos bytes ler.</p>
     *
     * @param origem  fluxo de onde os bytes do arquivo sao lidos
     * @param destino fluxo para onde os bytes sao escritos (a rede)
     * @param tamanho quantidade total de bytes a transferir
     * @throws IOException em caso de falha de leitura/escrita
     */
    public static void transferir(InputStream origem, OutputStream destino, long tamanho)
            throws IOException {
        byte[] buffer = new byte[TAMANHO_BUFFER];
        long restante = tamanho;
        while (restante > 0) {
            // Nunca lemos mais do que o restante, para nao invadir a proxima mensagem.
            int aLer = (int) Math.min(buffer.length, restante);
            int lidos = origem.read(buffer, 0, aLer);
            if (lidos == -1) {
                throw new IOException("Fim inesperado do fluxo durante a transferencia.");
            }
            destino.write(buffer, 0, lidos);
            restante -= lidos;
        }
        destino.flush();
    }
}
