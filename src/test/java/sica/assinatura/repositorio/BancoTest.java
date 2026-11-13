package sica.assinatura.repositorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BancoTest {

    @TempDir
    Path pasta;

    Banco banco;

    @BeforeEach
    void preparar() throws Exception {
        banco = new Banco(pasta.resolve("sub/teste.db"));
        banco.inicializar();
    }

    @Test
    void criaTodasAsTabelas() throws Exception {
        Set<String> tabelas = new HashSet<>();
        try (Connection c = banco.abrir();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                tabelas.add(rs.getString(1));
            }
        }
        assertTrue(tabelas.containsAll(
                Set.of("envelopes", "signers", "signature_fields", "audit_events")));
    }

    @Test
    void inicializarEhIdempotente() throws Exception {
        banco.inicializar();
        banco.inicializar();
    }

    @Test
    void auditoriaPermiteInsercaoMasBloqueiaUpdateEDelete() throws Exception {
        try (Connection c = banco.abrir()) {
            inserirEnvelope(c, "e1");
            inserirAuditoria(c, "e1", "envelope.created");

            SQLException update = assertThrows(SQLException.class, () -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("UPDATE audit_events SET tipo = 'adulterado'");
                }
            });
            assertTrue(update.getMessage().contains("somente insercao"));

            SQLException delete = assertThrows(SQLException.class, () -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM audit_events");
                }
            });
            assertTrue(delete.getMessage().contains("somente insercao"));

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT tipo FROM audit_events")) {
                assertTrue(rs.next());
                assertEquals("envelope.created", rs.getString(1));
            }
        }
    }

    @Test
    void chavesEstrangeirasSaoAplicadas() throws Exception {
        try (Connection c = banco.abrir()) {
            assertThrows(SQLException.class, () -> inserirAuditoria(c, "inexistente", "x"));
        }
    }

    @Test
    void statusInvalidoEhRejeitado() throws Exception {
        try (Connection c = banco.abrir();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO envelopes (id, titulo, status, sha256_original,"
                             + " caminho_original, criado_em) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, "e2");
            ps.setString(2, "t");
            ps.setString(3, "QUALQUER");
            ps.setString(4, "h");
            ps.setString(5, "p");
            ps.setString(6, "2026-01-01T00:00:00Z");
            assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void tokenHashDuplicadoEhRejeitado() throws Exception {
        try (Connection c = banco.abrir()) {
            inserirEnvelope(c, "e3");
            inserirSignatario(c, "s1", "e3", "hash-igual");
            assertThrows(SQLException.class, () -> inserirSignatario(c, "s2", "e3", "hash-igual"));
        }
    }

    // ----- Auxiliares -----

    private static void inserirEnvelope(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO envelopes (id, titulo, status, sha256_original,"
                        + " caminho_original, criado_em) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, "Contrato");
            ps.setString(3, "DRAFT");
            ps.setString(4, "abc");
            ps.setString(5, "storage/x.pdf");
            ps.setString(6, "2026-01-01T00:00:00Z");
            ps.executeUpdate();
        }
    }

    private static void inserirAuditoria(Connection c, String envelopeId, String tipo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_events (envelope_id, tipo, ocorrido_em) VALUES (?,?,?)")) {
            ps.setString(1, envelopeId);
            ps.setString(2, tipo);
            ps.setString(3, "2026-01-01T00:00:00Z");
            ps.executeUpdate();
        }
    }

    private static void inserirSignatario(Connection c, String id, String envelopeId,
                                          String tokenHash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO signers (id, envelope_id, nome, email, status, token_hash)"
                        + " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, envelopeId);
            ps.setString(3, "Fulano");
            ps.setString(4, "fulano@exemplo.com");
            ps.setString(5, "PENDING");
            ps.setString(6, tokenHash);
            ps.executeUpdate();
        }
    }
}
