package sica.assinatura.repositorio;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sica.assinatura.modelo.AssinaturaRegistrada;
import sica.assinatura.modelo.CampoAssinatura;
import sica.assinatura.modelo.Signatario;
import sica.assinatura.modelo.StatusSignatario;

/** Acesso as tabelas {@code signers} e {@code signature_fields}. */
public final class SignatarioRepositorio {

    private static final String SELECT =
            "SELECT s.id, s.envelope_id, s.nome, s.email, s.status, s.token_expira_em,"
                    + " s.assinado_em, f.pagina, f.x, f.y, f.largura, f.altura"
                    + " FROM signers s LEFT JOIN signature_fields f ON f.signer_id = s.id"
                    + " WHERE s.envelope_id = ?";

    private SignatarioRepositorio() {
    }

    /** Insere o signatario e seu campo de assinatura (sem token). */
    public static void inserir(Connection c, Signatario s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO signers (id, envelope_id, nome, email, status) VALUES (?,?,?,?,?)")) {
            ps.setString(1, s.id());
            ps.setString(2, s.envelopeId());
            ps.setString(3, s.nome());
            ps.setString(4, s.email());
            ps.setString(5, s.status().name());
            ps.executeUpdate();
        }
        CampoAssinatura f = s.campo();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO signature_fields (signer_id, pagina, x, y, largura, altura)"
                        + " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, s.id());
            ps.setInt(2, f.pagina());
            ps.setDouble(3, f.x());
            ps.setDouble(4, f.y());
            ps.setDouble(5, f.largura());
            ps.setDouble(6, f.altura());
            ps.executeUpdate();
        }
    }

    /** Lista os signatarios do envelope, na ordem de cadastro. */
    public static List<Signatario> listar(Connection c, String envelopeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " ORDER BY s.rowid")) {
            ps.setString(1, envelopeId);
            return ler(ps);
        }
    }

    /** Busca um signatario dentro de um envelope especifico. */
    public static Optional<Signatario> buscar(Connection c, String envelopeId, String signerId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " AND s.id = ?")) {
            ps.setString(1, envelopeId);
            ps.setString(2, signerId);
            return ler(ps).stream().findFirst();
        }
    }

    public static int contar(Connection c, String envelopeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM signers WHERE envelope_id = ?")) {
            ps.setString(1, envelopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Indica se o e-mail (ja normalizado em minusculas) ja esta no envelope. */
    public static boolean emailExiste(Connection c, String envelopeId, String email)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM signers WHERE envelope_id = ? AND email = ?")) {
            ps.setString(1, envelopeId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Define (ou substitui) o hash do token do signatario. Um novo token
     * invalida o anterior. So afeta signatarios que ainda nao concluiram.
     *
     * @return {@code true} se o signatario pode receber token (PENDING/VIEWED)
     */
    public static boolean definirToken(Connection c, String signerId, String tokenHash,
                                       String expiraEm) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE signers SET token_hash = ?, token_expira_em = ?"
                        + " WHERE id = ? AND status IN ('PENDING','VIEWED')")) {
            ps.setString(1, tokenHash);
            ps.setString(2, expiraEm);
            ps.setString(3, signerId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Localiza o signatario pelo hash do token do link.
     */
    public static Optional<Signatario> buscarPorTokenHash(Connection c, String tokenHash)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                SELECT.replace("WHERE s.envelope_id = ?", "WHERE s.token_hash = ?"))) {
            ps.setString(1, tokenHash);
            return ler(ps).stream().findFirst();
        }
    }

    /** Marca como visualizado (so a partir de PENDING); idempotente. */
    public static void marcarVisto(Connection c, String signerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE signers SET status = 'VIEWED' WHERE id = ? AND status = 'PENDING'")) {
            ps.setString(1, signerId);
            ps.executeUpdate();
        }
    }

    /**
     * Registra a assinatura de forma atomica: so tem efeito se o signatario ainda
     * estiver pendente <b>e</b> o token ainda for o mesmo (um reenvio no meio do
     * caminho invalida o link antigo).
     *
     * @return {@code true} se esta chamada assinou; {@code false} se outra chegou antes
     */
    public static boolean assinar(Connection c, String signerId, String tokenHash,
                                  String nomeConfirmado, byte[] png, String assinadoEm)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE signers SET status = 'SIGNED', nome_confirmado = ?, assinatura_png = ?,"
                        + " assinado_em = ? WHERE id = ? AND token_hash = ?"
                        + " AND status IN ('PENDING','VIEWED')")) {
            ps.setString(1, nomeConfirmado);
            ps.setBytes(2, png);
            ps.setString(3, assinadoEm);
            ps.setString(4, signerId);
            ps.setString(5, tokenHash);
            return ps.executeUpdate() == 1;
        }
    }

    /** Registra a recusa, com as mesmas garantias de {@link #assinar}. */
    public static boolean recusar(Connection c, String signerId, String tokenHash)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE signers SET status = 'DECLINED' WHERE id = ? AND token_hash = ?"
                        + " AND status IN ('PENDING','VIEWED')")) {
            ps.setString(1, signerId);
            ps.setString(2, tokenHash);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Assinaturas ja registradas do envelope (somente signatarios SIGNED), na
     * ordem de cadastro, com o nome digitado e a imagem.
     */
    public static List<AssinaturaRegistrada> assinaturas(Connection c, String envelopeId)
            throws SQLException {
        List<Signatario> todos = listar(c, envelopeId);
        List<AssinaturaRegistrada> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT nome_confirmado, assinatura_png FROM signers WHERE id = ?"
                        + " AND status = 'SIGNED'")) {
            for (Signatario s : todos) {
                ps.setString(1, s.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        lista.add(new AssinaturaRegistrada(s, rs.getString(1), rs.getBytes(2)));
                    }
                }
            }
        }
        return lista;
    }

    /** Quantos signatarios do envelope ainda nao assinaram. */
    public static int contarNaoAssinados(Connection c, String envelopeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM signers WHERE envelope_id = ? AND status <> 'SIGNED'")) {
            ps.setString(1, envelopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<Signatario> ler(PreparedStatement ps) throws SQLException {
        List<Signatario> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int pagina = rs.getInt("pagina");
                CampoAssinatura campo = rs.wasNull() ? null : new CampoAssinatura(
                        pagina, rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("largura"), rs.getDouble("altura"));
                lista.add(new Signatario(
                        rs.getString("id"),
                        rs.getString("envelope_id"),
                        rs.getString("nome"),
                        rs.getString("email"),
                        StatusSignatario.valueOf(rs.getString("status")),
                        rs.getString("token_expira_em"),
                        rs.getString("assinado_em"),
                        campo));
            }
        }
        return lista;
    }
}
