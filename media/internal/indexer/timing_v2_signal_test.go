package indexer

// 판 2 섞기 신호의 방출 자리 시험(POK-196 PDT 생산자 c4b · 설계 r7 8절 · 계획 14절). store 가 InsertCommit 으로 돌려준 섞기
// 결과를 advance 가 행마다 한 번 낸다 — segment_indexed 의 견줌 속성 · timing_reset · timing_round_broken · pdt_new_chapter ·
// pdt_overlap. 판 NULL 행에서는 어느 것도 나가지 않는다.

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// mixStore 는 가짜 장부가 커밋한 결과에 seq 별 섞기 결과를 얹는다 — 섞기 식은 store(index) 의 몫이라 여기서는 결과만 꾸민다.
type mixStore struct {
	*fakeStore
	mixes map[int64]func(*index.InsertCommit)
}

func (s mixStore) Insert(ctx context.Context, r index.Record, seed index.Seed, src index.SessionSource) (index.InsertOutcome, index.InsertCommit, error) {
	out, res, err := s.fakeStore.Insert(ctx, r, seed, src)
	if fn := s.mixes[r.Seq]; fn != nil && err == nil && out == index.InsertInserted {
		fn(&res)
	}
	return out, res, err
}

var signalV2 = int16(2)

// v2Commit 은 판 2 행의 커밋 결과를 꾸민다 — 회차 R · 갈래 b · 견줌 차 Δ.
func v2Commit(b index.Branch, delta int64, edit func(*index.InsertCommit)) func(*index.InsertCommit) {
	return func(res *index.InsertCommit) {
		res.TimingVersion, res.TimingRoundID, res.TimingBranch, res.DeltaMS = &signalV2, "R", b, delta
		if edit != nil {
			edit(res)
		}
	}
}

// pdtMixSignals 는 판 2 섞기 신호 네 이름이다.
var pdtMixSignals = []string{"timing_reset", "timing_round_broken", "pdt_new_chapter", "pdt_overlap"}

// 섞기 갈래마다 신호 하나 — 회차 첫 행은 timing_reset(까닭 · q 끝 − w), 판 고정 위반은 timing_round_broken(Error · 회차 · 까닭 ·
// 두 판), 새 장은 pdt_new_chapter(Δ · 까닭 · 머리 트랙), 겹침은 pdt_overlap(Δ · 겹친 양 · 남은 양 · 이긴 항 · 까닭 — |Δ| 1초
// 미만 Info, 그 밖 Warn). join · continue 는 신호를 내지 않는다. 잡는 결함: 방출을 빠뜨리면 판 2 운영 판별 문자열(설계 8절
// 「거짓 새 장」 · 「하한 거부 끼어듦」)이 0 으로 보인다.
func TestV2MixSignalsPerBranch(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000, 4000, 4000)
	back := int64(1000)
	recX, recY := [16]byte{0x58}, [16]byte{0x59}
	n10 := int64(10)
	prevX := index.TimingFacts{Recorder: &recX, Number: &n10}
	prevY := index.TimingFacts{Recorder: &recY, Number: &n10}
	store := mixStore{fakeStore: f.store, mixes: map[int64]func(*index.InsertCommit){
		0: v2Commit(index.BranchRoundFirst, 0, func(r *index.InsertCommit) { r.TimingReset, r.TimingResetBackMS = "new_round", &back }),
		1: v2Commit(index.BranchJoin, 30, func(r *index.InsertCommit) { r.TimingCompared, r.TimingSameInstance = true, true }),
		2: v2Commit(index.BranchNewChapterGap, 500, func(r *index.InsertCommit) { r.TimingCompared, r.TimingPrevFacts = true, &prevX }),
		3: v2Commit(index.BranchNewChapterOverlap, -500, func(r *index.InsertCommit) {
			r.TimingCompared, r.TimingPrevFacts, r.TimingLimitedBy = true, &prevY, "w"
			r.PlaybackPDT = baseWall.Add(12 * time.Second)
		}),
		4: v2Commit(index.BranchNewChapterOverlap, -1500, func(r *index.InsertCommit) {
			r.TimingCompared, r.TimingPrevFacts, r.TimingLimitedBy = true, &prevX, "overlap_cap"
			r.PlaybackPDT = baseWall.Add(16*time.Second + 501*time.Millisecond)
		}),
		5: v2Commit(index.BranchRoundFirst, 0, func(r *index.InsertCommit) { r.TimingBroken = "version_mismatch" }),
	}}
	f.ix.store = store
	// 지금 조각은 녹화기 X 번호 12 — p(X · 10)와는 번호 건너뜀, p(Y · 10)와는 녹화기 바뀜이다. w = 파일명 시각(mtxi NTP).
	var wall time.Time
	number := uint64(12)
	f.ix.timingFn = func(string) (playback.SegmentTiming, error) {
		st := sampleTiming()
		st.Mtxi.RecorderID, st.Mtxi.SegmentNumber = recX, number
		st.Mtxi.NTP = wall
		st.Video = &playback.TrackSpan{Start: 0, End: 4 * time.Second}
		st.Head = playback.Track{ID: 1, Video: true}
		return st, nil
	}
	for i := range 6 {
		wall = baseWall.Add(time.Duration(i) * 4 * time.Second)
		f.mustHandle(f.segment("s1", segName(wall, 0), 1000, recording.ReasonNextFile))
	}

	if a := f.logs.attrsAll("timing_reset"); len(a) != 1 || a[0]["reason"] != "new_round" || a[0]["reset_back_ms"] != int64(1000) ||
		a[0]["seq"] != int64(0) || a[0]["round_id"] != "R" {
		t.Errorf("timing_reset = %v, want 한 건(seq 0 · round_id R · reason new_round · reset_back_ms 1000)", a)
	}
	if n := f.logs.count(slog.LevelError, "timing_round_broken"); n != 1 {
		t.Errorf("timing_round_broken(Error) = %d건, want 1", n)
	} else if a := f.logs.attrs("timing_round_broken"); a["seq"] != int64(5) || a["reason"] != "version_mismatch" ||
		a["round_id"] != "R" || a["timing_version"] != "2" || a["prev_version"] != "NULL" {
		t.Errorf("timing_round_broken 속성 = %v", a)
	}
	if a := f.logs.attrsAll("pdt_new_chapter"); len(a) != 1 || a[0]["seq"] != int64(2) || a[0]["delta_ms"] != int64(500) ||
		a[0]["reason"] != "number_skipped" || a[0]["head_track"] != "video" {
		t.Errorf("pdt_new_chapter = %v, want 한 건(seq 2 · delta_ms 500 · reason number_skipped · head_track video)", a)
	}
	overlaps := f.logs.attrsAll("pdt_overlap")
	if len(overlaps) != 2 || f.logs.count(slog.LevelInfo, "pdt_overlap") != 1 || f.logs.count(slog.LevelWarn, "pdt_overlap") != 1 {
		t.Fatalf("pdt_overlap = %v, want 두 건(|Δ| 500 Info · 1500 Warn)", overlaps)
	}
	// seq 3: w = 12000 · E = w − Δ = 12500 · PDT = 12000 → 겹친 양 500 · 남은 양(PDT − w) 0.
	if a := overlaps[0]; a["seq"] != int64(3) || a["delta_ms"] != int64(-500) || a["overlap_ms"] != int64(500) ||
		a["residual_ms"] != int64(0) || a["limited_by"] != "w" || a["reason"] != "instance_changed" {
		t.Errorf("pdt_overlap seq 3 = %v", a)
	}
	// seq 4: w = 16000 · E = 17500 · PDT = 16501(E − 999) → 겹친 양 999 · 남은 양 501.
	if a := overlaps[1]; a["seq"] != int64(4) || a["overlap_ms"] != int64(999) || a["residual_ms"] != int64(501) ||
		a["limited_by"] != "overlap_cap" || a["reason"] != "number_skipped" {
		t.Errorf("pdt_overlap seq 4 = %v", a)
	}

	indexed := f.logs.attrsAll("segment_indexed")
	if len(indexed) != 6 {
		t.Fatalf("segment_indexed = %d건, want 6", len(indexed))
	}
	if a := indexed[1]; a["comparable"] != true || a["same_instance"] != true || a["delta_ms"] != int64(30) || a["timing_branch"] != "join" {
		t.Errorf("segment_indexed seq 1 = %v, want comparable · same_instance 참 · delta_ms 30 · join", a)
	}
	if a := indexed[0]; a["comparable"] != false || a["same_instance"] != false {
		t.Errorf("segment_indexed seq 0 = %v, want comparable · same_instance 거짓", a)
	}
	if _, ok := indexed[0]["delta_ms"]; ok {
		t.Errorf("견주지 않은 행에 delta_ms 가 실렸다: %v", indexed[0])
	}
}

// 판 NULL 행은 섞기 신호도 segment_indexed 의 견줌 속성도 내지 않는다 — 견줌은 판 2 섞기가 정하는 값이다.
func TestLegacyRowEmitsNoMixSignals(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.store.scriptSessions("S-1", "S-1")
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))
	for _, name := range pdtMixSignals {
		if n := len(f.logs.attrsAll(name)); n != 0 {
			t.Errorf("판 NULL 행에서 %s = %d건, want 0", name, n)
		}
	}
	for _, a := range f.logs.attrsAll("segment_indexed") {
		for _, k := range []string{"comparable", "same_instance", "delta_ms"} {
			if _, ok := a[k]; ok {
				t.Errorf("판 NULL 행 segment_indexed 에 %s 가 실렸다: %v", k, a)
			}
		}
	}
}

// 새 장 · 겹침의 까닭(설계 8절 — new_recording · instance_changed · no_mtxi · number_skipped · in_chapter). MediaMTX 녹화기
// 인스턴스의 조각 번호는 0 부터다(recorder/format_fmp4_track.go nextSegmentNumber 영값) — 녹화기가 바뀌고 번호 0 이면 새 녹화다.
func TestChapterReason(t *testing.T) {
	recX, recY := [16]byte{0x58}, [16]byte{0x59}
	facts := func(rec *[16]byte, n int64) index.TimingFacts {
		if rec == nil {
			return index.TimingFacts{}
		}
		return index.TimingFacts{Recorder: rec, Number: &n}
	}
	for _, tc := range []struct {
		name      string
		prev, cur index.TimingFacts
		want      string
	}{
		{"p mtxi 없음", facts(nil, 0), facts(&recX, 11), "no_mtxi"},
		{"k mtxi 없음", facts(&recX, 10), facts(nil, 0), "no_mtxi"},
		{"새 녹화", facts(&recX, 10), facts(&recY, 0), "new_recording"},
		{"녹화기 바뀜 · 번호 0 아님", facts(&recX, 10), facts(&recY, 5), "instance_changed"},
		{"번호 건너뜀", facts(&recX, 10), facts(&recX, 12), "number_skipped"},
		{"같은 녹화기 이어짐", facts(&recX, 10), facts(&recX, 11), "in_chapter"},
	} {
		prev := tc.prev
		if got := chapterReason(&prev, tc.cur); got != tc.want {
			t.Errorf("%s: chapterReason = %q, want %q", tc.name, got, tc.want)
		}
	}
	if got := chapterReason(nil, facts(&recX, 11)); got != "no_mtxi" {
		t.Errorf("p 없음: chapterReason = %q, want no_mtxi", got)
	}
}
