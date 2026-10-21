package com.veriqra.app.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for the audit trail and for login throttling records.
 */
@Repository
public class AuditRepository {

    /** One immutable entry of the audit trail. */
    public record AuditEntry(long id, long actorId, String actorName, String action, String target,
                             String reasonCode, String explanation, String oldValue, String newValue,
                             Instant createdAt) {
    }

    /** A recorded login attempt, successful or not. */
    public record LoginAttempt(String identifier, String ipAddress, boolean successful, Instant attemptedAt) {
    }

    private final JdbcTemplate db;

    public AuditRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void record(long actorId, String action, String target, String reasonCode, String explanation,
                       String oldValue, String newValue) {
        db.update("""
                insert into audit_log(actor_id, action, target, reason_code, explanation, old_value, new_value, created_at)
                values(?,?,?,?,?,?,?,current_timestamp)
                """, actorId, action, target, reasonCode, explanation, oldValue, newValue);
    }

    public List<AuditEntry> recent(int limit) {
        return db.query("""
                select a.id, a.actor_id, u.full_name as actor_name, a.action, a.target,
                       a.reason_code, a.explanation, a.old_value, a.new_value, a.created_at
                  from audit_log a
                  join app_user u on u.id = a.actor_id
                 order by a.created_at desc, a.id desc
                 limit ?
                """, (rs, rowNum) -> new AuditEntry(rs.getLong("id"), rs.getLong("actor_id"),
                rs.getString("actor_name"), rs.getString("action"), rs.getString("target"),
                rs.getString("reason_code"), rs.getString("explanation"), rs.getString("old_value"),
                rs.getString("new_value"), rs.getTimestamp("created_at").toInstant()), limit);
    }

    /** The audit entries written by one actor, most recent first. */
    public List<AuditEntry> recentByActor(long actorId, int limit) {
        return db.query("""
                select a.id, a.actor_id, u.full_name as actor_name, a.action, a.target,
                       a.reason_code, a.explanation, a.old_value, a.new_value, a.created_at
                  from audit_log a
                  join app_user u on u.id = a.actor_id
                 where a.actor_id = ?
                 order by a.created_at desc, a.id desc
                 limit ?
                """, (rs, rowNum) -> new AuditEntry(rs.getLong("id"), rs.getLong("actor_id"),
                rs.getString("actor_name"), rs.getString("action"), rs.getString("target"),
                rs.getString("reason_code"), rs.getString("explanation"), rs.getString("old_value"),
                rs.getString("new_value"), rs.getTimestamp("created_at").toInstant()), actorId, limit);
    }

    // ------------------------------------------------------------ login throttle

    public void recordLoginAttempt(String identifier, String ipAddress, boolean successful) {
        db.update("insert into auth_attempt(identifier, ip_address, outcome, attempted_at) values(?,?,?,current_timestamp)",
                identifier == null ? "" : identifier.toLowerCase(), ipAddress == null ? "" : ipAddress,
                successful ? "SUCCESS" : "FAILURE");
    }

    /** Number of failed attempts for an identifier inside the given window. */
    public long countRecentFailures(String identifier, Instant since) {
        Long count = db.queryForObject("""
                select count(*) from auth_attempt
                 where identifier = ? and outcome = 'FAILURE' and attempted_at >= ?
                """, Long.class, identifier == null ? "" : identifier.toLowerCase(), Timestamp.from(since));
        return count == null ? 0L : count;
    }

    /** Number of failures from one address inside the given window, across all identifiers. */
    public long countRecentFailuresByIp(String ipAddress, Instant since) {
        Long count = db.queryForObject("""
                select count(*) from auth_attempt
                 where ip_address = ? and outcome = 'FAILURE' and attempted_at >= ?
                """, Long.class, ipAddress == null ? "" : ipAddress, Timestamp.from(since));
        return count == null ? 0L : count;
    }

    public int deleteAttemptsOlderThan(Instant cutoff) {
        return db.update("delete from auth_attempt where attempted_at < ?", Timestamp.from(cutoff));
    }

    // ------------------------------------------------------------------ settings

    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        db.query("select setting_key, setting_value from app_setting order by setting_key",
                rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                });
        return result;
    }

    public void putSetting(String key, String value) {
        int updated = db.update("update app_setting set setting_value = ? where setting_key = ?", value, key);
        if (updated == 0) {
            db.update("insert into app_setting(setting_key, setting_value) values(?,?)", key, value);
        }
    }
}
