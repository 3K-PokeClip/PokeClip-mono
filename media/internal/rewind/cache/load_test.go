package cache_test

// 적재 순서 규약(POK-195 M4 PR ⓒ 커밋 5 — 계획 4.5 A3 결정 2 · 5 · 6 · 증명 Q1–Q4 · 체크리스트 A-2 · A-4 · A-5).
// 적재 상태 · 토큰 · push 로그는 캐시가 스트림마다 든다. 모든 push 는 캐시 메서드이고, 메서드마다 첫 줄에 「적재
// 중이면 로그에 적고 돌아간다」 관문이 있다. CompleteLoad 는 토큰이 맞을 때만 적재분으로 뷰를 갈아 끼우고 로그를
// 차례대로 재생한다. 뷰가 없는 스트림의 개시 · 주조 push 는 뷰를 만들지 않고 적재를 요구한다.

import (
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// twoSessions 는 컷오프 0 의 적재분이다 — 회차 P(끝남 · init 확정) 0..1(③ 확정) · S(live · init 미확정 · P 계승 ·
// base 5) 2..3(③ 전). 적재 중 push 가 바꿀 값(행 · ③ · GAP · 교정 · init · 계승 · state)을 모두 든다.
func twoSessions() index.RewindLedger {
	p := index.RewindSession{SessionID: "P", State: "ended", EndReason: "offline", InitUploaded: true,
		FirstPDT: at(0), TargetDuration: 6, MinSeq: 0}
	s := index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 5, FirstPDT: at(8 * time.Second),
		InheritsSession: "P", TargetDuration: 6, MinSeq: 2}
	return ledgerOf(0, append(ledgerRun("P", 0, 1, at(0)), pendingRun("S", 2, 3, at(8*time.Second))...), p, s)
}

// hintOf 는 seq 를 힌트(P.MSN)로 쓴다.
func hintOf(seq int64) *int64 { return &seq }

// pushes_during_load_are_replayed(계획 6.3 #79 · A3 증명 — Q1 · Q4) — 적재 중에 온 push 는 뷰에 바로 닿지 않고 로그에
// 쌓였다가 CompleteLoad 가 적재분으로 뷰를 갈아 끼운 뒤 차례대로 재생된다. 적재분(워커가 BeginLoad 뒤에 뜬 스냅숏)은
// 그 뒤에 커밋된 사실을 모른다 — 재생이 없거나 관문이 한 메서드라도 빠지면(옛 뷰에 바로 적용 · 적재분이 덮는다)
// 그 사실이 사라진다. push 는 일곱 메서드 전부이고 인덱서가 부르는 둘(ApplyInsert · ApplyTailCorrection)을
// 포함한다. 재생 순서가 뒤집히면 행 4 의 교정이 행보다 먼저 와 버려진다. 적재분은 워커 고루틴이 값으로만 만들고
// 캐시를 만지지 않는다(-race — A3 「동시성」).
func TestPushesDuringLoadAreReplayed(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, twoSessions())
	token := c.BeginLoad(stream, nil)
	snapshot := make(chan index.RewindLedger)
	go func() { snapshot <- twoSessions() }() // 워커 — BeginLoad 뒤의 장부를 값으로 만든다

	c.ApplyInsert(stream, 4, seedOf("S", 4, at(16*time.Second), 2000))
	c.ApplyTailCorrection(stream, 4, 4012)
	c.ApplyPlaybackUploaded(stream, 2)
	c.ApplyPlaybackUploaded(stream, 4)
	c.ApplyPublishedGap(stream, 3)
	c.ApplyInitUploaded(stream, "S", false)
	c.ApplyInheritanceRevoked(stream, "S")
	c.ApplySessionEnding(stream, "S")
	if _, ok := c.Snapshot(stream).Cutoff(); ok {
		t.Error("적재 중에 컷오프가 읽혔다 — 적재 중 읽기는 「모른다」다")
	}

	if applied, _ := c.CompleteLoad(stream, token, <-snapshot); !applied {
		t.Fatal("CompleteLoad 가 적재를 적용하지 않았다")
	}
	want := append(ledgerRun("P", 0, 1, at(0)), []index.RewindRow{
		{Seq: 2, SessionID: "S", DurationMS: 4000, PlaybackPDT: at(8 * time.Second), PlaybackS3Key: key(2), PlaybackUploaded: true},
		{Seq: 3, SessionID: "S", DurationMS: 4000, PlaybackPDT: at(12 * time.Second), PlaybackS3Key: key(3), IsGap: true},
		{Seq: 4, SessionID: "S", DurationMS: 4012, PlaybackPDT: at(16 * time.Second), PlaybackS3Key: key(4), PlaybackUploaded: true},
	}...)
	if got := c.Snapshot(stream).RowsFrom(0); !reflect.DeepEqual(got, boundaryRows(want)) {
		t.Errorf("재생 뒤 행 =\n%+v\nwant\n%+v", got, boundaryRows(want))
	}
	s, _ := c.Session(stream, "S")
	if !s.InitUploaded || s.InheritsSession != "" || s.DiscontinuityBase != 0 || s.State != "ending" {
		t.Errorf("재생 뒤 S = %+v, want init 확정 · 계승 없음 · base 0 · ending", s.RewindSession)
	}
}

// stale_load_result_discarded(계획 6.3 #78) — CompleteLoad 는 그 스트림의 지금 토큰일 때만 뷰를 갈아 끼운다. 적재를
// 다시 열면(요구가 겹쳤다 · 로그가 넘쳤다) 옛 토큰의 결과는 버려지고 적재 중이 이어진다. 적재 중이 아닌 스트림 ·
// 모르는 스트림의 결과도 버린다.
func TestStaleLoadResultDiscarded(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, openedAt40())
	stale := c.BeginLoad(stream, nil)
	fresh := c.BeginLoad(stream, nil)
	staleLedger := ledgerOf(40, pendingRun("O", 40, 41, at(0)), withMinSeq(sessionO, 40))
	freshLedger := ledgerOf(40, pendingRun("O", 40, 42, at(0)), withMinSeq(sessionO, 40))

	if applied, _ := c.CompleteLoad(stream, stale, staleLedger); applied || !c.Loading(stream) {
		t.Errorf("옛 토큰 결과 = (적용 %v, 적재 중 %v), want (거짓, 참)", applied, c.Loading(stream))
	}
	if applied, _ := c.CompleteLoad(stream, fresh, freshLedger); !applied || c.Loading(stream) {
		t.Errorf("지금 토큰 결과 = (적용 %v, 적재 중 %v), want (참, 거짓)", applied, c.Loading(stream))
	}
	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{40, 41, 42}) {
		t.Errorf("행 = %v, want [40 41 42](지금 토큰의 적재분)", got)
	}
	for _, sid := range []string{stream, "nobody"} {
		if applied, _ := c.CompleteLoad(sid, fresh, staleLedger); applied {
			t.Errorf("적재 중이 아닌 스트림 %s 의 결과를 적용했다", sid)
		}
	}
}

// no_publish_while_loading(계획 6.3 #80 · 체크리스트 A-2 3 — G-1 창의 캐시 쪽) — 적재 중 읽기는 「모른다」다:
// Loading 참 · Playlist 거짓 · Snapshot 컷오프 없음(행도 없음). 루프는 적재 중인 스트림에 틱을 내지 않는다(커밋
// 7). 회차 값은 적재 중에도 읽힌다 — 틱을 막는 것은 게이트의 적재 중 칸이다(판단 J37 · publish.ShouldTick).
func TestNoPublishWhileLoading(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(40, ledgerRun("O", 40, 42, at(0)), withMinSeq(sessionO, 40)))
	w := window(t, c)
	if _, ok := c.Playlist(stream, "O", w); !ok {
		t.Fatal("적재 전 Playlist(O) = 거짓(전제)")
	}

	token := c.BeginLoad(stream, nil)

	_, cut := c.Snapshot(stream).Cutoff()
	_, listed := c.Playlist(stream, "O", w)
	if !c.Loading(stream) || listed || cut || len(c.Snapshot(stream).RowsFrom(0)) != 0 {
		t.Errorf("적재 중 (Loading, Playlist, 컷오프) = (%v, %v, %v), want (참, 거짓, 거짓)", c.Loading(stream), listed, cut)
	}
	if s, ok := c.Session(stream, "O"); !ok || s.State != "live" {
		t.Errorf("적재 중 Session(O) = (%+v, %v), want live 회차", s.RewindSession, ok)
	}
	c.CompleteLoad(stream, token, ledgerOf(40, ledgerRun("O", 40, 42, at(0)), withMinSeq(sessionO, 40)))
	if _, ok := c.Playlist(stream, "O", w); !ok || c.Loading(stream) {
		t.Errorf("적재 뒤 (Playlist, Loading) = (%v, %v), want (참, 거짓)", ok, c.Loading(stream))
	}
}

// bounded_load_keeps_session_min_seq(계획 6.3 #84 · A1 E1) — 회차 최소 seq 는 적재분이 싣는 장부 값이고 적재 하한과
// 별개다. 하한(700)이 계승 회차 S 의 첫 행(600) 뒤여도 S 의 MinSeq 는 600 이라, 끊김 표시(계승 회차의 첫 조각
// 앞)가 뷰 첫 행 700 으로 옮겨 가지 않는다. 적재한 행의 최솟값으로 되돌리면 이미 나간 목록 머리에 표시가 새로
// 선다.
func TestBoundedLoadKeepsSessionMinSeq(t *testing.T) {
	c := &cache.Cache{}
	sessionS := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true, FirstPDT: at(0),
		InheritsSession: "P", TargetDuration: 6, MinSeq: 600}
	l := ledgerOf(0, ledgerRun("S", 700, 710, at(400*time.Second)), sessionS)
	l.FloorSeq = 700
	load(t, c, stream, l)

	if s, _ := c.Session(stream, "S"); s.MinSeq != 600 {
		t.Errorf("Session(S).MinSeq = %d, want 600(장부 값)", s.MinSeq)
	}
	body := render(t, c, "S", boundary.Window{ScanFrom: 700, TailSeq: 700, HeadSeq: 710})
	if strings.Contains(body, "#EXT-X-DISCONTINUITY\n") {
		t.Errorf("뷰 첫 행 700 앞에 끊김 표시가 섰다 — S 의 첫 조각은 600 이다:\n%s", body)
	}
}

// negative_tombstone_holds_no_session_entries(계획 6.3 #85 — 〔r41〕 재정의) — 적재 결과가 재생 뒤에도 컷오프
// 없음이면 캐시는 뷰 대신 「컷오프 없음」 표식 하나만 든다. 그 스트림의 push 는 무시한다 — 회차 개시 push 도 회차
// 항목을 만들지 않고 적재를 되풀이하지 않는다(B-2). 표식을 푸는 것은 주조 push 뿐이다
// (TestSeededAfterNegativeTombstoneCreatesView).
func TestNegativeTombstoneHoldsNoSessionEntries(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, index.RewindLedger{})

	c.ApplyInsert(stream, 5, openingOf(sessionO, 5, 4000))
	c.ApplyInsert(stream, 6, seedOf("O", 6, at(4*time.Second), 4000))
	c.ApplyPlaybackUploaded(stream, 5)
	c.ApplyInitUploaded(stream, "O", false)
	c.ApplySessionEnding(stream, "O")

	if _, ok := c.Session(stream, "O"); ok {
		t.Error("부정 표식 스트림의 개시 push 가 회차 항목을 만들었다")
	}
	if _, ok := c.Snapshot(stream).Cutoff(); ok || len(c.Snapshot(stream).RowsFrom(0)) != 0 {
		t.Error("부정 표식 스트림에 뷰가 생겼다")
	}
	if c.Loading(stream) || len(c.DemandedLoads()) != 0 {
		t.Errorf("부정 표식 스트림이 적재를 되풀이했다(적재 중 %v)", c.Loading(stream))
	}
}

// seeded_after_negative_tombstone_creates_view(계획 6.3 #110 · 〔r42〕 재정의 · #119) — 부정 표식 스트림에 주조 push 가
// 오면 표식을 지우고 스스로 적재를 연다(요구 목록에 오른다). 뷰를 push 하나로 만들지 않는다 — 주조가 비개시 행에서
// 일어났으면 표식이 있는 동안 무시한 회차 개시(여기서는 O)를 push 는 모른다. 적재가 끝나면 그 회차를 포함한 뷰다.
func TestSeededAfterNegativeTombstoneCreatesView(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, index.RewindLedger{})
	c.ApplyInsert(stream, 40, openingOf(sessionO, 40, 7600)) // 표식이 있어 무시된다

	c.ApplyInsert(stream, 41, seeded(seedOf("O", 41, at(7600*time.Millisecond), 4000)))

	if _, ok := c.Snapshot(stream).Cutoff(); ok {
		t.Error("주조 push 가 뷰를 바로 만들었다(뮤테이션 119) — 적재를 요구해야 한다")
	}
	if got := c.DemandedLoads(); !c.Loading(stream) || !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream}}) {
		t.Fatalf("주조 push 뒤 (적재 중, 요구) = (%v, %+v), want (참, [%s])", c.Loading(stream), got, stream)
	}
	load(t, c, stream, ledgerOf(41, pendingRun("O", 41, 41, at(7600*time.Millisecond)), withMinSeq(sessionO, 40)))
	if s, ok := c.Session(stream, "O"); !ok || s.MinSeq != 40 {
		t.Errorf("적재 뒤 Session(O) = (%+v, %v), want 회차 O(최소 seq 40)", s.RewindSession, ok)
	}
	if cutoff, ok := c.Snapshot(stream).Cutoff(); !ok || cutoff != 41 {
		t.Errorf("적재 뒤 Cutoff() = (%d, %v), want (41, true)", cutoff, ok)
	}
}

// 다섯째 요구(계획 6.3 #109 의 캐시 층 · A3 결정 6 ⑤) — 뷰가 없는 스트림에 회차 개시 push 가 오면 캐시가 그 push 를
// 로그에 담고 스스로 적재를 열어 요구 목록에 올린다. 부팅 때 live 회차가 없던 스트림 · Forget 된 스트림의 다음 방송이
// 이 길로 캐시에 들어온다. 개시도 주조도 아닌 push 는 할 일이 없다(뷰가 생기면 적재가 그 행을 싣는다). 뷰가 있는
// 스트림의 주조 push 는 적재를 부르지 않는다(B-2).
func TestViewlessPushDemandsLoad(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert("plain", 3, seedOf("O", 3, at(0), 4000))
	if c.Loading("plain") || len(c.DemandedLoads()) != 0 {
		t.Error("뷰가 없는 스트림의 일반 행 push 가 적재를 불렀다")
	}

	c.ApplyInsert(stream, 40, openingOf(sessionO, 40, 7600))

	if got := c.DemandedLoads(); !c.Loading(stream) || !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream}}) {
		t.Errorf("뷰 없는 개시 push 뒤 (적재 중, 요구) = (%v, %+v), want (참, [%s])", c.Loading(stream), got, stream)
	}
	if _, ok := c.Snapshot(stream).Cutoff(); ok {
		t.Error("개시 push 가 뷰를 만들었다")
	}
	viewed := &cache.Cache{}
	load(t, viewed, stream, openedAt40())
	viewed.ApplyInsert(stream, 41, seeded(seedOf("O", 41, at(7600*time.Millisecond), 4000)))
	if viewed.Loading(stream) || len(viewed.DemandedLoads()) != 0 {
		t.Error("뷰가 있는 스트림의 주조 push 가 적재를 불렀다")
	}
}

// load_log_overflow_self_restarts(체크리스트 A-2 4) — 적재 중 로그가 LoadPushBuffer 를 넘으면 캐시가 그 적재를
// 스스로 다시 연다: 새 토큰 · 로그 비움 · 요구 목록 등재(적재의 힌트를 잇는다). 옛 토큰의 결과는 버려지고 적재
// 중이 이어진다. 넘친 사이의 사실은 다시 연 적재의 스냅숏에 든다(증명 끝줄) — 로그에 남지 않는다.
func TestLoadLogOverflowSelfRestarts(t *testing.T) {
	c := &cache.Cache{Options: cache.Options{LoadPushBuffer: 2}}
	load(t, c, stream, openedAt40())
	first := c.BeginLoad(stream, hintOf(40))

	pushRun(c, "O", 41, 43, at(7600*time.Millisecond)) // 셋째 push 가 로그 상한 2 를 넘긴다

	if got := c.DemandedLoads(); !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream, Hint: hintOf(40)}}) {
		t.Errorf("넘침 뒤 요구 = %+v, want [%s · 힌트 40]", got, stream)
	}
	if applied, _ := c.CompleteLoad(stream, first, openedAt40()); applied || !c.Loading(stream) {
		t.Errorf("넘치기 전 토큰 결과 = (적용 %v, 적재 중 %v), want (거짓, 참)", applied, c.Loading(stream))
	}
	load(t, c, stream, ledgerOf(40, pendingRun("O", 40, 43, at(0)), withMinSeq(sessionO, 40)))
	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{40, 41, 42, 43}) {
		t.Errorf("다시 연 적재 뒤 행 = %v, want [40 41 42 43]", got)
	}
}

// 영값 캐시(장부 421 P-10 · 체크리스트 A-3 7)는 튜너블의 기본값(LoadPushBuffer 1,024)을 쓴다 — 적재 중 push 한 건에
// 넘치지 않고 스스로 다시 열지 않는다. 0 을 「제한 없음」으로도 「0 건」으로도 읽지 않는다.
func TestZeroValueCacheAbsorbsPushDuringLoad(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, openedAt40())
	token := c.BeginLoad(stream, nil)

	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 4000))

	if got := c.DemandedLoads(); len(got) != 0 {
		t.Errorf("push 한 건 뒤 요구 = %+v, want 없음(넘침 0)", got)
	}
	if applied, _ := c.CompleteLoad(stream, token, openedAt40()); !applied {
		t.Error("push 한 건 뒤 토큰이 바뀌었다(스스로 다시 열었다)")
	}
	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{40, 41}) {
		t.Errorf("재생 뒤 행 = %v, want [40 41]", got)
	}
	if o := (cache.Options{}).WithDefaults(); o != (cache.Options{LedgerLookback: 2 * time.Hour, LedgerRowCap: 10000,
		LoadPushBuffer: 1024}) {
		t.Errorf("Options{}.WithDefaults() = %+v, want 되짚기 2시간 · 행 10,000 · 로그 1,024", o)
	}
	if o := (cache.Options{LedgerRowCap: 7}).WithDefaults(); o.LedgerRowCap != 7 || o.LoadPushBuffer != 1024 {
		t.Errorf("WithDefaults 가 채운 칸을 바꿨다: %+v", o)
	}
}

// 요구 적재 입구(판단 J38) — 요구는 목록에 오르기만 하고, 같은 스트림의 요구는 하나로 합친다(힌트는 가장 낮은 것 —
// 두 요구의 하한을 모두 덮는다). DemandedLoads 는 쌓인 요구를 돌려주고 목록을 비운다. 적재 중인 스트림의 요구는 그
// 적재의 힌트와도 합친다 — 적재가 실패해 다시 올릴 때(판단 J46) 힌트를 잃지 않는다.
func TestDemandLoadMergesSameStream(t *testing.T) {
	c := &cache.Cache{}
	c.DemandLoad(stream, nil)
	c.DemandLoad(stream, hintOf(9))
	c.DemandLoad("other", nil)
	c.DemandLoad(stream, hintOf(7))
	c.DemandLoad("other", nil)

	want := []cache.LoadDemand{{StreamID: stream, Hint: hintOf(7)}, {StreamID: "other"}}
	if got := c.DemandedLoads(); !reflect.DeepEqual(got, want) {
		t.Errorf("요구 = %+v, want %+v", got, want)
	}
	if got := c.DemandedLoads(); len(got) != 0 {
		t.Errorf("비운 뒤 요구 = %+v, want 없음", got)
	}
	c.BeginLoad(stream, hintOf(5))
	c.DemandLoad(stream, nil)
	if got := c.DemandedLoads(); !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream, Hint: hintOf(5)}}) {
		t.Errorf("적재 중 요구 = %+v, want [%s · 힌트 5](적재의 힌트)", got, stream)
	}
}

// boundaryRows 는 적재 행을 경계 행으로 옮긴다(두 타입은 필드가 같다).
func boundaryRows(rows []index.RewindRow) []boundary.Row {
	out := make([]boundary.Row, 0, len(rows))
	for _, r := range rows {
		out = append(out, boundary.Row(r))
	}
	return out
}

// trim_keeps_rows_from_last_published_msn(계획 6.3 #86 · A3 결정 5) — 발행이 성공하면 루프가 Trim 을 부른다(커밋 7).
// Trim 은 min(마지막으로 계산한 꼬리, live 소유 회차의 발행본 MSN) 앞의 행만 버린다 — 다음 창 계산(prevTail = 꼬리)과
// 발행본 재구성(A1 무결성 대조가 읽는 [P.MSN, P 끝])이 둘 다 뷰 안에서 선다. 꼬리만으로 자르면 발행본 MSN 이 꼬리보다
// 앞일 때 그 행이 빠져 대조가 실패한다. 뷰 하한 앞으로는 되돌아가지 않고, 잘라도 이어 붙이기는 그대로다.
func TestTrimKeepsRowsFromLastPublishedMSN(t *testing.T) {
	c := &cache.Cache{}
	sessionS := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true, FirstPDT: at(0), TargetDuration: 6}
	load(t, c, stream, ledgerOf(0, ledgerRun("S", 0, 20, at(0)), sessionS))

	for _, tt := range []struct {
		tail, msn int64
		first     int64
	}{
		{10, 6, 6},   // 발행본 MSN 이 꼬리보다 앞 — MSN 부터 남긴다
		{12, 15, 12}, // 꼬리가 앞 — 꼬리부터 남긴다
		{3, 3, 12},   // 뷰 하한 앞 — 되돌아가지 않는다
	} {
		c.Trim(stream, tt.tail, tt.msn)
		if rows := c.Snapshot(stream).RowsFrom(0); len(rows) == 0 || rows[0].Seq != tt.first || rows[len(rows)-1].Seq != 20 {
			t.Errorf("Trim(꼬리 %d, MSN %d) 뒤 행 = %v, want %d..20", tt.tail, tt.msn, seqsOf(rows), tt.first)
		}
	}
	c.ApplyInsert(stream, 21, seedOf("S", 21, at(84*time.Second), 4000))
	c.Trim(stream, 30, 30) // 뷰 끝 너머 — 다 버려도 다음 행 자리는 그대로다
	c.ApplyInsert(stream, 22, seedOf("S", 22, at(88*time.Second), 4000))
	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{22}) {
		t.Errorf("뷰 끝 너머 Trim 뒤 행 = %v, want [22](이어 붙이기 그대로)", got)
	}
}

// idle_stream_view_forgotten_after_reconnect_window(계획 6.3 #87 · A3 결정 5 — 입력은 시험이 준다) — live 회차가
// 없고 마지막 행 끝이 ReconnectWindow(300초)보다 오래된 스트림의 뷰나 부정 표식을 버린다. 입력(마지막 행 끝 = 커서
// Tail 끝 · 없으면 적재 완료 시각)은 루프가 댄다(커밋 7). live 회차가 있거나 · 창 안이거나(정확히 300초) · 적재
// 중인 스트림은 남긴다. 버린 스트림의 다음 방송은 뷰 없는 개시 push 로 다시 적재된다(요구 ⑤).
func TestIdleStreamViewForgottenAfterReconnectWindow(t *testing.T) {
	const window = 300 * time.Second
	end := at(time.Hour)
	ended := index.RewindSession{SessionID: "P", State: "ended", EndReason: "offline", InitUploaded: true,
		FirstPDT: at(0), TargetDuration: 6}
	live := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true, FirstPDT: at(0), TargetDuration: 6}
	for _, tt := range []struct {
		name string
		prep func(t *testing.T, c *cache.Cache)
		now  time.Time
		want bool
	}{
		{"끝난_방송_창_밖", func(t *testing.T, c *cache.Cache) {
			load(t, c, stream, ledgerOf(0, ledgerRun("P", 0, 3, at(0)), ended))
		},
			end.Add(window + time.Second), true},
		{"끝난_방송_창_경계", func(t *testing.T, c *cache.Cache) {
			load(t, c, stream, ledgerOf(0, ledgerRun("P", 0, 3, at(0)), ended))
		},
			end.Add(window), false},
		{"live_회차가_있음", func(t *testing.T, c *cache.Cache) { load(t, c, stream, ledgerOf(0, ledgerRun("S", 0, 3, at(0)), live)) },
			end.Add(time.Hour), false},
		{"부정_표식_창_밖", func(t *testing.T, c *cache.Cache) { load(t, c, stream, index.RewindLedger{}) },
			end.Add(window + time.Second), true},
		{"적재_중", func(t *testing.T, c *cache.Cache) {
			load(t, c, stream, ledgerOf(0, ledgerRun("P", 0, 3, at(0)), ended))
			c.BeginLoad(stream, nil)
		}, end.Add(time.Hour), false},
		{"모르는_스트림", func(*testing.T, *cache.Cache) {}, end.Add(time.Hour), false},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := &cache.Cache{}
			tt.prep(t, c)
			loading := c.Loading(stream)

			if got := c.Forget(stream, end, tt.now, window); got != tt.want {
				t.Fatalf("Forget = %v, want %v", got, tt.want)
			}
			if c.Loading(stream) != loading {
				t.Errorf("Forget 뒤 적재 중 = %v, want %v(적재 중 스트림은 건너뛴다)", c.Loading(stream), loading)
			}
			if !tt.want {
				return
			}
			c.ApplyInsert(stream, 40, openingOf(sessionO, 40, 7600)) // 다음 방송
			if !c.Loading(stream) {
				t.Error("버린 스트림의 다음 방송 개시 push 가 적재를 열지 않았다")
			}
		})
	}
}

// 첫째 요구(계획 4.5 A3 결정 6 ① · 체크리스트 A-5 3 — 캐시가 알아챌 자리) — 계승 개시 push 가 왔는데 뷰가 접두
// 회차를 모르면 적재를 요구한다(힌트 없음). 개시 push 자체는 반영한다(회차 축 · 행). 접두 회차를 알면 요구하지
// 않는다(TestApplyInsertRecordsInheritedOpening).
func TestInheritingOpeningWithUnknownPrefixDemandsLoad(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, openedAt40())
	sessionS := index.RewindSession{SessionID: "S", State: "live", FirstPDT: at(132 * time.Second), InheritsSession: "Q",
		TargetDuration: 6}

	c.ApplyInsert(stream, 41, openingOf(sessionS, 41, 4000))

	if got := c.DemandedLoads(); !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream}}) {
		t.Errorf("요구 = %+v, want [%s](접두 회차 Q 를 모른다)", got, stream)
	}
	if s, ok := c.Session(stream, "S"); !ok || s.MinSeq != 41 || c.Loading(stream) {
		t.Errorf("개시 push 뒤 (Session(S), 적재 중) = (%+v %v, %v), want (최소 seq 41, 거짓)", s.RewindSession, ok, c.Loading(stream))
	}
}

// 회차 push 셋(계획 4.5 B #2 · A2 결정 8 · 뮤테이션 35 · 106 의 캐시 층) — ApplyInitUploaded 는 init 확정과
// (revoked 면) 계승 해제(계승 "" · base 0)를 한 호출로 반영한다(106 — 해제를 버리면 「init 확정 ∧ 낡은 계승」이
// 렌더에 보인다). ApplyInheritanceRevoked 는 P2′ 가 쓴 값(계승 "" · base 0)을 반영한다(35). ApplySessionEnding 은
// live → ending 한 방향이다 — ended 를 되돌리지 않는다. 캐시가 모르는 회차면 아무것도 하지 않는다.
func TestSessionPushesApplyOneDirection(t *testing.T) {
	inherited := func(id, state string) index.RewindSession {
		return index.RewindSession{SessionID: id, State: state, FirstPDT: at(0), InheritsSession: "P", DiscontinuityBase: 5,
			TargetDuration: 6}
	}
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(0, nil, inherited("A", "live"), inherited("B", "live"), inherited("C", "live"),
		inherited("D", "ended")))

	c.ApplyInitUploaded(stream, "A", true)
	c.ApplyInitUploaded(stream, "B", false)
	c.ApplyInheritanceRevoked(stream, "C")
	c.ApplySessionEnding(stream, "C")
	c.ApplySessionEnding(stream, "D")
	c.ApplyInitUploaded(stream, "Z", true)
	c.ApplySessionEnding(stream, "Z")

	for _, tt := range []struct {
		id, state, inherits string
		init                bool
		base                int64
	}{
		{"A", "live", "", true, 0},
		{"B", "live", "P", true, 5},
		{"C", "ending", "", false, 0},
		{"D", "ended", "P", false, 5},
	} {
		s, _ := c.Session(stream, tt.id)
		if s.State != tt.state || s.InheritsSession != tt.inherits || s.InitUploaded != tt.init || s.DiscontinuityBase != tt.base {
			t.Errorf("%s = (state %s, 계승 %q, init %v, base %d), want (%s, %q, %v, %d)", tt.id, s.State, s.InheritsSession,
				s.InitUploaded, s.DiscontinuityBase, tt.state, tt.inherits, tt.init, tt.base)
		}
	}
	if _, ok := c.Session(stream, "Z"); ok {
		t.Error("모르는 회차 Z 에 회차 push 가 항목을 만들었다")
	}
}

// 적재 중에 온 TD 분할 개시 push(EndingSessionID 를 싣는다 — 계획 4.5 B #1)는 로그에 담겼다가 적재분 위에 재생된다
// (커밋 5 의 관문 — 새 규칙 0 · 판단 J62). 적재분의 옛 회차 O 가 live 면(스냅숏이 분할 커밋보다 먼저) 재생 뒤 ending
// 이고, 이미 ending · ended 면(스냅숏이 분할 커밋 뒤 · 그 뒤 종료) 재생이 되돌리지도 넘어서지도 않는다(한 방향).
// 잡는 결함: 재생이 그 칸을 버리면 옛 회차가 live 로 남아 게이트가 열리고, 무조건 ending 을 쓰면 ended 가 되살아난다.
func TestLoadReplaysEndingSessionOfSplitOpening(t *testing.T) {
	for _, snapState := range []string{"live", "ending", "ended"} {
		t.Run("스냅숏_"+snapState, func(t *testing.T) {
			c := &cache.Cache{}
			token := c.BeginLoad(stream, nil)
			split := index.RewindSession{SessionID: "P", State: "live", FirstPDT: at(12 * time.Second), TargetDuration: 7}
			res := openingOf(split, 43, 6500)
			res.EndingSessionID = "O"
			c.ApplyInsert(stream, 43, res)

			old := withMinSeq(sessionO, 40)
			old.State = snapState
			if applied, _ := c.CompleteLoad(stream, token, ledgerOf(40, ledgerRun("O", 40, 42, at(0)), old)); !applied {
				t.Fatal("CompleteLoad 가 적재를 적용하지 않았다")
			}

			want := snapState
			if snapState == "live" {
				want = "ending"
			}
			if o, _ := c.Session(stream, "O"); o.State != want {
				t.Errorf("재생 뒤 O state = %q, want %q", o.State, want)
			}
			if p, ok := c.Session(stream, "P"); !ok || p.State != "live" {
				t.Errorf("재생 뒤 P = (%+v, %v), want live 회차", p.RewindSession, ok)
			}
		})
	}
}

// 부정 표식은 재생 뒤에 정한다(체크리스트 A-4 2 「재생 뒤에도 컷오프 없음이면」) — 적재 중에 주조 push 가 와서 로그에
// 있으면, 컷오프 없는 적재분(스냅숏이 주조보다 먼저였다)을 적용한 뒤의 재생에서 그 push 가 표식을 풀고 적재를 다시
// 연다(요구 목록에 오른다). 표식을 재생 뒤에 덮으면 주조가 사라져 그 방송이 끝까지 발행되지 않는다.
func TestSeededPushInLogLiftsTombstoneAfterReplay(t *testing.T) {
	c := &cache.Cache{}
	token := c.BeginLoad(stream, nil)
	c.ApplyInsert(stream, 41, seeded(seedOf("O", 41, at(7600*time.Millisecond), 4000))) // 적재 중 — 로그

	if applied, _ := c.CompleteLoad(stream, token, index.RewindLedger{}); !applied {
		t.Fatal("CompleteLoad 가 적재를 적용하지 않았다")
	}

	if got := c.DemandedLoads(); !c.Loading(stream) || !reflect.DeepEqual(got, []cache.LoadDemand{{StreamID: stream}}) {
		t.Errorf("재생 뒤 (적재 중, 요구) = (%v, %+v), want (참, [%s]) — 로그의 주조 push 가 적재를 다시 연다",
			c.Loading(stream), got, stream)
	}
}
