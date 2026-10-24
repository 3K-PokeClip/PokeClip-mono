-- 설정 › 계정의 「이용 분석 참여」 토글(처리방침 7·9절). 끄면 대시보드가 분석 도구(PostHog)로 보내지 않는다.
-- 기기마다가 아니라 계정에 둔다 — 한 기기에서 끄면 다른 기기에서도 꺼져 있어야 한다.
-- auth는 이 값을 저장하고 돌려줄 뿐 분석 도구와 통신하지 않는다(서버 이벤트 없음, 2026-10-24 결정).
-- 기본은 참여(false) — 정당한 이익 + 처리방침 고지, 동의 창 없음(POK-354).
ALTER TABLE users ADD COLUMN analytics_opt_out BOOLEAN NOT NULL DEFAULT FALSE;
