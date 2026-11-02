package main

// 발행 루프 통합 시험 가운데 기동 전 장부를 바로 만드는 것들(체크리스트 474 B-4 8 · 9 · B-3 32 PG 반쪽 · B-5) — 회차 · 조각을
// 장부에 넣고 프로세스를 띄운다(부팅 첫 감시 → 적재 → 적재 뒤 틱). 픽스처는 publish_it_fixture_pg_test.go 다.

import (
	"context"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// B-4 8 — 빈 접두 가장자리(419 미확인 13). 오래 발행하지 못한 방송을 장부로 만든다 — 25분 조각 열 개(4시간 10분)이고 행 5 만
// ③ 이 실패로 남았다. 적재 되짚기(기본 2시간)가 하한을 행 5 에 두므로 뷰 하한 F = 5 가 컷오프(0)보다 뒤인 미settled 행이다.
// 그때 경계의 머리는 컷오프 − 1 이고 사다리 k 도 뷰 밖 행이라
//
//	발행 0 · Expedite 0 · GAP 틱 0(L1 · L2 가 서지 않는다) · 이 프로세스의 발행 이력이 없어 L4 도 0 — 무음 정지
//	행 5 의 ③ 이 오면(재수거 성공) 그 holdTick 의 틱이 1시간 창(행 7..9)을 발행한다
//
// 조각이 길어 목록이 1시간을 채우므로 S3(1시간 미만이면 회차 첫 조각부터)에 걸리지 않는다 — 운영의 되짚기 2시간 · 행 상한
// 10,000 에서도 F > 컷오프인 뷰는 1시간을 넘는다. 감시는 2초 주기다 — 드리프트 대조(H = F − 1)가 그 상태를 수리 대상으로
// 보는지 기록한다.
func TestPublishLoopEmptyPrefixEdge(t *testing.T) {
	h := newITHarness(t)
	const sid = "S-edge"
	const rowMS = 25 * 60 * 1000
	pdt0 := h.t0.Add(-10*rowMS*time.Millisecond - 5*time.Minute)
	h.seedSession(itSeed{id: sid, state: "live", initUp: true, firstPDT: pdt0, targetDuration: rowMS / 1000})
	h.seedRows(sid, 0, 9, pdt0, rowMS, func(seq int64) string {
		if seq == 5 {
			return "failed"
		}
		return "uploaded"
	})
	h.start(itOptions{watchEvery: 2 * time.Second})
	h.waitLoads(1)
	time.Sleep(6 * time.Second) // 감시 셋 · 적재 뒤 틱 판단 · 사다리 평가 여섯
	edgeTicks, edgeLoads := len(h.ticks.list()), len(h.loads.list())
	edgeExp, edgeGaps := len(h.expedites.list()), len(h.gapTicks.list())
	edgeStalls, edgeRepairs := h.logRecords("rewind_publish_stall_seconds"), h.logRecords("cache_drift_repaired")
	edgeDegraded := h.logRecords("rewind_ledger_load_degraded")

	h.uploadPlayback(5, sid)
	time.Sleep(2 * time.Second)
	body := h.store.body(h.manifestKey(sid))
	h.stop()

	if edgeTicks != 0 || edgeExp != 0 || edgeGaps != 0 || len(edgeStalls) != 0 {
		t.Errorf("가장자리 동안 틱 %d · Expedite %d · GAP 틱 %d · L4 %v, want 모두 0(무음 정지)", edgeTicks, edgeExp, edgeGaps, edgeStalls)
	}
	seqs, gaps, err := bodySegments(body)
	if err != nil {
		t.Fatalf("목록 본문 해석 실패: %v", err)
	}
	if !slices.Equal(seqs, []int64{7, 8, 9}) || len(gaps) != 0 {
		t.Errorf("행 5 확정 뒤 목록 seq %v · GAP %v, want 1시간 창 7..9 · GAP 0\n%s", seqs, gaps, h.logDump())
	}
	t.Logf("⑧ 가장자리 6초: 틱 %d · 적재 %d · Expedite %d · GAP 틱 %d · L4 %v · 드리프트 수리 %v · 적재 저하 %v",
		edgeTicks, edgeLoads, edgeExp, edgeGaps, edgeStalls, edgeRepairs, edgeDegraded)
	t.Logf("⑧ 행 5 확정 뒤: 틱 %d(발행 %d) · 적재 %d · 목록 seq %v", len(h.ticks.list()), countOK(h.ticks.list()),
		len(h.loads.list()), seqs)
}

// seedInherited 는 계승 장부다 — 회차 P(ending · init 확정 · 행 0..4)와 그것을 잇는 회차 S(live · 계승 P · base 5 · 행 5..9)이고
// 행은 모두 ③ 확정이다. S 의 init 확정은 sInit 이 정한다.
func (h *itHarness) seedInherited(sInit bool) {
	h.t.Helper()
	pdt0 := h.t0.Add(-2 * time.Minute)
	h.seedSession(itSeed{id: "P", state: "ending", initUp: true, firstPDT: pdt0})
	h.seedSession(itSeed{id: "S", state: "live", inherits: "P", base: 5, initUp: sInit, firstPDT: pdt0.Add(20 * time.Second)})
	h.seedRows("P", 0, 4, pdt0, 4000, allUploaded)
	h.seedRows("S", 5, 9, pdt0.Add(20*time.Second), 4000, allUploaded)
}

// B-4 9 — 탐침 C(보안 r4 L-1 · 미확인 1 · R4-9)를 루프 끝까지 돌린다. 첫 프로세스가 계승 목록(접두 P 포함)을 발행한 뒤(B-5
// 「직전 init 이 착지한 정상 계승은 첫 PUT 에서 취소되지 않는다」) 내려가고, 장부에서 P 의 init 확정을 지워 그 회차를 발행 뒤 S4
// 상태로 만든다. 둘째 프로세스가 같은 장부 · 저장소로 뜬다.
//
//	대조군  실 Head — s4_after_publish 로 발행 중단 · DB 계승 P 유지(설계 A2 결정 3)
//	탐침    Head 가 객체를 두고도 404 — R4 → P0 의 ETag NULL → 첫 PUT 전으로 읽혀 P2′ 가 계승을 영속 해제 → IfAbsent 412
//	        (create_412). 위조를 거둔 뒤 P 행 대조가 풀리는지(발행이 다시 서는지) 기록한다 — 감시 주기 3초
func TestPublishLoopProbeCFalseHead404(t *testing.T) {
	for _, probe := range []bool{false, true} {
		name := "대조군_실Head"
		if probe {
			name = "탐침_거짓404"
		}
		t.Run(name, func(t *testing.T) { probeC(t, probe) })
	}
}

func probeC(t *testing.T, probe bool) {
	h := newITHarness(t)
	h.seedInherited(true)
	opt := itOptions{watchEvery: 3 * time.Second}
	h.start(opt)
	h.waitLoads(1)
	time.Sleep(1500 * time.Millisecond)
	first := h.store.body(h.manifestKey("S"))
	firstSeqs, _, _ := bodySegments(first)
	if s := h.session("S"); s.inherits == nil || *s.inherits != "P" || s.etag == nil || !slices.Equal(firstSeqs, seqRange(0, 9)) {
		t.Fatalf("첫 PUT 뒤 S = %+v · 목록 seq %v, want 계승 P 유지 · ETag 있음 · 접두 포함 0..9(B-5 A2)", s, firstSeqs)
	}
	h.stop()

	h.exec(`UPDATE stream_sessions SET init_uploaded_at = NULL WHERE session_id = 'P'`)
	h.store.set(func(s *itStore) { s.headFalse404 = probe })
	h.start(opt)
	h.waitLoads(1)
	drive := func(d time.Duration) {
		for end := time.Now().Add(d); time.Now().Before(end); time.Sleep(time.Second) {
			h.remark(9, "S")
		}
	}
	drive(5 * time.Second)
	during := h.abortReasons()
	h.store.set(func(s *itStore) { s.headFalse404 = false })
	recoverFrom := time.Now()
	drive(8 * time.Second)
	h.stop()

	s := h.session("S")
	reasons := h.abortReasons()
	mismatches := 0
	for _, l := range h.logRecords("rewind_ledger_load_degraded") {
		if l.attrs["reason"] == "prev_mismatch" && l.at.After(recoverFrom) {
			mismatches++
		}
	}
	published := 0
	for _, tk := range h.ticks.since(recoverFrom) {
		if tk.ok {
			published++
		}
	}
	if probe {
		if !slices.Contains(during, "create_412") || s.inherits != nil {
			t.Errorf("탐침: 중단 %v · DB 계승 %v, want create_412 · 계승 NULL(보안 L-1 재현)", during, s.inherits)
		}
	} else if !slices.Contains(reasons, "s4_after_publish") || slices.Contains(reasons, "create_412") || s.inherits == nil || *s.inherits != "P" {
		t.Errorf("대조군: 중단 %v · DB 계승 %v, want s4_after_publish · create_412 0 · 계승 P", reasons, s.inherits)
	}
	t.Logf("⑨ %s — 위조 중 중단 %v · 전체 중단 %v · DB 계승 %v · base %d · ETag 있음 %v · 위조 거둔 뒤 8초: 발행 %d · prev_mismatch %d줄 · 적재 %d",
		map[bool]string{false: "대조군", true: "탐침"}[probe], during, reasons, derefStr(s.inherits), s.base, s.etag != nil,
		published, mismatches, len(h.loads.list()))
}

// derefStr 은 NULL 이면 "NULL" 이다.
func derefStr(p *string) string {
	if p == nil {
		return "NULL"
	}
	return *p
}

// B-3 32 PG 반쪽(117 · 118 종단 — 118 은 이 시험이 죽인다) — 계승 회차 S 의 init 첫 CAS 를 실물 업로드 저장소로 보낸다: 서버는
// 커밋하고 계승을 푼다(InitMarkRevoked) · 워커는 결과를 잃는다(Dirty 0 — MarkTimeout 형). 재시도는 AlreadySame 이라 Dirty 가 없다.
// 캐시는 init 미확정 그대로라 발행 0 이고, 감시가 화해로 고친다(cache_drift_repaired kind=init_uploaded · revoked=true 한 줄).
// 그 뒤 첫 목록은 접두 없이 S 의 행만이고 DISC-SEQ 0 이다.
func TestPublishLoopLostInitResultRecoveredByWatch(t *testing.T) {
	h := newITHarness(t)
	h.seedInherited(false)
	h.start(itOptions{watchEvery: 2 * time.Second})
	h.waitLoads(1)

	key, err := playback.InitKey(h.stream, "S")
	if err != nil {
		t.Fatalf("InitKey 실패: %v", err)
	}
	up := index.NewUploadStore(h.pool)
	ctx := context.Background()
	m1, err1 := up.MarkInitUploaded(ctx, "S", []byte{7}, key, 1, true)
	m2, err2 := up.MarkInitUploaded(ctx, "S", []byte{7}, key, 1, true)
	if err1 != nil || err2 != nil || m1 != index.InitMarkRevoked || m2 != index.InitMarkAlreadySame {
		t.Fatalf("CAS = %v(%v) · 재시도 %v(%v), want InitMarkRevoked · InitMarkAlreadySame", m1, err1, m2, err2)
	}
	h.waitLog("cache_drift_repaired", 1, 5*time.Second)
	repairedAt := h.logRecords("cache_drift_repaired")[0].at
	h.remark(9, "S")
	time.Sleep(2 * time.Second)
	body := h.store.body(h.manifestKey("S"))
	stmts := h.tracer.log()
	h.stop()

	early := 0
	for _, s := range stmts {
		if s.class == "p0" && s.start.Before(repairedAt) {
			early++
		}
	}
	repairs := h.logRecords("cache_drift_repaired")
	if early != 0 || len(repairs) != 1 || repairs[0].level != slog.LevelWarn || repairs[0].attrs["kind"] != "init_uploaded" ||
		repairs[0].attrs["revoked"] != "true" {
		t.Errorf("화해 전 P0 %d · 수리 줄 %v, want P0 0 · WARN kind=init_uploaded revoked=true 한 줄", early, repairs)
	}
	seqs, _, err := bodySegments(body)
	if err != nil {
		t.Fatalf("목록 본문 해석 실패: %v", err)
	}
	if !slices.Equal(seqs, seqRange(5, 9)) || !strings.Contains(string(body), "#EXT-X-DISCONTINUITY-SEQUENCE:0\n") ||
		strings.Contains(string(body), "#EXT-X-DISCONTINUITY\n") {
		t.Errorf("첫 목록 seq %v · 본문\n%s\nwant 접두 없는 5..9 · DISC-SEQ 0 · 끊김 표시 0", seqs, body)
	}
	t.Logf("B-3 32 — CAS %v · 재시도 %v · 화해 전 P0 %d · 수리 %v · 첫 목록 seq %v · P0 %d · PUT %d", m1, m2, early, repairs, seqs,
		h.tracer.count("p0"), len(h.store.putLog()))
}

// B-5 같은 본문 생략 — 본문이 바뀐 틱은 반드시 PUT 하고(평시 ③ 마다), 이미 실린 행의 원소가 다시 와 본문이 같으면 P0 만 나가고
// PUT · P4 는 0 이다. 재기동한 프로세스의 적재 뒤 틱은 fence 를 얻고 화해가 P 를 되살린 준비 단계에서 끝난다(P0 · PUT 0 —
// 화해가 P 를 바꾼 틱은 옛 P 로 센 DISC-SEQ 를 쓰지 않는다 · tick.ready). 다음 트리거의 틱은 본문이 같아 PUT 0 이다.
func TestPublishLoopSameBodySkipsPut(t *testing.T) {
	h := newITHarness(t)
	h.start(itOptions{})
	run := newITRun(h)
	for k := int64(0); k < 3; k++ {
		run.next(k)
	}
	time.Sleep(1500 * time.Millisecond)
	puts, p0, p4 := len(h.store.putLog()), h.tracer.count("p0"), h.tracer.count("p4")
	h.remark(2, run.sid)
	time.Sleep(2 * time.Second)
	samePuts, sameP0, sameP4 := len(h.store.putLog()), h.tracer.count("p0"), h.tracer.count("p4")
	h.stop()

	h.start(itOptions{})
	h.waitLoads(1)
	time.Sleep(1500 * time.Millisecond)
	postTicks, postAcq, postP0, postPuts := len(h.ticks.list()), h.tracer.count("acquire"), h.tracer.count("p0"), len(h.store.putLog())
	h.remark(2, run.sid)
	time.Sleep(2 * time.Second)
	againP0, againP4, againPuts := h.tracer.count("p0"), h.tracer.count("p4"), len(h.store.putLog())
	h.stop()

	if puts != 3 || sameP0 != p0+1 || samePuts != puts || sameP4 != p4 {
		t.Errorf("평시 PUT %d(want 3) · 같은 본문 틱: P0 %d→%d · PUT %d→%d · P4 %d→%d, want P0 +1 · PUT · P4 그대로",
			puts, p0, sameP0, puts, samePuts, p4, sameP4)
	}
	if postTicks != 1 || postAcq != 1 || postP0 != 0 || postPuts != puts {
		t.Errorf("재기동 적재 뒤 틱 %d · 획득 %d · P0 %d · PUT %d, want 1 · 1 · 0(준비 단계에서 끝남) · %d 그대로",
			postTicks, postAcq, postP0, postPuts, puts)
	}
	if againP0 != 1 || againP4 != 0 || againPuts != puts {
		t.Errorf("재기동 뒤 다음 틱 P0 %d · P4 %d · PUT %d, want 1 · 0 · %d 그대로(같은 본문)", againP0, againP4, againPuts, puts)
	}
	if got := h.abortReasons(); len(got) != 0 {
		t.Errorf("중단 %v, want 0", got)
	}
	t.Logf("B-5 같은 본문 — 평시 PUT %d · 같은 본문 틱 P0 +%d · PUT +%d · P4 +%d · 재기동 적재 뒤 틱 %d(획득 %d · P0 %d · PUT +%d) · 다음 틱 P0 %d · P4 %d · PUT +%d",
		puts, sameP0-p0, samePuts-puts, sameP4-p4, postTicks, postAcq, postP0, postPuts-puts, againP0, againP4, againPuts-puts)
}
