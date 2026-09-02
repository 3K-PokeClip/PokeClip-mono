package main

// 세션 결정자 어댑터의 단위 검증(POK-195 M3 — 계획 4.6 조립 지점).
//
// 이 어댑터는 두 패키지의 타입을 잇는 얇은 층이라 로직이 없다시피 하지만, **매핑이
// 어긋나면 조용히 다른 갈래로 흐른다** — 스캔 유입이 OpenOrCurrent 로 접히면 옛 잔존물이
// 세션을 연다. 그래서 값 대조를 여기서 못 박는다.

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

func TestSessionOpMapsOneToOne(t *testing.T) {
	for _, c := range []struct {
		in   index.SessionOp
		want session.Op
	}{
		{index.SessionOpenOrCurrent, session.OpenOrCurrent},
		{index.SessionCurrentOrOpenIfCorroborated, session.CurrentOrOpenIfCorroborated},
		{index.SessionCurrentOnly, session.CurrentOnly},
	} {
		got, err := sessionOpOf(c.in)
		if err != nil {
			t.Fatalf("연산 %d 매핑 실패: %v", c.in, err)
		}
		if got != c.want {
			t.Errorf("연산 %d → %d, want %d", c.in, got, c.want)
		}
	}
}

// 영값은 "안 채웠다"는 사고 신호다 — 조용히 어느 갈래로 접히면 배선 누락이 새어 나간다.
func TestSessionOpRejectsUnspecified(t *testing.T) {
	if got, err := sessionOpOf(0); err == nil {
		t.Fatalf("미지정 연산이 %d 로 통과했다", got)
	}
}

// Open 은 자기가 Decide 에서 만든 불투명 값만 받는다 — 다른 결정의 값으로 세션을 열면
// 그 조각이 아닌 조각의 규칙으로 세션 행이 굳는다.
func TestSessionWireOpenRejectsForeignPlan(t *testing.T) {
	d := sessionDecider{reg: session.New(session.Options{})}
	_, err := d.Open(context.Background(), nil,
		index.SessionDecision{Opens: true, Plan: "남의 계획"}, time.Now())
	if err == nil {
		t.Fatal("남의 불투명 값으로 세션 개시가 시도됐다")
	}
}

// ── 종단 검증(실물 레지스트리 + 실 PG) ────────────────────────────────────────
//
// 위 단위 케이스는 연산 매핑과 Open 가드만 잰다. **필드 사상 자체**(Opens·SessionID·
// BaseSessionID·Plan 왕복과 Observation 5필드 복사)는 값이 DB 까지 흘러야 드러나므로
// 여기서 종단으로 잰다 — 이 어댑터가 조립 지점의 유일한 이음매이고, 한 필드만 빠뜨려도
// 프로덕션에서 기저항이 사라져 세션 경계마다 PDT 가 끊긴다(설계 5.1.1 · M4 S7 위반).

// TestMain 은 릴리스 게이트용 스위치다(index·session 과 같은 이디엄).
// PG_DSN 이 없으면 아래 케이스는 skip 되는데 skip 은 성공으로 집계된다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 어댑터 종단 케이스가 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

func newWireTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "cmd", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx,
			"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
		return err
	})
}

var wireBase = time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)

// 개시 갈래에서 **직전 세션이 기저 세션으로 실려 나오고**, Plan 왕복으로 Open 이 성공한다.
// BaseSessionID 가 빈 값으로 새면 기저항이 언제나 부재가 되어 세션 경계 PDT 연속성이 죽는다.
func TestSessionDeciderCarriesBaseSessionAndPlanEndToEnd(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	stream := "wirestream"
	prev := stream + "-prev"
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt)
		VALUES ($1, $2, $3, 'ended', $3)`, prev, stream, wireBase); err != nil {
		t.Fatalf("직전 세션 픽스처 실패: %v", err)
	}

	d := newSessionDecider(time.Second, 30*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
	wall := wireBase.Add(time.Minute)
	firstPDT := wall.Add(500 * time.Millisecond)

	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	dec, err := d.Decide(ctx, tx, index.SessionInput{
		StreamID: stream, Seq: 7, StartWallUTC: wall, DurationMS: 7600,
		SessionSource: index.SessionSource{Op: index.SessionOpenOrCurrent},
	}, wall)
	if err != nil {
		t.Fatalf("Decide 실패: %v", err)
	}
	if !dec.Opens {
		t.Fatalf("live 부재인데 개시 갈래가 아니다: %+v", dec)
	}
	if dec.SessionID != "" {
		t.Errorf("개시 갈래인데 귀속 세션이 실렸다: %q", dec.SessionID)
	}
	if dec.BaseSessionID != prev {
		t.Fatalf("BaseSessionID = %q, want %q — 기저 세션이 어댑터에서 유실됐다", dec.BaseSessionID, prev)
	}

	id, err := d.Open(ctx, tx, dec, firstPDT)
	if err != nil {
		t.Fatalf("Plan 왕복이 깨져 Open 이 실패했다: %v", err)
	}
	if id == "" {
		t.Fatal("Open 이 빈 session_id 를 돌려줬다")
	}

	var startedAt, gotPDT time.Time
	var td int32
	if err := tx.QueryRow(ctx,
		`SELECT started_at, first_pdt, target_duration FROM stream_sessions WHERE session_id = $1`, id).
		Scan(&startedAt, &gotPDT, &td); err != nil {
		t.Fatalf("개시 세션 조회 실패: %v", err)
	}
	if !startedAt.UTC().Equal(wall) {
		t.Errorf("started_at = %v, want %v(개시 행 벽시계)", startedAt.UTC(), wall)
	}
	if !gotPDT.UTC().Equal(firstPDT) {
		t.Errorf("first_pdt = %v, want %v(인자 그대로)", gotPDT.UTC(), firstPDT)
	}
	if td != 8 {
		t.Errorf("target_duration = %d, want 8 — DurationMS 가 어댑터에서 유실됐다", td)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("커밋 실패: %v", err)
	}
}

// Observation 5필드가 그대로 건너간다. 각 필드는 그것이 빠졌을 때 갈래가 뒤집히는
// 국면으로 잰다 — 긍정 케이스가 Publishing·EpochKnown·ObservedAt 을,
// 모호 구간과 하한 밖의 짝이 EpochStartedAt·EpochSlack 을 가른다.
func TestSessionDeciderCopiesObservationFields(t *testing.T) {
	wall := wireBase
	fresh := func() index.SessionObservation {
		return index.SessionObservation{
			Publishing: true, EpochKnown: true,
			ObservedAt: wall, EpochStartedAt: wall.Add(-time.Second),
		}
	}

	for _, c := range []struct {
		name         string
		mutate       func(*index.SessionObservation)
		wantOpens    bool
		wantAmbigMsg bool
	}{
		{"자격_충족이면_연다", func(*index.SessionObservation) {}, true, false},
		{"Publishing_거짓", func(o *index.SessionObservation) { o.Publishing = false }, false, false},
		{"EpochKnown_거짓", func(o *index.SessionObservation) { o.EpochKnown = false }, false, false},
		{"ObservedAt_낡음", func(o *index.SessionObservation) { o.ObservedAt = wall.Add(-31 * time.Second) }, false, false},
		// 에폭이 조각보다 뒤이고 여유가 0 — 하한 밖이라 조용히 접힌다.
		{"EpochStartedAt_하한_밖", func(o *index.SessionObservation) { o.EpochStartedAt = wall.Add(5 * time.Second) }, false, false},
		// 같은 에폭에 여유 10초 — 이번엔 모호 구간이라 신호가 뜬다(여유가 실려야 갈린다).
		{"EpochSlack_모호_구간", func(o *index.SessionObservation) {
			o.EpochStartedAt = wall.Add(5 * time.Second)
			o.EpochSlack = 10 * time.Second
		}, false, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newWireTestPool(t)
			ctx := context.Background()
			var logs bytes.Buffer
			d := newSessionDecider(time.Second, 30*time.Second, slog.New(slog.NewTextHandler(&logs, nil)))

			obs := fresh()
			c.mutate(&obs)

			tx, err := pool.Begin(ctx)
			if err != nil {
				t.Fatalf("트랜잭션 시작 실패: %v", err)
			}
			defer func() { _ = tx.Rollback(ctx) }()

			dec, err := d.Decide(ctx, tx, index.SessionInput{
				StreamID: "obsstream", Seq: 0, StartWallUTC: wall, DurationMS: 4000,
				SessionSource: index.SessionSource{Op: index.SessionCurrentOrOpenIfCorroborated, Obs: obs},
			}, wall)
			if err != nil {
				t.Fatalf("Decide 실패: %v", err)
			}
			if dec.Opens != c.wantOpens {
				t.Fatalf("Opens = %v, want %v — 관측 필드가 어댑터에서 유실됐다(%+v)", dec.Opens, c.wantOpens, obs)
			}
			if got := strings.Contains(logs.String(), "session_epoch_ambiguous"); got != c.wantAmbigMsg {
				t.Fatalf("모호 구간 신호 = %v, want %v — 에폭 여유가 전달되지 않았다\n%s", got, c.wantAmbigMsg, logs.String())
			}
		})
	}
}
