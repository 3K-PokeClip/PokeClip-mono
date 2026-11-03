package indexer

// 커밋 직후 메모리 커서 = 장부 커서(POK-196 PDT 생산자 c5 · 계획 4절 c5 시험 5 · r6 259–262 승계) — 실물 세션 결정자 ·
// 장부 위에서 잰다. 위치 · 길이는 store 가 판 NULL 식으로 정해 InsertCommit 으로 돌려주고, 칸은 인덱서가 판독한 그대로
// 장부에 실린다. 그러므로 advance 가 넣은 꼬리와 LoadCursor 가 다시 읽은 꼬리가 바이트 단위로 같아야 한다.
//
// PG_DSN 미설정이면 skip 된다(REQUIRE_PG=1 이 실주행 게이트다).

import (
	"context"
	"reflect"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 귀속(송출 중 관측 — 회차 개시 · 판 NULL PDT 이어짐)과 비귀속(관측 없음 — PDT NULL · 바로 앞 행 끝) 두 갈래에서, 6초 drift
// (톨러런스 1.5초 넘음) 조각까지 넣은 뒤 메모리 커서를 장부 커서와 견준다. 칸 있음(같은 녹화기 · 번호 7 → 8 → 9)과 칸
// 없음(mtxi 없음) 두 갈래도 함께 돈다. 드리프트 행의 is_discontinuity 는 칸이 있으면 거짓(같은 녹화기 이어짐 — 파일명
// 도약은 보지 않는다), 칸이 없으면 drift 폴백으로 참이다(c6 · r6 2.8 · I15 「+5001ms 도약 → 거짓」).
// 잡는 결함: advance 가 rec 값으로 전진하거나(drift 가산 위치 · mvhd 길이), 칸을 꼬리에 넣지 않으면 갈라진다.
func TestCommittedCursorEqualsLedgerCursorPG(t *testing.T) {
	// 비귀속 갈래는 방증 없는 재스캔 유입이다 — 세션 결정이 「지금 회차만」(SessionCurrentOnly)이라 회차가 열리지 않는다.
	type branch struct {
		name       string
		publishing bool
		reason     recording.CompletionReason
		marks      bool
	}
	var branches []branch
	for _, b := range []branch{{"귀속", true, recording.ReasonNextFile, false}, {"비귀속", false, recording.ReasonScan, false}} {
		withMarks, noMarks := b, b
		withMarks.name, withMarks.marks = b.name+"/칸 있음", true
		noMarks.name = b.name + "/칸 없음"
		branches = append(branches, withMarks, noMarks)
	}
	for _, tt := range branches {
		t.Run(tt.name, func(t *testing.T) {
			db := "cursor-" + map[bool]string{true: "attr", false: "unattr"}[tt.publishing] + map[bool]string{true: "-marks", false: ""}[tt.marks]
			f := newPhaseFixture(t, db, phase{watcher: true})
			if tt.publishing {
				f.publishing(0, 2*time.Minute)
			}
			number := uint64(7)
			f.ix.timingFn = func(string) (playback.SegmentTiming, error) {
				if !tt.marks {
					return playback.SegmentTiming{}, nil
				}
				st := sampleTiming()
				st.Mtxi.SegmentNumber = number
				number++
				return st, nil
			}
			ctx := context.Background()
			for _, ago := range []time.Duration{60 * time.Second, 56 * time.Second, 46 * time.Second} { // 셋째는 drift +6초
				if err := f.ix.Handle(ctx, f.segment(ago, tt.reason)); err != nil {
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
			var discont bool
			var attributed int
			var start int64
			if err := f.pool.QueryRow(ctx, `
SELECT (SELECT is_discontinuity FROM stream_segments WHERE stream_id = $1 AND seq = 2),
       (SELECT start_pts_ms FROM stream_segments WHERE stream_id = $1 AND seq = 2),
       (SELECT count(*) FROM stream_segments WHERE stream_id = $1 AND session_id IS NOT NULL)`,
				f.stream).Scan(&discont, &start, &attributed); err != nil {
				t.Fatalf("장부 조회 실패: %v", err)
			}
			if want := !tt.marks; discont != want {
				t.Errorf("6초 drift 행의 is_discontinuity = %v, want %v(칸 있음이면 같은 녹화기 이어짐 · 없으면 drift 폴백)", discont, want)
			}
			// 귀속 갈래가 실제로 귀속(판 NULL PDT 이어짐 · I23 앵커 위치)을 지났는지 — 아니면 두 갈래가 같은 식만 잰다.
			if want := map[bool]int{true: 3, false: 0}[tt.publishing]; attributed != want {
				t.Fatalf("귀속 행 = %d, want %d", attributed, want)
			}
			// 위치는 store 식이다 — 비귀속은 바로 앞 행 끝(8000), 귀속은 앵커 폭(⌊PDT⌋ − 위치)을 이어 drift 만큼 건너뛴다(약
			// 14000 — 벽시계의 ms 아래 자리만큼 흔들린다).
			if tt.publishing && (start < 13900 || start > 14100) || !tt.publishing && start != 8000 {
				t.Errorf("drift 행 위치 = %d, want 귀속 약 14000 · 비귀속 8000", start)
			}
		})
	}
}
