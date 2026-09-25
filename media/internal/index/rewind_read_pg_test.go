package index

// 되감기 캐시 부팅 재구성의 읽기(POK-195 M4 PR ⓑ 커밋 ④ — 설계 4.1 「부팅 재구성」 · 계획 2.3 ⑸ⓕ).
//
// 재구성은 두 단이다: ⑴ 조각 축 = 컷오프 이후 전량(seq ≥ cutoff) + ③ 상태 + GAP 원장 소속,
// ⑵ 세션 축 = ⑴ 이 참조하는 모든 회차의 여덟 열. 여기서는 SQL 이 **어느 행을 어떤 값으로** 가져오는지를
// 장부 실물로 잰다 — settled 판정은 SQL 이 아니라 boundary.Settled 하나라서 여기 없다(착수 점검 숨은
// 가정 6). PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"fmt"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// rewindT0 은 재구성 픽스처의 기준 벽시계다(마이크로초 미만이 없어 PG 왕복에 값이 보존된다).
var rewindT0 = time.Date(2026, 9, 24, 9, 0, 0, 0, time.UTC)

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
//	seq 10·11  회차 A — seq 10 의 7.6초 조각이 직전 회차의 TD 6 을 넘어 TD 분할로 열렸다(base 5 복사 ·
//	           계승 없음 · TD 8). 컷오프 전이다(주조가 방증 낡음으로 미뤄졌다)
//	seq 12–14  회차 B — 약 2분 순단 뒤 A 를 계승해 열렸다(base 는 A 의 5 를 그대로 옮김 · TD 6).
//	           seq 12 가 컷오프를 주조했고, 14 는 ③ 가 안 올라가 GAP 원장(upload_stall)에 있다
//	seq 15·16  회차 C — seq 15 의 7.6초 조각이 B 의 TD 6 을 넘어 TD 분할로 열렸다(base 5 복사 · 계승
//	           없음 · TD 8). B 는 그때 ending(td_exceeded)이 됐고 나중에 ended 로 정산됐다
//	seq 17     회차 없음 — C 가 오프라인으로 ending 이 된 뒤 스캔으로 들어와 현 회차가 없었다(carrier NULL)
//	seq 18     회차 D — 400초 뒤 새 방송(비계승 · base 0 · TD 6 · live · init 미확정)
//
// A·B 는 init 이 올라갔고 C·D 는 아직이다. A 가 옮겨 받은 base 5 는 그 앞 회차들의 계승·축출이 쌓인 값이다.
// 다른 스트림 하나(컷오프·행·회차)를 함께 심어 스트림 필터를 잰다.
func rewindLedgerFixture(t *testing.T, pool *pgxpool.Pool) string {
	t.Helper()
	const stream = "rwtest-ledger"
	at := func(d time.Duration) time.Time { return rewindT0.Add(d) }
	for _, s := range []rewindSessionRow{
		{RewindSession{SessionID: "rw-A", State: "ended", EndReason: "offline", InitUploaded: true,
			DiscontinuityBase: 5, FirstPDT: at(0), TargetDuration: 8}, stream, at(0)},
		{RewindSession{SessionID: "rw-B", State: "ended", EndReason: "td_exceeded", InitUploaded: true,
			DiscontinuityBase: 5, FirstPDT: at(128 * time.Second), InheritsSession: "rw-A", TargetDuration: 6}, stream, at(128 * time.Second)},
		{RewindSession{SessionID: "rw-C", State: "ending", EndReason: "offline",
			DiscontinuityBase: 5, FirstPDT: at(140 * time.Second), TargetDuration: 8}, stream, at(140 * time.Second)},
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

	got, err := LoadRewindLedger(context.Background(), pool, stream)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	if !got.HasCutoff || got.CutoffSeq != 12 {
		t.Errorf("컷오프 = (%d, %v), want (12, true)", got.CutoffSeq, got.HasCutoff)
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

// 세션 축 — 적재한 조각이 참조하는 회차만, 여덟 열을 그대로 읽는다(창 경계가 아니라 조각 기준). 참조가
// 컷오프 아래 행뿐인 A 는 B 가 계승한 회차여도 싣지 않는다 — inherits_session 은 참조가 아니다. 다른
// 스트림의 회차도 없다. 순서는 개시 순이다.
func TestLoadRewindLedgerReadsSessionAxisOfLoadedSegments(t *testing.T) {
	pool := newTestPool(t)
	stream := rewindLedgerFixture(t, pool)

	got, err := LoadRewindLedger(context.Background(), pool, stream)
	if err != nil {
		t.Fatalf("LoadRewindLedger 실패: %v", err)
	}

	want := []RewindSession{
		{SessionID: "rw-B", State: "ended", EndReason: "td_exceeded", InitUploaded: true,
			DiscontinuityBase: 5, FirstPDT: rewindT0.Add(128 * time.Second), InheritsSession: "rw-A", TargetDuration: 6},
		{SessionID: "rw-C", State: "ending", EndReason: "offline",
			DiscontinuityBase: 5, FirstPDT: rewindT0.Add(140 * time.Second), TargetDuration: 8},
		{SessionID: "rw-D", State: "live", FirstPDT: rewindT0.Add(551600 * time.Millisecond), TargetDuration: 6},
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

	got, err := LoadRewindLedger(context.Background(), pool, stream)
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

	if _, err := LoadRewindLedger(ctx, pool, "rwtest-any"); err == nil {
		t.Fatal("취소된 ctx 로 읽었는데 오류가 없다")
	}
}
