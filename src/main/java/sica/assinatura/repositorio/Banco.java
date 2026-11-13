package sica.assinatura.repositorio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Acesso ao banco SQLite do modulo de assinatura e criacao do esquema.
 *
 * <p>Cada conexao abre com chaves estrangeiras ativas ({@code foreign_keys=ON},
 * que no SQLite vale por conexao) e {@code busy_timeout}, para que escritas
 * concorrentes esperem em vez de falhar imediatamente.</p>
 *
 * <p>A tabela {@code audit_events} e <b>somente insercao</b>: triggers abortam
 * qualquer {@code UPDATE} ou {@code DELETE}. Assim a imutabilidade da trilha de
 * auditoria e imposta pelo proprio banco, e nao apenas pelo codigo.</p>
 */
public final class Banco {

    private static final List<String> ESQUEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS envelopes (
                id               TEXT PRIMARY KEY,
                titulo           TEXT NOT NULL,
                status           TEXT NOT NULL
                                 CHECK (status IN ('DRAFT','SENT','COMPLETED','DECLINED','EXPIRED','CANCELED')),
                sha256_original  TEXT NOT NULL,
                sha256_final     TEXT,
                caminho_original TEXT NOT NULL,
                caminho_final    TEXT,
                expira_em        TEXT,
                criado_em        TEXT NOT NULL,
                concluido_em     TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signers (
                id               TEXT PRIMARY KEY,
                envelope_id      TEXT NOT NULL REFERENCES envelopes(id),
                nome             TEXT NOT NULL,
                email            TEXT NOT NULL,
                status           TEXT NOT NULL
                                 CHECK (status IN ('PENDING','VIEWED','SIGNED','DECLINED')),
                token_hash       TEXT UNIQUE,
                token_expira_em  TEXT,
                nome_confirmado  TEXT,
                assinatura_png   BLOB,
                assinado_em      TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS signature_fields (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                signer_id TEXT NOT NULL REFERENCES signers(id),
                pagina    INTEGER NOT NULL CHECK (pagina >= 1),
                x         REAL NOT NULL,
                y         REAL NOT NULL,
                largura   REAL NOT NULL CHECK (largura > 0),
                altura    REAL NOT NULL CHECK (altura > 0)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS audit_events (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                envelope_id      TEXT NOT NULL REFERENCES envelopes(id),
                signer_id        TEXT REFERENCES signers(id),
                tipo             TEXT NOT NULL,
                ip               TEXT,
                user_agent       TEXT,
                sha256_documento TEXT,
                detalhe          TEXT,
                ocorrido_em      TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_signers_envelope ON signers(envelope_id)",
            "CREATE INDEX IF NOT EXISTS idx_audit_envelope ON audit_events(envelope_id)",
            """
            CREATE TRIGGER IF NOT EXISTS audit_events_sem_update
            BEFORE UPDATE ON audit_events
            BEGIN
                SELECT RAISE(ABORT, 'audit_events e somente insercao');
            END
            """,
            """
            CREATE TRIGGER IF NOT EXISTS audit_events_sem_delete
            BEFORE DELETE ON audit_events
            BEGIN
                SELECT RAISE(ABORT, 'audit_events e somente insercao');
            END
            """
    );

    private final String url;

    /**
     * Prepara o acesso ao banco no arquivo indicado, criando a pasta pai se
     * necessario. Nao abre conexao; use {@link #abrir()}.
     */
    public Banco(Path arquivo) throws IOException {
        Path absoluto = arquivo.toAbsolutePath();
        Path pasta = absoluto.getParent();
        if (pasta != null) {
            Files.createDirectories(pasta);
        }
        this.url = "jdbc:sqlite:" + absoluto;
    }

    /** Abre uma nova conexao com as configuracoes padrao do modulo. */
    public Connection abrir() throws SQLException {
        Connection conexao = DriverManager.getConnection(url);
        try (Statement st = conexao.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException e) {
            conexao.close();
            throw e;
        }
        return conexao;
    }

    /**
     * Cria as tabelas, indices e triggers que ainda nao existirem. E seguro
     * chamar mais de uma vez (idempotente).
     */
    public void inicializar() throws SQLException {
        try (Connection conexao = abrir(); Statement st = conexao.createStatement()) {
            for (String comando : ESQUEMA) {
                st.execute(comando);
            }
        }
    }
}
