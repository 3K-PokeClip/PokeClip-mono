package indexer

// 판 2 행의 커밋 직후 메모리 커서 = 장부 커서(POK-196 PDT 생산자 c4b · 계획 4절 c4b 시험 4 · r6 259–262 승계 · cx H2) — 실물
// 세션 결정자 · 장부 위에서 잰다. 판 2 겹침 입력은 바로 앞 행 끝(4000ms)이 아니라 섞기가 정한 3500ms 에 놓이고, 길이는
// mvhd(4010)가 아니라 영상 길이(4000)다 — Record 는 위치 · 길이의 입력이 아니므로 advance 가 커밋 값으로 전진해야 메모리
// 꼬리와 재적재 꼬리가 같다. PG_DSN 미설정이면 skip 된다(REQUIRE_PG=1 이 실주행 게이트다).

import (
	"context"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// roundDecider 는 phaseDecider 에 회차 칸 두 개(RoundID · FloorRejected)를 더 옮긴다 — 조립 지점(cmd/segment-indexer
// session_wire.go)의 변환과 같다. phaseDecider 는 두 칸을 옮기지 않아 이어 가는 행의 회차가 비고, 판 2 에서는 행마다 회차
// 첫 행이 된다.
type roundDecider struct{ phaseDecider }

func (d roundDecider) Decide(ctx context.Context, tx pgx.Tx, in index.SessionInput, now time.Time) (index.SessionDecision, error) {
	dec, err := d.phaseDecider.Decide(ctx, tx, in, now)
	if err != nil {
		return dec, err
	}
	plan := dec.Plan.(session.Decision)
	dec.RoundID, dec.FloorRejected = plan.RoundID, plan.FloorRejected
	return dec, nil
}

// newV2Fixture 는 게이트 스위치 2 인 장부 위의 국면 픽스처다(mvhd 4010ms). 끝나면 장부를 비우고 스위치 · 래치를 내린다 — 이
// 패키지의 다른 PG 시험은 게이트 NULL 을 전제한다(resetPhaseTables 는 게이트를 건드리지 않는다).
func newV2Fixture(t *testing.T, name string) *phaseFixture {
	t.Helper()
	pool := pgtest.Pool(t, "indexer", index.EnsureSchema, resetPhaseTables)
	t.Cleanup(func() {
		if err := resetPhaseTables(context.Background(), pool); err != nil {
			t.Errorf("장부 비우기 실패: %v", err)
		}
		if _, err := pool.Exec(context.Background(), `UPDATE stream_timing_gate SET new_round_version = NULL, highest_version = NULL`); err != nil {
			t.Errorf("게이트 되돌리기 실패: %v", err)
		}
	})
	if _, err := pool.Exec(context.Background(), `UPDATE stream_timing_gate SET new_round_version = 2, changed_at = now(), changed_by = 'test'`); err != nil {
		t.Fatalf("게이트 스위치 2 실패: %v", err)
	}
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second, ReconnectWindow: 300 * time.Second})
	f := &phaseFixture{
		t: t, pool: pool, root: t.TempDir(), stream: "phase-" + name,
		observer: newObserver(), logs: &logCapture{}, adopt: &fakeAdopter{},
		degraded: phase{watcher: true},
		store:    index.NewPGStore(pool, roundDecider{phaseDecider{reg: reg}}, playback.SegKey),
	}
	f.rebuild(4010)
	return f
}

// k1(녹화기 A · 번호 7 · 영상 [28초, 32초)) 다음 k2(A · 8 · 맞닿음 · 영상 머리 · w = k1 끝 − 500ms) — k2 는 겹침 갈래로
// PDT = start = 3500 이다. 조각마다 Handle 뒤 메모리 꼬리를 LoadCursor 꼬리와 견준다. 잡는 결함: m-R7(advance 가 rec 값으로
// 전진 — 메모리 꼬리가 위치 0 · 길이 4010 으로 장부와 갈린다).
func TestV2CommittedCursorEqualsLedgerCursorPG(t *testing.T) {
	f := newV2Fixture(t, "v2-cursor")
	f.publishing(0, 2*time.Minute)
	t0 := time.Now().UTC().Add(-time.Minute).Truncate(time.Millisecond)
	ntp := []time.Time{t0, t0.Add(3500 * time.Millisecond)}
	number := uint64(7)
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) {
		i := number - 7
		st := playback.SegmentTiming{
			Mtxi:  &playback.Mtxi{RecorderID: recA, SegmentNumber: number, DTS: time.Duration(number) * 4 * time.Second, NTP: ntp[i]},
			Video: &playback.TrackSpan{Start: 0, End: 4 * time.Second},
			Audio: &playback.TrackSpan{Start: 0, End: 4 * time.Second},
			Head:  playback.Track{ID: 1, Video: true},
		}
		number++
		return st, nil
	}
	ctx := context.Background()
	for _, ago := range []time.Duration{60 * time.Second, 56 * time.Second} {
		if err := f.ix.Handle(ctx, f.segment(ago, recording.ReasonNextFile)); err != nil {
			t.Fatalf("Handle 실패: %v", err)
		}
		ledger, err := f.store.LoadCursor(ctx, f.stream)
		if err != nil {
			t.Fatalf("LoadCursor 실패: %v", err)
		}
		mem := f.ix.cursors[f.stream]
		if mem == nil || mem.Tail == nil || ledger.Tail == nil {
			t.Fatalf("꼬리 없음 — 메모리 %+v · 장부 %+v", mem, ledger)
		}
		if !reflect.DeepEqual(*mem.Tail, *ledger.Tail) || mem.NextSeq != ledger.NextSeq {
			t.Fatalf("%v 전 조각 뒤 메모리 꼬리 = %+v\n장부 꼬리 = %+v", ago, *mem.Tail, *ledger.Tail)
		}
	}
	var (
		version    *int16
		start      int64
		dur        int32
		pdt, first time.Time
	)
	if err := f.pool.QueryRow(ctx, `
SELECT k.timing_version, k.start_pts_ms, k.duration_ms, k.playback_pdt,
       (SELECT playback_pdt FROM stream_segments WHERE stream_id = $1 AND seq = 0)
  FROM stream_segments k WHERE k.stream_id = $1 AND k.seq = 1`, f.stream).Scan(&version, &start, &dur, &pdt, &first); err != nil {
		t.Fatalf("장부 조회 실패: %v", err)
	}
	if version == nil || *version != 2 || start != 3500 || dur != 4000 || pdt.Sub(first) != 3500*time.Millisecond {
		t.Errorf("k2 (판, start, dur, PDT − PDT(k1)) = (%v, %d, %d, %v), want (2, 3500, 4000, 3.5s — 겹침 갈래)", version, start, dur, pdt.Sub(first))
	}
}
