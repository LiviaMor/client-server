package sica.assinatura.repositorio;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import sica.assinatura.modelo.EventoAuditoria;

/**
 * Registro de eventos na trilha de auditoria. A tabela e somente insercao
 * (imposto por triggers no banco), portanto esta classe so oferece
 * {@link #registrar}.
 */
public final class AuditoriaRepositorio {

    private AuditoriaRepositorio() {
    }

    /** Lista os eventos do envelope em ordem cronologica de registro. */
    public static List<EventoAuditoria> listar(Connection c, String envelopeId)
            throws SQLException {
        List<EventoAuditoria> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, envelope_id, signer_id, tipo, ip, user_agent, sha256_documento,"
                        + " detalhe, ocorrido_em FROM audit_events WHERE envelope_id = ?"
                        + " ORDER BY id")) {
            ps.setString(1, envelopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new EventoAuditoria(rs.getLong(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9)));
                }
            }
        }
        return lista;
    }

    /**
     * Registra um evento.
     *
     * @param signerId       signatario envolvido, ou {@code null}
     * @param tipo           tipo do evento (ex.: {@code envelope.created})
     * @param ip             IP de origem, ou {@code null}
     * @param userAgent      user agent, ou {@code null}
     * @param sha256Documento hash do documento no momento do evento, ou {@code null}
     * @param detalhe        informacao adicional livre, ou {@code null}
     */
    public static void registrar(Connection c, String envelopeId, String signerId, String tipo,
                                 String ip, String userAgent, String sha256Documento,
                                 String detalhe) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_events (envelope_id, signer_id, tipo, ip, user_agent,"
                        + " sha256_documento, detalhe, ocorrido_em) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, envelopeId);
            ps.setString(2, signerId);
            ps.setString(3, tipo);
            ps.setString(4, ip);
            ps.setString(5, userAgent);
            ps.setString(6, sha256Documento);
            ps.setString(7, detalhe);
            ps.setString(8, Instant.now().toString());
            ps.executeUpdate();
        }
    }
}
