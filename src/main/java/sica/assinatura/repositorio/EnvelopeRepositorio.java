package sica.assinatura.repositorio;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import sica.assinatura.modelo.Envelope;
import sica.assinatura.modelo.StatusEnvelope;

/**
 * Acesso a tabela {@code envelopes}. Todos os metodos recebem a
 * {@link Connection}, para que o servico controle a transacao.
 */
public final class EnvelopeRepositorio {

    private EnvelopeRepositorio() {
    }

    public static void inserir(Connection c, Envelope e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO envelopes (id, titulo, status, sha256_original, sha256_final,"
                        + " caminho_original, criado_em, expira_em, concluido_em)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.id());
            ps.setString(2, e.titulo());
            ps.setString(3, e.status().name());
            ps.setString(4, e.sha256Original());
            ps.setString(5, e.sha256Final());
            ps.setString(6, e.caminhoOriginal());
            ps.setString(7, e.criadoEm());
            ps.setString(8, e.expiraEm());
            ps.setString(9, e.concluidoEm());
            ps.executeUpdate();
        }
    }

    /**
     * Garante, de forma atomica, que o envelope esta no status esperado e
     * adquire o bloqueio de escrita do SQLite para o restante da transacao.
     * Serializa operacoes concorrentes sobre o mesmo banco (ex.: duas adicoes
     * de signatario ao mesmo tempo, ou um envio durante uma adicao).
     *
     * @return {@code true} se o envelope existe e esta no status esperado
     */
    public static boolean reservar(Connection c, String id, StatusEnvelope esperado)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE envelopes SET status = status WHERE id = ? AND status = ?")) {
            ps.setString(1, id);
            ps.setString(2, esperado.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Muda o status somente se o atual for {@code de} (compare-and-set).
     *
     * @return {@code true} se a transicao foi aplicada
     */
    public static boolean transitar(Connection c, String id, StatusEnvelope de,
                                    StatusEnvelope para) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE envelopes SET status = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, para.name());
            ps.setString(2, id);
            ps.setString(3, de.name());
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Conclui o envelope (SENT -> COMPLETED) registrando o PDF final.
     *
     * @return {@code true} se esta chamada concluiu; {@code false} se ja nao estava em SENT
     */
    public static boolean concluir(Connection c, String id, String sha256Final,
                                   String caminhoFinal, String concluidoEm) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE envelopes SET status = 'COMPLETED', sha256_final = ?, caminho_final = ?,"
                        + " concluido_em = ? WHERE id = ? AND status = 'SENT'")) {
            ps.setString(1, sha256Final);
            ps.setString(2, caminhoFinal);
            ps.setString(3, concluidoEm);
            ps.setString(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** Caminho do PDF final (relativo ao storage), ou vazio se nao concluido. */
    public static Optional<String> caminhoFinal(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT caminho_final FROM envelopes WHERE id = ? AND status = 'COMPLETED'")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Envelope cujo PDF final (ou original) tem o hash informado. */
    public static Optional<Envelope> buscarPorHash(Connection c, String sha256)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM envelopes WHERE sha256_final = ? OR sha256_original = ?"
                        + " ORDER BY CASE WHEN sha256_final = ? THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, sha256);
            ps.setString(2, sha256);
            ps.setString(3, sha256);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? buscar(c, rs.getString(1)) : Optional.empty();
            }
        }
    }

    public static Optional<Envelope> buscar(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, titulo, status, sha256_original, sha256_final, caminho_original,"
                        + " criado_em, expira_em, concluido_em FROM envelopes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Envelope(
                        rs.getString("id"),
                        rs.getString("titulo"),
                        StatusEnvelope.valueOf(rs.getString("status")),
                        rs.getString("sha256_original"),
                        rs.getString("sha256_final"),
                        rs.getString("caminho_original"),
                        rs.getString("criado_em"),
                        rs.getString("expira_em"),
                        rs.getString("concluido_em")));
            }
        }
    }
}
