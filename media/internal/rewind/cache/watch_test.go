package cache_test

// 30초 감시의 캐시 쪽(POK-195 M4 PR ⓒ 커밋 5 — 설계 4.1 (a) · 계획 4.5 A2 결정 11 · A3 결정 5 · 부기 43 · 체크리스트
// A-6 2 · 3 · 판단 J39 · 장부 421 P-4 · P-5 · P-6). 대조 입력(DriftProbes) · 드리프트 대조(AuditDrift) · 감시 화해
// (ReconcileWatch)는 캐시 메서드이고 감시 문장의 결과 값(index.RewindWatch 의 행)만 받는다 — DB 를 모른다. 감시가
// 실패한 틱에는 루프가 대조 · 화해를 부르지 않는다(커밋 7) — 이 시험은 그 틱을 「부르지 않음」으로 흉내 낸다.

import (
	"reflect"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// headAt5 는 컷오프 0 · 회차 S(live · init 확정) 0..5(③ 확정) 6..9(③ 전)의 적재분이다 — 캐시 머리 H 는 5 다.
func headAt5() index.RewindLedger {
	s := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true, FirstPDT: at(0), TargetDuration: 6}
	return ledgerOf(0, append(ledgerRun("S", 0, 5, at(0)), pendingRun("S", 6, 9, at(24*time.Second))...), s)
}

// dbRow 는 감시 문장이 돌려준 드리프트 재료 한 행이다 — DB 컷오프 cutoff, 그 스트림의 seq next 행(③ uploaded 면
// settled)이다.
func dbRow(streamID string, next, cutoff int64, uploaded bool) index.WatchDrift {
	return index.WatchDrift{StreamID: streamID, NextSeq: next, CutoffSeq: cutoff, HasCutoff: true, HasNext: true,
		Next: index.RewindRow{Seq: next, SessionID: "S", DurationMS: 4000, PlaybackPDT: at(time.Duration(next) * 4 * time.Second),
			PlaybackS3Key: key(next), PlaybackUploaded: uploaded}}
}

// 대조 입력(체크리스트 A-6 3 · 판단 J39) — 뷰가 있고 적재 중이 아닌 스트림마다 캐시 머리 다음 seq H+1 이다(스트림
// 순). H 는 뷰 첫 행부터의 settled 연속 끝이다. 〔r53b — Q-1〕 접두가 비면(뷰 첫 행 F 가 미settled) H = F − 1 이라
// 대조 대상은 뷰 첫 행이다 — 뷰 하한이 컷오프보다 뒤여도 뷰가 모르는 컷오프 쪽 행을 대조하지 않는다. 행이 없는 뷰는
// 하한이 대조 대상이다. 적재 중 · 부정 표식 스트림은 대조하지 않는다(A3 결정 5 · 뮤테이션 121 — 주조 push 가
// 표식을 푼다).
func TestDriftProbesSkipLoadingAndTombstones(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, "a", headAt5())
	late := ledgerOf(0, pendingRun("S", 10, 12, at(40*time.Second)),
		index.RewindSession{SessionID: "S", State: "live", TargetDuration: 6})
	late.FloorSeq = 10
	load(t, c, "b", late)
	load(t, c, "c", headAt5())
	c.BeginLoad("c", nil)
	load(t, c, "d", index.RewindLedger{})
	load(t, c, "e", ledgerOf(5, nil))

	want := []index.WatchProbe{{StreamID: "a", NextSeq: 6}, {StreamID: "b", NextSeq: 10}, {StreamID: "e", NextSeq: 5}}
	if got := c.DriftProbes(); !reflect.DeepEqual(got, want) {
		t.Errorf("DriftProbes() = %+v, want %+v", got, want)
	}
}

// (a) 드리프트 대조(설계 4.1 (a) · 부기 43 · 판단 J39 · 장부 421 P-4 · P-6 · 〔r53b — Q-1 · Q-2〕). 불일치는 DB
// 컷오프 ≠ 캐시 컷오프(DB 에 없음 포함) 또는 DB 의 H+1 행이 settled 다. 가드 단위는 스트림이다 — 같은 스트림이 연속
// 두 감시에서 불일치면 요구 ③ 을 등재한다(후보 seq 가 달라도). 한 번 불일치한 뒤 일치하면 연속이 풀리고, 감시가
// 실패한 틱(부르지 않음)은 세지도 풀지도 않으며, 적재가 뷰를 갈아 끼우면 새로 센다. 결과가 닿은 시점의 캐시
// 머리가 그 H+1 이상이면(입력을 뜬 뒤 push 가 닿았다) 일치다 — 입력과 결과 사이의 경합을 불일치로 세지 않는다.
func TestDriftAuditTable(t *testing.T) {
	missing := index.WatchDrift{StreamID: stream, NextSeq: 6} // DB 에 컷오프가 없다
	for _, tt := range []struct {
		name string
		// rounds 는 감시 틱이다 — 결과 행을 받기 전에 before 로 캐시를 바꿀 수 있고, rows 가 nil 이면 감시가
		// 실패한 틱이다(대조 메서드를 부르지 않는다).
		rounds []driftRound
		want   int
	}{
		{"H+1_settled_한_번", []driftRound{{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}}}, 0},
		{"H+1_settled_두_번_같은_seq", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
		}, 1},
		// 〔장부 421 P-4 — 이월 절 1〕 DB 는 감시 사이에 여러 행을 나아가고 캐시는 한 행만 따라온다 — 후보 seq 가 6 → 7
		// 로 바뀌어도 같은 스트림의 두 번째 불일치에 요구한다.
		{"두_번_연속_seq_다름", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{before: func(c *cache.Cache) { c.ApplyPlaybackUploaded(stream, 6) }, rows: []index.WatchDrift{dbRow(stream, 7, 0, true)}},
		}, 1},
		{"컷오프_다름_두_번", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 3, false)}},
			{rows: []index.WatchDrift{dbRow(stream, 6, 3, false)}},
		}, 1},
		{"DB_컷오프_없음_두_번", []driftRound{{rows: []index.WatchDrift{missing}}, {rows: []index.WatchDrift{missing}}}, 1},
		{"둘_다_아니면_일치", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, false)}},
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, false)}},
		}, 0},
		{"불일치_일치_불일치", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, false)}},
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
		}, 0},
		{"불일치_감시실패_불일치", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{rows: nil},
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
		}, 1},
		{"불일치_적재_불일치", []driftRound{
			{rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{before: func(c *cache.Cache) { c.CompleteLoad(stream, c.BeginLoad(stream, nil), headAt5()) },
				rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
		}, 0},
		// 〔Q-2〕 결과가 닿기 전에 ③ 6 · 7 이 push 로 닿아 캐시 머리가 7(≥ H+1 = 6)이 됐다 — 경합이라 일치이고 연속을
		// 세지 않는다. 다음 감시의 불일치는 첫 번째다.
		{"결과를_받을_때_다시_견줌", []driftRound{
			{before: func(c *cache.Cache) { uploadRun(c, 6, 7) }, rows: []index.WatchDrift{dbRow(stream, 6, 0, true)}},
			{rows: []index.WatchDrift{dbRow(stream, 8, 0, true)}},
		}, 0},
		{"다른_스트림_결과는_무시", []driftRound{
			{rows: []index.WatchDrift{dbRow("nobody", 6, 0, true)}},
			{rows: []index.WatchDrift{dbRow("nobody", 6, 0, true)}},
		}, 0},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := &cache.Cache{}
			load(t, c, stream, headAt5())

			for _, r := range tt.rounds {
				if r.before != nil {
					r.before(c)
				}
				if r.rows != nil {
					c.AuditDrift(r.rows)
				}
			}

			got := c.DemandedLoads()
			if len(got) != tt.want {
				t.Fatalf("요구 = %+v, want %d건", got, tt.want)
			}
			if tt.want == 1 && (got[0].StreamID != stream || got[0].Hint != nil) {
				t.Errorf("요구 = %+v, want %s · 힌트 없음(요구 ③)", got[0], stream)
			}
		})
	}
}

// driftRound 는 감시 틱 하나다(TestDriftAuditTable).
type driftRound struct {
	before func(c *cache.Cache)
	rows   []index.WatchDrift
}

// 〔Q-1〕 빈 접두의 대조(판단 J39) — 뷰 하한 F(10)가 컷오프(0)보다 뒤이고 F 가 미settled 면 대조 대상은 F 다. DB 의
// F 가 미settled 면 일치(요구 0)이고 settled 면 불일치다(두 번이면 요구). 뷰 밖의 컷오프 쪽 행은 대조하지 않는다.
func TestDriftAuditOfEmptyPrefix(t *testing.T) {
	for _, tt := range []struct {
		name     string
		uploaded bool
		want     int
	}{
		{"DB_F_미settled", false, 0},
		{"DB_F_settled", true, 1},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := &cache.Cache{}
			late := ledgerOf(0, pendingRun("S", 10, 12, at(40*time.Second)),
				index.RewindSession{SessionID: "S", State: "live", TargetDuration: 6})
			late.FloorSeq = 10
			load(t, c, stream, late)
			probe := c.DriftProbes()
			if len(probe) != 1 || probe[0].NextSeq != 10 {
				t.Fatalf("DriftProbes() = %+v, want seq 10(뷰 첫 행)", probe)
			}

			c.AuditDrift([]index.WatchDrift{dbRow(stream, 10, 0, tt.uploaded)})
			c.AuditDrift([]index.WatchDrift{dbRow(stream, 10, 0, tt.uploaded)})

			if got := c.DemandedLoads(); len(got) != tt.want {
				t.Errorf("요구 = %+v, want %d건", got, tt.want)
			}
		})
	}
}

// drift_audit_skips_loading_stream(계획 6.3 #121 · A3 결정 5) — 적재 중인 스트림은 대조하지 않는다. 적재 중 뷰는
// 「모른다」 상태라 대조하면 거짓 불일치가 나고, 그 불일치가 요구 ③ 으로 적재를 다시 연다. 불일치 결과가 와도 요구
// 0 · 재시작 0 이고, 적재 뒤 다음 감시부터 새로 센다.
func TestDriftAuditSkipsLoadingStream(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, headAt5())
	token := c.BeginLoad(stream, nil)

	c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)})
	c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)})

	if got := c.DemandedLoads(); len(got) != 0 {
		t.Errorf("적재 중 대조 뒤 요구 = %+v, want 없음", got)
	}
	if applied, drift := c.CompleteLoad(stream, token, headAt5()); !applied || drift {
		t.Errorf("CompleteLoad = (%v, %v), want (참, 거짓) — 재시작 0 · 드리프트 까닭 없음", applied, drift)
	}
	c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)})
	if got := c.DemandedLoads(); len(got) != 0 {
		t.Errorf("적재 뒤 첫 불일치에 요구 = %+v, want 없음(새로 센다)", got)
	}
}

// 드리프트 까닭(장부 421 P-5 — 캐시 쪽 · 체크리스트 A-6 3 「수리」) — 대조가 등재한 요구 ③ 의 까닭(드리프트)은 그
// 스트림의 적재 상태로 옮겨 가고, CompleteLoad 가 그 토큰의 결과로 뷰를 갈아 끼우면 「드리프트로 연 적재였다」를
// 돌려준다. 까닭은 요구가 합쳐져도(힌트가 있는 요구) 남고, 토큰이 달라 버린 결과에서는 돌려주지 않고 다시 연
// 적재로 이어지며, 실패한 적재를 다시 올려 다시 연 적재에도 이어진다(판단 J46). 드리프트가 아닌 요구의 적재는
// 거짓이다. 발행 층의 래퍼가 이 값으로 WARN 을 남긴다 — 감지 때가 아니다.
func TestDriftReasonTravelsWithLoad(t *testing.T) {
	mismatchTwice := func(c *cache.Cache) {
		c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)})
		c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)})
	}
	t.Run("드리프트_요구의_적재", func(t *testing.T) {
		c := &cache.Cache{}
		load(t, c, stream, headAt5())
		mismatchTwice(c)
		c.DemandLoad(stream, hintOf(3)) // 겹친 요구 — 힌트만 합쳐지고 까닭은 남는다
		d := c.DemandedLoads()
		if len(d) != 1 || d[0].Hint == nil || *d[0].Hint != 3 {
			t.Fatalf("요구 = %+v, want %s · 힌트 3 한 건", d, stream)
		}
		stale := c.BeginLoad(stream, d[0].Hint)
		fresh := c.BeginLoad(stream, d[0].Hint) // 다시 연 적재
		if applied, drift := c.CompleteLoad(stream, stale, headAt5()); applied || drift {
			t.Errorf("버린 결과 = (%v, %v), want (거짓, 거짓)", applied, drift)
		}
		failed := c.BeginLoad(stream, d[0].Hint) // fresh 의 적재가 실패해 다시 올려 다시 열었다(J46)
		if applied, _ := c.CompleteLoad(stream, fresh, headAt5()); applied {
			t.Error("실패 뒤 다시 연 적재 앞의 토큰을 적용했다")
		}
		if applied, drift := c.CompleteLoad(stream, failed, headAt5()); !applied || !drift {
			t.Errorf("다시 연 적재 = (%v, %v), want (참, 참)", applied, drift)
		}
		if applied, drift := c.CompleteLoad(stream, c.BeginLoad(stream, nil), headAt5()); !applied || drift {
			t.Errorf("다음 적재 = (%v, %v), want (참, 거짓) — 까닭은 한 번 쓰였다", applied, drift)
		}
	})
	t.Run("드리프트가_아닌_요구의_적재", func(t *testing.T) {
		c := &cache.Cache{}
		load(t, c, stream, headAt5())
		c.AuditDrift([]index.WatchDrift{dbRow(stream, 6, 0, true)}) // 한 번 — 요구 없음
		c.DemandLoad(stream, nil)                                   // 다른 요구(예: ②)
		if applied, drift := c.CompleteLoad(stream, c.BeginLoad(stream, nil), headAt5()); !applied || drift {
			t.Errorf("적재 = (%v, %v), want (참, 거짓)", applied, drift)
		}
	})
}

// watch_reconcile_is_monotone(계획 4.5 A2 결정 11 · 6.4 음성 대조 · 뮤테이션 118 의 함수 층 — 판단 J44) — 감시
// 결과의 live 행마다 DB 는 init 확정인데 캐시는 아니면 init 확정과 (DB 에 계승이 없고 캐시가 계승을 들었을 때만)
// 계승 해제를 한 호출(ApplyInitUploaded)로 반영하고, 반영한 (스트림, 회차, revoked) 목록을 돌려준다(래퍼가
// cache_drift_repaired(kind=init_uploaded)로 남긴다). 그 밖에는 아무것도 하지 않는다 — 같으면 목록 0 · 계승을
// 되살리지 않음 · init 을 거짓으로 되돌리지 않음 · 적재 중 스트림 · 캐시가 모르는 회차 · 모르는 스트림은 건너뜀.
func TestWatchReconcileIsMonotone(t *testing.T) {
	sess := func(id, inherits string, init bool) index.RewindSession {
		s := index.RewindSession{SessionID: id, State: "live", InitUploaded: init, FirstPDT: at(0), InheritsSession: inherits,
			TargetDuration: 6}
		if inherits != "" {
			s.DiscontinuityBase = 5 // 계승 회차는 직전 회차의 base 를 옮겨 받았다
		}
		return s
	}
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(0, nil, sess("A", "X", false), sess("B", "Y", false), sess("C", "", false),
		sess("D", "", true), sess("E", "", true), sess("F", "", false)))
	load(t, c, "busy", ledgerOf(0, nil, sess("L", "", false)))
	c.BeginLoad("busy", nil)

	got := c.ReconcileWatch([]index.WatchLive{
		{StreamID: stream, SessionID: "A", InitUploaded: true, InheritAbsent: true},   // 잃은 init 결과 + 해제
		{StreamID: stream, SessionID: "B", InitUploaded: true},                        // DB 는 계승 유지 — init 만
		{StreamID: stream, SessionID: "C", InitUploaded: true, InheritAbsent: true},   // 캐시에 계승이 없다 — 해제 아님
		{StreamID: stream, SessionID: "D", InitUploaded: true, InheritAbsent: true},   // 같다 — 할 일 없음
		{StreamID: stream, SessionID: "E", InheritAbsent: true},                       // init 을 거짓으로 되돌리지 않는다
		{StreamID: stream, SessionID: "F"},                                            // 계승을 되살리지 않는다
		{StreamID: stream, SessionID: "G", InitUploaded: true, InheritAbsent: true},   // 캐시가 모르는 회차
		{StreamID: "busy", SessionID: "L", InitUploaded: true, InheritAbsent: true},   // 적재 중
		{StreamID: "nobody", SessionID: "N", InitUploaded: true, InheritAbsent: true}, // 모르는 스트림
	})

	want := []index.InitRepair{{StreamID: stream, SessionID: "A", Revoked: true}, {StreamID: stream, SessionID: "B"},
		{StreamID: stream, SessionID: "C"}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("화해 목록 = %+v, want %+v", got, want)
	}
	for _, tt := range []struct {
		id       string
		init     bool
		inherits string
		base     int64
	}{
		{"A", true, "", 0}, {"B", true, "Y", 5}, {"C", true, "", 0}, {"D", true, "", 0}, {"E", true, "", 0}, {"F", false, "", 0},
	} {
		s, _ := c.Session(stream, tt.id)
		if s.InitUploaded != tt.init || s.InheritsSession != tt.inherits || s.DiscontinuityBase != tt.base {
			t.Errorf("화해 뒤 %s = (init %v, 계승 %q, base %d), want (%v, %q, %d)",
				tt.id, s.InitUploaded, s.InheritsSession, s.DiscontinuityBase, tt.init, tt.inherits, tt.base)
		}
	}
	if s, _ := c.Session("busy", "L"); s.InitUploaded {
		t.Error("적재 중 스트림을 화해했다")
	}
}
