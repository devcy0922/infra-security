-- =============================================================
-- infra-security — 시드 데이터
-- 목적: 데모용 레거시 유저 + 상품/주문 데이터 생성
--
-- 유저 패스워드 해시는 실행 시 DEMO_PASSWORD_HASH로 주입
-- Lazy Migration 데모: 이 유저들은 KC DB에 없고 외부 DB에만 존재
-- =============================================================

USE appdb;

-- ------------------------------------------------------------------
-- 1. 레거시 유저 (50명) — Keycloak Lazy Migration 데모 대상
--    migrated_at = NULL → 아직 KC로 이관되지 않은 상태
-- ------------------------------------------------------------------
INSERT INTO users (username, email, password_hash, full_name, department, role, is_active) VALUES

-- 관리자
('admin.hong',    'admin.hong@corp-demo.local',    '__DEMO_PASSWORD_HASH__', '홍길동',   '정보보안팀', 'admin',    1),
('admin.kim',     'admin.kim@corp-demo.local',     '__DEMO_PASSWORD_HASH__', '김관리',   '시스템팀',  'admin',    1),

-- 매니저
('mgr.lee',       'mgr.lee@corp-demo.local',       '__DEMO_PASSWORD_HASH__', '이팀장',   '영업팀',    'manager',  1),
('mgr.park',      'mgr.park@corp-demo.local',      '__DEMO_PASSWORD_HASH__', '박부장',   '마케팅팀',  'manager',  1),
('mgr.choi',      'mgr.choi@corp-demo.local',      '__DEMO_PASSWORD_HASH__', '최팀장',   '개발팀',    'manager',  1),

-- 일반 직원
('staff.user01',  'user01@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '이민준',   '영업팀',    'staff',    1),
('staff.user02',  'user02@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '김서연',   '마케팅팀',  'staff',    1),
('staff.user03',  'user03@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '박준혁',   '개발팀',    'staff',    1),
('staff.user04',  'user04@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '최지은',   '인사팀',    'staff',    1),
('staff.user05',  'user05@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '정승현',   '재무팀',    'staff',    1),
('staff.user06',  'user06@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '한지훈',   '영업팀',    'staff',    1),
('staff.user07',  'user07@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '오예린',   '개발팀',    'staff',    1),
('staff.user08',  'user08@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '임도현',   '마케팅팀',  'staff',    1),
('staff.user09',  'user09@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '신혜원',   '재무팀',    'staff',    1),
('staff.user10',  'user10@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '강민석',   '인사팀',    'staff',    1),
('staff.user11',  'user11@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '윤지수',   '영업팀',    'staff',    1),
('staff.user12',  'user12@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '배현우',   '개발팀',    'staff',    1),
('staff.user13',  'user13@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '조은비',   '마케팅팀',  'staff',    1),
('staff.user14',  'user14@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '권태양',   '재무팀',    'staff',    1),
('staff.user15',  'user15@corp-demo.local',        '__DEMO_PASSWORD_HASH__', '류소희',   '인사팀',    'staff',    1),

-- 읽기 전용
('readonly.ext1', 'ext1@external-demo.local',      '__DEMO_PASSWORD_HASH__', '외부감사1', '외부',     'readonly', 1),
('readonly.ext2', 'ext2@external-demo.local',      '__DEMO_PASSWORD_HASH__', '외부감사2', '외부',     'readonly', 1),

-- 비활성 계정 (데모: 비활성 유저 Federation 제외 처리)
('inactive.user1','inactive1@corp-demo.local',     '__DEMO_PASSWORD_HASH__', '퇴사자1',  '구영업팀',  'staff',    0),
('inactive.user2','inactive2@corp-demo.local',     '__DEMO_PASSWORD_HASH__', '퇴사자2',  '구개발팀',  'staff',    0);

-- ------------------------------------------------------------------
-- 2. 상품 데이터
-- ------------------------------------------------------------------
INSERT INTO products (sku, name, category, price, stock) VALUES
('PRD-001', '노트북 프로 15인치',      '전자기기',   1890000.00, 47),
('PRD-002', '무선 마우스',             '주변기기',     45000.00, 312),
('PRD-003', '기계식 키보드 TKL',       '주변기기',    139000.00, 88),
('PRD-004', '27인치 QHD 모니터',      '전자기기',    459000.00, 23),
('PRD-005', 'USB-C 허브 7포트',       '주변기기',     79000.00, 156),
('PRD-006', '웹캠 1080p',             '주변기기',     89000.00, 72),
('PRD-007', '노이즈캔슬링 헤드셋',    '오디오',      259000.00, 34),
('PRD-008', '외장 SSD 1TB',           '저장장치',    129000.00, 98),
('PRD-009', '스탠딩 데스크 어댑터',   '가구/인테리어', 349000.00, 15),
('PRD-010', '케이블 정리 키트',       '액세서리',     19000.00, 430),
('PRD-011', '태블릿 거치대',          '액세서리',     39000.00, 211),
('PRD-012', '블루투스 스피커',        '오디오',       89000.00, 67);

-- ------------------------------------------------------------------
-- 3. 주문 데이터 (CUD 감사 시연용)
-- ------------------------------------------------------------------
INSERT INTO orders (order_no, user_id, status, total_amount) VALUES
('ORD-2024-000001',  6, 'delivered',  1935000.00),
('ORD-2024-000002',  7, 'delivered',   184000.00),
('ORD-2024-000003',  8, 'shipped',     459000.00),
('ORD-2024-000004',  9, 'processing',  218000.00),
('ORD-2024-000005', 10, 'pending',     349000.00),
('ORD-2024-000006', 11, 'delivered',    89000.00),
('ORD-2024-000007', 12, 'cancelled',   259000.00),
('ORD-2024-000008', 13, 'delivered',   168000.00),
('ORD-2024-000009', 14, 'shipped',     208000.00),
('ORD-2024-000010', 15, 'pending',      79000.00);

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1,  1, 1, 1890000.00),
(1,  2, 1,   45000.00),
(2,  3, 1,  139000.00),
(2, 10, 1,   19000.00),
(2, 11, 1,   39000.00),
(3,  4, 1,  459000.00),
(4,  6, 1,   89000.00),
(4, 10, 3,   19000.00),
(4,  2, 1,   45000.00),
(5,  9, 1,  349000.00),
(6,  7, 1,  259000.00),  -- 취소된 주문
(7, 12, 1,   89000.00),
(8,  5, 1,   79000.00),
(8,  8, 1,  129000.00),
(9,  3, 1,  139000.00),
(9,  2, 1,   45000.00),
(9,  6, 1,   89000.00),
(10, 5, 1,   79000.00);
