package index

// 되감기 캐시 적재의 읽기(POK-195 M4 PR ⓑ 커밋 ④ · PR ⓒ 커밋 5 — 설계 4.1 「부팅 재구성」 · 계획 2.3 ⑸ⓕ ·
// 4.5 A3 결정 3 · 4).
//
// 적재는 네 문장(컷오프 · 되짚기 하한 · 행 · 회차)을 읽기 전용 · REPEATABLE READ 한 트랜잭션에서 돈다. 행은 적재
// 하한부터(하한 = max(컷오프, min(되짚기 하한, 힌트))) 최신 쪽으로 행 수 상한까지이고, 회차는 적재한 행이 참조하는
// 모든 회차의 여덟 열과 장부의 회차 최소 seq 다. 여기서는 SQL 이 **어느 행을 어떤 값으로** 가져오는지를 장부 실물로
// 잰다 — settled 판정은 SQL 이 아니라 boundary.Settled 하나라서 여기 없다(착수 점검 숨은 가정 6). PG_DSN
// 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// rewindT0 은 재구성 픽스처의 기준 벽시계다(마이크로초 미만이 없어 PG 왕복에 값이 보존된다).
var rewindT0 = time.Date(2026, 9, 24, 9, 0, 0, 0, time.UTC)

// ledgerBounds 는 적재의 기본 범위다 — rewind/cache 의 튜너블 기본값(되짚기 2시간 · 행 수 상한 10,000)과 같은 값을
// 적는다(이 패키지는 cache 를 임포트할 수 없다). 한 시간 남짓한 픽스처에서는 되짚기가 닿지 않아 하한이 컷오프다.
var ledgerBounds = LedgerBounds{Lookback: 2 * time.Hour, RowCap: 10000}

// rewindSessionRow 는 직접 SQL 로 심는 회차 한 행이다 — 재구성이 읽는 여덟 열을 모두 정한다.
type rewindSessionRow struct {
	RewindSession
	stream    string
	startedAt time.Time
}

func putRewindSession(t *testing.T, pool *pgxpool.Pool, s rewindSessionRow) {
	t.Helper()
	nullable := func(v string) any {
		if v == "" {
			return nil
		}
		return v
	}
	var initAt, firstPDT any
	if s.InitUploaded {
		initAt = s.startedAt.Add(time.Second)
	}
	if !s.FirstPDT.IsZero() {
		firstPDT = s.FirstPDT
	}
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_sessions
			(session_id, stream_id, started_at, state, end_reason, init_uploaded_at,
			 discontinuity_base, first_pdt, inherits_session, target_duration)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		s.SessionID, s.stream, s.startedAt, s.State, nullable(s.EndReason), initAt,
		s.DiscontinuityBase, firstPDT, nullable(s.InheritsSession), s.TargetDuration)
	if err != nil {
		t.Fatalf("회차 픽스처 삽입 실패 %s: %v", s.SessionID, err)
	}
}

// putRewindRow 는 조각 한 행을 심는다. 회차가 있으면 carrier 셋(세션·PDT = 벽시계·③ 키)을 채우고,
// 없으면 셋 다 NULL 이다(settleCarrier 의 비귀속 갈래와 같다). uploaded 면 ③ 이 올라간 행이다.
func putRewindRow(t *testing.T, pool *pgxpool.Pool, stream string, seq int64, wall time.Time, durationMS int32, sessionID string, uploaded bool) {
	t.Helper()
	r := fixtureRow{stream: stream, seq: seq, wall: wall, durationMS: durationMS, sessionID: sessionID}
	if sessionID != "" {
		r.pdt, r.key = wall, fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq)
	}
	putRow(t, pool, r)
	if !uploaded {
		return
	}
	if _, err := pool.Exec(context.Background(), `
		UPDATE stream_segments
		   SET playback_upload_state = 'uploaded', playback_uploaded_at = now(), playback_bytes = 900
		 WHERE stream_id = $1 AND seq = $2`, stream, seq); err != nil {
		t.Fatalf("③ 확정 픽스처 실패 seq=%d: %v", seq, err)
	}
}

func putPublishedGap(t *testing.T, pool *pgxpool.Pool, stream string, seq int64, sessionID string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
		INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
		VALUES ($1, $2, $3, 'upload_stall')`, stream, seq, sessionID); err != nil {
		t.Fatalf("GAP 원장 픽스처 실패 seq=%d: %v", seq, err)
	}
}

// rewindLedgerFixture 는 한 스트림의 장부 이력을 심고 그 이름을 돌려준다. 장부 쓰기 규칙과 맞춘 이력이다.
//
//	seq 10·11  회차 A — seq 10 의 7.6초 조각이 직전 회차의 TD 6 을 넘어 TD 분할로 열렸다(base 0 복사 ·
//	           계승 없음 · TD 8). 컷오프 전이다(주조가 방증 낡음으로 미뤄졌다)
//	seq 12–14  회차 B — 약 2분 순단 뒤 A 를 계승해 열렸다(base 는 A 의 0 을 그대로 옮김 · TD 6).
//	           seq 12 가 컷오프를 주조했고, 14 는 ③ 가 안 올라가 GAP 원장(upload_stall)에 있다
//	seq 15·16  회차 C — seq 15 의 7.6초 조각이 B 의 TD 6 을 넘어 TD 분할로 열렸다(base 0 복사 · 계승
//	           없음 · TD 8). B 는 그때 ending(td_exceeded)이 됐고 나중에 ended 로 정산됐다
//	seq 17     회차 없음 — C 가 오프라인으로 ending 이 된 뒤 스캔으로 들어와 현 회차가 없었다(carrier NULL)
//	seq 18     회차 D — 400초 뒤 새 방송(비계승 · base 0 · TD 6 · live · init 미확정)
//
// A·B 는 init 이 올라갔고 C·D 는 아직이다. base 는 모두 0 이다 — 컷오프 전에는 목록이 없어 A 와 그 앞
// 회차의 base 가 오른 적이 없고, B 의 목록(12..14)은 한 시간이 안 돼 끊김 표시를 내보낸 적이 없다(0 이
// 아닌 base 는 TestLoadRewindLedgerReadsRaisedDiscontinuityBase 가 잰다).
// 다른 스트림 하나(컷오프·행·회차)를 함께 심어 스트림 필터를 잰다.
func rewindLedgerFixture(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	const stream = "rwtest-ledger"
	at := func(d time.Duration) time.Time { return rewindT0.Add(d) }
	for _, s := range []rewindSessionRow{
		{RewindSession{SessionID: "rw-A", State: "ended", EndReason: "offline", InitUploaded: true,
			FirstPDT: at(0), TargetDuration: 8}, stream, at(0)},
		{RewindSession{SessionID: "rw-B", State: "ended", EndReason: "td_exceeded", InitUploaded: true,
			FirstPDT: at(128 * time.Second), InheritsSession: "rw-A", TargetDuration: 6}, stream, at(128 * time.Second)},
		{RewindSession{SessionID: "rw-C", State: "ending", EndReason: "offline",
			FirstPDT: at(140 * time.Second), TargetDuration: 8}, stream, at(140 * time.Second)},
		{RewindSession{SessionID: "rw-D", State: "live",
			FirstPDT: at(551600 * time.Millisecond), TargetDuration: 6}, stream, at(551600 * time.Millisecond)},
		{RewindSession{SessionID: "rw-other", State: "live", FirstPDT: at(0), TargetDuration: 6}, "rwtest-other", at(0)},
	} {
		putRewindSession(t, pool, s)
	}
	for _, r := range []struct {
		seq      int64
		wall     time.Duration
		dur      int32
		session  string
		uploaded bool
	}{
		{10, 0, 7600, "rw-A", true},
		{11, 7600 * time.Millisecond, 4000, "rw-A", true},
		{12, 128 * time.Second, 4000, "rw-B", true},
		{13, 132 * time.Second, 4000, "rw-B", true},
		{14, 136 * time.Second, 4000, "rw-B", false},
		{15, 140 * time.Second, 7600, "rw-C", true},
		{16, 147600 * time.Millisecond, 4000, "rw-C", true},
		{17, 151600 * time.Millisecond, 4000, "", false},
		{18, 551600 * time.Millisecond, 4000, "rw-D", false},
	} {
		putRewindRow(t, pool, stream, r.seq, at(r.wall), r.dur, r.session, r.uploaded)
	}
	putPublishedGap(t, pool, stream, 14, "rw-B")
	cutoffAt(t, pool, stream, 12)

	putRewindRow(t, pool, "rwtest-other", 12, at(0), 4000, "rw-other", true)
	putRewindRow(t, pool, "rwtest-other", 13, at(4*time.Second), 4000, "rw-other", true)
	putPublishedGap(t, pool, "rwtest-other", 13, "rw-other")
	cutoffAt(t, pool, "rwtest-other", 12)
	return stream
}

// 조각 축 — seq ≥ 컷오프 행 전량을 seq 순으로, ③ 상태와 GAP 원장 소속을 얹어 읽는다. 컷오프 행 자신이
// 포함되고(seq 12 — `>` 로 쓰면 빠진다) 그 아래 행(A 의 10·11)과 다른 스트림 행은 없다. 비귀속 행의
// NULL carrier 는 영값으로 온다(SeedResult 와 같은 규약).
func TestLoadRewindLedgerReadsSegmentAxisFromCutoff(t *testing.T) {
	pool := newTestPool(t)
	stream := rewindLedgerFixture(t, pool)

	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	if !got.HasCutoff || got.CutoffSeq != 12 || got.FloorSeq != 12 || got.Capped {
		t.Errorf("(컷오프, 하한, 상한 도달) = (%d, %v · %d · %v), want (12, true · 12 · 거짓) — 되짚기가 닿지 않는 이력이다",
			got.CutoffSeq, got.HasCutoff, got.FloorSeq, got.Capped)
	}
	key := func(seq int64) string { return fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq) }
	want := []RewindRow{
		{Seq: 12, SessionID: "rw-B", DurationMS: 4000, PlaybackPDT: rewindT0.Add(128 * time.Second), PlaybackS3Key: key(12), PlaybackUploaded: true},
		{Seq: 13, SessionID: "rw-B", DurationMS: 4000, PlaybackPDT: rewindT0.Add(132 * time.Second), PlaybackS3Key: key(13), PlaybackUploaded: true},
		{Seq: 14, SessionID: "rw-B", DurationMS: 4000, PlaybackPDT: rewindT0.Add(136 * time.Second), PlaybackS3Key: key(14), IsGap: true},
		{Seq: 15, SessionID: "rw-C", DurationMS: 7600, PlaybackPDT: rewindT0.Add(140 * time.Second), PlaybackS3Key: key(15), PlaybackUploaded: true},
		{Seq: 16, SessionID: "rw-C", DurationMS: 4000, PlaybackPDT: rewindT0.Add(147600 * time.Millisecond), PlaybackS3Key: key(16), PlaybackUploaded: true},
		{Seq: 17, DurationMS: 4000},
		{Seq: 18, SessionID: "rw-D", DurationMS: 4000, PlaybackPDT: rewindT0.Add(551600 * time.Millisecond), PlaybackS3Key: key(18)},
	}
	if !reflect.DeepEqual(got.Rows, want) {
		t.Errorf("조각 축 =\n%+v\nwant\n%+v", got.Rows, want)
	}
}

// 세션 축 — 적재한 조각이 참조하는 회차만, 여덟 열과 회차 최소 seq 를 그대로 읽는다(창 경계가 아니라 조각
// 기준). 참조가 컷오프 아래 행뿐인 A 는 B 가 계승한 회차여도 싣지 않는다 — inherits_session 은 참조가
// 아니다. 다른 스트림의 회차도 없다. 순서는 개시 순이다. 이 이력의 base 는 규칙상 모두 0 이라 base 칸은
// TestLoadRewindLedgerReadsRaisedDiscontinuityBase 가 가른다. 회차 최소 seq 가 적재 하한보다 앞인 갈래는
// TestLedgerLoadBoundedByLookback 이 가른다.
func TestLoadRewindLedgerReadsSessionAxisOfLoadedSegments(t *testing.T) {
	pool := newTestPool(t)
	stream := rewindLedgerFixture(t, pool)

	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	want := []RewindSession{
		{SessionID: "rw-B", State: "ended", EndReason: "td_exceeded", InitUploaded: true,
			FirstPDT: rewindT0.Add(128 * time.Second), InheritsSession: "rw-A", TargetDuration: 6, MinSeq: 12},
		{SessionID: "rw-C", State: "ending", EndReason: "offline",
			FirstPDT: rewindT0.Add(140 * time.Second), TargetDuration: 8, MinSeq: 15},
		{SessionID: "rw-D", State: "live", FirstPDT: rewindT0.Add(551600 * time.Millisecond), TargetDuration: 6, MinSeq: 18},
	}
	if !reflect.DeepEqual(got.Sessions, want) {
		t.Errorf("세션 축 =\n%+v\nwant\n%+v", got.Sessions, want)
	}
}

// 0 이 아닌 base 도 장부 값 그대로 읽는다. base 는 컷오프 뒤 발행된 목록에서 끊김 표시(계승 회차의 첫
// 조각)가 빠질 때만 오르고, 조각이 목록에서 빠지려면 그 뒤로 한 시간이 쌓여야 한다 — 그래서 한 시간 넘는
// 이력을 한 문장(generate_series)으로 심는다.
//
//	회차 O      컷오프 아래 회차(오프라인으로 끝남 · 컷오프 전에는 목록이 없어 base 0) — 행은 심지 않는다
//	seq 60–960  회차 P — O 가 끝나고 120초 뒤 O 를 계승해 열렸다(O 의 base 0 복사 · TD 6). seq 60 이
//	            컷오프를 주조했고 첫 조각 60 에 끊김 표시가 선다. 4초 901조각을 쓰는 사이 머리 960 에서
//	            창 꼬리가 61 로 넘어가(61..960 = 900 × 4초 = 한 시간) 목록에서 60 의 표시가 빠졌다 →
//	            base 1. 그 뒤 오프라인으로 끝났다
//	seq 961     회차 S — P 가 끝나고 120초 뒤 P 를 계승해 열렸다(base 1 복사 · TD 6 · init 미확정)
//
// 같은 이야기를 캐시 쪽 TestCacheReceivesInheritsOnOpen 이 창 계산으로 확인한다.
func TestLoadRewindLedgerReadsRaisedDiscontinuityBase(t *testing.T) {
	pool := newTestPool(t)
	const stream = "rwtest-hour"
	at := func(d time.Duration) time.Time { return rewindT0.Add(d) }
	for _, s := range []rewindSessionRow{
		{RewindSession{SessionID: "rw-O", State: "ended", EndReason: "offline",
			FirstPDT: at(-6 * time.Minute), TargetDuration: 6}, stream, at(-6 * time.Minute)},
		{RewindSession{SessionID: "rw-P", State: "ended", EndReason: "offline", InitUploaded: true,
			DiscontinuityBase: 1, FirstPDT: at(0), InheritsSession: "rw-O", TargetDuration: 6}, stream, at(0)},
		{RewindSession{SessionID: "rw-S", State: "live",
			DiscontinuityBase: 1, FirstPDT: at(3724 * time.Second), InheritsSession: "rw-P", TargetDuration: 6}, stream, at(3724 * time.Second)},
	} {
		putRewindSession(t, pool, s)
	}
	// P 의 60..960 — putRewindRow 와 같은 모양(벽시계 = PDT · ③ 확정 · carrier 셋) · 4초 간격.
	if _, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments
			(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes,
			 session_id, playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at, playback_bytes)
		SELECT stream, seq, seq * 4000, wall, 4000,
		       format('streams/%s/%s/seg_%s.m4s', stream, to_char(wall AT TIME ZONE 'UTC', 'YYYY-MM-DD/HH24'), seq6),
		       format('/recordings/%s/raw-%s.mp4', stream, seq), 'pending', 1000,
		       sid, wall, format('dvr/%s/seg/%s.m4s', stream, seq6), 'uploaded', now(), 900
		  FROM (SELECT $1::text AS stream, $2::text AS sid, g::bigint AS seq, lpad(g::text, 6, '0') AS seq6,
		               $3::timestamptz + (g - 60) * interval '4 seconds' AS wall
		          FROM generate_series(60, 960) AS g) AS r`, stream, "rw-P", rewindT0); err != nil {
		t.Fatalf("P 의 한 시간 이력 픽스처 실패: %v", err)
	}
	putRewindRow(t, pool, stream, 961, at(3724*time.Second), 4000, "rw-S", false)
	cutoffAt(t, pool, stream, 60)

	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	if n := len(got.Rows); n != 902 || got.Rows[0].Seq != 60 || got.Rows[n-1].Seq != 961 {
		t.Fatalf("픽스처 전제: 조각 축 %d행, want 902행(seq 60..961) — P 의 901조각이 있어야 base 1 이 성립한다", n)
	}
	want := []RewindSession{
		{SessionID: "rw-P", State: "ended", EndReason: "offline", InitUploaded: true,
			DiscontinuityBase: 1, FirstPDT: rewindT0, InheritsSession: "rw-O", TargetDuration: 6, MinSeq: 60},
		{SessionID: "rw-S", State: "live",
			DiscontinuityBase: 1, FirstPDT: rewindT0.Add(3724 * time.Second), InheritsSession: "rw-P", TargetDuration: 6,
			MinSeq: 961},
	}
	if !reflect.DeepEqual(got.Sessions, want) {
		t.Errorf("세션 축 =\n%+v\nwant\n%+v", got.Sessions, want)
	}
}

// 컷오프가 없는 스트림은 되감기를 제공하지 않는다(설계 4.2 ⓑ) — 행이 있어도 아무것도 싣지 않는다.
func TestLoadRewindLedgerWithoutCutoffLoadsNothing(t *testing.T) {
	pool := newTestPool(t)
	const stream = "rwtest-uncut"
	putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: "rw-U", State: "live",
		FirstPDT: rewindT0, TargetDuration: 6}, stream, rewindT0})
	putRewindRow(t, pool, stream, 0, rewindT0, 4000, "rw-U", true)

	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	if got.HasCutoff || len(got.Rows) != 0 || len(got.Sessions) != 0 {
		t.Errorf("재구성 = %+v, want 컷오프 없음 · 행 0 · 회차 0", got)
	}
}

// 장부를 못 읽으면 오류다 — 빈 결과로 삼키면 캐시가 「컷오프 없음」으로 굳어 그 스트림의 되감기가
// 조용히 사라진다.
func TestLoadRewindLedgerReportsQueryFailure(t *testing.T) {
	pool := newTestPool(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := LoadRewindLedger(ctx, pool, "rwtest-any", ledgerBounds); err == nil {
		t.Fatal("취소된 ctx 로 읽었는데 오류가 없다")
	}
}

// putSeries 는 회차 sessionID 의 조각 from..to 를 한 문장(generate_series)으로 심는다 — putRewindRow 와 같은
// 모양이다(벽시계 = PDT · ③ 확정 · carrier 셋). 조각 길이는 모두 durationMS 이고 from 의 벽시계가 start 다.
func putSeries(t *testing.T, pool *pgxpool.Pool, stream, sessionID string, from, to int64, start time.Time, durationMS int32) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments
			(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes,
			 session_id, playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at, playback_bytes)
		SELECT $1::text, seq, seq * $5::int, wall, $5::int,
		       format('streams/%s/seg_%s.m4s', $1::text, seq), format('/recordings/%s/raw-%s.mp4', $1::text, seq),
		       'pending', 1000, $2::text, wall, format('dvr/%s/seg/%s.m4s', $1::text, lpad(seq::text, 6, '0')),
		       'uploaded', now(), 900
		  FROM (SELECT g AS seq, $6::timestamptz + (g - $3::bigint) * $5::int * interval '1 millisecond' AS wall
		          FROM generate_series($3::bigint, $4::bigint) AS g) AS r`,
		stream, sessionID, from, to, durationMS, start); err != nil {
		t.Fatalf("조각 %d..%d 픽스처 실패: %v", from, to, err)
	}
}

// lookbackFixture 는 되짚기가 닿는 두 시간 넘는 이력을 스트림 stream 에 심는다 — 컷오프는 cutoff 다.
//
//	회차 P  seq 0..999     4초 조각(③ 확정) — seq 0 에서 열렸다
//	회차 S  seq 1000..2000 4초 조각 — P 를 계승해 열렸다
//
// 머리 2000 에서 거꾸로 1800조각(201..2000 = 7,200초)이 두 시간에 닿는다 — 컷오프가 201 앞이면 되짚기 하한은
// 201 이다.
func lookbackFixture(t *testing.T, pool *pgxpool.Pool, stream string, cutoff int64) {
	t.Helper()
	at := func(d time.Duration) time.Time { return rewindT0.Add(d) }
	putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: stream + "-P", State: "ended", EndReason: "offline",
		InitUploaded: true, FirstPDT: at(0), TargetDuration: 6}, stream, at(0)})
	putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: stream + "-S", State: "live", InitUploaded: true,
		FirstPDT: at(4000 * time.Second), InheritsSession: stream + "-P", TargetDuration: 6}, stream, at(4000 * time.Second)})
	putSeries(t, pool, stream, stream+"-P", 0, 999, at(0), 4000)
	putSeries(t, pool, stream, stream+"-S", 1000, 2000, at(4000*time.Second), 4000)
	cutoffAt(t, pool, stream, cutoff)
}

// ledger_load_bounded_by_lookback(계획 6.3 #81) — 적재 하한은 max(컷오프, min(되짚기 하한, 힌트)) 다(A3 결정 3).
// 되짚기 하한은 머리에서 거꾸로 길이를 더해 되짚기(두 시간)에 닿는 가장 늦은 seq 다 — 컷오프 이후 전량이 아니다.
// 힌트(A1 무결성 대조 실패의 P.MSN)는 하한을 그 아래로 내리고, 되짚기 하한보다 늦은 힌트는 하한을 올리지 않으며,
// 하한은 컷오프 아래로 내려가지 않는다. 회차 최소 seq 는 적재 하한과 무관한 장부 값이다 — 하한 201 에서도 P 는
// 0 이다(계획 6.3 #84 의 SQL 쪽 · A1 E1).
func TestLedgerLoadBoundedByLookback(t *testing.T) {
	pool := newTestPool(t)
	lookbackFixture(t, pool, "rwlook", 0)
	lookbackFixture(t, pool, "rwlook-late", 300)
	hint := func(seq int64) *int64 { return &seq }

	for _, tt := range []struct {
		name   string
		stream string
		hint   *int64
		floor  int64
	}{
		{"힌트_없음", "rwlook", nil, 201},
		{"힌트가_되짚기_하한_앞", "rwlook", hint(150), 150},
		{"힌트가_되짚기_하한_뒤", "rwlook", hint(500), 201},
		{"하한이_컷오프_아래로_가지_않음", "rwlook-late", hint(250), 300},
	} {
		t.Run(tt.name, func(t *testing.T) {
			b := ledgerBounds
			b.Hint = tt.hint

			got, err := LoadRewindLedger(context.Background(), pool, tt.stream, b)

			if err != nil {
				t.Fatalf("LoadRewindLedger 실패: %v", err)
			}
			n := len(got.Rows)
			if got.FloorSeq != tt.floor || got.Capped || n != int(2000-tt.floor+1) || got.Rows[0].Seq != tt.floor ||
				got.Rows[n-1].Seq != 2000 {
				t.Errorf("(하한, 상한 도달, 행) = (%d, %v, %d행 %d..%d), want (%d, 거짓, %d행 %d..2000)",
					got.FloorSeq, got.Capped, n, got.Rows[0].Seq, got.Rows[n-1].Seq, tt.floor, 2000-tt.floor+1, tt.floor)
			}
			if tt.hint != nil {
				return
			}
			var minSeqs []string
			for _, s := range got.Sessions {
				minSeqs = append(minSeqs, fmt.Sprintf("%s:%d", s.SessionID, s.MinSeq))
			}
			if want := []string{"rwlook-P:0", "rwlook-S:1000"}; !reflect.DeepEqual(minSeqs, want) {
				t.Errorf("회차 최소 seq = %v, want %v — 적재 하한이 아니라 장부 값이다", minSeqs, want)
			}
		})
	}
}

// lookback_floor_bounded_by_row_cap(계획 6.3 #111) — 되짚기 합산은 행 수 상한 안의 최신 행만 거꾸로 더한다(A3 결정
// 3 — DB 작업량 ≤ 상한). 0.5초 조각 12,000행(6,000초)은 두 시간에 닿지 않는다. 상한 10,000 안에서 닿지 못하면
// 하한은 상한 안 가장 오래된 행(최신 10,000번째 = seq 2000)이고 상한 도달이 참이다. 합산이 상한 밖(컷오프 이후
// 전량)으로 나가면 하한이 컷오프 0 이 되고 상한 도달이 거짓이 된다 — 적재 결과는 행 적재의 상한이 다시 잘라 같게
// 보이므로, 되짚기 하한 문장 자체의 두 값도 단언한다.
func TestLookbackFloorBoundedByRowCap(t *testing.T) {
	pool := newTestPool(t)
	const stream = "rwcap"
	putRewindSession(t, pool, rewindSessionRow{RewindSession{SessionID: "rwcap-P", State: "live", InitUploaded: true,
		FirstPDT: rewindT0, TargetDuration: 6}, stream, rewindT0})
	putSeries(t, pool, stream, "rwcap-P", 0, 11999, rewindT0, 500)
	cutoffAt(t, pool, stream, 0)

	var floor int64
	var capped bool
	err := pool.QueryRow(context.Background(), rewindFloorSQL, stream, int64(0), (2*time.Hour).Milliseconds(), int64(10000)).
		Scan(&floor, &capped)
	if err != nil || floor != 2000 || !capped {
		t.Errorf("되짚기 하한 문장 = (%d, %v, %v), want (2000, 참, nil) — 합산이 상한 안의 최신 10,000행에 묶인다", floor, capped, err)
	}
	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}
	if n := len(got.Rows); got.FloorSeq != 2000 || !got.Capped || n != 10000 || got.Rows[0].Seq != 2000 {
		t.Errorf("(하한, 상한 도달, 행) = (%d, %v, %d행), want (2000, 참, 10000행 2000..11999)", got.FloorSeq, got.Capped, n)
	}
}

// ledger_load_tx_options_read_only_repeatable_read(계획 6.3 #83) — 적재 네 문장은 읽기 전용 · REPEATABLE READ 한
// 트랜잭션이다(A3 결정 4). 한 스냅숏이라, 첫 문장 뒤 다른 연결이 커밋한 행(seq 19)은 행 문장에 보이지 않는다 —
// READ COMMITTED 면 보여 하한 · 회차와 다른 시점의 행이 섞인다. 트랜잭션 옵션은 풀이 보낸 BEGIN 문장으로 본다.
func TestLedgerLoadTxOptionsReadOnlyRepeatableRead(t *testing.T) {
	base := newTestPool(t)
	stream := rewindLedgerFixture(t, base)
	var begins []string
	injected := false
	pool := hookedPool(t, base, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		switch {
		case strings.HasPrefix(sql, "begin"):
			begins = append(begins, sql)
		case sql == rewindRowsSQL && !injected: // 행 문장 직전 — 다른 연결이 새 행을 커밋한다
			injected = true
			putRewindRow(t, base, stream, 19, rewindT0.Add(555600*time.Millisecond), 4000, "rw-D", true)
		}
		return ctx
	}))

	got, err := LoadRewindLedger(context.Background(), pool, stream, ledgerBounds)

	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}
	if want := []string{"begin isolation level repeatable read read only"}; !reflect.DeepEqual(begins, want) {
		t.Errorf("BEGIN 문장 = %q, want %q", begins, want)
	}
	if last := got.Rows[len(got.Rows)-1].Seq; !injected || last != 18 {
		t.Errorf("마지막 행 = %d(주입 %v), want 18 — 첫 문장 뒤 커밋한 행 19 는 한 스냅숏 밖이다", last, injected)
	}
}

// ledger_load_rolls_back_with_live_context(장부 421 P-2 · TestGapTickRollsBackWithLiveContext 형) — 적재 트랜잭션은
// LoadRewindLedger 가 BEGIN 부터 끝까지 소유하고, 실패하면 되돌리는 순간에 만든 끝나지 않은 ctx(부른 쪽 ctx 에서
// 취소를 떼고 같은 시한)로 되돌린다. 끝난 ctx(시한이 지난 트랜잭션 ctx · 끝난 부른 쪽 ctx · BEGIN 때 미리 만들어
// 시한을 다 쓴 ctx)로 보내면 pgx 가 그 연결을 버린다. 연결이 이미 선 풀에서 재므로 새 연결의 수립 시간이 되돌림의
// 시한을 벌어 주지 않는다 — 되돌림이 오류 없이 끝나고 새 연결 없이 같은 풀의 다음 문장이 선다. 시한은 문장 시한과
// 되돌림 시한이 같은 손잡이(비공개 loadRewindLedger 의 시한 인자)로 200ms 로 줄인다.
func TestLedgerLoadRollsBackWithLiveContext(t *testing.T) {
	cases := []struct {
		name string
		// inject 는 행 문장을 실패시킨다 — 부른 쪽 ctx 의 cancel 을 받는다.
		inject func(ctx context.Context, cancel context.CancelFunc) context.Context
	}{
		{"문장_시한_지남", func(ctx context.Context, _ context.CancelFunc) context.Context {
			select { // 트랜잭션 시한이 지날 때까지 붙잡고(시한이 없는 구현이면 2초 뒤 놓아 시험이 멈추지 않는다)
			case <-ctx.Done():
			case <-time.After(2 * time.Second):
			}
			time.Sleep(100 * time.Millisecond) // BEGIN 때 만든 ctx 의 시한도 지나도록 늦게 돌아온다
			return ctx
		}},
		{"부른_쪽_ctx_끝남", func(ctx context.Context, cancel context.CancelFunc) context.Context {
			cancel()
			return ctx
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			base := newTestPool(t)
			stream := rewindLedgerFixture(t, base)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			var rollbacks []error
			pool := hookedPool(t, base, traceSQLEnd{
				start: func(sctx context.Context, _ *pgx.Conn, sql string) context.Context {
					if sql == rewindRowsSQL {
						return tc.inject(sctx, cancel)
					}
					return sctx
				},
				end: func(sql string, err error) {
					if sql == "rollback" {
						rollbacks = append(rollbacks, err)
					}
				},
			})
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 연결 하나를 세워 둔다
				t.Fatalf("풀 데우기 실패: %v", err)
			}
			conns := pool.Stat().NewConnsCount()

			_, err := loadRewindLedger(ctx, pool, stream, ledgerBounds, 200*time.Millisecond)

			if err == nil {
				t.Error("행 문장이 실패했는데 오류가 없다")
			}
			if len(rollbacks) != 1 || rollbacks[0] != nil {
				t.Errorf("되돌림 결과 %v, want 한 번 · 오류 nil(끝나지 않은 ctx)", rollbacks)
			}
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 같은 풀의 다음 문장
				t.Errorf("되돌림 뒤 다음 문장 실패: %v", err)
			}
			if n := pool.Stat().NewConnsCount() - conns; n != 0 {
				t.Errorf("새 연결 %d개, want 0(되돌림이 연결을 버리지 않았다)", n)
			}
		})
	}
}

// traceSQL 은 문장마다 부르는 추적기다(pgx.QueryTracer — rewind/publish 시험의 같은 이름 도우미와 같은 형). 돌려준
// ctx 로 그 문장을 보낸다. pgx 는 BEGIN · COMMIT · ROLLBACK 도 문장으로 보내 여기를 지난다.
type traceSQL func(ctx context.Context, conn *pgx.Conn, sql string) context.Context

func (f traceSQL) TraceQueryStart(ctx context.Context, conn *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	return f(ctx, conn, d.SQL)
}

func (traceSQL) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// traceSQLEnd 는 traceSQL 에 문장 끝을 더한 추적기다 — end 는 끝난 문장과 그 결과 오류를 받는다.
type traceSQLEnd struct {
	start traceSQL
	end   func(sql string, err error)
}

// traceSQLKey 는 문장 시작이 ctx 에 적어 끝에서 읽는 그 문장이다 — pgx 는 시작이 돌려준 ctx 로 끝을 부른다.
type traceSQLKey struct{}

func (f traceSQLEnd) TraceQueryStart(ctx context.Context, conn *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	return context.WithValue(f.start(ctx, conn, d.SQL), traceSQLKey{}, d.SQL)
}

func (f traceSQLEnd) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	sql, _ := ctx.Value(traceSQLKey{}).(string)
	f.end(sql, d.Err)
}

// hookedPool 은 pool 과 같은 테스트 DB 에 붙되 문장마다 hook 을 거치는 새 풀이다. 풀은 t.Cleanup 이 닫는다.
func hookedPool(t *testing.T, pool *pgxpool.Pool, hook pgx.QueryTracer) *pgxpool.Pool {
	t.Helper()
	cfg := pool.Config()
	cfg.ConnConfig.Tracer = hook
	p, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("추적 풀 생성 실패: %v", err)
	}
	t.Cleanup(p.Close)
	return p
}
