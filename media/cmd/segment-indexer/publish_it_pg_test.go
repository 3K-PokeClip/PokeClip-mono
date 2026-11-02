package main

// 발행 루프 통합 시험(체크리스트 474 B-4 · 판단 J88) — 픽스처는 publish_it_fixture_pg_test.go 다. 실시계 4초 조각이라 시험마다
// 수십 초가 걸린다. 잰 값은 t.Logf 로 남긴다(-v 로 본다 — 보고의 계측 기록).

import (
	"context"
	"log/slog"
	"math/rand/v2"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// itRun 은 평시 박자로 조각을 흘리는 손이다 — 조각은 계획 끝에서 [0, 400ms) 늦게 닿고 ③ 은 그 뒤 [200ms, 1s) 에 확정된다.
// 쓴 지터는 기록한다(체크리스트 B-4 3 「쓴 지터 분포 기록」). 첫 조각이 연 회차에 init 을 곧바로 확정하고, 그 개시 push 가 연
// 적재가 끝난 뒤 200ms 에 첫 ③ 을 낸다 — 적재 중에 반영된 ③ 는 틱을 부르지 않고 적재 뒤 틱이 대신해(판단 J81 · J80 ⑴) 틱
// 수 등식(③ 수 + 적재 뒤 틱)이 흐려지기 때문이다. 그 갈래는 B-3 34 가 본다.
type itRun struct {
	h       *itHarness
	rng     *rand.Rand
	sid     string
	uploads int
	arrive  []time.Duration
	upload  []time.Duration
}

func newITRun(h *itHarness) *itRun { return &itRun{h: h, rng: rand.New(rand.NewPCG(7, 195))} }

// next 는 seq 조각 하나를 흘리고 ③ 을 확정한다.
func (r *itRun) next(seq int64) {
	r.h.t.Helper()
	a := time.Duration(r.rng.Int64N(int64(400 * time.Millisecond)))
	u := 200*time.Millisecond + time.Duration(r.rng.Int64N(int64(800*time.Millisecond)))
	r.h.waitUntil(seq, a)
	sid := r.h.segment(seq)
	if r.sid == "" {
		r.sid = sid
		r.h.uploadInit(sid)
		r.h.waitLoads(1)
		u = 200 * time.Millisecond
	}
	if sid != r.sid {
		r.h.t.Fatalf("조각 %d 의 회차 %s ≠ %s — 한 회차여야 한다", seq, sid, r.sid)
	}
	time.Sleep(u)
	r.h.uploadPlayback(seq, sid)
	r.uploads++
	r.arrive, r.upload = append(r.arrive, a), append(r.upload, u)
}

// leaseGaps 는 lease 를 늘린 문장(획득 · P0 · lazy 갱신 — 오류 없는 것) 사이의 간격이다. 하나라도 Lease 이상이면 그 사이에
// fence 가 끝났다.
func leaseGaps(stmts []itStmt) []time.Duration {
	var last time.Time
	var out []time.Duration
	for _, s := range stmts {
		if s.err != nil || (s.class != "acquire" && s.class != "p0" && s.class != "renew") {
			continue
		}
		if !last.IsZero() {
			out = append(out, s.start.Sub(last))
		}
		last = s.start
	}
	return out
}

// postLoadTicks 는 적재 작업이 끝난 직후(100ms 안)에 시작한 틱 수다 — 적재 뒤 틱(판단 J80 ⑴)을 Dirty 틱과 가른다.
func postLoadTicks(ticks, loads []itEvent) int {
	n := 0
	for _, tk := range ticks {
		for _, ld := range loads {
			if d := tk.start.Sub(ld.at); d >= 0 && d < 100*time.Millisecond {
				n++
				break
			}
		}
	}
	return n
}

// B-4 1–4 · B-5 「정상 writer 는 stale 판정에 걸리지 않는다」 · B-1 7(PG 형) — 평시 4초 조각 10개 → 7초 공백 → 조각 3개.
//
//	①  평시 틱에서 p0_no_row 0(중단 0 — 정상 writer 는 stale 판정에 걸리지 않는다)
//	②  5초를 넘는 틱 공백에서도 lease 가 이어진다 — 공백 안 lazy 갱신 ≥ 1 · 획득 1 · lease 연장 간격 최댓값 < Lease
//	③  틱 간격 8초 미만 창의 틱당 DB 왕복(P0 · P4 · lazy) 최댓값 ≤ 3 · 틱 수 = ③ 수 + 적재 뒤 틱
//	④  P0 · P4 지연 분포(기록)
//	종료 반납은 끊기지 않은 ctx 로 한 번 나가고 fence 가 비는다(B-1 7 — hookedPool 형을 실물 풀 추적기로)
func TestPublishLoopSteadyStateKeepsLeaseAndTripBound(t *testing.T) {
	h := newITHarness(t)
	h.start(itOptions{})
	run := newITRun(h)
	for k := int64(0); k < 10; k++ {
		run.next(k)
	}
	h.shift = 7 * time.Second
	for k := int64(10); k < 13; k++ {
		run.next(k)
	}
	time.Sleep(2 * time.Second) // 마지막 ③ 의 틱이 끝나게
	stmts := h.tracer.log()

	var releases atomic.Int32
	var releaseCtxErr atomic.Value
	h.tracer.setOnStart(func(ctx context.Context, _ *pgx.Conn, class string) context.Context {
		if class == "release" {
			releases.Add(1)
			releaseCtxErr.Store(fmtErr(ctx.Err()))
		}
		return ctx
	})
	h.stop()

	// ①
	if got := h.abortReasons(); len(got) != 0 {
		t.Errorf("평시 · 공백 구간의 발행 중단 = %v, want 없음(① p0_no_row 0 · 정상 writer 는 stale 판정 0)", got)
	}
	// ②
	if n := h.tracer.count("acquire"); n != 1 {
		t.Errorf("fence 획득 = %d번, want 1(공백 뒤 다시 얻었다면 lease 가 끊긴 것이다)", n)
	}
	p0s := starts(stmts, "p0")
	if len(p0s) < 2 {
		t.Fatalf("P0 = %d번 — 공백을 잴 수 없다", len(p0s))
	}
	gi := 0 // 가장 긴 P0 간격 = 공백
	for i := 1; i+1 < len(p0s); i++ {
		if p0s[i+1].Sub(p0s[i]) > p0s[gi+1].Sub(p0s[gi]) {
			gi = i
		}
	}
	lastBefore, firstAfter := p0s[gi], p0s[gi+1]
	renewsInGap := 0
	for _, s := range stmts {
		if s.class == "renew" && s.err == nil && s.start.After(lastBefore) && s.start.Before(firstAfter) {
			renewsInGap++
		}
	}
	gapSpan := firstAfter.Sub(lastBefore)
	if gapSpan <= 5*time.Second || renewsInGap < 1 {
		t.Errorf("공백 틱 간격 %v · 그 안의 lazy 갱신 %d번, want 간격 > 5s(Lease) · 갱신 ≥ 1", gapSpan, renewsInGap)
	}
	lease := leaseGaps(stmts)
	if m := slicesMax(lease); m >= 5*time.Second {
		t.Errorf("lease 연장 간격 최댓값 %v ≥ Lease 5s — fence 가 끊긴 구간이 있다", m)
	}
	// ③
	counts, spans := tripWindows(stmts)
	var under8 []int
	maxUnder8 := 0
	for i, c := range counts {
		if spans[i] < 8*time.Second {
			under8 = append(under8, c)
			maxUnder8 = max(maxUnder8, c)
		}
	}
	if maxUnder8 > 3 {
		t.Errorf("틱 간격 8초 미만 창의 틱당 DB 왕복 최댓값 = %d, want ≤ 3(4.5 D — 넘으면 배선 결함)", maxUnder8)
	}
	ticks, loads := h.ticks.list(), h.loads.list()
	post := postLoadTicks(ticks, loads)
	if len(ticks) != run.uploads+post {
		t.Errorf("틱 수 = %d, want ③ 수 %d + 적재 뒤 틱 %d(병합으로 추가 0)", len(ticks), run.uploads, post)
	}
	// 종료 반납(B-1 7 PG 형)
	if n := releases.Load(); n != 1 {
		t.Errorf("반납 문장 = %d번, want 1", n)
	}
	if v, _ := releaseCtxErr.Load().(string); v != "" {
		t.Errorf("반납 문장의 ctx 가 이미 끝나 있었다: %s", v)
	}
	if s := h.session(run.sid); s.fence != nil {
		t.Errorf("종료 뒤 writer_fence = %q, want NULL(반납)", *s.fence)
	}
	// B-5 L1–L4 음성 대조 — holdAge 가 음수인 정상 경로라 Expedite 0 · GAP 틱 0
	if n, g := len(h.expedites.list()), len(h.gapTicks.list()); n != 0 || g != 0 {
		t.Errorf("정상 경로의 Expedite %d · GAP 틱 %d, want 0 · 0(B-5 L1–L4)", n, g)
	}
	// L4 — 공백 동안 머리 다음 행이 없다(no_row) → WARN 한 줄(kty 결정 ④ A) · ERROR 0
	stalls := h.logRecords("rewind_publish_stall_seconds")
	for _, l := range stalls {
		if l.level != slog.LevelWarn || l.attrs["gap"] != "no_row" {
			t.Errorf("L4 줄 %v %v, want WARN · gap=no_row 뿐", l.level, l.attrs)
		}
	}

	t.Logf("③ 수 %d · 틱 %d(적재 뒤 %d · 발행 %d) · 적재 %d · 획득 %d · P0 %d · P4 %d · lazy %d · PUT %d",
		run.uploads, len(ticks), post, countOK(ticks), len(loads), h.tracer.count("acquire"),
		h.tracer.count("p0"), h.tracer.count("p4"), h.tracer.count("renew"), len(h.store.putLog()))
	t.Logf("③ 틱당 DB 왕복 — 8초 미만 창 도수 %s(최댓값 %d) · 전체 창 도수 %s · 창 길이 %s",
		histogram(under8), maxUnder8, histogram(counts), dist(spans))
	t.Logf("② 공백 틱 간격 %v · 그 안 lazy %d · lease 연장 간격 %s", gapSpan, renewsInGap, dist(lease))
	t.Logf("④ P0 지연 %s · P4 지연 %s · 획득 지연 %s · lazy 지연 %s", dist(latencies(stmts, "p0")),
		dist(latencies(stmts, "p4")), dist(latencies(stmts, "acquire")), dist(latencies(stmts, "renew")))
	t.Logf("지터 — 조각 늦음 %s · ③ 늦음 %s", dist(run.arrive), dist(run.upload))
	t.Logf("L4 줄 %d(%v) · 반납 %d번", len(stalls), stalls, releases.Load())
}

// B-4 6 — G-1 종단(계획 커밋 순서 5 〔r53〕 · 보안 c4 r1–r4 미확인 1). 행 3 의 ③ 을 막아 사다리 L2 가 GAP 틱을 내게 하고, 그 GAP
// 트랜잭션의 COMMIT 결과를 잃게 한다 — 추적기가 COMMIT 을 서버에 먼저 보내고(GAP 행 · P0 세대가 선다) 클라이언트 쪽 COMMIT 은
// 끝난 ctx 로 실패시킨다(stage=commit · 결과 모름). 같은 자리에서 행 3 의 ③ 결과를 장부 · Dirty 에 먼저 닿게 하고 holdTick 하나를
// 넘겨 그 틱 요청이 GAP 틱에 병합되게 한다(k 업로드 결과 선착). 적재는 2초 늦춘다.
//
//	적재 완료 전  그 스트림 틱 0(병합된 재실행 · 그 사이 holdTick 의 Dirty 틱 모두)
//	적재 뒤      행 3 은 GAP 줄이고 목록이 그 뒤로 이어진다 · 원장 행은 다음 P4 가 put_confirmed 로 바꾼다
func TestPublishLoopUnknownGapCommitEndToEnd(t *testing.T) {
	h := newITHarness(t)
	h.start(itOptions{})
	run := newITRun(h)
	for k := int64(0); k < 3; k++ {
		run.next(k)
	}
	const k = int64(3)
	var gapConn atomic.Pointer[pgx.Conn]
	var commitGate, loadGate atomicGate
	injected := make(chan struct{})
	var hookErr atomic.Value
	h.tracer.setOnStart(func(ctx context.Context, conn *pgx.Conn, class string) context.Context {
		switch {
		case class == "gap":
			gapConn.Store(conn)
		case class == "commit" && conn == gapConn.Load() && commitGate.once():
			if _, err := conn.PgConn().Exec(context.Background(), "commit").ReadAll(); err != nil {
				hookErr.Store("서버 COMMIT 실패: " + err.Error())
			}
			if err := h.markPlayback(k, run.sid); err != nil {
				hookErr.Store(err.Error())
			}
			time.Sleep(1500 * time.Millisecond) // holdTick 하나 이상 — ③ 결과가 반영되고 틱 요청이 병합된다
			close(injected)
			lost, cancel := context.WithCancel(ctx)
			cancel()
			return lost
		case class == "begin_ro" && commitGate.done.Load() && loadGate.once():
			time.Sleep(2 * time.Second)
		}
		return ctx
	})

	h.waitUntil(k, 100*time.Millisecond)
	if sid := h.segment(k); sid != run.sid {
		t.Fatalf("조각 %d 의 회차 %s ≠ %s", k, sid, run.sid)
	}
	for s := k + 1; s <= k+2; s++ {
		run.next(s)
	}
	select {
	case <-injected:
	case <-time.After(20 * time.Second):
		t.Fatal("GAP 틱이 20초 안에 COMMIT 에 닿지 않았다")
	}
	h.waitLoads(2) // 개시 적재 · 결과 모름의 요구 적재
	time.Sleep(1500 * time.Millisecond)
	h.tracer.setOnStart(nil)
	body := h.store.body(h.manifestKey(run.sid))
	h.stop()
	if v, _ := hookErr.Load().(string); v != "" {
		t.Fatalf("주입 훅 실패: %s", v)
	}

	aborts := h.logRecords("rewind_publish_aborted")
	var gapEnd time.Time
	for _, g := range h.gapTicks.list() {
		if g.seq == k && gapEnd.IsZero() {
			gapEnd = g.at
		}
	}
	var loadEnd time.Time
	for _, l := range h.loads.list() {
		if l.start.After(gapEnd) {
			loadEnd = l.at
			break
		}
	}
	early := 0
	for _, tk := range h.ticks.list() {
		if tk.start.After(gapEnd) && tk.start.Before(loadEnd) {
			early++
		}
	}
	if len(aborts) != 1 || aborts[0].attrs["reason"] != "gap_tx_failed" || aborts[0].attrs["stage"] != "commit" {
		t.Errorf("중단 줄 = %v, want gap_tx_failed(stage=commit) 한 줄", aborts)
	}
	if gapEnd.IsZero() || loadEnd.IsZero() || early != 0 {
		t.Errorf("GAP 틱 끝 %v · 적재 끝 %v · 그 사이 시작한 틱 %d, want 둘 다 있고 틱 0(G-1)", gapEnd, loadEnd, early)
	}
	seqs, gaps, err := bodySegments(body)
	if err != nil {
		t.Fatalf("목록 본문 해석 실패: %v", err)
	}
	if !gaps[k] || len(gaps) != 1 || seqs[len(seqs)-1] < k+1 {
		t.Errorf("적재 뒤 목록 seq %v · GAP 줄 %v, want 행 %d 만 GAP · 그 뒤로 이어짐", seqs, gaps, k)
	}
	versions := h.store.versions(h.manifestKey(run.sid))
	for i, v := range versions {
		vs, vg, verr := bodySegments(v)
		if verr != nil || (slices.Contains(vs, k) && !vg[k]) {
			t.Errorf("판 %d/%d 에서 행 %d 가 일반 줄로 실렸다(seq %v · GAP %v · %v) — 줄이 뒤에 GAP 으로 바뀐다(G-1)", i+1, len(versions), k, vs, vg, verr)
		}
	}
	var state string
	if err := h.pool.QueryRow(context.Background(),
		`SELECT published_state FROM stream_published_gaps WHERE stream_id = $1 AND seq = $2`, h.stream, k).Scan(&state); err != nil ||
		state != "put_confirmed" {
		t.Errorf("원장 행 %d = %q(%v), want put_confirmed", k, state, err)
	}

	t.Logf("G-1 — GAP 틱 끝 → 적재 끝 %v · 그 사이 틱 %d · 틱 %d(발행 %d) · GAP 틱 %d · 적재 %d · Expedite %d · 판 %d · 목록 seq %v · GAP 줄 %v · 원장 %s",
		loadEnd.Sub(gapEnd), early, len(h.ticks.list()), countOK(h.ticks.list()), len(h.gapTicks.list()),
		len(h.loads.list()), len(h.expedites.list()), len(versions), seqs, gaps, state)
	stmts := h.tracer.log()
	t.Logf("G-1 지연 — 원장 문장 %s · P4(창 안 GAP 행 포함 — 363 미확인 10) %s", dist(latencies(stmts, "gap")), dist(latencies(stmts, "p4")))
}

// intervals 는 기록 사이의 간격이다(시각 순).
func intervals(ev []itEvent) []time.Duration {
	var out []time.Duration
	for i := 1; i < len(ev); i++ {
		out = append(out, ev[i].at.Sub(ev[i-1].at))
	}
	return out
}

// B-4 5 · 7 — 행 3 을 캐시는 GAP 적격 · DB 는 부적격(장부의 길이만 0 으로 고친다 — 불변 트리거 밖 열)으로 두고 ③ 을 막는다. 행 4 ·
// 5 는 ③ 확정이다. 머리 다음 행이 3 인 동안
//
//	⑤  L1 되풀이 — holdTick 마다 Expedite(행 3)
//	⑦  서지 않은 GAP 틱 — GAP_HOLD 뒤 차선이 비는 holdTick 마다 GAP 틱(후보 3) · 모두 서지 않음
//	L4  발행 정체 7.5초를 넘은 첫 평가에 ERROR gap=unstood 한 줄(363 ⓖ 종단)
//
// L1 뒤 재시도까지 시간(363 미확인 1 · 8)은 실물 업로더(스위퍼 주기)를 붙이지 않아 재지 않는다 — 기록만 남긴다.
func TestPublishLoopStalledRowRepeatsL1AndUnstoodGapTicks(t *testing.T) {
	h := newITHarness(t)
	h.start(itOptions{})
	run := newITRun(h)
	for k := int64(0); k < 3; k++ {
		run.next(k)
	}
	const k = int64(3)
	h.waitUntil(k, 100*time.Millisecond)
	if sid := h.segment(k); sid != run.sid {
		t.Fatalf("조각 %d 의 회차 %s ≠ %s", k, sid, run.sid)
	}
	h.exec(`UPDATE stream_segments SET duration_ms = 0 WHERE stream_id = $1 AND seq = $2`, h.stream, k)
	for s := k + 1; s <= k+2; s++ {
		run.next(s)
	}
	h.waitUntil(k+3, 0)
	stmts := h.tracer.log()
	h.stop()

	exp, gaps := h.expedites.list(), h.gapTicks.list()
	for _, e := range exp {
		if e.seq != k {
			t.Errorf("Expedite seq %d, want %d 뿐", e.seq, k)
		}
	}
	if gi := slicesMax(intervals(exp)); len(exp) < 5 || gi > 1500*time.Millisecond {
		t.Errorf("Expedite %d번 · 최대 간격 %v, want ≥ 5번 · holdTick 마다(간격 ≤ 1.5s)", len(exp), gi)
	}
	for _, g := range gaps {
		if g.seq != k || g.ok {
			t.Errorf("GAP 틱 seq %d · 섬 %v, want 후보 %d · 서지 않음", g.seq, g.ok, k)
		}
	}
	if len(gaps) < 4 {
		t.Errorf("서지 않은 GAP 틱 %d번, want ≥ 4(GAP_HOLD 뒤 holdTick 마다)", len(gaps))
	}
	stalls := h.logRecords("rewind_publish_stall_seconds")
	if len(stalls) != 1 || stalls[0].level != slog.LevelError || stalls[0].attrs["gap"] != "unstood" {
		t.Errorf("L4 줄 = %v, want ERROR gap=unstood 한 줄", stalls)
	}
	if n := countOK(h.ticks.list()); n != 3 {
		t.Errorf("발행 %d번, want 3(행 0..2 — 행 3 이 서지 않아 머리가 2 에 멈춘다)", n)
	}
	var gen int64
	if err := h.pool.QueryRow(context.Background(), `SELECT manifest_gen FROM stream_sessions WHERE session_id = $1`,
		run.sid).Scan(&gen); err != nil {
		t.Fatalf("세대 조회 실패: %v", err)
	}

	gapP0 := 0
	for _, s := range stmts {
		if s.class == "gap" {
			gapP0++
		}
	}
	t.Logf("⑤ Expedite %d번(행 %d) · 간격 %s · 첫 L1 은 행 %d 계획 끝 뒤 %v", len(exp), k, dist(intervals(exp)), k,
		firstAt(exp).Sub(h.wall(k).Add(4*time.Second)))
	t.Logf("⑦ 서지 않은 GAP 틱 %d번 · 간격 %s · GAP 문장 %d · BEGIN %d · COMMIT %d · 세대 %d(P0 %d)", len(gaps),
		dist(intervals(gaps)), gapP0, h.tracer.count("begin"), h.tracer.count("commit"), gen, h.tracer.count("p0"))
	t.Logf("L4 %v · 틱 %d(발행 %d) · L1 뒤 재시도까지 시간: 미확인(실물 업로더 없음)", stalls, len(h.ticks.list()),
		countOK(h.ticks.list()))
}

// firstAt 은 첫 기록의 시각이다(없으면 영값).
func firstAt(ev []itEvent) time.Time {
	if len(ev) == 0 {
		return time.Time{}
	}
	return ev[0].at
}
