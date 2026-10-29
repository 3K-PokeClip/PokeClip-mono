package index

// 회차 상태 읽기 셋(POK-196 M6-3a 커밋 ④ · 설계 M6 2.2 판정식 · 경계 확정 2 · 3 · 9절 D67 · D73 · M5 R52 정본)의 실 PG
// 시험. 세 함수가 같은 판정식(한 SQL 문장)을 쓴다 — 회차 하나(LookupRoundPlayback) · 스트림 최신 회차(LoadStreamPlayback) ·
// 기동 적재(LoadPlaybackStates).

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// roundFixture 는 회차 칸까지 심는 세션 행이다. 영값 시각 · 빈 문자열은 NULL 이다.
type roundFixture struct {
	id, stream, state, roundID, vodState, vodKey string
	startedAt, endedAt, closedAt, expiredAt      time.Time
	vodFirst, vodLast                            int64 // 0 이면 NULL
}

func plantRound(t *testing.T, pool *pgxpool.Pool, f roundFixture) {
	t.Helper()
	if f.vodState == "" {
		f.vodState = "none"
	}
	nt := func(v time.Time) any {
		if v.IsZero() {
			return nil
		}
		return v
	}
	ns := func(v string) any {
		if v == "" {
			return nil
		}
		return v
	}
	ni := func(v int64) any {
		if v == 0 {
			return nil
		}
		return v
	}
	var endingAt any
	if f.state != "live" {
		endingAt = f.startedAt.Add(time.Minute)
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at, expired_at,
     vod_state, vod_s3_key, vod_first_seq, vod_last_seq)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
		f.id, f.stream, f.startedAt, f.state, endingAt, nt(f.endedAt), ns(f.roundID), nt(f.closedAt), nt(f.expiredAt),
		f.vodState, ns(f.vodKey), ni(f.vodFirst), ni(f.vodLast)); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", f.id, err)
	}
}

// plantPDT 는 VOD 범위의 조각 한 행이다(첫 조각 PDT · 마지막 조각 끝의 재료).
func plantPDT(t *testing.T, pool *pgxpool.Pool, stream, session string, seq int64, pdt time.Time, durMS int32) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id, playback_pdt)
VALUES ($1, $2, 0, $3, $4, $5, $6, 'uploaded', 1000, $7, $3)`,
		stream, seq, pdt, durMS, fmt.Sprintf("k-%s-%d", stream, seq), fmt.Sprintf("/recordings/%s/%d.mp4", stream, seq), session); err != nil {
		t.Fatalf("조각 픽스처 실패 %s/%d: %v", stream, seq, err)
	}
}

// D67 · 경계 확정 3 — 기동 적재의 판정. 완결 안 된 비-live 최신 회차는 within 과 무관하게 Ending(닫힘 전 · 닫힘 뒤 정산
// 중 둘 다), 완결 값은 완결 시각이 within(30분) 안일 때만, VOD 는 키가 있어야 하고 빈 동결 완결은 Ended, 만료 · live 는 내지
// 않는다. 변이 = Ending 을 「round_closed_at IS NOT NULL」 로만 · within 을 Ending 에도 · VOD 행의 키 조건 제거 → 각각 FAIL.
func TestLoadPlaybackStatesJudgesEachRoundByVerdict(t *testing.T) {
	pool := newTestPool(t)
	// PG timestamptz 는 마이크로초까지만 담는다 — Linux 시계의 나노초가 남으면 아래 FirstPDT · HeadPDT 왕복 비교가 갈린다.
	now := time.Now().UTC().Truncate(time.Microsecond)
	// 오프라인 종료 120초 뒤 — 회차는 아직 닫히지 않았다(360초 전).
	plantRound(t, pool, roundFixture{id: "A1", stream: "a", state: "ending", roundID: "A1", startedAt: now.Add(-time.Hour)})
	// 회차는 40분 전에 닫혔지만 정산 중(ended 아님) — 여전히 Ending.
	plantRound(t, pool, roundFixture{id: "B1", stream: "b", state: "ending", roundID: "B1", startedAt: now.Add(-2 * time.Hour), closedAt: now.Add(-40 * time.Minute)})
	// 10분 전 완결 · VOD 키 있음.
	plantRound(t, pool, roundFixture{id: "C1", stream: "c", state: "ended", roundID: "C1", startedAt: now.Add(-time.Hour),
		endedAt: now.Add(-10 * time.Minute), closedAt: now.Add(-12 * time.Minute), vodState: "frozen", vodKey: "vod/c/C1.m3u8", vodFirst: 1, vodLast: 2})
	plantPDT(t, pool, "c", "C1", 1, now.Add(-time.Hour), 4000)
	plantPDT(t, pool, "c", "C1", 2, now.Add(-time.Hour+4*time.Second), 3000)
	// 40분 전 완결 — 기동 적재에서 빠진다.
	plantRound(t, pool, roundFixture{id: "D1", stream: "d", state: "ended", roundID: "D1", startedAt: now.Add(-2 * time.Hour),
		endedAt: now.Add(-40 * time.Minute), closedAt: now.Add(-41 * time.Minute), vodState: "frozen", vodKey: "vod/d/D1.m3u8", vodFirst: 1, vodLast: 1})
	// 빈 동결 완결(frozen 인데 키 없음) — Ended.
	plantRound(t, pool, roundFixture{id: "E1", stream: "e", state: "ended", roundID: "E1", startedAt: now.Add(-time.Hour),
		endedAt: now.Add(-5 * time.Minute), closedAt: now.Add(-6 * time.Minute), vodState: "frozen"})
	// 만료 표시 · live — 기동 적재에 없다.
	plantRound(t, pool, roundFixture{id: "F1", stream: "f", state: "ended", roundID: "F1", startedAt: now.Add(-time.Hour),
		endedAt: now.Add(-5 * time.Minute), closedAt: now.Add(-6 * time.Minute), expiredAt: now})
	plantRound(t, pool, roundFixture{id: "G1", stream: "g", state: "live", roundID: "G1", startedAt: now.Add(-time.Hour)})

	got, err := LoadPlaybackStates(context.Background(), pool, 30*time.Minute)
	if err != nil {
		t.Fatalf("LoadPlaybackStates 실패: %v", err)
	}

	want := map[string]PlaybackPhase{"a": PhaseEnding, "b": PhaseEnding, "c": PhaseVOD, "e": PhaseEnded}
	if len(got) != len(want) {
		t.Errorf("적재 스트림 = %v, want %v 넷(d 완결 40분 · f 만료 · g live 는 빠짐)", keysOf(got), want)
	}
	for s, ph := range want {
		if got[s].Phase != ph {
			t.Errorf("스트림 %s 국면 = %d, want %d", s, got[s].Phase, ph)
		}
	}
	c := got["c"]
	if c.RoundID != "C1" || c.LatestSessionID != "C1" || c.VODKey != "vod/c/C1.m3u8" ||
		!c.FirstPDT.Equal(now.Add(-time.Hour)) || !c.HeadPDT.Equal(now.Add(-time.Hour+7*time.Second)) {
		t.Errorf("c VOD = %+v, want 회차 C1 · 키 · 첫 PDT · 끝(배타) = 첫 + 7초", c)
	}
	if a := got["a"]; a.RoundID != "A1" || a.LatestSessionID != "A1" || a.VODKey != "" {
		t.Errorf("a Ending = %+v, want 회차 · 최신 세션 A1 · VOD 칸 빈 값", a)
	}
}

func keysOf(m map[string]PlaybackState) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	return out
}

// D73(판정식 쪽) · 3.3 선택 규칙 — 늦게 온 옛 연결 조각이 연 live L 이 최신 ending B 보다 이른 started_at 이어도 스트림의
// 최신 회차는 L 의 회차이고, live 라 보낼 값이 없다(ok=false). 변이 = 최신 회차를 started_at 최대로 → B 회차 값을 내 FAIL.
func TestLoadStreamPlaybackSkipsStreamWhoseMembershipRowIsLive(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: "A", stream: "s", state: "ended", roundID: "A", startedAt: now.Add(-3 * time.Hour), endedAt: now.Add(-2 * time.Hour), closedAt: now.Add(-2 * time.Hour)})
	plantRound(t, pool, roundFixture{id: "B", stream: "s", state: "ending", roundID: "B", startedAt: now.Add(-30 * time.Minute)})
	plantRound(t, pool, roundFixture{id: "L", stream: "s", state: "live", roundID: "L", startedAt: now.Add(-40 * time.Minute)})

	st, ok, err := LoadStreamPlayback(context.Background(), pool, "s")
	if err != nil || ok {
		t.Errorf("LoadStreamPlayback = (%+v, %v, %v), want 값 없음(ok=false) — 최신 회차가 live", st, ok, err)
	}
	all, err := LoadPlaybackStates(context.Background(), pool, 30*time.Minute)
	if _, has := all["s"]; err != nil || has {
		t.Errorf("기동 적재 = (%v, %v), want s 없음", all["s"], err)
	}
}

// LoadStreamPlayback 의 갈래 — 장부에 없는 스트림은 Unknown(빈 회차) · 최신 회차 만료는 Unknown · 끝난 회차는 그 값.
// 옛 회차(지난 방송)의 완결이 아니라 늘 최신 회차의 값이다(경계 약속 3).
func TestLoadStreamPlaybackAnswersLatestRound(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: "OLD", stream: "s", state: "ended", roundID: "OLD", startedAt: now.Add(-3 * time.Hour), endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute)})
	plantRound(t, pool, roundFixture{id: "NEW", stream: "s", state: "ending", roundID: "NEW", startedAt: now.Add(-20 * time.Minute)})
	plantRound(t, pool, roundFixture{id: "X", stream: "x", state: "ended", roundID: "X", startedAt: now.Add(-time.Hour), endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute), expiredAt: now})
	ctx := context.Background()

	for _, c := range []struct {
		stream string
		want   PlaybackState
	}{
		{"s", PlaybackState{Phase: PhaseEnding, RoundID: "NEW", LatestSessionID: "NEW"}},
		{"x", PlaybackState{Phase: PhaseUnknown, RoundID: "X", LatestSessionID: "X"}},
		{"none", PlaybackState{Phase: PhaseUnknown}},
	} {
		st, ok, err := LoadStreamPlayback(ctx, pool, c.stream)
		if err != nil || !ok || st != c.want {
			t.Errorf("LoadStreamPlayback(%s) = (%+v, %v, %v), want (%+v, true)", c.stream, st, ok, err, c.want)
		}
	}
}

// LookupRoundPlayback — 그 회차의 국면만(같은 키 다른 회차 값을 섞지 않음 · 경계 확정 4 ②). 회차에 live 가 있으면 PhaseLive,
// 지난 회차는 그 회차의 값, 모르는 회차 · 다른 스트림의 회차 · 만료는 Unknown. 회차 안 최신 세션은 선택 규칙대로다.
// M6 전 세션(round_id NULL)은 세션 하나가 회차다.
func TestLookupRoundPlaybackAnswersThatRoundOnly(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now().UTC()
	plantRound(t, pool, roundFixture{id: "R1a", stream: "s", state: "ended", roundID: "R1", startedAt: now.Add(-3 * time.Hour), endedAt: now.Add(-2 * time.Hour), closedAt: now.Add(-2 * time.Hour)})
	plantRound(t, pool, roundFixture{id: "R1b", stream: "s", state: "ended", roundID: "R1", startedAt: now.Add(-150 * time.Minute), endedAt: now.Add(-2 * time.Hour), closedAt: now.Add(-2 * time.Hour)})
	plantRound(t, pool, roundFixture{id: "R2a", stream: "s", state: "ending", roundID: "R2", startedAt: now.Add(-20 * time.Minute)})
	plantRound(t, pool, roundFixture{id: "R2b", stream: "s", state: "live", roundID: "R2", startedAt: now.Add(-10 * time.Minute)})
	plantRound(t, pool, roundFixture{id: "LEG", stream: "s", state: "ended", startedAt: now.Add(-5 * time.Hour), endedAt: now.Add(-4 * time.Hour)})
	plantRound(t, pool, roundFixture{id: "EXP", stream: "s", state: "ended", roundID: "EXP", startedAt: now.Add(-6 * time.Hour), endedAt: now.Add(-5 * time.Hour), closedAt: now.Add(-5 * time.Hour), expiredAt: now})
	ctx := context.Background()

	for _, c := range []struct {
		stream, round string
		want          PlaybackState
	}{
		{"s", "R1", PlaybackState{Phase: PhaseEnded, RoundID: "R1", LatestSessionID: "R1b"}},
		{"s", "R2", PlaybackState{Phase: PhaseLive, RoundID: "R2", LatestSessionID: "R2b"}},
		{"s", "LEG", PlaybackState{Phase: PhaseEnded, RoundID: "LEG", LatestSessionID: "LEG"}},
		{"s", "EXP", PlaybackState{Phase: PhaseUnknown, RoundID: "EXP", LatestSessionID: "EXP"}},
		{"s", "nope", PlaybackState{Phase: PhaseUnknown}},
		{"other", "R1", PlaybackState{Phase: PhaseUnknown}},
	} {
		st, err := LookupRoundPlayback(ctx, pool, c.stream, c.round)
		if err != nil || st != c.want {
			t.Errorf("LookupRoundPlayback(%s, %s) = (%+v, %v), want %+v", c.stream, c.round, st, err, c.want)
		}
	}
}

// 판정식 오류는 감추지 않는다 — 끝난 ctx 는 오류다(M5 RoundRoute 가 503 으로 바꾼다).
func TestRoundPlaybackReadsSurfaceErrors(t *testing.T) {
	pool := newTestPool(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := LookupRoundPlayback(ctx, pool, "s", "r"); err == nil {
		t.Error("LookupRoundPlayback 이 끝난 ctx 에서 오류 없이 돌아왔다")
	}
	if _, _, err := LoadStreamPlayback(ctx, pool, "s"); err == nil {
		t.Error("LoadStreamPlayback 이 끝난 ctx 에서 오류 없이 돌아왔다")
	}
	if _, err := LoadPlaybackStates(ctx, pool, time.Minute); err == nil {
		t.Error("LoadPlaybackStates 가 끝난 ctx 에서 오류 없이 돌아왔다")
	}
}
