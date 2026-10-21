package com.veriqra.app.repository;

import com.veriqra.app.domain.AppUser;
import com.veriqra.app.security.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the {@code app_user} table.
 */
@Repository
public class UserRepository {

    /** Row shape that also carries the password hash, used only during authentication. */
    public record StoredUser(
            long id,
            String email,
            String passwordHash,
            String fullName,
            Role role,
            boolean active,
            String studentNumber,
            AppUser.FaceState faceState,
            Instant faceVerifiedAt,
            Instant createdAt) {
    }

    private static final String BASE_SELECT = """
            select id, email, password_hash, full_name, role, active, student_number,
                   face_state, face_verified_at, created_at
              from app_user
            """;

    private final JdbcTemplate db;

    public UserRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final RowMapper<StoredUser> STORED_MAPPER = (rs, rowNum) -> new StoredUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("full_name"),
            Role.fromDatabase(rs.getString("role")),
            rs.getBoolean("active"),
            rs.getString("student_number"),
            readFaceState(rs.getString("face_state")),
            readInstant(rs.getTimestamp("face_verified_at")),
            readInstant(rs.getTimestamp("created_at")));

    private static AppUser.FaceState readFaceState(String value) {
        if (value == null || value.isBlank()) {
            return AppUser.FaceState.PENDING;
        }
        try {
            return AppUser.FaceState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return AppUser.FaceState.PENDING;
        }
    }

    private static Instant readInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public Optional<StoredUser> findStoredByEmail(String email) {
        List<StoredUser> rows = db.query(BASE_SELECT + " where lower(email) = lower(?)", STORED_MAPPER, email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AppUser> findByEmail(String email) {
        return findStoredByEmail(email).map(UserRepository::toDomain);
    }

    public Optional<AppUser> findById(long id) {
        List<AppUser> rows = db.query(BASE_SELECT + " where id = ?", (rs, rowNum) -> toDomain(STORED_MAPPER.mapRow(rs, rowNum)), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean existsByEmail(String email) {
        Integer count = db.queryForObject("select count(*) from app_user where lower(email) = lower(?)",
                Integer.class, email);
        return count != null && count > 0;
    }

    public boolean existsByStudentNumber(String studentNumber) {
        Integer count = db.queryForObject("select count(*) from app_user where student_number = ?",
                Integer.class, studentNumber);
        return count != null && count > 0;
    }

    /**
     * Creates an account. The caller is responsible for hashing the password.
     *
     * @return the identifier of the new row
     */
    public long create(String email, String passwordHash, String fullName, Role role, String studentNumber) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into app_user(email, password_hash, full_name, role, student_number, active, face_state, created_at) "
                            + "values(?,?,?,?,?,true,'PENDING',current_timestamp)",
                    new String[]{"id"});
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, fullName);
            statement.setString(4, role.name());
            statement.setString(5, studentNumber);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public void recordSuccessfulLogin(long userId) {
        db.update("update app_user set last_login_at = current_timestamp where id = ?", userId);
    }

    public void markFaceVerified(long userId, Instant verifiedAt) {
        db.update("update app_user set face_state = 'VERIFIED', face_verified_at = ? where id = ?",
                Timestamp.from(verifiedAt), userId);
    }

    public void markFaceRevoked(long userId) {
        db.update("update app_user set face_state = 'REVOKED', face_verified_at = null where id = ?", userId);
    }

    public void setActive(long userId, boolean active) {
        db.update("update app_user set active = ? where id = ?", active, userId);
    }

    /** Replaces the password of an account. */
    public void updatePassword(long userId, String passwordHash) {
        db.update("update app_user set password_hash = ? where id = ?", passwordHash, userId);
    }

    public List<AppUser> listAll() {
        return db.query(BASE_SELECT + " order by role, full_name", (rs, rowNum) -> toDomain(STORED_MAPPER.mapRow(rs, rowNum)));
    }

    public List<AppUser> listByRole(Role role) {
        return db.query(BASE_SELECT + " where role = ? order by full_name",
                (rs, rowNum) -> toDomain(STORED_MAPPER.mapRow(rs, rowNum)), role.name());
    }

    public long countByRole(Role role) {
        Long count = db.queryForObject("select count(*) from app_user where role = ? and active = true", Long.class, role.name());
        return count == null ? 0L : count;
    }

    private static AppUser toDomain(StoredUser stored) {
        return new AppUser(stored.id(), stored.email(), stored.fullName(), stored.role(), stored.active(),
                stored.studentNumber(), stored.faceState(), stored.faceVerifiedAt(), stored.createdAt());
    }
}
