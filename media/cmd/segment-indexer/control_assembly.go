package main

// 되감기 제어면의 조립 — 상태 몫(Board 손잡이 · 받은편지함 · 재기동 적재)과 서버(startControl)다(설계 M5 2.1 · 3.3 · 3.7 ·
// 보완 2 ㉠). run 이 assemblePublishing 앞에서 상태 몫을, holdTicker 뒤에서 서버를 조립한다. 루프가 Board 를 갱신하는
// 흐름은 rewind_status.go 다.

import (
	"context"
	"log/slog"
	"maps"
	"slices"
	"sync"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// finalsInbox 는 M6 상태 전달의 받은편지함이다 — Post 는 index.PlaybackSink 이고 여러 고루틴이 막히지 않고 부른다.
// 같은 스트림은 마지막 값만 남는다(last-value-wins — 자라지 않는다). 루프가 holdTick 에 Drain 으로 통째로 가져간다.
type finalsInbox struct {
	mu sync.Mutex
	m  map[string]index.PlaybackState
}

func newFinalsInbox() *finalsInbox { return &finalsInbox{m: map[string]index.PlaybackState{}} }

// Post 는 streamID 의 최신 상태 st 를 넣는다.
func (b *finalsInbox) Post(streamID string, st index.PlaybackState) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.m[streamID] = st
}

// Drain 은 쌓인 값을 통째로 가져가고 비운다.
func (b *finalsInbox) Drain() map[string]index.PlaybackState {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := b.m
	b.m = make(map[string]index.PlaybackState, len(out))
	return out
}

// statusSeed 는 재기동 적재다 — 스트림 id 를 키로 최신 회차 상태를 돌려준다. ②a 의 main.go 는 nil(적재 없음)이고 M6 가
// index.LoadPlaybackStates 로 채운다(계획 4.2 seed 칸 · round 와 같은 꼴).
type statusSeed func(ctx context.Context) (map[string]index.PlaybackState, error)

// assembleStatus 는 r 에 상태 몫을 채우고 Board 손잡이를 돌려준다. run 이 assemblePublishing 앞에서 한 번 부른다 —
// 그래서 꺼짐 · 강등 · 정상 세 상태 모두 받은편지함이 있다(종결 정정 cc M3 · R59 ③).
//
//	꺼짐(S3_BUCKET 빈 값)  DisabledBoard 를 한 번 넣고 루프는 Board 를 갱신하지 않는다(모든 스트림 404)
//	그 밖(강등 포함)       첫 스냅숏 전(nil — 503)이고 루프가 매 holdTick 갱신한다. 강등이어도 정상 Board 다(보완 2 ㉢)
//
// 재기동 적재는 그 뒤 seed 로 한다. nil 이면 INFO rewind_final_seeded count=0, 오류면 WARN rewind_final_seed_failed 를 남기고
// 적재 없이 기동을 잇는다(R32). 성공이면 받은편지함에 Post 하고 첫 holdTick 의 Drain 이 반영한다.
func assembleStatus(ctx context.Context, cfg config.Config, r *rewindLoop, seed statusSeed) *status.Holder {
	r.board, r.finals, r.final, r.baseURL = &status.Holder{}, newFinalsInbox(), map[string]status.Final{}, cfg.RewindPublicBaseURL
	if cfg.S3.Bucket == "" {
		r.board.Store(status.DisabledBoard())
		r.statusOff = true
	}
	if seed == nil {
		r.log.Info("rewind_final_seeded", "count", 0, "note", "재기동 적재 없음 — 끝난 스트림의 종료 국면은 다음 상태 전달부터다")
		return r.board
	}
	seedCtx, cancel := context.WithTimeout(ctx, dbStepTimeout)
	defer cancel()
	states, err := seed(seedCtx)
	if err != nil {
		r.log.Warn("rewind_final_seed_failed", "err", err, "note", "재기동 적재 없이 기동한다 — 캐시가 모르는 끝난 스트림은 스트림 경로 404 다")
		return r.board
	}
	for _, s := range slices.Sorted(maps.Keys(states)) { // Post 차례를 실행마다 같게
		r.finals.Post(s, states[s])
	}
	r.log.Info("rewind_final_seeded", "count", len(states))
	return r.board
}

// startControl 은 제어면 서버를 조립해 띄우고 멈추는 함수를 돌려준다(보완 2 ㉠). Limiter 를 하나 만들어 두 처리기에 주고,
// 스트림 경로는 늘, 회차 경로는 round 가 있을 때만 등록한다(②a 의 main.go 는 nil — 미등록 404 · M6 가 채운다). extra
// (POK-472 end-signal 등)는 완성된 Route 그대로 등록하고 한도를 쓰지 않는다.
//
// CONTROL_LISTEN 이 비면 띄우지 않는다(INFO control_server_disabled). 듣기에 실패하면 강등이다 — ERROR control_server_failed
// stage=bind 한 줄을 남기고 아무것도 하지 않는 stop 을 돌려준다. 인덱싱 · 업로드 · 발행은 산다(3.7절 · ADR-063 결정 1).
// run 은 stop 을 defer 로 rewind.stop · 풀 Close 보다 뒤에 등록한다 — LIFO 라 서버가 먼저 멈춘다.
func startControl(cfg config.Config, board *status.Holder, log *slog.Logger, round control.RoundLookup, extra ...control.Route) (stop func()) {
	noop := func() {}
	c := cfg.Control
	if c.Listen == "" {
		log.Info("control_server_disabled", "note", "CONTROL_LISTEN 이 비어 있다 — 되감기 상태 제어면을 띄우지 않는다")
		return noop
	}
	opt := control.Options{
		Listen: c.Listen, AllowOrigin: c.AllowOrigin, Log: log,
		Limiter: control.NewLimiter(c.StreamRPS, control.DefaultLimiterCells, nil, log),
	}
	routes := []control.Route{control.RewindRoute(board, opt)}
	if round != nil {
		routes = append(routes, control.RoundRoute(board, opt, round))
	}
	srv, err := control.Start(opt, append(routes, extra...)...)
	if err != nil {
		log.Error("control_server_failed", "listen", c.Listen, "stage", "bind", "err", err,
			"note", "제어면만 강등된다 — 인덱싱 · 업로드 · 발행은 계속되고 회복은 재기동이다")
		return noop
	}
	return func() { srv.Shutdown() } // 종료 결과는 Shutdown 이 control_server_stopped 로 남긴다
}
