-- =============================================================
-- infra-security — MariaDB 초기화 스크립트
-- 목적: 레거시 앱 DB 스키마 + 감사 계정 설정
-- =============================================================

-- ProxySQL 모니터 계정 생성
CREATE USER IF NOT EXISTS 'monitor'@'%' IDENTIFIED BY '${PROXYSQL_MONITOR_PASS}';
GRANT REPLICATION CLIENT ON *.* TO 'monitor'@'%';
FLUSH PRIVILEGES;

-- Keycloak Federation 읽기 전용 계정
CREATE USER IF NOT EXISTS 'kc_reader'@'%' IDENTIFIED BY 'kcreader_change_me';
GRANT SELECT ON appdb.users TO 'kc_reader'@'%';
FLUSH PRIVILEGES;

-- =============================================================
-- 레거시 앱 스키마 — users 테이블 (Federation 대상)
-- =============================================================
USE appdb;

CREATE TABLE IF NOT EXISTS users (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(100) NOT NULL UNIQUE,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL COMMENT '평문 → bcrypt $2a$ 형식 (레거시 시스템)',
    full_name    VARCHAR(200),
    department   VARCHAR(100),
    role         ENUM('admin', 'manager', 'staff', 'readonly') NOT NULL DEFAULT 'staff',
    is_active    TINYINT(1) NOT NULL DEFAULT 1,
    last_login   DATETIME,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    migrated_at  DATETIME NULL COMMENT 'Keycloak으로 이관된 시각 (NULL = 미이관)',
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_migrated (migrated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================
-- 주문 시스템 테이블 (CUD 감사 시연용)
-- =============================================================

CREATE TABLE IF NOT EXISTS products (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    sku         VARCHAR(50) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    category    VARCHAR(100),
    price       DECIMAL(10, 2) NOT NULL,
    stock       INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(30) NOT NULL UNIQUE,
    user_id     BIGINT UNSIGNED NOT NULL,
    status      ENUM('pending', 'processing', 'shipped', 'delivered', 'cancelled') NOT NULL DEFAULT 'pending',
    total_amount DECIMAL(12, 2) NOT NULL,
    note        TEXT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_items (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT UNSIGNED NOT NULL,
    product_id  INT UNSIGNED NOT NULL,
    quantity    INT NOT NULL,
    unit_price  DECIMAL(10, 2) NOT NULL,
    INDEX idx_order (order_id),
    INDEX idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================
-- 감사 로그 테이블 (DB 레벨 보완 — ProxySQL 로그와 이중화)
-- =============================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    event_time   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    user         VARCHAR(100),
    client_host  VARCHAR(100),
    db_name      VARCHAR(100),
    query_type   ENUM('INSERT', 'UPDATE', 'DELETE', 'DDL', 'DCL') NOT NULL,
    query_digest VARCHAR(500),
    rows_affected INT,
    INDEX idx_event_time (event_time),
    INDEX idx_query_type (query_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
