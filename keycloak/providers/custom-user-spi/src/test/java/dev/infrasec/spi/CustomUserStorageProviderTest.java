package dev.infrasec.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.models.*;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * CustomUserStorageProvider 단위 테스트
 *
 * <p>테스트 전략:
 * <ul>
 *   <li>DataSource를 Mockito로 대체 → 실제 DB 연결 없이 동작 검증</li>
 *   <li>bcrypt 해시는 실제로 생성하여 BCrypt.checkpw 검증</li>
 *   <li>KC 세션/모델은 Mockito stub으로 처리</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CustomUserStorageProviderTest {

    // ── 픽스처 상수 ──────────────────────────────────────────
    private static final String USERNAME       = "staff.user01";
    private static final String EMAIL          = "user01@corp-demo.local";
    private static final String RAW_PASSWORD   = "unit-test-" + UUID.randomUUID();
    // bcrypt $2a$12$ 해시 (RAW_PASSWORD 기준)
    private static final String BCRYPT_HASH    = BCrypt.hashpw(RAW_PASSWORD, BCrypt.gensalt(12));
    private static final String WRONG_PASSWORD = "wrong-unit-test-" + UUID.randomUUID();

    // ── Mocks ────────────────────────────────────────────────
    @Mock KeycloakSession     session;
    @Mock RealmModel          realm;
    @Mock ComponentModel      model;
    @Mock DataSource          dataSource;
    @Mock Connection          connection;
    @Mock PreparedStatement   preparedStatement;
    @Mock ResultSet           resultSet;
    @Mock UserProvider        userProvider;
    @Mock UserCredentialManager credentialManager;
    @Mock UserModel           kcUser;
    @Mock CredentialInput     credentialInput;

    private CustomUserStorageProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        provider = new CustomUserStorageProvider(session, model, dataSource);

        // DataSource → Connection → PreparedStatement → ResultSet 체인 기본 설정
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.prepareStatement(anyString())).willReturn(preparedStatement);
        given(preparedStatement.executeQuery()).willReturn(resultSet);

        // KC 세션 기본 설정
        given(session.users()).willReturn(userProvider);
        given(session.userCredentialManager()).willReturn(credentialManager);
        given(userProvider.addUser(any(), anyString())).willReturn(kcUser);

        // CredentialInput 설정
        given(credentialInput.getType()).willReturn(PasswordCredentialModel.TYPE);
        given(credentialInput.getChallengeResponse()).willReturn(RAW_PASSWORD);
    }

    // ─────────────────────────────────────────────────────────
    // isValid() 테스트
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("isValid() — Lazy Migration 핵심 로직")
    class IsValidTests {

        @Test
        @DisplayName("올바른 패스워드로 인증 성공 + 이관 로직 실행됨")
        void whenCorrectPassword_thenAuthSuccessAndMigrate() throws SQLException {
            // given: 레거시 DB에 미이관 유저 존재
            givenLegacyUserExists(USERNAME, BCRYPT_HASH);

            // UPDATE (markAsMigrated) mock
            given(preparedStatement.executeUpdate()).willReturn(1);

            // when
            boolean result = provider.isValid(realm, mockUserModel(USERNAME), credentialInput);

            // then
            assertThat(result).isTrue();

            // KC에 유저 추가되었는지 검증
            then(userProvider).should(times(1)).addUser(eq(realm), eq(USERNAME));

            // KC 크레덴셜 업데이트되었는지 검증
            then(credentialManager).should(times(1))
                    .updateCredential(eq(realm), eq(kcUser), any(UserCredentialModel.class));

            // migrated_at 갱신 UPDATE 실행되었는지 검증 (executeUpdate 호출 확인)
            then(preparedStatement).should(atLeastOnce()).executeUpdate();
        }

        @Test
        @DisplayName("잘못된 패스워드 — 인증 실패, 이관 실행 안 됨")
        void whenWrongPassword_thenAuthFails() throws SQLException {
            // given
            givenLegacyUserExists(USERNAME, BCRYPT_HASH);
            given(credentialInput.getChallengeResponse()).willReturn(WRONG_PASSWORD);

            // when
            boolean result = provider.isValid(realm, mockUserModel(USERNAME), credentialInput);

            // then
            assertThat(result).isFalse();
            then(userProvider).should(never()).addUser(any(), any());
            then(credentialManager).should(never()).updateCredential(any(), any(), any());
        }

        @Test
        @DisplayName("레거시 DB에 유저 없음 — false 반환")
        void whenUserNotInLegacyDb_thenReturnFalse() throws SQLException {
            // given: ResultSet.next() = false (유저 없음)
            given(resultSet.next()).willReturn(false);

            // when
            boolean result = provider.isValid(realm, mockUserModel(USERNAME), credentialInput);

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("이관 실패해도 인증은 성공 처리 — 서비스 연속성 보장")
        void whenMigrationFails_thenAuthStillSucceeds() throws SQLException {
            // given: 인증은 성공하지만 KC addUser 에서 예외 발생
            givenLegacyUserExists(USERNAME, BCRYPT_HASH);
            given(userProvider.addUser(any(), anyString()))
                    .willThrow(new RuntimeException("KC DB 장애 시뮬레이션"));

            // when: 예외 전파 없이 처리
            boolean result = assertThatCode(
                    () -> provider.isValid(realm, mockUserModel(USERNAME), credentialInput)
            ).doesNotThrowAnyException()
             .extracting(__ -> provider.isValid(realm, mockUserModel(USERNAME), credentialInput))
             .asInstanceOf(BOOLEAN).isTrue();

            // KC 이관 실패해도 true 반환 확인
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("credentialType이 PASSWORD 아닌 경우 — false 반환")
        void whenUnsupportedCredentialType_thenReturnFalse() {
            // given
            given(credentialInput.getType()).willReturn("otp");

            // when
            boolean result = provider.isValid(realm, mockUserModel(USERNAME), credentialInput);

            // then
            assertThat(result).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────
    // getUserByUsername() 테스트
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getUserByUsername() — Federation 조회")
    class GetUserByUsernameTests {

        @Test
        @DisplayName("미이관 활성 유저 → LegacyUserAdapter 반환")
        void whenUnmigratedActiveUser_thenReturnAdapter() throws SQLException {
            // given
            givenLegacyUserExists(USERNAME, BCRYPT_HASH);

            // when
            UserModel userModel = provider.getUserByUsername(realm, USERNAME);

            // then
            assertThat(userModel).isNotNull();
            assertThat(userModel.getUsername()).isEqualTo(USERNAME);
        }

        @Test
        @DisplayName("유저 없음 → null 반환 (Keycloak 규약)")
        void whenUserNotFound_thenReturnNull() throws SQLException {
            // given
            given(resultSet.next()).willReturn(false);

            // when
            UserModel userModel = provider.getUserByUsername(realm, "nonexistent");

            // then
            assertThat(userModel).isNull();
        }

        @Test
        @DisplayName("비활성 유저(is_active=0) → null 반환")
        void whenInactiveUser_thenReturnNull() throws SQLException {
            // given: is_active = false
            givenLegacyUserExists(USERNAME, BCRYPT_HASH, false);

            // when
            UserModel userModel = provider.getUserByUsername(realm, USERNAME);

            // then: 비활성 유저는 Federation에서 제외
            assertThat(userModel).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────
    // supportsCredentialType() 테스트
    // ─────────────────────────────────────────────────────────
    @Test
    @DisplayName("PASSWORD 타입 지원 — true")
    void supportsPasswordCredentialType() {
        assertThat(provider.supportsCredentialType(PasswordCredentialModel.TYPE)).isTrue();
    }

    @Test
    @DisplayName("OTP 타입 미지원 — false")
    void doesNotSupportOtpCredentialType() {
        assertThat(provider.supportsCredentialType("otp")).isFalse();
    }

    // ─────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────────────────

    private void givenLegacyUserExists(String username, String hash) throws SQLException {
        givenLegacyUserExists(username, hash, true);
    }

    private void givenLegacyUserExists(String username, String hash, boolean isActive) throws SQLException {
        given(resultSet.next()).willReturn(true);
        given(resultSet.getLong("id")).willReturn(6L);
        given(resultSet.getString("username")).willReturn(username);
        given(resultSet.getString("email")).willReturn(EMAIL);
        given(resultSet.getString("password_hash")).willReturn(hash);
        given(resultSet.getString("full_name")).willReturn("이민준");
        given(resultSet.getString("department")).willReturn("영업팀");
        given(resultSet.getString("role")).willReturn("staff");
        given(resultSet.getBoolean("is_active")).willReturn(isActive);
    }

    private UserModel mockUserModel(String username) {
        UserModel user = mock(UserModel.class);
        given(user.getUsername()).willReturn(username);
        return user;
    }
}
