-- POK-391: 수동 카드(OBS 핫키, 계약4 4B)를 받는다. 유일 제약을 출처별로 가른다.
--
-- 옛 제약 (stream_id, source, window_start_ms)는 수동 카드에 맞지 않는다: 구간이 [T-40초, T+20초]이고
-- 0 아래는 0으로 잘라, 방송 시각 40초 안의 수동 카드는 20초 넘게 떨어져도 구간 시작이 둘 다 0이 된다.
-- 수동 카드의 중복은 eventId(플러그인이 누를 때마다 새로 만드는 UUID)가 정한다.
--
-- 자동 카드(2A)는 지금과 같은 뜻이다: 같은 방송·같은 창 시작이면 한 장. 2A 저장의 ON CONFLICT도
-- 이 색인을 고르도록 WHERE source = 'auto'를 단다(부분 색인은 같은 술어를 적어야 ON CONFLICT가 고른다).
--
-- 🔴 배포 순서: 이 마이그레이션 뒤에 옛 clip이 돌면 옛 2A 저장(ON CONFLICT (stream_id, source, window_start_ms))이
-- 고를 제약이 없어 42P10으로 실패하고 판별기가 재시도 뒤 포기한다. 되돌릴 때·겹쳐 도는 구간이 위험하다.
ALTER TABLE jump_cards DROP CONSTRAINT uq_jump_cards_window;

CREATE UNIQUE INDEX uq_jump_cards_auto_window ON jump_cards (stream_id, window_start_ms) WHERE source = 'auto';

-- 이 마이그레이션 전에는 2A가 hotkey도 받았고 event_id는 추적용이라 유일하지 않았다. 같은 event_id의 hotkey 줄이
-- 둘 이상이면 아래 색인 생성이 실패해 부팅이 막힌다(PR #338 codex P1). 그래서 색인 앞에서 정리한다: id가 가장 작은 줄만
-- 값을 지키고 나머지는 event_id를 비운다. 줄은 안 지운다(편집본·행동 기록이 그 카드를 가리킬 수 있다).
-- 비운 줄은 트리거가 event_seq·updated_at을 새로 매긴다(UPDATE라서). 화면 목록 정렬은 stream_timestamp_ms라 영향이 없다.
UPDATE jump_cards c
   SET event_id = NULL
 WHERE c.source = 'hotkey'
   AND c.event_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM jump_cards d
                WHERE d.source = 'hotkey' AND d.event_id = c.event_id AND d.id < c.id);

-- NULL끼리는 안 부딪힌다. 옛 행·2A 자동 카드의 event_id는 추적용이라 이 색인 밖이다(source 조건).
CREATE UNIQUE INDEX uq_jump_cards_hotkey_event ON jump_cards (event_id) WHERE source = 'hotkey';
