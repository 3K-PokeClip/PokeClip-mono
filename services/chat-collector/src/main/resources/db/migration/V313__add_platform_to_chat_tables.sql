-- 🔴 네 표를 차례로 강한 잠금(AccessExclusive)으로 잡는다. 앞에 오래 도는 읽기가 있으면 ALTER가 그것을 기다리는 동안 뒤의
-- 채팅 INSERT가 줄을 서 적재가 멈춘다(PostgreSQL 잠금 대기열). 5초를 넘기면 마이그레이션을 실패시켜 부팅을 다시 시도하게 한다.
-- 쓰기를 오래 막는 것보다 낫다(POK-397 계획 검증 F8). SET LOCAL이라 이 트랜잭션에서만 산다.
SET LOCAL lock_timeout = '5s';

-- 채팅이 어느 방송 플랫폼에서 왔나(POK-397). 채널 아이디는 플랫폼마다 따로 매겨서 치지직과 SOOP에 같은 아이디가 있을 수 있다.
-- 값은 com.pokeclip.chat.collector.Platform 이름 그대로다.
--
-- 기본값 CHZZK인 이유: 이 칸 전의 줄은 전부 치지직이고, 배포 중 옛 인스턴스는 칸을 모른 채 넣는다. 상수 기본값이라 표를
-- 다시 쓰지 않는다(100만 줄 1.2ms, 계획 검증 실측).
-- 🔴 대가: 새 코드가 플랫폼을 빠뜨려도 오류 없이 CHZZK가 된다. 시험은 SOOP 값으로만 잰다. 옛 인스턴스가 다 내려간 뒤
-- 기본값을 지우는 것은 다음 PR 후보다.
--
-- CHECK를 NOT VALID로 거는 이유: 여기서 검증하면 강한 잠금을 쥔 채 표 전체를 읽는다. 검증은 쓰기를 안 막는 V315에서 한다.
-- 새로 들어오는 줄은 NOT VALID여도 지금부터 검사한다. 폭을 enum 이름이 아니라 모양(대문자·숫자·밑줄)으로 둔 것은 플랫폼이
-- 늘 때 마이그레이션 없이 받으려는 것이고, 소문자를 막는 것은 「soop」과 「SOOP」이 다른 줄로 갈리지 않게 하려는 것이다.
-- 🔴 순서: 강한 잠금은 트랜잭션 끝까지 쥔다. 뜨거운 표(채팅)를 먼저 잡으면 뒤 표의 잠금 대기(각 최대 5초) 동안 채팅 적재가
-- 함께 멈춘다. 그래서 덜 쓰이는 표부터 잡고 채팅 표를 마지막에 둔다(로컬 리뷰 1라운드).
ALTER TABLE purged_channels ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'CHZZK';
ALTER TABLE purged_channels ADD CONSTRAINT ck_purged_channels_platform CHECK (platform ~ '^[A-Z0-9_]{1,16}$') NOT VALID;

-- 탈퇴 명부는 (플랫폼, 채널)마다 한 줄이다. 같은 아이디의 치지직 탈퇴와 SOOP 탈퇴가 서로의 줄을 덮지 않게 한다.
-- 표가 작아(탈퇴한 채널 수) 여기서 바로 바꾼다.
-- 🔴 배포 중 옛 인스턴스의 ON CONFLICT (channel_id)는 이 PK와 맞지 않아 탈퇴 알림에 500을 낸다. auth 발송기가 다시 보내
-- 새 인스턴스에 닿으므로 유실은 없다(계획 검증 F4).
ALTER TABLE purged_channels DROP CONSTRAINT purged_channels_pkey, ADD PRIMARY KEY (platform, channel_id);

ALTER TABLE broadcast_info ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'CHZZK';
ALTER TABLE broadcast_info ADD CONSTRAINT ck_broadcast_info_platform CHECK (platform ~ '^[A-Z0-9_]{1,16}$') NOT VALID;
ALTER TABLE chat_donations ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'CHZZK';
ALTER TABLE chat_donations ADD CONSTRAINT ck_chat_donations_platform CHECK (platform ~ '^[A-Z0-9_]{1,16}$') NOT VALID;
ALTER TABLE chat_messages  ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'CHZZK';
ALTER TABLE chat_messages  ADD CONSTRAINT ck_chat_messages_platform  CHECK (platform ~ '^[A-Z0-9_]{1,16}$') NOT VALID;
