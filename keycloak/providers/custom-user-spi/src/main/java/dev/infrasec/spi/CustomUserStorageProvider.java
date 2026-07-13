package dev.infrasec.spi;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.*;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.mindrot.jbcrypt.BCrypt;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * CustomUserStorageProvider — 레거시 DB 기반 User Storage + Lazy Migration
 *
 * <p>동작 흐름:
 * <ol>
 *   <li>Keycloak 내부 DB에서 유저 조회 시도</li>
 *   <li>없으면 외부 MariaDB(레거시)에서 조회</li>
 *   <li>인증 시 외부 DB bcrypt 검증 → 성공하면 즉시 KC 내부 DB로 이관</li>
 *   <li>이관 후 외부 DB의 migrated_at 갱신</li>
 * </ol>
 *
 * <p>이 방식으로 다운타임 없이 점진적 마이그레이션 가능
 */
public class CustomUserStorageProvider
        implements UserStorageProvider,
                   UserLookupProvider,
                   CredentialInputValidator {

    private static final Logger LOG = Logger.getLogger(CustomUserStorageProvider.class);

    private final KeycloakSession session;
    private final ComponentModel model;
    private final DataSource dataSource;

    public CustomUserStorageProvider(
            KeycloakSession session,
            ComponentModel model,
            DataSource dataSource) {
        this.session = session;
        this.model = model;
        this.dataSource = dataSource;
    }

    // ─────────────────────────────────────────────────────────
    // UserLookupProvider
    // ─────────────────────────────────────────────────────────

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        String externalId = StorageId.externalId(id);
        return findByUsername(realm, externalId);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        return findByUsername(realm, username);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return findByEmail(realm, email);
    }

    // ─────────────────────────────────────────────────────────
    // CredentialInputValidator
    // ─────────────────────────────────────────────────────────

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType);
    }

    /**
     * 인증 검증 + Lazy Migration 핵심 로직
     *
     * <p>외부 DB bcrypt 검증 성공 시 → Keycloak 내부 DB로 PBKDF2 해시로 자동 이관
     */
    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) return false;

        String rawPassword = input.getChallengeResponse();
        String username = user.getUsername();

        Optional<LegacyUser> legacyOpt = fetchLegacyUser(username);
        if (legacyOpt.isEmpty()) {
            LOG.warnf("레거시 DB에서 유저를 찾을 수 없음: %s", username);
            return false;
        }

        LegacyUser legacy = legacyOpt.get();

        // bcrypt 검증
        boolean isValid = BCrypt.checkpw(rawPassword, legacy.passwordHash());

        if (isValid) {
            // ── Lazy Migration 실행 ──
            LOG.infof("[MIGRATION] 유저 이관 시작: %s → Keycloak 내부 DB", username);
            try {
                migrateToKeycloak(realm, user, rawPassword, legacy);
                markAsMigrated(username);
                LOG.infof("[MIGRATION] 유저 이관 완료: %s", username);
            } catch (Exception e) {
                // 이관 실패해도 이번 인증은 성공 처리 (다음 로그인에 재시도)
                LOG.errorf(e, "[MIGRATION] 유저 이관 실패 (인증은 허용): %s", username);
            }
        }

        return isValid;
    }

    // ─────────────────────────────────────────────────────────
    // 내부 메서드
    // ─────────────────────────────────────────────────────────

    private UserModel findByUsername(RealmModel realm, String username) {
        return fetchLegacyUser(username)
                .filter(u -> u.isActive())
                .map(u -> new LegacyUserAdapter(session, realm, model, u))
                .orElse(null);
    }

    private UserModel findByEmail(RealmModel realm, String email) {
        Optional<LegacyUser> legacyOpt;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, username, email, full_name, department, role, is_active " +
                     "FROM users WHERE email = ? AND migrated_at IS NULL LIMIT 1")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    legacyOpt = Optional.of(mapResultSet(rs));
                } else {
                    return null;
                }
            }
        } catch (SQLException e) {
            LOG.errorf(e, "이메일로 유저 조회 실패: %s", email);
            return null;
        }
        return legacyOpt
                .filter(LegacyUser::isActive)
                .map(u -> new LegacyUserAdapter(session, realm, model, u))
                .orElse(null);
    }

    private Optional<LegacyUser> fetchLegacyUser(String username) {
        // migrated_at IS NULL 조건 → 이미 이관된 유저는 외부 DB에서 조회 안 함
        final String sql = """
            SELECT id, username, email, password_hash, full_name, department, role, is_active
            FROM users
            WHERE username = ? AND migrated_at IS NULL
            LIMIT 1
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf(e, "레거시 유저 조회 중 DB 오류: %s", username);
        }
        return Optional.empty();
    }

    private LegacyUser mapResultSet(ResultSet rs) throws SQLException {
        return new LegacyUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("department"),
                rs.getString("role"),
                rs.getBoolean("is_active")
        );
    }

    /**
     * Keycloak 내부 DB로 유저 등록 + PBKDF2 패스워드 설정
     */
    private void migrateToKeycloak(
            RealmModel realm,
            UserModel federatedUser,
            String rawPassword,
            LegacyUser legacy) {

        // UserModel이 이미 KC 세션에 있는 경우 패스워드만 설정
        UserModel kcUser = session.users().addUser(realm, legacy.username());
        kcUser.setEmail(legacy.email());
        kcUser.setEmailVerified(true);
        kcUser.setEnabled(legacy.isActive());
        kcUser.setFirstName(extractFirstName(legacy.fullName()));
        kcUser.setLastName(extractLastName(legacy.fullName()));
        kcUser.setSingleAttribute("department", legacy.department());
        kcUser.setSingleAttribute("legacy_role", legacy.role());

        // KC 내부 PBKDF2 해시로 패스워드 저장 (평문 → KC가 알아서 해시)
        session.userCredentialManager()
               .updateCredential(realm, kcUser,
                       UserCredentialModel.password(rawPassword, false));

        LOG.infof("[MIGRATION] KC 유저 생성 완료: %s (email=%s)", legacy.username(), legacy.email());
    }

    /**
     * 이관 완료 표시 — 다음 조회부터 외부 DB에서 제외됨
     */
    private void markAsMigrated(String username) {
        final String sql = "UPDATE users SET migrated_at = ? WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(2, username);
            int updated = ps.executeUpdate();
            LOG.infof("[MIGRATION] migrated_at 갱신 완료: %s (rows=%d)", username, updated);
        } catch (SQLException e) {
            LOG.errorf(e, "[MIGRATION] migrated_at 갱신 실패: %s", username);
            throw new RuntimeException("마이그레이션 상태 갱신 실패", e);
        }
    }

    private String extractFirstName(String fullName) {
        if (fullName == null || fullName.length() < 2) return "";
        return fullName.substring(1); // 홍길동 → 길동
    }

    private String extractLastName(String fullName) {
        if (fullName == null || fullName.isEmpty()) return "";
        return fullName.substring(0, 1); // 홍길동 → 홍
    }

    @Override
    public void close() {
        // DataSource는 Factory에서 관리
    }

    // ─────────────────────────────────────────────────────────
    // 내부 레코드
    // ─────────────────────────────────────────────────────────

    record LegacyUser(
            long id,
            String username,
            String email,
            String passwordHash,
            String fullName,
            String department,
            String role,
            boolean isActive) {
    }
}
