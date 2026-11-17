package indexer

// PDT 판 신호 두 개의 방출 자리 시험(POK-196 PDT 생산자 c4a · 계획 14절). store(pgStore)에는 로거가 없어 판 정하기의
// 결과는 Insert 의 오류 · InsertCommit 으로 돌아오고 인덱서가 신호를 낸다.

import (
	"errors"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 쓰기 시점 판 검사 거부(timing_version_unsupported · Error)는 시도마다 남고, 처분은 H9 재시도다 — PgError 가 아니라
// 재시도로 접히고(classifyInsertError), 상한을 다 쓰면 기존 D8 처분(오류 → 종료 → 재기동)에 합류한다. 잡는 결함:
// 신호가 없으면 판 2 요구로 색인이 멈춘 까닭이 insert_retry 한 줄로만 보인다.
func TestTimingVersionUnsupportedSignalsAndRetries(t *testing.T) {
	f := newFixture(t, 4000)
	refused := fmt.Errorf("stream_id=%q seq=0: %w: timing_version=2", "s1", index.ErrTimingVersionUnsupported)
	f.store.insertErrs = []error{refused}

	if err := f.handle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)); err != nil {
		t.Fatalf("한 번 거부 뒤 재시도가 오류로 올라왔다: %v", err)
	}
	if n := f.logs.count(slog.LevelError, "timing_version_unsupported"); n != 1 {
		t.Errorf("timing_version_unsupported = %d건, want 1", n)
	}
	if got := len(f.store.records("s1")); got != 1 {
		t.Errorf("재시도 뒤 행 수 = %d, want 1", got)
	}

	g := newFixture(t, 4000)
	errs := make([]error, g.opt.InsertRetryMax)
	for i := range errs {
		errs[i] = refused
	}
	g.store.insertErrs = errs
	err := g.handle(g.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	if !errors.Is(err, index.ErrTimingVersionUnsupported) {
		t.Fatalf("상한 소진 오류 = %v, want ErrTimingVersionUnsupported 연쇄", err)
	}
	if n := g.logs.count(slog.LevelError, "timing_version_unsupported"); n != g.opt.InsertRetryMax {
		t.Errorf("timing_version_unsupported = %d건, want %d(시도마다)", n, g.opt.InsertRetryMax)
	}
}

// 새 회차 개시 행은 판을 고른 게이트를 신호 timing_gate_read(Info — 회차 · 고른 판 · 게이트 changed_at)로 남긴다. 게이트를
// 읽지 않은 행(회차를 이어 가는 행)과 개시 아닌 행은 남기지 않는다.
func TestTimingGateReadSignalsOnRoundOpen(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	f.store.scriptSessions("S-1", "S-1")
	changed := baseWall.Add(-time.Hour)
	f.store.gateRead = &index.TimingGate{ChangedAt: changed}

	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile))
	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile))

	if n := f.logs.count(slog.LevelInfo, "timing_gate_read"); n != 1 {
		t.Fatalf("timing_gate_read = %d건, want 1(개시 행 하나)", n)
	}
	attrs := f.logs.attrs("timing_gate_read")
	if attrs["round_id"] != "S-1" || attrs["timing_version"] != "NULL" || attrs["gate_changed_at"] != changed {
		t.Errorf("timing_gate_read 속성 = %v, want round_id S-1 · timing_version NULL · gate_changed_at %v", attrs, changed)
	}
}
