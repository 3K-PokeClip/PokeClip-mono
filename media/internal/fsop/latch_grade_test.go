package fsop

import (
	"context"
	"log/slog"
	"testing"
	"time"
)

// recordingHandler 는 발화된 레코드를 그대로 모은다 — 등급·값을 재기 위한 최소 핸들러.
type recordingHandler struct{ sink *[]slog.Record }

func (h *recordingHandler) Enabled(context.Context, slog.Level) bool { return true }
func (h *recordingHandler) Handle(_ context.Context, r slog.Record) error {
	*h.sink = append(*h.sink, r)
	return nil
}
func (h *recordingHandler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h *recordingHandler) WithGroup(string) slog.Handler      { return h }

// fs_degraded 게이지의 set(value=1)과 clear(value=0)는 같은 Error 등급이어야 한다.
//
// 잡는 결함: clear 만 Info 로 내려가면 LOG_LEVEL=warn 이상에서 clear 가 걸러져, 로그로
// 게이지를 읽는 쪽에는 복구 뒤에도 1 이 고정된 것처럼 보인다(PR #153 리뷰 지적).
func TestLatchGaugeSetAndClearShareErrorGrade(t *testing.T) {
	var recs []slog.Record
	l := NewLatch(slog.New(&recordingHandler{sink: &recs}))

	l.Trip("/p", "measure")
	if !l.Reset(t.TempDir(), time.Second) {
		t.Fatal("응답하는 FS 에서 Reset 이 해제하지 못했다")
	}

	type gauge struct {
		level slog.Level
		value int64
	}
	var got []gauge
	for _, r := range recs {
		if r.Message != "fs_degraded" {
			continue
		}
		g := gauge{level: r.Level, value: -1}
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "value" {
				g.value = a.Value.Int64()
			}
			return true
		})
		got = append(got, g)
	}

	want := []gauge{{slog.LevelError, 1}, {slog.LevelError, 0}}
	if len(got) != len(want) {
		t.Fatalf("fs_degraded 발화 %d건 (set·clear 2건 기대): %+v", len(got), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("fs_degraded[%d] = %+v, 기대 %+v — set/clear 등급이 갈리면 warn 필터에서 clear 만 사라진다", i, got[i], want[i])
		}
	}
}
