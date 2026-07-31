# stream_segments — 세그먼트 인덱스 (DDL 초안)

**스키마 소유 = 3번 · 쓰기 = 1번 · 읽기 = 1번(매니페스트)·3번(클립 구간)**

계약 본문은 `PokeClip-LLM-WIKI`의 [`contracts/계약-세그먼트인덱스.md`](https://github.com/3K-PokeClip/PokeClip-LLM-WIKI/blob/main/contracts/%EA%B3%84%EC%95%BD-%EC%84%B8%EA%B7%B8%EB%A8%BC%ED%8A%B8%EC%9D%B8%EB%8D%B1%EC%8A%A4.md)에 있다.
이 문서는 **거기 §5로 남겨 둔 3번 리뷰 항목에 대한 답과, 실제 DDL 초안**이다.

---

## DDL

```sql
CREATE TABLE stream_segments (
    stream_id         text        NOT NULL,
    seq               bigint      NOT NULL,

    start_pts_ms      bigint      NOT NULL,
    start_wall_utc    timestamptz NOT NULL,
    duration_ms       integer     NOT NULL,

    s3_key            text        NOT NULL,
    local_path        text,

    upload_state      text        NOT NULL DEFAULT 'pending',
    upload_attempts   smallint    NOT NULL DEFAULT 0,
    uploaded_at       timestamptz,

    bytes             integer,
    is_discontinuity  boolean     NOT NULL DEFAULT false,

    created_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_stream_segments PRIMARY KEY (stream_id, seq),
    CONSTRAINT ck_stream_segments_upload_state
        CHECK (upload_state IN ('pending', 'uploaded', 'failed')),
    CONSTRAINT ck_stream_segments_duration
        CHECK (duration_ms > 0),
    CONSTRAINT ck_stream_segments_uploaded_at
        CHECK ((upload_state = 'uploaded') = (uploaded_at IS NOT NULL))
);

-- 클립 구간 조회: WHERE stream_id = ? AND start_pts_ms BETWEEN ? AND ?
CREATE INDEX ix_stream_segments_pts
    ON stream_segments (stream_id, start_pts_ms);

-- 업로드 적체 감시. 정상 상태에서 거의 비어 있으므로 부분 인덱스로 둔다
CREATE INDEX ix_stream_segments_pending
    ON stream_segments (stream_id, seq)
    WHERE upload_state <> 'uploaded';

COMMENT ON TABLE  stream_segments                  IS '방송 영상 조각 목록. 매니페스트 생성의 단일 진실원이며 S3 LIST를 대체한다';
COMMENT ON COLUMN stream_segments.seq              IS '스트림 안에서 단조 증가. 재사용·재정렬 금지';
COMMENT ON COLUMN stream_segments.start_pts_ms     IS '방송 시작을 0으로 하는 시작 위치(ms). 클립 구간 지정이 이 자를 쓴다';
COMMENT ON COLUMN stream_segments.start_wall_utc   IS '실제 시작 시각. 반드시 UTC';
COMMENT ON COLUMN stream_segments.duration_ms      IS '실측 길이. 매니페스트 #EXTINF의 출처. 목표 4000이나 키프레임 위치상 다를 수 있다';
COMMENT ON COLUMN stream_segments.upload_state     IS 'pending·uploaded·failed. uploaded인 것만 cold URL로 광고할 수 있다';
COMMENT ON COLUMN stream_segments.upload_attempts  IS '업로드 시도 횟수. failed와 함께 알람 판정에 쓴다';
COMMENT ON COLUMN stream_segments.local_path       IS '핫 티어 로컬 경로. 그레이스 경과 후 NULL로 만든다';
COMMENT ON COLUMN stream_segments.is_discontinuity IS '앞 조각과 불연속. 매니페스트에 EXT-X-DISCONTINUITY를 넣는다';
```

> `stream_id`의 FK는 `broadcasts` 테이블이 생긴 뒤에 붙인다.
> 방송 시작 이벤트보다 첫 세그먼트가 먼저 도착할 수 있어, 부팅 순서를 확인하고 결정한다.

---

## 인덱스를 이렇게 잡은 이유

| 조회 | 어떻게 처리되나 |
|---|---|
| `ORDER BY seq DESC LIMIT 900` (라이브 매니페스트) | **PK로 커버.** `(stream_id, seq)` 역방향 스캔 |
| `ORDER BY seq DESC LIMIT 1` (헬스 신선도) | **PK로 커버.** 별도 인덱스 불필요 |
| `start_pts_ms BETWEEN ?` (클립 구간) | `ix_stream_segments_pts` |
| `upload_state = 'pending'` (적체 감시) | `ix_stream_segments_pending` |

**적체 감시를 부분 인덱스로 만든 이유:** 정상 운영에서 `uploaded`가 99% 이상이다.
전체 인덱스를 만들면 43M행을 담으면서 실제로는 수십 행을 찾는 데 쓰인다.
부분 인덱스는 `pending`·`failed`만 담아 거의 항상 비어 있다.

---

## §5 리뷰 항목 — 3번 답

### ① 파티셔닝 — **MVP에선 하지 않는다**

| | 계산 |
|---|---|
| 세그먼트 4초 | 시간당 **900행/스트림** |
| 8시간 방송 | 7,200행/스트림 |
| 동시 100명이 매일 | **72만 행/일** |
| 60일 보관 | **약 4,300만 행** (상한 가정) |

행 하나가 대략 150바이트라 **6~7GB + 인덱스** 수준이다. PostgreSQL이 파티션 없이 감당하는 범위다.

**하지 않는 진짜 이유는 크기가 아니라 PK다.** PostgreSQL 선언적 파티셔닝은 파티션 키가
PK에 포함돼야 한다. 시각으로 파티션을 나누려면 PK가 `(stream_id, seq, start_wall_utc)`가 되는데,
그러면 **"스트림 안에서 seq는 유일하다"는 불변식 4번을 DB가 더 이상 보장하지 못한다.**
멱등성의 근거가 사라진다.

**재검토 조건**: 행 1억 개 초과, 또는 만료 삭제가 방송 시간대를 침범할 때.

### ② 보관 정책 — **`broadcasts` 만료 시 함께 지운다. 단 CASCADE는 쓰지 않는다**

`vod_expires_at`(60일, ADR-004)이 지난 방송의 세그먼트 행을 배치로 지운다.

```sql
DELETE FROM stream_segments
 WHERE stream_id = ?    -- 만료된 방송 하나씩
 LIMIT 10000;           -- 나눠서 반복
```

**FK `ON DELETE CASCADE`를 쓰지 않는 이유:** 방송 하나를 지우는 순간 7,200행이 한 트랜잭션에서
지워진다. 방송 여러 개가 같은 날 만료되면 잠금이 길어져 **라이브 매니페스트 조회가 밀린다.**
삭제는 방송 시간대를 피해 배치로 돈다.

S3 객체 정리는 라이프사이클 정책이 따로 맡는다 — 이 테이블은 행만 지운다.

### ③ enum vs boolean — **`text + CHECK` 유지, `upload_attempts` 추가**

| 안 | 판단 |
|---|---|
| boolean `uploaded` | ✗ `pending`과 `failed`를 구분 못 한다. **적체 알람과 실패 알람이 달라야 한다** |
| PostgreSQL native `ENUM` | ✗ 값을 추가하려면 `ALTER TYPE`이고, Flyway에서 다루기 번거롭다 |
| **`text` + `CHECK`** | ✓ 제약만 갈아 끼우면 값이 늘어난다. 마이그레이션이 단순하다 |

실패 카운트는 **별도 컬럼(`upload_attempts`)** 으로 둔다. 상태에 섞으면
`failed_1`·`failed_2` 같은 값이 생겨 상태 기계가 망가진다.

### ④ Redis 캐시 — **필요 없다. 1번의 부분 답에 동의한다**

라이브 매니페스트가 메모리 캐시로 평시 DB 조회 ~0이면, 남는 DB 접근은
재시작 복구·장애 인계·VOD 확정뿐이다. **빈도가 낮고 PK로 커버되는 조회라 캐시를 얹을 이유가 없다.**

클립 구간 조회도 편집자가 승인을 누를 때만 일어난다. 초당 수천 건이 아니다.

**Redis는 세션과 SSE 팬아웃에만 쓴다.**

---

## 1번 확인 부탁

| # | |
|---|---|
| 1 | `upload_attempts` 컬럼을 늘리는 주체가 1번이 맞나 (업로더가 재시도할 때마다 +1) |
| 2 | `s3_key`를 INSERT 시점에 예약해서 넣나, 업로드 성공 후 넣나. 위 CHECK 제약은 **INSERT 시점에 이미 있다**고 가정했다 |
| 3 | 첫 세그먼트가 방송 시작 이벤트보다 먼저 도착할 수 있나. FK를 붙일지 여기서 갈린다 |
