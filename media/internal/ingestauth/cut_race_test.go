package ingestauth

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxctl"
)

// 착수 체크리스트 C2 회전 경합 뒷부분(209행 — c2 의 TestLoadAllowedAcrossRotations 와 짝): 기록 판독의 두 읽기 사이에
// 회전이 0 · 1 · 2번 껴도, Since 앞에서 허용된 판독 중 연결은 기록 밖 갈래가 끊거나(30초 넘음) 미완(30초 안)으로
// 알린다. 그 연결의 줄은 판독 전에 두 번의 회전으로 밀려나 있다. 판독 사이의 회전 수는 cut 의 판독 사이 고리(첫
// 판독에서만)로 정확히 만든다 — maxBytes 1 이라 Append 하나가 회전 하나다.
func TestCutCatchesConnsLostAcrossRotations(t *testing.T) {
	for _, rotations := range []int{0, 1, 2} {
		// 판독 전 세대는 G2(시작 표시 2초) · G3(3초)다. 회전이 0번이면 Since 는 G2 의 시작 표시, 1 · 2번이면 처음 읽은
		// G3 의 시작 표시다(설계 3-4 「LoadAllowed 의 성질」).
		covered := map[int]time.Duration{0: 8 * time.Second, 1: 7 * time.Second, 2: 7 * time.Second}[rotations]
		for _, tc := range []struct {
			name string
			age  time.Duration // 목록을 받은 때(10초)의 나이
			want CutReport
		}{
			{name: "31초_끊음", age: 31 * time.Second, want: CutReport{FromUnmapped: 1, Kicked: 1, Covered: covered}},
			{name: "20초_미완", age: 20 * time.Second, want: CutReport{UnmappedYoung: 1, Covered: covered}},
		} {
			t.Run(fmt.Sprintf("회전_%d번_%s", rotations, tc.name), func(t *testing.T) {
				f := newCutFixture(t, 1)
				log, rec := newLogRecorder()
				f.conns.log = log
				lost := testConnID(1)
				f.clock.moveTo(time.Second)
				f.mustAllow(t, testTokenA, lost)
				f.clock.moveTo(2 * time.Second)
				f.mustAllow(t, testTokenB, testConnID(2))
				f.clock.moveTo(3 * time.Second)
				f.mustAllow(t, testTokenB, testConnID(3))
				f.clock.moveTo(10 * time.Second)
				f.mtx.add(fakeConn{id: lost, created: f.at(10*time.Second - tc.age)})
				reads := 0
				f.between = func() {
					reads++
					if reads != 1 {
						return
					}
					for i := range rotations {
						if err := f.allow(testTokenB, testConnID(10+i)); err != nil {
							t.Errorf("Append between the two reads returned error: %v", err)
						}
					}
				}
				rotatedBefore := len(rec.events(t, "ingestauth_connlog_rotated"))

				rep, err := f.cut(t, testTokenA)

				if n := len(rec.events(t, "ingestauth_connlog_rotated")) - rotatedBefore; n != rotations {
					t.Fatalf("Setup: rotations between the two reads = %d, want %d", n, rotations)
				}
				checkReport(t, rep, tc.want)
				if tc.want.Kicked == 1 {
					if err != nil {
						t.Errorf("Cut(X) returned error: %v", err)
					}
					if got, want := f.mtx.kicked200(), []string{lost}; !slices.Equal(got, want) {
						t.Errorf("kicked %v, want %v", got, want)
					}
				} else {
					if !errors.Is(err, ErrConnLogWindowShort) {
						t.Errorf("Cut(X) = %v, want ErrConnLogWindowShort", err)
					}
					if got := f.mtx.kicked200(); len(got) != 0 {
						t.Errorf("kicked %v, want none (20s — not yet)", got)
					}
				}
			})
		}
	}
}

// cutHandler 는 C2 동시성 시험의 응답기다 — c4 처리기(가짜 auth · 실제 keyresolve.Client)가 거부 목록 · 허용 연결 기록을
// 쓴다. 기록은 실제 시계로 찍는다 — 가짜 MediaMTX 의 created 와 cut 의 목록 시각이 같은 시계다. 처리기 시계(예산 ·
// 차례)는 멈춰 있어 예산이 바닥나지 않는다.
type cutHandler struct {
	srv      http.Handler
	auth     *fakeAuth
	denyPath string
	connDir  string
}

func newCutHandler(t *testing.T) *cutHandler {
	t.Helper()
	clock := newHandlerClock()
	auth := newFakeAuth(t, clock)
	connDir := t.TempDir()
	conns, err := OpenConnLog(connDir, 1<<20, nil)
	if err != nil {
		t.Fatalf("Setup: OpenConnLog returned error: %v", err)
	}
	conns.log = discardLogger()
	t.Cleanup(func() { closeConnLog(conns) })
	denyPath := filepath.Join(t.TempDir(), "deny-tokens")
	h, err := newHandler(newTestResolver(t, auth.srv.URL), NewDenyList(denyPath, discardLogger()), conns, Options{
		CallerIP:   testCallerIP,
		SelfIP:     testSelfIP,
		APIUser:    testAPIUser,
		APIPass:    NewSecret(testAPIPass),
		CallerUser: testCallerUser,
		CallerPass: NewSecret(testCallerPass),
		Now:        clock.now,
		Log:        discardLogger(),
	})
	if err != nil {
		t.Fatalf("Setup: newHandler returned error: %v", err)
	}
	return &cutHandler{srv: h.layers(), auth: auth, denyPath: denyPath, connDir: connDir}
}

// serveAuth 는 MediaMTX(CallerIP)가 보낸 인증 요청 body 를 처리기 전체에 보내고 상태 코드를 돌려준다. 고루틴 안에서도
// 부르므로 t 를 받지 않는다.
func (h *cutHandler) serveAuth(body mtxAuthBody) int {
	raw, err := json.Marshal(body)
	if err != nil {
		return 0 // 문자열 필드뿐이라 일어나지 않는다
	}
	r := httptest.NewRequest(http.MethodPost, "/mediamtx/auth", bytes.NewReader(raw))
	r.RemoteAddr = netip.AddrPortFrom(testCallerIP, 40000).String()
	r.Header.Set("Content-Type", "application/json")
	r.SetBasicAuth(testCallerUser, testCallerPass)
	rr := httptest.NewRecorder()
	h.srv.ServeHTTP(rr, r)
	return rr.Code
}

// publish 는 token · connID 의 SRT publish 인증이다.
func (h *cutHandler) publish(token, connID string) int {
	return h.serveAuth(publishBody(token, connID))
}

// authorizeAPI 는 MediaMTX 가 Control API 요청의 자격을 응답기 api 행에 묻는 것을 흉내 낸다(api.go 316–341행). 출발지는
// cut 이 도는 응답기 자신의 주소(SelfIP)다.
func (h *cutHandler) authorizeAPI(user, pass string) bool {
	return h.serveAuth(apiBody(testSelfIP.String(), user, pass)) == http.StatusOK
}

// denyAndCut 은 운영자의 긴급 절단이다 — 거부 목록에 토큰 한 줄을 덧붙이고(>>) 쓰기를 마친 뒤 cut 을 돌린다(설계 3-4
// 1 · 2). cut 은 따로 도는 프로세스라 거부 목록을 새 DenyList 로 읽는다.
func (h *cutHandler) denyAndCut(ctx context.Context, token string, mtx *mtxctl.Client) (CutReport, error) {
	f, err := os.OpenFile(h.denyPath, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		return CutReport{}, err
	}
	_, werr := f.WriteString(token + "\n")
	if err := errors.Join(werr, f.Close()); err != nil {
		return CutReport{}, err
	}
	return Cut(ctx, token, NewDenyList(h.denyPath, discardLogger()), h.connDir, mtx)
}

// newCutStage 는 응답기 · 그 api 행에 자격을 묻는 가짜 MediaMTX · 그것을 부르는 실제 mtxctl.Client 다.
func newCutStage(t *testing.T) (*cutHandler, *fakeMediaMTX, *mtxctl.Client) {
	t.Helper()
	h := newCutHandler(t)
	mtx := newFakeMediaMTX(t)
	mtx.set(func(m *fakeMediaMTX) { m.authorize = h.authorizeAPI })
	return h, mtx, newTestMTXClient(t, mtx.srv.URL)
}

// publishPhase 는 송출 연결 하나의 생애에서 운영자의 「거부 목록 쓰기 → cut」이 끼는 자리다.
type publishPhase int

const (
	beforeAuth           publishPhase = iota + 1 // 연결은 목록에 있고 인증 요청 전
	duringResolve                                // 처리기가 resolve 하는 동안 — 기록 추가 · 마지막 확인 전
	afterAllow                                   // 처리기가 200 을 낸 뒤, MediaMTX 가 수락하기 전
	acceptedUnregistered                         // 수락 뒤 등록 전(목록에서 idle · path 빈 값)
	registered                                   // 등록 뒤(publish · path 토큰)
)

// 착수 체크리스트 C2 동시성(211행)의 단계별 강제 끼움: 송출 연결 하나의 생애의 자리마다 「거부 목록 쓰기 → cut」을 끼우고,
// cut 이 nil(종료 코드 0)로 끝난 뒤 그 연결이 수락 상태로 남지 않음을 본다(설계 3-4 「거짓 성공 0 — 증명」). cut 의
// Control API 요청은 응답기 api 행을 거친다. 마지막 확인 전에 낀 cut 은 그 연결을 끊지 않아도 된다(처리기가 401).
// 그 뒤에 낀 cut 은 기록 갈래로 끊고, MediaMTX 는 kick 된 연결을 수락하지 못한다.
func TestCutAtEachPublishPhase(t *testing.T) {
	tests := []struct {
		name     string
		phase    publishPhase
		wantCode int
		wantKick bool
	}{
		{name: "인증_전", phase: beforeAuth, wantCode: http.StatusUnauthorized},
		{name: "resolve_도중", phase: duringResolve, wantCode: http.StatusUnauthorized},
		{name: "허용_뒤_수락_전", phase: afterAllow, wantCode: http.StatusOK, wantKick: true},
		{name: "수락_뒤_등록_전", phase: acceptedUnregistered, wantCode: http.StatusOK, wantKick: true},
		{name: "등록_뒤", phase: registered, wantCode: http.StatusOK, wantKick: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h, mtx, client := newCutStage(t)
			ctx := t.Context()
			var (
				mu     sync.Mutex // duringResolve 의 cut 은 가짜 auth 의 서버 고루틴에서 돈다
				ran    bool
				rep    CutReport
				cutErr error
			)
			at := func(p publishPhase) {
				if p != tc.phase {
					return
				}
				r, err := h.denyAndCut(ctx, testTokenA, client)
				mu.Lock()
				defer mu.Unlock()
				ran, rep, cutErr = true, r, err
			}
			ans := answerUsable(0)
			ans.before = func() { at(duringResolve) }
			h.auth.answerAll(ans)

			id, _ := mtx.open(time.Now())
			at(beforeAuth)
			code := h.publish(testTokenA, id)
			if code == http.StatusOK {
				at(afterAllow)
				if mtx.accept(id, testTokenA) {
					at(acceptedUnregistered)
					mtx.register(id)
					at(registered)
				}
			} else {
				mtx.close(id)
			}

			mu.Lock()
			defer mu.Unlock()
			if !ran {
				t.Fatalf("the cut did not run at phase %s", tc.name)
			}
			if cutErr != nil {
				t.Errorf("Cut(X) at %s returned error: %v (report %+v)", tc.name, cutErr, rep)
			}
			if code != tc.wantCode {
				t.Errorf("publish answered %d, want %d", code, tc.wantCode)
			}
			if kicked := slices.Contains(mtx.kicked200(), id); kicked != tc.wantKick {
				t.Errorf("connection kicked = %t, want %t (report %+v)", kicked, tc.wantKick, rep)
			}
			if live := mtx.acceptedLive(testTokenA); len(live) != 0 {
				t.Errorf("connections of X still accepted after Cut = %v, want none", live)
			}
		})
	}
}

// 착수 체크리스트 C2 동시성 반복(211행): 송출자 여럿이 같은 토큰으로 허용 갈래를 도는 중에 「거부 목록 쓰기 → cut」을
// 돌린다. 송출자는 연결을 열고(목록에 idle) 처리기에 인증을 묻고, 200 이면 가짜 MediaMTX 가 수락 · 등록한다 — kick 된
// 연결은 수락하지 못한다. 송출자는 두 부류다 — kick 될 때까지 송출하는 쪽(오래 가는 방송 — cut 이 반드시 살아 있는
// 연결을 만난다)과 잠깐 송출하다 스스로 닫고 다시 붙는 쪽(재접속이 끊이지 않아 cut 이 허용 갈래 · 200 · 수락 · 등록
// 사이에 낀다). cut 이 nil 로 끝난 뒤 그 토큰의 연결이 수락 상태로 남은 경우가 0 이어야 한다. 회마다 새 응답기 · 기록 ·
// 가짜 MediaMTX 다(-race -count=20 으로 돈다).
//
// cut 은 거부 쓰기부터 첫 kick 까지 수 ms 를 쓴다(-race 에서 회당 7–13ms 실측). 그동안 거부 쓰기 전에 마지막 확인을
// 지난 재접속 송출자는 수락 · 등록 · 스스로 닫음까지 지나 버려, 무작위로는 「200 을 받았고 아직 수락 전」인 연결을 cut 이
// 거의 만나지 못한다. 그래서 회마다 그 겹침 하나를 보장한다 — 데운 뒤 처음 200 을 받은 재접속 송출자가 운영자에게
// 알리고(거부 쓰기 → cut 시작) cut 이 끝날 때까지 수락을 미룬다(증명의 (가) — 마지막 확인이 거부 쓰기보다 앞). 나머지
// 송출자는 그동안 무작위로 돈다.
func TestCutConcurrentWithPublishes(t *testing.T) {
	const (
		rounds   = 10
		keepers  = 2 // kick 될 때까지 송출한다
		churners = 3 // 잠깐 송출하고 다시 붙는다
	)
	var total overlapStats
	for round := range rounds {
		t.Run(fmt.Sprintf("회_%02d", round), func(t *testing.T) {
			h, mtx, client := newCutStage(t)
			var stats overlapStats
			trigger := &cutTrigger{fired: make(chan struct{}), cutDone: make(chan struct{})}
			stop := make(chan struct{})
			var wg sync.WaitGroup
			var stopOnce sync.Once
			halt := func() {
				stopOnce.Do(func() { close(stop) })
				wg.Wait()
			}
			t.Cleanup(halt) // 시험이 도중에 멈춰도 송출자가 남지 않게 — 가짜 서버들을 닫기 전에 돈다
			for i := range keepers + churners {
				p := publisher{h: h, mtx: mtx, keep: i < keepers, stats: &stats, trigger: trigger}
				wg.Go(func() { p.run(stop) })
			}
			// kick 될 때까지 송출하는 쪽이 모두 등록되고, 다시 붙는 쪽이 몇 바퀴 돈 뒤에 신호를 건다.
			waitFor(t, func() bool { return stats.keeping.Load() == keepers && stats.accepted.Load() >= keepers+2*churners },
				"warm-up acceptances")
			trigger.armed.Store(true)
			select {
			case <-trigger.fired:
			case <-time.After(5 * time.Second):
				t.Fatalf("no churner received a 200 within 5s after arming")
			}

			rep, err := h.denyAndCut(t.Context(), testTokenA, client)
			close(trigger.cutDone)
			halt()

			if err != nil {
				t.Errorf("Cut(X) during publishes returned error: %v (report %+v)", err, rep)
			}
			if live := mtx.acceptedLive(testTokenA); len(live) != 0 {
				t.Errorf("connections of X still accepted after Cut = %v (report %+v), want none", live, rep)
			}
			if n := stats.kickedLive.Load(); n < keepers {
				t.Errorf("connections kicked while publishing = %d, want at least %d (the keepers)", n, keepers)
			}
			if n := stats.lateRefused.Load(); n < 1 {
				t.Errorf("connections refused after their 200 = %d, want at least 1 (the trigger's)", n)
			}
			total.add(&stats)
		})
	}
	t.Logf("겹침(회 %d): 수락 %d · 송출 중 kick 된 연결 %d · 200 을 받고도 kick 이 먼저라 수락하지 못한 연결 %d(신호 %d 포함) · 수락 뒤 등록 전에 kick 된 연결 %d",
		rounds, total.accepted.Load(), total.kickedLive.Load(), total.lateRefused.Load(), rounds, total.unregisteredKick.Load())
}

// cutTrigger 는 동시성 반복에서 겹침 하나를 보장하는 신호다. 데운 뒤(armed) 처음 200 을 받은 송출자가 fired 를 닫고,
// 운영자의 cut 이 끝날 때까지(cutDone) 수락을 미룬다.
type cutTrigger struct {
	armed   atomic.Bool
	taken   atomic.Bool
	fired   chan struct{}
	cutDone chan struct{}
}

// hold 는 이 200 이 신호를 걸 차례면 fired 를 닫고 cut 이 끝날 때까지 기다린 뒤 참이다. stop 이 먼저 닫히면 거짓이다.
// 차례가 아니면 0–3ms 를 쉬고 참이다.
func (c *cutTrigger) hold(stop <-chan struct{}) bool {
	if !c.armed.Load() || !c.taken.CompareAndSwap(false, true) {
		pause(3 * time.Millisecond)
		return true
	}
	close(c.fired)
	select {
	case <-c.cutDone:
		return true
	case <-stop:
		return false
	}
}

// overlapStats 는 동시성 반복의 겹침 셈이다.
type overlapStats struct {
	accepted         atomic.Int64 // 수락된 연결
	keeping          atomic.Int64 // 지금 kick 될 때까지 송출하는 연결
	kickedLive       atomic.Int64 // 등록된 채 송출하다 kick 된 연결
	lateRefused      atomic.Int64 // 처리기의 200 을 받았지만 kick 이 먼저라 수락하지 못한 연결
	unregisteredKick atomic.Int64 // 수락 뒤 등록 전에 kick 된 연결
}

// add 는 s 의 셈을 o 에 더한다.
func (o *overlapStats) add(s *overlapStats) {
	o.accepted.Add(s.accepted.Load())
	o.kickedLive.Add(s.kickedLive.Load())
	o.lateRefused.Add(s.lateRefused.Load())
	o.unregisteredKick.Add(s.unregisteredKick.Load())
}

// publisher 는 송출자 하나다. keep 이면 kick 될 때까지 송출하고(오래 가는 방송), 아니면 잠깐(0–200µs) 송출하다 스스로
// 닫는다. 200 과 수락 · 수락과 등록 사이에 0–3ms 를 둔다 — 원본에서도 200 뒤 Accept(SRT 핸드셰이크 응답)와 등록
// (PAT · PMT 를 읽은 뒤 AddPublisher — srt/conn.go 228–245행)은 수 ms 이상 걸린다. 다시 붙는 쪽은 신호(trigger)도 건다.
type publisher struct {
	h       *cutHandler
	mtx     *fakeMediaMTX
	keep    bool
	stats   *overlapStats
	trigger *cutTrigger
}

// run 은 stop 이 닫힐 때까지 연결을 열고, 인증받고, 송출하고, 끊기면 다시 붙는다.
func (p publisher) run(stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		default:
		}
		id, gone := p.mtx.open(time.Now())
		if p.h.publish(testTokenA, id) != http.StatusOK {
			p.mtx.close(id)
			// 거절 뒤 곧바로 다시 붙으면 송출자들이 CPU 를 차지해 GOMAXPROCS=1 에서 cut 이 굶는다. 플러그인도 간격을 두고
			// 다시 붙는다(5초 — 시험은 줄인다).
			pause(time.Millisecond)
			continue
		}
		if p.keep {
			pause(3 * time.Millisecond)
		} else if !p.trigger.hold(stop) {
			return
		}
		if !p.mtx.accept(id, testTokenA) {
			p.stats.lateRefused.Add(1)
			continue
		}
		p.stats.accepted.Add(1)
		pause(3 * time.Millisecond)
		if !p.mtx.register(id) {
			p.stats.unregisteredKick.Add(1)
			continue
		}
		if !p.stream(id, gone, stop) {
			return
		}
	}
}

// stream 은 등록된 연결 id 로 송출한다 — keep 이면 kick 될 때까지, 아니면 잠깐 뒤 스스로 닫는다. stop 이 닫히면 거짓이다.
func (p publisher) stream(id string, gone <-chan struct{}, stop <-chan struct{}) bool {
	var done <-chan time.Time
	if p.keep {
		p.stats.keeping.Add(1)
		defer p.stats.keeping.Add(-1)
	} else {
		done = time.After(time.Duration(rand.IntN(200)) * time.Microsecond)
	}
	select {
	case <-gone:
		p.stats.kickedLive.Add(1)
	case <-stop:
		return false
	case <-done:
		p.mtx.close(id)
	}
	return true
}

// pause 는 0 이상 limit 미만의 무작위 시간을 쉰다. 고루틴 차례도 넘긴다.
func pause(limit time.Duration) {
	runtime.Gosched()
	time.Sleep(time.Duration(rand.Int64N(int64(limit))))
}

// waitFor 는 cond 가 참이 되기를 5초까지 기다린다. 안 되면 시험을 멈춘다.
func waitFor(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("%s did not happen within 5s", what)
		}
		time.Sleep(100 * time.Microsecond)
	}
}
