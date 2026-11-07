package index

// VOD 문서 구멍 진단(POK-549 C6 · 설계 2.5 ②). 방송 가운데만 만료되면(또는 다른 경로로 조각 행이 지워지면) 확정된
// VOD 문서가 지운 조각을 가리킬 수 있다. 고침은 POK-551 이고, 이 PR 은 그런 문서를 찾는 질의 원본 하나만 둔다 —
// README 는 이 이름을 가리키고 PG 시험이 이 원본을 그대로 돌린다(사본 없음).

// VODDocumentHolesSQL 은 지운 조각을 가리키는 ended VOD 문서를 찾는 읽기 전용 진단 질의다. 제품 코드는 부르지 않는다 —
// 사람이 읽기 전용 트랜잭션에서 돌린다(인자 없음). 열은 stream_id · session_id · vod_s3_key · vod_first_seq ·
// vod_last_seq · missing(문서 범위 안에서 빠진 행 수)이다.
//
// 확정 순간 문서 범위 [vod_first_seq, vod_last_seq] 의 seq 는 모두 장부 행이었다(계획 행 = 장부 행 · 렌더의 seq 연속
// 강제 · seq 재사용 없음 — 설계 5절 P12). 그래서 범위 안 행 수가 범위 길이보다 작으면 그 뒤에 지워진 것이다. 문서는 사슬
// 회원 전원이 같은 키 · 범위를 가지므로 구멍 하나에 회원 수만큼 행이 나온다 — vod_s3_key 로 묶어 읽는다. state =
// 'ended' 만 본다 — 문서를 확정했지만 phase 4 전(ending)인 창은 못 본다. 빈 동결(키 없음)과 조회 제외 표시(expired_at)가
// 있는 문서는 빠진다. 키는 있는데 범위(vod_first_seq · vod_last_seq)가 NULL 인 이상 행도 비교가 NULL 이라 조용히 빠진다 —
// 확정 문장은 키와 범위를 함께 쓰므로 정상 장부에는 없다. 내장 함수는 pg_catalog 로 한정한다.
const VODDocumentHolesSQL = `
-- 읽기 전용. 조회 제외 표시가 없는 ended 문서 가운데, 확정 범위 안 seq 가 장부에서 빠진 것(문서 구멍)을 찾는다.
SELECT s.stream_id, s.session_id, s.vod_s3_key, s.vod_first_seq, s.vod_last_seq,
       (s.vod_last_seq - s.vod_first_seq + 1) - c.n AS missing
  FROM stream_sessions s
 CROSS JOIN LATERAL (SELECT pg_catalog.count(*) AS n FROM stream_segments t
                      WHERE t.stream_id = s.stream_id
                        AND t.seq BETWEEN s.vod_first_seq AND s.vod_last_seq) c
 WHERE s.state = 'ended' AND s.vod_s3_key IS NOT NULL AND s.expired_at IS NULL
   AND c.n < s.vod_last_seq - s.vod_first_seq + 1
 ORDER BY s.stream_id, s.session_id`
