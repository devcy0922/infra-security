# Custom Keycloak SPI — 코드 리뷰 포인트

> **이 문서는 `keycloak/providers/custom-user-spi/` 코드를 처음 보는 리뷰어를 위한 설명서입니다.**  
> 코드 선택의 배경과 의도를 정리합니다.

---

## 1. 전체 클래스 구조

```
CustomUserStorageProviderFactory   ← Keycloak이 SPI 등록 시 호출 (Factory)
        │  creates
        ▼
CustomUserStorageProvider          ← 요청마다 인스턴스 생성, 인증 핵심 로직
        │  wraps
        ▼
LegacyUserAdapter                  ← 레거시 DB 레코드 → Keycloak UserModel 변환
```

---

## 2. 핵심 설계 결정 & 리뷰 포인트

### 2-1. `migrated_at IS NULL` 필터 — 이중 이관 방지

```java
// CustomUserStorageProvider.java
final String sql = """
    SELECT ... FROM users
    WHERE username = ? AND migrated_at IS NULL  ← 핵심
    """;
```

**왜 중요한가?**  
Keycloak Federation은 요청마다 `getUserByUsername()`을 호출할 수 있습니다.  
`migrated_at IS NULL` 조건이 없으면 이미 KC 내부 DB로 이관된 유저도 매번 외부 DB를 조회하게 됩니다.  
이 필터로 이관 완료된 유저는 외부 DB 쿼리 자체를 건너뜁니다.

**트레이드오프**: 외부 DB에 `migrated_at` 컬럼 추가 필요 (스키마 변경 1회).

---

### 2-2. 인증 실패와 이관 실패를 분리한 이유

```java
if (isValid) {
    try {
        migrateToKeycloak(realm, user, rawPassword, legacy);
        markAsMigrated(username);
    } catch (Exception e) {
        // 이관 실패해도 이번 인증은 성공 처리 ← 의도적
        LOG.errorf(e, "[MIGRATION] 유저 이관 실패 (인증은 허용): %s", username);
    }
}
return isValid;
```

**왜 이렇게 하는가?**  
이관(Migration)은 부수 효과(side effect)입니다.  
KC 내부 DB 장애나 네트워크 오류로 이관이 실패해도, **사용자 로그인 경험을 깨면 안 됩니다.**  
다음 로그인 시 `migrated_at IS NULL` 이므로 자동 재시도됩니다.

**리스크**: 이관 실패 로그를 반드시 모니터링해야 합니다 (`[MIGRATION] 유저 이관 실패` 로그 알림 설정 권장).

---

### 2-3. BCrypt → PBKDF2 패스워드 업그레이드

```java
// 레거시 DB: bcrypt $2a$ 해시로 검증
boolean isValid = BCrypt.checkpw(rawPassword, legacy.passwordHash());

// KC 내부 DB: KC가 PBKDF2-HMAC-SHA256으로 저장
session.userCredentialManager()
       .updateCredential(realm, kcUser,
               UserCredentialModel.password(rawPassword, false));
```

**보안 업그레이드 효과**:

| | 레거시 DB | KC 내부 DB |
|---|---|---|
| 알고리즘 | bcrypt `$2a$12$` | PBKDF2-HMAC-SHA256 |
| 반복 횟수 | 4096 (rounds=12) | 27,500 (KC 기본) |
| 솔트 | bcrypt 내장 | KC 자동 관리 |

사용자가 인지하지 못하는 사이 패스워드 해시가 더 강한 알고리즘으로 교체됩니다.

---

### 2-4. HikariCP 커넥션 풀 — 왜 Factory에서 관리하는가

```java
// CustomUserStorageProviderFactory.java
public void init(org.keycloak.Config.Scope config) {
    // 풀을 Factory 레벨(싱글톤)에서 생성
    this.dataSource = new HikariDataSource(hikariConfig);
}

public CustomUserStorageProvider create(KeycloakSession session, ...) {
    // Provider는 요청마다 생성되지만 DataSource는 공유
    return new CustomUserStorageProvider(session, model, dataSource);
}
```

**이유**: `create()`는 요청마다 호출됩니다. 여기서 커넥션 풀을 만들면 요청마다 DB 연결을 새로 맺게 됩니다.  
Factory는 Keycloak 시작 시 1회 초기화 → 풀 공유 → 연결 오버헤드 최소화.

---

### 2-5. `AbstractUserAdapterFederatedStorage` 상속 선택 이유

`LegacyUserAdapter`는 `UserAdapter`가 아닌 `AbstractUserAdapterFederatedStorage`를 상속합니다.

```java
public class LegacyUserAdapter extends AbstractUserAdapterFederatedStorage { ... }
```

**차이점**:
- `UserAdapter` → JPA 엔티티 기반, KC 내부 DB 유저 전용
- `AbstractUserAdapterFederatedStorage` → **외부 스토리지 유저**를 위한 추상 클래스. 롤, 그룹, 크레덴셜을 Federated Storage에 위임.

레거시 DB 유저는 KC 내부 DB에 실제 레코드가 없으므로 반드시 후자를 사용해야 합니다.

---

### 2-6. SSL 강제 — 레거시 DB 연결 구간

```java
// Factory.init() 에서
hikariConfig.addDataSourceProperty("sslMode", "REQUIRED");
```

SPI → 레거시 DB 구간도 암호화 강제.  
MariaDB `require_ssl = ON` 설정과 쌍을 이룹니다.

---

## 3. 테스트 커버리지 전략

| 테스트 대상 | 방법 | 핵심 검증 |
|---|---|---|
| `isValid()` — 성공 경로 | JUnit5 + Mockito | bcrypt 검증 후 `migrateToKeycloak` 호출 확인 |
| `isValid()` — 이관 실패 허용 | Mockito exception stubbing | 이관 예외 발생해도 `true` 반환 |
| `isValid()` — 미이관 유저 제외 | MockResultSet | `migrated_at IS NULL` 필터 동작 |
| `LegacyUserAdapter` 속성 매핑 | Unit | `department`, `legacy_role` 속성 반환 검증 |
| `markAsMigrated()` | Mockito verify | `UPDATE SET migrated_at` 실행 확인 |

---

## 4. 알려진 한계 및 개선 방향

| 현재 한계 | 개선 방향 |
|---|---|
| 이관 실패 시 재시도가 다음 로그인에 의존 | Dead Letter Queue 또는 배치 재시도 스케줄러 추가 |
| `migrated_at` 컬럼 추가로 레거시 DB 스키마 변경 필요 | 별도 이관 추적 테이블을 KC DB에 두는 방법 |
| BCrypt 검증이 CPU bound | 비동기 처리 검토 (Keycloak Reactive 모드) |
| 비활성 유저(`is_active=0`)는 Federation에서 `null` 반환 | Admin 이벤트 연동으로 KC에서도 비활성화 동기화 |
