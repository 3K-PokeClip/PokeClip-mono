package main

// 제어면 조립(설계 M5 2.1 · 3.3 · 3.7 · 보완 2 ㉠ — control_assembly.go)의 시험이다. 받은편지함은 여러 고루틴이 Post 하고
// 루프가 Drain 한다(R26). startControl 은 Limiter 하나를 두 경로에 주고 round 가 nil 이면 회차 경로를 등록하지 않는다.
// bind 실패는 강등이다(R8). 재기동 적재(seed)는 nil · 오류 · 성공 세 갈래 모두 기동을 잇는다(계획 4.2 seed 칸).

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

func TestFinalsInboxLastValueWins(t *testing.T) {
	b := newFinalsInbox()
	b.Post("s", index.PlaybackState{Phase: index.PhaseEnding, RoundID: "A"})
	b.Post("s", index.PlaybackState{Phase: index.PhaseEnded, RoundID: "A"})
	b.Post("t", index.PlaybackState{Phase: index.PhaseUnknown})
	got := b.Drain()
	if len(got) != 2 || got["s"].Phase != index.PhaseEnded || got["t"].Phase != index.PhaseUnknown {
		t.Errorf("Drain = %+v, want s ended · t unknown(같은 스트림은 마지막 값)", got)
	}
	if again := b.Drain(); len(again) != 0 {
		t.Errorf("두 번째 Drain = %+v, want 빈 맵(통째 교체)", again)
	}
}

// R26: Post 고루틴 여럿과 Drain 이 함께 돌아도 경합이 없고, 스트림마다 마지막 값이 남는다.
func TestFinalsInboxConcurrentPostAndDrain(t *testing.T) {
	b := newFinalsInbox()
	const writers, posts = 8, 500
	var wg sync.WaitGroup
	for w := range writers {
		wg.Go(func() {
			for i := range posts {
				b.Post(fmt.Sprintf("s%d", w), index.PlaybackState{Phase: index.PhaseEnding, RoundID: fmt.Sprint(i)})
			}
		})
	}
	seen := map[string]index.PlaybackState{}
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	for drained := false; !drained; {
		select {
		case <-done:
			drained = true
		default:
		}
		for s, st := range b.Drain() {
			seen[s] = st
		}
	}
	for w := range writers {
		if got := seen[fmt.Sprintf("s%d", w)].RoundID; got != fmt.Sprint(posts-1) {
			t.Errorf("s%d 마지막 값 = %q, want %d", w, got, posts-1)
		}
	}
}

// controlConfig 는 임의 포트에서 듣는 제어면 설정이다.
func controlConfig(rps int) config.Config {
	return config.Config{Control: config.ControlOptions{Listen: "127.0.0.1:0", AllowOrigin: "http://localhost:3000", StreamRPS: rps}}
}

// startedAddr 는 control_server_started 줄의 듣는 주소다.
func startedAddr(t *testing.T, logs *logCapture) string {
	t.Helper()
	a, _ := logs.attrs("control_server_started")["listen"].(string)
	if a == "" {
		t.Fatal("control_server_started 줄이 없다")
	}
	return "http://" + a
}

// controlTestClient 는 제어면 시험의 HTTP 클라이언트다 — 요청마다 10초 시한이라 서버가 멈춰도 시험이 go test 상한까지
// 매달리지 않고 그 요청에서 실패한다.
var controlTestClient = &http.Client{Timeout: 10 * time.Second}

func httpGet(t *testing.T, url string) (int, string) {
	t.Helper()
	res, err := controlTestClient.Get(url)
	if err != nil {
		t.Fatalf("GET %s = %v", url, err)
	}
	defer res.Body.Close()
	b, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatal(err)
	}
	return res.StatusCode, string(b)
}

// vodBoard 는 스트림 s 가 회차 R1 의 vod 종료 항목을 든 신선한 Board 다.
func vodBoard() *status.Holder {
	st := status.Status{Ready: true, Mode: status.ModeVOD, URL: "https://m.example/vod/s/R1/index.m3u8",
		FirstPDT: loopBase, HeadPDT: loopBase.Add(time.Minute)}
	h := &status.Holder{}
	h.Store(status.NewBoard(time.Now(), true, map[string]status.Entry{
		"s": {Stream: st, Round: &status.RoundEntry{RoundID: "R1", Status: st}},
	}))
	return h
}

func TestStartControlDisabledWhenListenEmpty(t *testing.T) {
	logs := &logCapture{}
	stop := startControl(config.Config{}, &status.Holder{}, slog.New(logs), nil)
	stop()
	if logs.count("control_server_disabled") != 1 || logs.count("control_server_started") != 0 {
		t.Errorf("disabled · started 줄 = %d · %d, want 1 · 0", logs.count("control_server_disabled"), logs.count("control_server_started"))
	}
}

// 보완 2 ㉠: round 가 nil 이면 회차 경로는 등록되지 않아 404 이고, 스트림 경로는 200 이다.
func TestStartControlWithoutRoundLeavesRoundPathUnregistered(t *testing.T) {
	logs := &logCapture{}
	stop := startControl(controlConfig(10), vodBoard(), slog.New(logs), nil)
	defer stop()
	base := startedAddr(t, logs)
	if code, body := httpGet(t, base+"/api/streams/s/rewind"); code != 200 || !strings.Contains(body, `"mode":"vod"`) {
		t.Errorf("스트림 경로 = (%d, %s), want 200 vod", code, body)
	}
	if code, _ := httpGet(t, base+"/api/streams/s/rounds/R1/rewind"); code != 404 {
		t.Errorf("미등록 회차 경로 = %d, want 404", code)
	}
}

// 보완 2 ㉠: round 가 있으면 회차 경로가 등록되고, 두 경로가 같은 Limiter 를 센다(스트림 경로 2회 뒤 회차 hit 503).
func TestStartControlWithRoundSharesLimiter(t *testing.T) {
	logs := &logCapture{}
	lookup := func(context.Context, string, string) (status.Status, status.Answer, error) {
		return status.Status{}, status.NotFound, nil
	}
	stop := startControl(controlConfig(2), vodBoard(), slog.New(logs), lookup)
	defer stop()
	base := startedAddr(t, logs)
	for range 2 {
		httpGet(t, base+"/api/streams/s/rewind")
	}
	if code, _ := httpGet(t, base+"/api/streams/s/rounds/R1/rewind"); code != 503 {
		t.Errorf("스트림 경로 2회 뒤 회차 hit = %d, want 503(한도 공유)", code)
	}
	if code, _ := httpGet(t, base+"/api/streams/x/rounds/R9/rewind"); code != 404 {
		t.Errorf("Board 밖 회차 = %d, want 404(lookup NotFound)", code)
	}
}

// extra 라우트는 그대로 등록되고 한도를 쓰지 않는다(POK-472 자리).
func TestStartControlRegistersExtraRoutes(t *testing.T) {
	logs := &logCapture{}
	extra := control.Route{Pattern: "POST /api/ingest/end-signal", Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusAccepted)
	})}
	stop := startControl(controlConfig(1), vodBoard(), slog.New(logs), nil, extra)
	defer stop()
	base := startedAddr(t, logs)
	for range 3 {
		res, err := controlTestClient.Post(base+"/api/ingest/end-signal", "application/json", strings.NewReader("{}"))
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if res.StatusCode != http.StatusAccepted {
			t.Fatalf("extra 라우트 = %d, want 202", res.StatusCode)
		}
	}
}

// R8: 점유 포트면 ERROR control_server_failed stage=bind 한 줄이고, 돌려준 stop 은 불러도 된다(run 계속).
func TestStartControlBusyPortDegrades(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	cfg := controlConfig(10)
	cfg.Control.Listen = ln.Addr().String()
	logs := &logCapture{}
	stop := startControl(cfg, &status.Holder{}, slog.New(logs), nil)
	stop()
	failed := logs.attrs("control_server_failed")
	if logs.countLevel(slog.LevelError, "control_server_failed") != 1 || failed["stage"] != "bind" || failed["listen"] != cfg.Control.Listen {
		t.Errorf("control_server_failed = %v(ERROR %d줄), want stage=bind listen=%s 한 줄", failed,
			logs.countLevel(slog.LevelError, "control_server_failed"), cfg.Control.Listen)
	}
}

// 3.7절 LIFO: stop 은 서버를 멈춘 뒤에 돌아온다 — 그 뒤에는 연결을 받지 않는다.
func TestStartControlStopShutsServerDown(t *testing.T) {
	logs := &logCapture{}
	stop := startControl(controlConfig(10), vodBoard(), slog.New(logs), nil)
	addr := strings.TrimPrefix(startedAddr(t, logs), "http://")
	stop()
	if logs.count("control_server_stopped") != 1 {
		t.Errorf("control_server_stopped = %d줄, want 1", logs.count("control_server_stopped"))
	}
	if c, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		c.Close()
		t.Error("stop 뒤에도 연결을 받는다")
	}
}

// assembleTarget 은 seed 시험용 루프다.
func assembleTarget(logs *logCapture) *rewindLoop { return &rewindLoop{log: slog.New(logs)} }

// 계획 4.2 seed 칸: nil 이면 INFO rewind_final_seeded count=0 · 받은편지함 빔.
func TestAssembleStatusNilSeed(t *testing.T) {
	logs := &logCapture{}
	r := assembleTarget(logs)
	if h := assembleStatus(t.Context(), bucketConfig(), r, nil); h == nil || h != r.board {
		t.Fatal("assembleStatus 가 루프의 Board 손잡이를 돌려주지 않는다")
	}
	if got := logs.attrs("rewind_final_seeded")["count"]; got != int64(0) {
		t.Errorf("rewind_final_seeded count = %v, want 0", got)
	}
	if len(r.finals.Drain()) != 0 {
		t.Error("nil seed 인데 받은편지함에 값이 있다")
	}
}

// 오류면 WARN rewind_final_seed_failed 한 줄 · 적재 없이 기동을 잇는다(R32).
func TestAssembleStatusSeedErrorContinues(t *testing.T) {
	logs := &logCapture{}
	r := assembleTarget(logs)
	seed := func(context.Context) (map[string]index.PlaybackState, error) {
		return map[string]index.PlaybackState{"s": {Phase: index.PhaseEnded}}, errors.New("relation stream_rounds does not exist")
	}
	if h := assembleStatus(t.Context(), bucketConfig(), r, seed); h == nil {
		t.Fatal("seed 오류에 기동이 멈췄다")
	}
	if logs.countLevel(slog.LevelWarn, "rewind_final_seed_failed") != 1 || logs.count("rewind_final_seeded") != 0 {
		t.Errorf("seed_failed WARN · seeded = %d · %d, want 1 · 0",
			logs.countLevel(slog.LevelWarn, "rewind_final_seed_failed"), logs.count("rewind_final_seeded"))
	}
	if len(r.finals.Drain()) != 0 {
		t.Error("seed 오류인데 받은편지함에 값이 있다")
	}
}

// 성공이면 맵을 받은편지함에 Post 하고 INFO count=n 이다. seed 는 시한이 있는 ctx 를 받는다.
func TestAssembleStatusSeedPostsStates(t *testing.T) {
	logs := &logCapture{}
	r := assembleTarget(logs)
	var hasDeadline bool
	seed := func(ctx context.Context) (map[string]index.PlaybackState, error) {
		_, hasDeadline = ctx.Deadline()
		return map[string]index.PlaybackState{
			"s": {Phase: index.PhaseEnded, RoundID: "A", LatestSessionID: "SA"},
			"t": {Phase: index.PhaseEnding, RoundID: "B", LatestSessionID: "SB"},
		}, nil
	}
	assembleStatus(t.Context(), bucketConfig(), r, seed)
	if !hasDeadline {
		t.Error("seed ctx 에 시한이 없다 — 느린 DB 가 기동을 붙잡는다")
	}
	if got := logs.attrs("rewind_final_seeded")["count"]; got != int64(2) {
		t.Errorf("rewind_final_seeded count = %v, want 2", got)
	}
	if got := r.finals.Drain(); len(got) != 2 || got["s"].RoundID != "A" || got["t"].RoundID != "B" {
		t.Errorf("받은편지함 = %+v, want s A · t B", got)
	}
}

// 3.3절 꺼짐과 강등: 버킷이 비면(꺼짐) DisabledBoard 를 한 번 넣고 갱신하지 않는다. 버킷이 있으면 첫 스냅숏 전(nil)이다.
func TestAssembleStatusDisabledOnlyWhenBucketEmpty(t *testing.T) {
	logs := &logCapture{}
	off := assembleTarget(logs)
	h := assembleStatus(t.Context(), config.Config{}, off, nil)
	if _, answer := h.Load().Find("s", time.Now(), time.Minute); answer != status.NotFound || !off.statusOff {
		t.Errorf("꺼짐: Find = %v · statusOff = %v, want NotFound · true", answer, off.statusOff)
	}
	on := assembleTarget(logs)
	if h := assembleStatus(t.Context(), bucketConfig(), on, nil); h.Load() != nil || on.statusOff {
		t.Errorf("버킷 있음: Load = %v · statusOff = %v, want nil · false", h.Load(), on.statusOff)
	}
}

// bucketConfig 는 발행이 켜진(버킷 있는) 설정이다 — 발행자 조립 성패와 무관하게 Board 는 정상이다.
func bucketConfig() config.Config {
	return config.Config{S3: upload.S3Options{Bucket: "b"}, RewindPublicBaseURL: "https://media.example.com"}
}
