package janitor

import (
	"context"
	"log/slog"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// gauge 는 켜질 때(value=1)와 꺼질 때(value=0) 한 번씩 같은 등급으로 내는 상태 신호다. clear 만
// 등급이 낮으면 LOG_LEVEL=warn 수집에서 회복이 걸러져 장애로 고착된다(D32 — ingestauth 관례).
type gauge struct {
	log   *slog.Logger
	name  string
	level slog.Level
	on    bool
}

func (g *gauge) emit(value int, attrs []any) {
	g.log.Log(context.Background(), g.level, g.name, append([]any{"value", value}, attrs...)...)
}

// set 은 꺼져 있으면 켜고 한 줄 낸다.
func (g *gauge) set(attrs ...any) {
	if g.on {
		return
	}
	g.on = true
	g.emit(1, attrs)
}

// clear 는 켜져 있으면 끄고 한 줄 낸다.
func (g *gauge) clear(attrs ...any) {
	if !g.on {
		return
	}
	g.on = false
	g.emit(0, attrs)
}

// gauges 는 janitor 게이지 8종이다(설계 8절 · abandoned_local_retained 는 M6 7절 X5). cutoff_invalid 만 스트림별이다.
type gauges struct {
	log            *slog.Logger
	deleteFailing  *gauge
	cycleFailing   *gauge
	backlogCapped  *gauge
	volumeSuspect  *gauge
	fsSuspended    *gauge
	reclaimBlocked *gauge
	// abandonedRetained 는 ③ 포기 보존 기간이 지났는데 ② 가 안 올라 지울 수 없는 행이 있다는 신호다.
	abandonedRetained *gauge
	cutoffInvalid     map[string]bool // 켜진 스트림
}

const (
	cutoffInvalidName = "janitor_cutoff_invalid"
	// abandonedRetainedName · abandonedReclaimedName 은 설계 M6 7절 X5 의 신호 이름 그대로다(janitor_ 접두 없음).
	abandonedRetainedName  = "abandoned_local_retained"
	abandonedReclaimedName = "local_abandoned_reclaimed"
)

func newGauges(log *slog.Logger) *gauges {
	mk := func(name string, level slog.Level) *gauge { return &gauge{log: log, name: name, level: level} }
	return &gauges{
		log:            log,
		deleteFailing:  mk("janitor_delete_failing", slog.LevelError),
		cycleFailing:   mk("janitor_cycle_failing", slog.LevelError),
		backlogCapped:  mk("janitor_backlog_capped", slog.LevelWarn),
		volumeSuspect:  mk("janitor_volume_suspect", slog.LevelError),
		fsSuspended:    mk("janitor_fs_suspended", slog.LevelError),
		reclaimBlocked: mk("janitor_reclaim_blocked", slog.LevelError),
		// WARN — 정리기는 기다릴 뿐 장애는 아니고, ② 정체 자체는 업로더 신호가 ERROR 로 낸다.
		abandonedRetained: mk(abandonedRetainedName, slog.LevelWarn),
		cutoffInvalid:     make(map[string]bool),
	}
}

// startDump 는 시작 덤프다(시작 덤프 소비 계약 — 설계 8절). 다른 어떤 게이지 줄보다 먼저 8종을
// value=0 reason=startup 으로 낸다. cutoff_invalid 는 stream_id="*" 하나로 낸다 — 「이 run_id 이전의
// 모든 스트림 값을 0으로」라는 뜻이다. 현재 무효 스트림의 value=1 은 첫 주기의 무효 컷오프 신호가
// 낸다. 모든 줄의 run_id 는 로거가 싣는다.
func (g *gauges) startDump() {
	for _, x := range []*gauge{g.deleteFailing, g.cycleFailing, g.backlogCapped, g.volumeSuspect, g.fsSuspended, g.reclaimBlocked, g.abandonedRetained} {
		x.emit(0, []any{"reason", "startup"})
	}
	g.log.Error(cutoffInvalidName, "value", 0, "reason", "startup", "stream_id", "*")
}

// cutoffs 는 현재 무효 컷오프 목록으로 스트림별 게이지를 맞춘다.
func (g *gauges) cutoffs(cuts []index.InvalidCutoff) {
	now := make(map[string]bool, len(cuts))
	for _, c := range cuts {
		now[c.StreamID] = true
		if !g.cutoffInvalid[c.StreamID] {
			g.cutoffInvalid[c.StreamID] = true
			g.log.Error(cutoffInvalidName, "value", 1, "stream_id", c.StreamID, "cutoff_seq", c.CutoffSeq,
				"note", "컷오프가 가리키는 행이 장부에 없다 — 이 스트림의 로컬 삭제를 멈춘다")
		}
	}
	for id := range g.cutoffInvalid {
		if !now[id] {
			delete(g.cutoffInvalid, id)
			g.log.Error(cutoffInvalidName, "value", 0, "stream_id", id)
		}
	}
}

// backlog 은 상한에 닿고 남은 행이 있었는지로 backlog_capped 를 맞춘다.
func (g *gauges) backlog(capped bool, examined int) {
	if capped {
		g.backlogCapped.set("examined", examined)
		return
	}
	g.backlogCapped.clear()
}
