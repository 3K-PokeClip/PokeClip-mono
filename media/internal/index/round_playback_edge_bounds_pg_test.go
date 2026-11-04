package index

// 판정식의 하한 원장 줄(POK-525 설계 4-3 · 계획 Ⓒ-3 · E3 앞 몫)의 실 PG 시험. 원장(stream_edge_bounds)에 그 회차 행이
// 남아 있는 동안은 엣지 KVS 에 하한 키가 남았거나 지운 지 유예(ReleaseSettle)가 안 지난 때라, 완결된 회차도 vod 가
// 아니라 ending 이다 — VOD 가 해제 · 유예보다 먼저 열리면 갓 연 VOD 의 하한 아래 조각이 엣지에서 404 다. 행이 지워진
// 뒤(DeleteReleased)에야 vod 다. 세 읽기(회차 하나 · 스트림 최신 회차 · 기동 적재)가 판정식 한 글자를 쓰므로 셋 다 본다.
// 변이 = 판정식의 원장 줄 제거 → 행 있음 · 유예 안 갈래가 vod 를 내 FAIL.

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
)

// plantFrozenRound 는 stream 의 완결 · 동결 회차 round 다(원장 줄이 없으면 vod).
func plantFrozenRound(t *testing.T, pool *pgxpool.Pool, stream, round string) {
	t.Helper()
	plantFrozenRoundAt(t, pool, stream, round, 1, time.Minute)
}

// plantFrozenRoundAt 은 completedAgo 전에 닫힌 완결 · 동결 회차 round 다(조각 seq 하나 · 한 시간 방송). 한 스트림에 회차
// 여럿을 심으면 seq 를 달리 준다 — 늦게 닫힌 회차가 최신 회차다.
func plantFrozenRoundAt(t *testing.T, pool *pgxpool.Pool, stream, round string, seq int64, completedAgo time.Duration) {
	t.Helper()
	end := time.Now().UTC().Add(-completedAgo)
	plantRound(t, pool, roundFixture{id: round, stream: stream, state: "ended", roundID: round,
		startedAt: end.Add(-time.Hour), endedAt: end, closedAt: end,
		vodState: "frozen", vodKey: "vod/" + stream + "/" + round + ".m3u8", vodFirst: seq, vodLast: seq})
	plantPDT(t, pool, stream, round, seq, end.Add(-time.Hour), 4000)
}

// plantEdgeBoundsRow 는 원장 한 행이다. released 가 영값이면 해제 전(released_at NULL)이다. 열린 회차만 올리는 Raise 를
// 거치지 않고 닫힌 회차에 직접 심는다 — 회차가 닫힌 뒤 Writer 가 해제하기 전 상태다.
func plantEdgeBoundsRow(t *testing.T, pool *pgxpool.Pool, stream, round string, released time.Time) {
	t.Helper()
	var at any
	if !released.IsZero() {
		at = released
	}
	if _, err := pool.Exec(context.Background(),
		`INSERT INTO stream_edge_bounds (stream_id, round_id, dvr_floor, released_at) VALUES ($1, $2, 7, $3)`,
		stream, round, at); err != nil {
		t.Fatalf("원장 행 픽스처 실패 %s/%s: %v", stream, round, err)
	}
}

// readAllPhases 는 세 읽기가 (stream, round) 에 낸 국면이다 — 회차 하나 · 스트림 최신 회차 · 기동 적재 순.
func readAllPhases(t *testing.T, pool *pgxpool.Pool, stream, round string) [3]PlaybackPhase {
	t.Helper()
	ctx := context.Background()
	one, err := LookupRoundPlayback(ctx, pool, stream, round)
	if err != nil {
		t.Fatalf("LookupRoundPlayback(%s, %s) 실패: %v", stream, round, err)
	}
	latest, ok, err := LoadStreamPlayback(ctx, pool, stream)
	if err != nil || !ok {
		t.Fatalf("LoadStreamPlayback(%s) = (%+v, %v, %v), want 값 있음", stream, latest, ok, err)
	}
	all, err := LoadPlaybackStates(ctx, pool, 30*time.Minute)
	if err != nil {
		t.Fatalf("LoadPlaybackStates 실패: %v", err)
	}
	return [3]PlaybackPhase{one.Phase, latest.Phase, all[stream].Phase}
}

// E3 앞 몫 — 원장 행이 있으면(해제 전 · 해제 뒤 유예 안) ending, 행이 없으면(DeleteReleased 뒤) vod. 원장 줄은 그
// 스트림의 그 회차만 본다 — 다른 스트림의 같은 회차 이름 행은 vod 를 막지 않는다.
func TestRoundPlaybackHoldsEndingWhileEdgeBoundsRowRemains(t *testing.T) {
	ending := [3]PlaybackPhase{PhaseEnding, PhaseEnding, PhaseEnding}
	vod := [3]PlaybackPhase{PhaseVOD, PhaseVOD, PhaseVOD}
	for _, c := range []struct {
		name  string
		plant func(t *testing.T, pool *pgxpool.Pool)
		want  [3]PlaybackPhase
	}{
		{"해제 전 행", func(t *testing.T, pool *pgxpool.Pool) {
			plantEdgeBoundsRow(t, pool, "s", "R", time.Time{})
		}, ending},
		{"해제 뒤 유예 안 행", func(t *testing.T, pool *pgxpool.Pool) {
			plantEdgeBoundsRow(t, pool, "s", "R", time.Now().UTC().Add(-30*time.Second))
		}, ending},
		{"행 없음", func(*testing.T, *pgxpool.Pool) {}, vod},
		{"다른 스트림의 같은 회차 이름 행", func(t *testing.T, pool *pgxpool.Pool) {
			plantEdgeBoundsRow(t, pool, "other", "R", time.Time{})
		}, vod},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := edgeBoundsPool(t)
			plantFrozenRound(t, pool, "s", "R")
			c.plant(t, pool)

			if got := readAllPhases(t, pool, "s", "R"); got != c.want {
				t.Errorf("국면(회차 · 스트림 · 기동) = %v, want %v", got, c.want)
			}
		})
	}
}

// 원장 줄의 경계 — 만료가 원장 줄보다 앞이고(unknown), 원장 줄은 그 회차 열쇠만 본다(M6 전 세션의 NULL 회차는 빈 문자열
// 회차 행에 걸리지 않고, 같은 스트림의 다른 회차 행은 최신 회차의 vod 를 막지 않는다). 기동 적재는 원장 행 회차를 완결
// 시각과 무관하게(within 밖이어도) Ending 으로 싣는다 — 행이 오래 남으면 기동마다 실린다. 변이 = 판정식의 원장 줄 제거 →
// 「다른 회차 · 원장 행 회차」 · 「within 밖」 FAIL · 원장 줄을 만료 줄 위로 → 「만료」 FAIL · 원장 줄의 회차 조건 제거 →
// 「NULL 회차」 · 「다른 회차」 둘 FAIL(앞 시험의 「다른 스트림」 사례는 이 변이에서 통과한다).
func TestRoundPlaybackEdgeBoundsBoundaries(t *testing.T) {
	none := PlaybackPhase(0) // 기동 적재에 없음
	for _, c := range []struct {
		name  string
		plant func(t *testing.T, pool *pgxpool.Pool)
		round string
		want  [3]PlaybackPhase
	}{
		{"만료 + 원장 행", func(t *testing.T, pool *pgxpool.Pool) {
			now := time.Now().UTC()
			plantRound(t, pool, roundFixture{id: "R", stream: "s", state: "ended", roundID: "R",
				startedAt: now.Add(-time.Hour), endedAt: now.Add(-time.Minute), closedAt: now.Add(-time.Minute),
				expiredAt: now, vodState: "frozen", vodKey: "vod/s/R.m3u8", vodFirst: 1, vodLast: 1})
			plantPDT(t, pool, "s", "R", 1, now.Add(-time.Hour), 4000)
			plantEdgeBoundsRow(t, pool, "s", "R", time.Time{})
		}, "R", [3]PlaybackPhase{PhaseUnknown, PhaseUnknown, none}},
		{"NULL 회차(M6 전 세션) + 빈 문자열 회차 행", func(t *testing.T, pool *pgxpool.Pool) {
			now := time.Now().UTC()
			plantRound(t, pool, roundFixture{id: "L", stream: "s", state: "ended", startedAt: now.Add(-time.Hour),
				endedAt: now.Add(-time.Minute), vodState: "frozen", vodKey: "vod/s/L.m3u8", vodFirst: 1, vodLast: 1})
			plantPDT(t, pool, "s", "L", 1, now.Add(-time.Hour), 4000)
			plantEdgeBoundsRow(t, pool, "s", "", time.Time{})
		}, "L", [3]PlaybackPhase{PhaseVOD, PhaseVOD, PhaseVOD}},
		{"같은 스트림 다른 회차 · 원장 행 회차", plantTwoRoundsWithOlderHeld, "R1",
			[3]PlaybackPhase{PhaseEnding, PhaseVOD, PhaseVOD}},
		{"같은 스트림 다른 회차 · 최신 회차", plantTwoRoundsWithOlderHeld, "R2",
			[3]PlaybackPhase{PhaseVOD, PhaseVOD, PhaseVOD}},
		{"within 밖 완결 + 원장 행", func(t *testing.T, pool *pgxpool.Pool) {
			plantFrozenRoundAt(t, pool, "s", "R", 1, 2*time.Hour) // 기동 적재 within 30분 밖
			plantEdgeBoundsRow(t, pool, "s", "R", time.Time{})
		}, "R", [3]PlaybackPhase{PhaseEnding, PhaseEnding, PhaseEnding}},
		{"within 밖 완결 · 행 없음(대조)", func(t *testing.T, pool *pgxpool.Pool) {
			plantFrozenRoundAt(t, pool, "s", "R", 1, 2*time.Hour)
		}, "R", [3]PlaybackPhase{PhaseVOD, PhaseVOD, none}},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := edgeBoundsPool(t)
			c.plant(t, pool)

			if got := readAllPhases(t, pool, "s", c.round); got != c.want {
				t.Errorf("%s 국면(회차 · 스트림 · 기동) = %v, want %v", c.round, got, c.want)
			}
		})
	}
}

// plantTwoRoundsWithOlderHeld 는 스트림 s 의 완결 회차 둘(R1 이 먼저 · R2 가 최신)과 R1 의 해제 전 원장 행이다.
func plantTwoRoundsWithOlderHeld(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	plantFrozenRoundAt(t, pool, "s", "R1", 1, 3*time.Hour)
	plantFrozenRoundAt(t, pool, "s", "R2", 2, time.Minute)
	plantEdgeBoundsRow(t, pool, "s", "R1", time.Time{})
}

// E3 앞 몫의 이음 — 유예가 지나 Writer 의 DeleteReleased 가 행을 지우면 같은 회차가 ending 에서 vod 로 넘어간다. 유예
// 안의 행은 DeleteReleased 가 지우지 않아 ending 이 남는다.
func TestRoundPlaybackTurnsVODAfterDeleteReleased(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantFrozenRound(t, pool, "s", "R")
	plantEdgeBoundsRow(t, pool, "s", "R", time.Now().UTC().Add(-30*time.Second))
	l := NewEdgeBoundsLedger(pool)
	ctx := context.Background()
	const settle = 120 * time.Second

	if n, err := l.DeleteReleased(ctx, settle); err != nil || n != 0 {
		t.Fatalf("유예 안 DeleteReleased = (%d, %v), want (0, nil)", n, err)
	}
	if got := readAllPhases(t, pool, "s", "R"); got != [3]PlaybackPhase{PhaseEnding, PhaseEnding, PhaseEnding} {
		t.Errorf("유예 안 국면 = %v, want ending 셋", got)
	}
	if _, err := pool.Exec(ctx, `UPDATE stream_edge_bounds SET released_at = pg_catalog.now() - interval '5 minutes'`); err != nil {
		t.Fatalf("해제 시각 되돌림 실패: %v", err)
	}
	if n, err := l.DeleteReleased(ctx, settle); err != nil || n != 1 {
		t.Fatalf("유예 밖 DeleteReleased = (%d, %v), want (1, nil)", n, err)
	}

	if got := readAllPhases(t, pool, "s", "R"); got != [3]PlaybackPhase{PhaseVOD, PhaseVOD, PhaseVOD} {
		t.Errorf("DeleteReleased 뒤 국면 = %v, want vod 셋", got)
	}
}

// 원장 줄은 live · unknown 보다 뒤다 — 열린 회차에 원장 행이 있는 것이 평시라(Writer 가 올림) live 는 그대로 live 다.
func TestRoundPlaybackKeepsLiveWithEdgeBoundsRow(t *testing.T) {
	pool := edgeBoundsPool(t)
	plantOpenRound(t, pool, "s", "R")
	if _, err := NewEdgeBoundsLedger(pool).Raise(context.Background(),
		[]edgebounds.Floor{{StreamID: "s", RoundID: "R", Value: 3}}); err != nil {
		t.Fatalf("Raise 실패: %v", err)
	}

	st, err := LookupRoundPlayback(context.Background(), pool, "s", "R")

	if err != nil || st.Phase != PhaseLive {
		t.Errorf("LookupRoundPlayback = (%+v, %v), want live", st, err)
	}
}
