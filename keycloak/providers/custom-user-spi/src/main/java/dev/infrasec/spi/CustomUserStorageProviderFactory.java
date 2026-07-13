package dev.infrasec.spi;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * CustomUserStorageProviderFactory — SPI 팩토리
 *
 * <p>Keycloak Admin에서 User Federation 추가 시 이 팩토리가 사용됨.
 * 컴포넌트 설정으로 외부 DB 접속 정보를 받아 HikariCP 커넥션 풀을 생성한다.
 */
public class CustomUserStorageProviderFactory
        implements UserStorageProviderFactory<CustomUserStorageProvider> {

    private static final Logger LOG = Logger.getLogger(CustomUserStorageProviderFactory.class);

    public static final String PROVIDER_ID = "custom-user-storage";

    private HikariDataSource dataSource;

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void init(org.keycloak.Config.Scope config) {
        // Keycloak 컴포넌트 설정에서 DB 접속 정보를 읽어 HikariCP 초기화
        String jdbcUrl  = config.get("jdbcUrl",  System.getenv("LEGACY_DB_URL"));
        String dbUser   = config.get("dbUser",   System.getenv("LEGACY_DB_USER"));
        String dbPass   = config.get("dbPass",   System.getenv("LEGACY_DB_PASS"));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(dbUser);
        hikariConfig.setPassword(dbPass);
        hikariConfig.setDriverClassName("org.mariadb.jdbc.Driver");

        // 커넥션 풀 설정
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(3000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("infrasec-legacy-pool");

        // SSL 강제 (레거시 DB ↔ SPI 구간 암호화)
        hikariConfig.addDataSourceProperty("sslMode", "REQUIRED");

        this.dataSource = new HikariDataSource(hikariConfig);
        LOG.infof("[SPI] 레거시 DB 커넥션 풀 초기화 완료: %s", jdbcUrl);
    }

    @Override
    public CustomUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new CustomUserStorageProvider(session, model, dataSource);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOG.info("[SPI] 레거시 DB 커넥션 풀 종료");
        }
    }
}
