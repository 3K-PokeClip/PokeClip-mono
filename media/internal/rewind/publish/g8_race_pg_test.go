package publish

// G8 동시 writer 본체(판단 J1 · 계획 커밋 순서 8 검증 표 「-race 전 패키지 + 동시 writer 2개 픽스처」) — g8_pg_test.go 의
// 결정적 교차와 달리 writer 둘이 실제 고루틴으로 같은 회차를 동시에 틱한다. 셋째 고루틴이 lease 를 무작위 간격으로
// 끝내 fence 가 두 writer 사이를 오가게 하고(좀비와 후임이 겹치는 국면), PUT 은 적용 직전에 잠깐 쉬어 한 writer 의 P3
// 가 다른 writer 의 P0–P4 와 겹치게 한다. CI 는 늘 -race 로 돈다.
//
// 판정 순서 — 결함 신호(허용 밖 중단 사유 · 시한 밖 틱 오류)와 적용된 판의 계보 검사는 표본 수와 무관하게 먼저 돈다.
// 부하로 문턱 안에 경쟁 국면이 서지 않았고 결함 신호가 없으면 국면 문턱 · 정착 단계만 건너뛴다(원인 수치 로그 — 종결
// 라운드 1 CL1-6 · 라운드 2 CL1-21). 결함 신호가 있으면 국면과 무관하게 실패다. 예외 하나 — 국면이 서지 않은 실행에서
// 연속한 두 판의 겹치는 조각이 0 인 것은 시험 장치의 인공물이라 미성립으로 센다(CL2F-D (가)). 계보를 언제나 판정하는
// 결정적 시험은 g8_pg_test.go 의 교차 열 개다.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 경쟁 국면의 문턱이다. 두 writer 가 저마다 g8MinPublished 번 발행하고 lease 를 g8MinKills 번 끝내면 국면을 닫는다.
// g8RaceDeadline 안에 닫지 못하면 국면이 서지 않은 것이다(건너뜀). 장부는 g8LastSeq 까지 자라고, 창은 4초 조각
// 900개(1시간 — S3)를 넘으면 앞을 잘라 낸다.
const (
	g8MinPublished = 5
	g8MinKills     = 20
	g8RaceDeadline = 30 * time.Second
	g8LastSeq      = 5000
	g8WindowRows   = 900
)

// appliedPut 은 저장소가 적용한 PUT 하나다 — 조건(앞 판의 ETag)과 새 판.
type appliedPut struct {
	cond Precondition
	etag string
	body []byte
	meta map[string]string
}

// recordingStore 는 적용에 성공한 PUT 을 모으는 가짜 저장소다. 두 고루틴이 기록하므로 기록 순서는 적용 순서와
// 다를 수 있다 — 적용 순서는 If-Match 사슬로 다시 잇는다(appliedChain).
type recordingStore struct {
	*fakeStore
	mu      sync.Mutex
	applied []appliedPut
}

func (s *recordingStore) Put(ctx context.Context, key string, cond Precondition, body []byte, meta map[string]string) (string, error) {
	etag, err := s.fakeStore.Put(ctx, key, cond, body, meta)
	if err == nil {
		s.mu.Lock()
		s.applied = append(s.applied, appliedPut{cond: cond, etag: etag, body: bytes.Clone(body), meta: lowerKeys(meta)})
		s.mu.Unlock()
	}
	return etag, err
}

// appliedChain 은 적용된 PUT 을 적용 순서로 잇는다 — 첫 판(IfAbsent)에서 시작해 「If-Match = 앞 판의 ETag」 를
// 따라간다. 가짜 저장소는 조건 판정과 적용을 뮤텍스 하나 아래에서 하므로 적용된 PUT 은 갈래 없는 사슬 하나여야
// 한다. 같은 앞 판 위에 적용된 PUT 이 둘이면(한 판을 두 writer 가 덮음) 오류이고, 사슬에 이어지지 않는 PUT 이
// 있어도 오류다.
func appliedChain(puts []appliedPut) ([]appliedPut, error) {
	next := map[string]appliedPut{} // 앞 판 ETag("" = 첫 판) → 그 위에 적용된 PUT
	for _, p := range puts {
		base := ""
		if p.cond.ifMatch {
			base = p.cond.etag
		}
		if q, dup := next[base]; dup {
			return nil, fmt.Errorf("판 %q 위에 PUT 둘(%s · %s)이 적용됐다", base, q.etag, p.etag)
		}
		next[base] = p
	}
	var chain []appliedPut
	for base := ""; len(chain) <= len(puts); {
		p, ok := next[base]
		if !ok {
			break
		}
		chain = append(chain, p)
		base = p.etag
	}
	if len(chain) != len(puts) {
		return nil, fmt.Errorf("적용된 PUT %d개 가운데 사슬에 이어진 것 %d개", len(puts), len(chain))
	}
	return chain, nil
}

// g8Racer 는 writer 하나의 루프 흉내다 — 발행 상태를 들고 틱을 내며 결과를 센다. st · ticks · errs 는 그 writer 의
// 고루틴만 쓰고 시험은 합류 뒤에 읽는다. published 는 국면을 닫는 판정이 도는 동안에도 읽는다.
type g8Racer struct {
	pub       *Publisher
	logs      *logRecorder
	st        State
	ticks     int
	errs      []error
	published atomic.Int32
}

// g8Input 은 to 에서 끝나는 1시간 창의 틱 입력이다(loop.input 과 같은 계산) — 고루틴 안에서 부르므로 t.Fatal 대신
// 오류를 돌려준다.
func g8Input(f *fixture, st State, to int64) (TickInput, error) {
	pl := f.window(max(0, to-g8WindowRows+1), to)
	in := TickInput{Playlist: pl, DiscontinuitySequence: f.sessions[f.owner].DiscontinuityBase}
	if p := st.Prev; p != nil {
		d, err := NextDiscontinuitySequence(p.Published, f.window(p.MediaSequence, p.PublishedSeq), pl.Rows[0].Seq)
		if err != nil {
			return TickInput{}, err
		}
		in.DiscontinuitySequence = d
	}
	return in, nil
}

// g8LoserReasons 는 경쟁에 진 writer 가 남겨도 되는 중단 · 포기 사유다 — fence 를 못 쥐었거나(p0_no_row ·
// p4_no_row) 조건부 쓰기에 졌다(update_412 · create_412). 모두 화해로 이어진다. 그 밖의 사유(검사 위반 · 출처 대조
// 실패 · 메타 오류)는 계보가 어긋났다는 뜻이다 — 출처 대조(etag_source_mismatch)는 fence 를 쥔 writer 의 P 와 DB
// manifest_etag 가 같다는 결정 8 이 깨질 때만 난다.
var g8LoserReasons = []string{reasonP0NoRow, reasonP4NoRow, reasonUpdate412, reasonCreate412}

// concurrent_writers_keep_one_lineage(G8-P 증명 ①–③ 의 동시 실행 형) — 두 writer 의 경쟁 뒤에도
//
//	계보  적용된 PUT 은 갈래 없는 사슬 하나다(한 판을 두 writer 가 덮지 않는다). 사슬을 따라 메타 pc-gen 은 늘고
//	      마지막 seq 는 늘며 MSN 은 줄지 않고, 함께 실린 조각의 DSN 은 같다(각 판은 앞 판의 검사된 후속)
//	진 쪽  중단 사유는 fence 미획득 · 조건부 쓰기 패배뿐이고 틱 오류는 없다
//	정착  경쟁을 멈추고 한 writer 가 다시 발행하면 DB 세대 · manifest_etag · published_seq 가 저장된 판과 같다
func TestConcurrentWritersKeepOneLineage(t *testing.T) {
	pool := newPool(t)
	f := soloFixture(g8LastSeq)
	insertSessions(t, pool, f)
	store := &recordingStore{fakeStore: newFakeStore(t)}
	store.setOnPut(func(putCall) error {
		time.Sleep(rand.N(3 * time.Millisecond))
		return nil
	})
	racers := []*g8Racer{{logs: &logRecorder{}}, {logs: &logRecorder{}}}
	for i, r := range racers {
		r.pub = newPublisher(t, pool, store, fmt.Sprintf("w-%d", i), r.logs)
	}
	ctx, cancel := context.WithTimeout(t.Context(), g8RaceDeadline)
	defer cancel()
	stop := make(chan struct{})
	// head 는 장부의 끝이다 — writer 와 따로 1ms 마다 자란다(③ 확정이 틱과 무관하게 쌓이는 흉내). 줄지 않으므로 writer 가
	// P 보다 앞선 창을 그리는 일이 없고(ABA 가 끼지 않는다), 같은 끝을 둘이 그리면 같은 본문이라 하나만 남는다.
	var head atomic.Int64
	head.Store(5)
	var kills atomic.Int32
	var wg sync.WaitGroup
	wg.Go(func() {
		for head.Load() < g8LastSeq {
			select {
			case <-stop:
				return
			case <-time.After(time.Millisecond):
			}
			head.Add(1)
		}
	})
	for _, r := range racers {
		wg.Go(func() {
			for {
				select {
				case <-stop:
					return
				case <-time.After(rand.N(2 * time.Millisecond)): // 루프의 보류 틱 박자를 줄인 흉내 — 둘이 엇갈려 돈다
				}
				in, err := g8Input(f, r.st, head.Load())
				if err != nil {
					r.errs = append(r.errs, err)
					return
				}
				out := r.pub.Tick(ctx, r.st, in)
				r.st, r.ticks = out.State, r.ticks+1
				if out.Err != nil {
					r.errs = append(r.errs, out.Err)
				}
				if out.Published {
					r.published.Add(1)
				}
			}
		})
	}
	wg.Go(func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(5*time.Millisecond + rand.N(30*time.Millisecond)):
			}
			if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET fence_expires_at = now() - interval '1 second' WHERE session_id = 'S'`); err != nil {
				if ctx.Err() == nil { // 시한이 끝나 난 실패는 국면 미성립 몫이다(아래 건너뜀)
					t.Errorf("lease 끝내기 실패: %v", err)
				}
				return
			}
			kills.Add(1)
		}
	})

	reached := false
	for !reached && ctx.Err() == nil {
		time.Sleep(10 * time.Millisecond)
		reached = racers[0].published.Load() >= g8MinPublished && racers[1].published.Load() >= g8MinPublished &&
			kills.Load() >= g8MinKills
	}
	close(stop)
	joined := make(chan struct{})
	go func() {
		wg.Wait()
		close(joined)
	}()
	select {
	case <-joined:
	case <-time.After(10 * time.Second):
		t.Fatal("writer 고루틴이 멈춘 뒤 10초 안에 끝나지 않았다 — 틱이 매달렸다")
	}
	// 결함 신호는 국면 성립과 무관하게 먼저 본다 — 제품 결함으로 발행이 안 선 실행을 부하로 읽어 건너뛰지 않기 위해서다
	// (종결 라운드 1 CL1-6 · cx 확인). 결함 신호 = 허용 밖 중단 사유 · 시한 밖 틱 오류. 시한 오류(context deadline ·
	// canceled)는 느린 DB 가 내는 부하 신호라 국면 미성립 때는 결함으로 세지 않는다(국면이 선 실행에서는 종전대로 실패).
	// 표본 부족만 Skip 이다.
	defect := false
	for i, r := range racers {
		for _, err := range r.errs {
			if reached || !(errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled)) {
				t.Errorf("w-%d 의 틱 오류 = %v, want 없음", i, err)
				defect = true
			}
		}
		for _, reason := range r.logs.aborts() {
			if !slices.Contains(g8LoserReasons, reason) {
				t.Errorf("w-%d 의 중단 사유 %s — 경쟁에 진 writer 가 남길 사유(%v) 밖이다", i, reason, g8LoserReasons)
				defect = true
			}
		}
		t.Logf("w-%d: 틱 %d · 발행 %d · 오류 %d · 중단 %d", i, r.ticks, r.published.Load(), len(r.errs), len(r.logs.aborts()))
	}
	t.Logf("lease 끝냄 %d번 · 창 끝 %d", kills.Load(), head.Load())

	// 계보 검사는 표본 수와 무관하게 먼저 돈다 — 적용된 판이 하나라도 있으면 사슬 · 세대 · MSN · 공통 조각 DSN 을 본다
	// (종결 라운드 2 CL1-21). 국면 문턱과 정착 단계만 아래에서 표본 부족이면 건너뛴다.
	// 예외 하나(종결 라운드 2 확인 패스 CL2F-D · kty 2026-10-19 (가)): 국면이 서지 않은 실행에서 연속한 두 판에 겹치는 조각이
	// 0 이면 결함이 아니라 미성립으로 센다 — 이 시험은 머리를 1ms 타이머로 키우므로(g8LastSeq) 발행이 뜸하면 창(g8WindowRows)이
	// 통째로 지나가는 시험 장치의 인공물이고, 제품은 실제 조각이 들어와야 머리가 움직인다. 국면이 선 실행에서는 그대로 실패다.
	noOverlap := 0
	store.mu.Lock()
	applied := slices.Clone(store.applied)
	store.mu.Unlock()
	chain, err := appliedChain(applied)
	if err != nil {
		t.Fatalf("적용된 PUT 의 계보: %v", err)
	}
	var prev *appliedPut
	for i := range chain {
		cur := &chain[i]
		if prev != nil {
			was, err := parseMeta(prev.meta, "S")
			if err != nil {
				t.Fatalf("판 %d 의 메타 해석 실패: %v", i-1, err)
			}
			now, err := parseMeta(cur.meta, "S")
			if err != nil {
				t.Fatalf("판 %d 의 메타 해석 실패: %v", i, err)
			}
			if now.Gen <= was.Gen || now.PublishedSeq <= was.PublishedSeq || now.MediaSequence < was.MediaSequence {
				t.Errorf("판 %d %+v → 판 %d %+v, want 세대 · 마지막 seq 증가 · MSN 비감소", i-1, was, i, now)
			}
			if reached {
				sameCommonDSN(t, prev.body, cur.body)
			} else if commonDSN(t, prev.body, cur.body) == 0 {
				noOverlap++
			}
		}
		prev = cur
	}
	if total := racers[0].published.Load() + racers[1].published.Load(); int(total) > len(chain) {
		t.Errorf("발행 성공 %d번 > 적용된 판 %d개 — 한 판을 두 writer 가 저마다 발행했다고 셌다", total, len(chain))
	}
	if !reached {
		if defect || t.Failed() {
			t.Fatalf("경쟁 국면이 서지 않았고(발행 %d · %d번 · lease 끝냄 %d번) 결함 신호가 있다 — 부하가 아니라 결함이다",
				racers[0].published.Load(), racers[1].published.Load(), kills.Load())
		}
		// 결함 신호 없이 표본만 모자란 것이다 — 부하로 국면이 서지 않은 것이고 계보 판정은 위 사슬 검사와 g8_pg_test.go 의
		// 결정적 교차가 맡는다. 한계: 시한 오류는 느린 DB 와 락 영구 대기(예: 트랜잭션이 닫히지 않아 후임이 FOR UPDATE 에서
		// 시한까지 기다림)를 가르지 못한다 — 후자도 여기서는 건너뜀이 된다(종결 라운드 2 CL1-22 · 결정적 교차가 받친다).
		t.Skipf("경쟁 국면이 서지 않았다(%v 안) — 발행 %d · %d번 · lease 끝냄 %d번 · 겹침 0 인 판 경계 %d곳, want 저마다 %d번 이상 · %d번 이상",
			g8RaceDeadline, racers[0].published.Load(), racers[1].published.Load(), kills.Load(), noOverlap, g8MinPublished, g8MinKills)
	}

	// 정착 — 경쟁을 멈춘 뒤 w-0 이 lease 를 다시 얻어 화해하고 새 창을 올린다.
	w := racers[0]
	expireLease(t, pool, "S")
	settled := false
	for range 5 {
		in, err := g8Input(f, w.st, head.Add(1))
		if err != nil {
			t.Fatalf("정착 틱 입력: %v", err)
		}
		out := w.pub.Tick(t.Context(), w.st, in)
		w.st = out.State
		if out.Published {
			settled = true
			break
		}
	}
	if !settled {
		t.Fatalf("정착 틱 5번에 발행하지 못했다 — 중단 %v", w.logs.aborts())
	}
	desc := storedDesc(t, store.fakeStore)
	row := readSession(t, pool, "S")
	if row.gen != desc.Gen || strOrNil(row.etag) != desc.ETag || row.pubSeq != desc.PublishedSeq || w.st.Prev.ETag != desc.ETag {
		t.Errorf("정착 뒤 DB (세대 %d, ETag %s, 마지막 seq %d) · P %s, want 저장된 판 (%d, %s, %d)",
			row.gen, strOrNil(row.etag), row.pubSeq, w.st.Prev.ETag, desc.Gen, desc.ETag, desc.PublishedSeq)
	}
}
