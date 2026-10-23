-- 탈퇴 장부의 수집기 줄에 플랫폼을 적는다(POK-397). 수집기 정리가 (플랫폼, 채널)로 지우므로 SOOP 연동의 아이디도
-- 같은 장부로 넘긴다. 채널 아이디는 플랫폼마다 따로 매겨서 치지직과 SOOP에 같은 아이디가 있을 수 있다.
-- 표가 작아(탈퇴 수만큼) 한 트랜잭션으로 바꾼다.

-- NULL 허용인 이유: 배포가 겹치는 동안 옛 auth는 플랫폼 없이 수집기 줄을 적는다. NOT NULL이거나 「수집기 줄이면 플랫폼
-- 필수」 CHECK면 그 INSERT가 탈퇴 트랜잭션째 되감겨 치지직 연동 회원의 탈퇴가 500이 된다(계획 검증 F1 실측).
-- 수집기 줄의 NULL은 옛 auth가 적은 치지직 줄이라 읽을 때 CHZZK로 본다(PurgeJobRepository.claim).
-- 엄격한 CHECK는 auth 배포가 끝난 뒤 다음 PR에서 채우기 + VALIDATE로 올린다.
ALTER TABLE withdrawal_purge_jobs ADD COLUMN platform VARCHAR(16);

-- 이 칸 전의 수집기 줄은 전부 치지직 채널이다.
UPDATE withdrawal_purge_jobs SET platform = 'CHZZK' WHERE target = 'COLLECTOR' AND platform IS NULL;

-- clip 줄에는 플랫폼이 없다(회원 하나의 기록 전부라 플랫폼으로 안 갈린다).
ALTER TABLE withdrawal_purge_jobs
    ADD CONSTRAINT ck_withdrawal_purge_platform CHECK (target = 'COLLECTOR' OR platform IS NULL);

-- 같은 탈퇴를 두 번 적지 않는 열쇠에 플랫폼을 넣는다. 안 넣으면 같은 아이디의 치지직 줄과 SOOP 줄 중 뒤엣것이
-- ON CONFLICT DO NOTHING에 조용히 버려진다.
DROP INDEX uq_withdrawal_purge_jobs;
CREATE UNIQUE INDEX uq_withdrawal_purge_jobs
    ON withdrawal_purge_jobs (user_id, target, COALESCE(platform, ''), COALESCE(channel_id, ''));
