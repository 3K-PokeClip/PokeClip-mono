package main

// 루프 보조 타입 — 메인 루프(loop)가 되감기 회차 · 캐시 · 발행을 돌보는 몫이다(계획 4.1 · 4.5 E 부속 「루프 보조 타입」 · 체크리스트
// 474 판단 J89). main.go 가 800줄을 넘기 전에 나눴다: 이 파일은 타입과 holdTicks · 완료 case 의 흐름이고, 틱 입력 · 적재 · 결과
// 반영은 rewind_publish.go 다.

import (
	"context"
	"log/slog"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// rewindLoop 은 루프가 되감기 회차 · 캐시 · 발행을 돌보는 일이다 — loop 고루틴이 부르고 자기 고루틴이 없다(D10). 하는 일은 둘이다.
//
//	4.1 종료 전이   판정은 indexer 가 하고(OfflineDue · OfflineTried) 이 타입은 점검 발사 · 전이 · 캐시 push · 요구 적재로 옮긴다
//	발행 배선       holdTicks 마다 Dirty 반영 · 사다리 · 틱 · 요구 적재 · lazy 갱신 · 감시를 차선으로 내고, 결과를 완료 case 로
//	                받아 캐시 · 발행 상태에 반영한다(체크리스트 474 A-4 · A-5)
//
// 캐시 · Dirty 손잡이 · 차선의 메서드는 이 타입이 loop 고루틴에서만 부른다 — 작업 몸체(work)만 차선 고루틴에서 돌고 값만 받는다
// (계획 4.5 A3 「동시성」 · A-9). 발행 몫(pub · lanes 와 그 짝)은 조립이 버킷을 받아 발행자를 만들었을 때만 채운다 — 없으면
// 발행 갈래는 아무것도 하지 않고 완료 case 는 nil 채널이다(판단 J69).
//
// 메서드가 받는 ctx 는 루프 수명 ctx 이고 차선 작업에 그대로 넘긴다(Lanes.Start 의 계약) — 그래서 루프의 ctx 가 곧 차선 ctx 다.
// 조립은 루프에 run ctx 에서 뗀 취소 가능한 ctx 를 주고, 루프가 돌아온 뒤 stopPublishing 으로 그것을 끊는다(루프가 오류로 끝나
// run ctx 가 살아 있어도 작업이 끝나게 — 체크리스트 474 A-3 2 · 3).
type rewindLoop struct {
	ix   *indexer.Indexer
	root string
	// cache 는 되감기 캐시다. nil 이면 push · 요구 적재가 아무것도 하지 않는다(캐시의 nil 규약).
	cache *cache.Cache
	log   *slog.Logger
	// endLive 는 스트림의 live 회차를 ending(offline)으로 보내고 그 회차 ID 를 돌려준다(live 회차가 없으면 "") —
	// 운영은 풀을 묶은 index.EndLive 다. 오류면 서버가 커밋했는지 모른다.
	endLive func(ctx context.Context, streamID string) (sessionID string, err error)

	// pub 은 사다리 평가와 신호 래퍼(ReportLoad · ReportWatch · ReportPrevMismatch · ReportJobError)를 쓰는 발행자고, lanes 는
	// 작업을 나르는 차선이다. 둘 가운데 하나라도 nil 이면 발행 몫이 없다.
	pub   *publish.Publisher
	lanes *publish.Lanes
	// work 는 차선 작업의 몸체다 — 운영은 발행자 메서드(Tick · RenewFence · LoadLedger · Watch)이고 시험이 가짜로 바꾼다.
	work laneWork
	// peekDirty · ackDirty 는 ③ · init 결과 이벤트 집합의 손잡이다(운영 = upload.Dirty 의 Peek · Ack). nil 이면 원소가 없다.
	peekDirty func() []upload.DirtyEvent
	ackDirty  func([]upload.DirtyEvent)
	// expedite 는 정체 사다리 L1 의 손잡이고(upload.Uploader.Expedite), forgetSession 은 끝난 회차의 업로더 메모리를 지우는
	// 손잡이다(upload.Uploader.ForgetSession — 판단 J86). nil 이면 부르지 않는다.
	expedite      func(streamID string, seq int64)
	forgetSession func(sessionID string)
	// reconnectWindow 는 쉬는 뷰를 버리는 창이다(cfg.ReconnectWindow — Forget). watchEvery 는 30초 감시 주기이고 요구 적재의
	// 반복 제한 간격을 겸한다(cfg.RewindWatchEvery — 판단 J85 ⑴ · J79 ⑵ · 새 값 0). holdEvery 는 보류 틱 주기다
	// (cfg.Indexer.HoldTick) — 감시는 watchEvery ÷ holdEvery 번째 holdTick 마다다. lazyRenewAfter 는 lazy 갱신 간격이다(발행자
	// Options.LazyRenewAfter 와 같은 값 — 루프가 먼저 걸러 차선 작업을 줄인다). 조립이 설정 · 발행자 Options 에서 넘긴다.
	reconnectWindow time.Duration
	watchEvery      time.Duration
	holdEvery       time.Duration
	lazyRenewAfter  time.Duration

	// streams 는 스트림마다의 발행 몫 상태다(stream 이 만든다 · Forget 이 버린다). absent 는 (c) 컷오프 부재 감시의 상태고,
	// ticks 는 발행 갈래를 돈 holdTick 수(감시 주기를 센다), booted 는 첫 감시가 성공해 부팅 목록을 냈는가다(판단 J78 ⑶).
	streams map[string]*rewindStream
	absent  publish.CutoffAbsentState
	ticks   int
	booted  bool

	// 아래는 제어면 상태 몫이다(설계 M5 3.3 · assembleStatus 가 채운다 · rewind_status.go 가 쓴다). board 는 HTTP 고루틴과
	// 나눠 쓰는 유일한 칸이다(원자 포인터 — 불변 Board 만 오간다). statusOff 는 발행 꺼짐이라 Board 를 갱신하지 않는가다.
	// finals 는 M6 상태 전달의 받은편지함이고, final 은 스트림마다의 종료 항목이다(루프 고루틴 전용 — 처리기는 Board 의 사본만
	// 본다). baseURL 은 REWIND_PUBLIC_BASE_URL 이다.
	board     *status.Holder
	statusOff bool
	finals    *finalsInbox
	final     map[string]status.Final
	baseURL   string
	// invalid 는 스트림마다 마지막으로 버린 M6 값이다(rewind_final_invalid 를 같은 값에 한 번만). rangeWarned 는
	// rewind_status_range_missing 을 남긴 회차다(회차당 한 번). alarms 는 rewind_ready_delayed 의 스트림별 상태다.
	invalid     map[string]index.PlaybackState
	rangeWarned map[string]string
	alarms      map[string]*readyAlarm

	// endGapRun 은 L3 종료 손잡이다(gap_run.go — 운영은 index.EndGapRun). nil 이면 스위치 끔이라 셈만 하고 끝내지 않는다.
	endGapRun func(ctx context.Context, streamID, sessionID string) (string, error)
	// endStallSeal 은 L2′ 종료 손잡이다(stall_seal.go — 운영은 index.EndStallSeal). nil 이면 스위치 끔이라 봉인도 내지 않는다.
	endStallSeal func(ctx context.Context, streamID, sessionID string) (string, error)
	// liveEndRetryAt 은 L3 · L2′ 종료 문장이 실패한 뒤 다시 부를 시각이다(영값 = 바로). 스트림 · 회차를 가리지 않는다 — DB 가
	// 멈추면 한 틱에 스트림마다 시한이 쌓이지 않게 루프 전체가 쉰다(오프라인 전이의 쉼과 같은 처분 · live_end.go).
	liveEndRetryAt time.Time

	// fin 은 정산 몫이다(M6-3b — rewind_finalize.go). run 이 조립 밖에서 채워 발행 꺼짐 · 강등에서도 돈다. nil 이면 정산 ·
	// 상태 전달 · 계승 해제를 하지 않는다(4.1 전이 · 발행 몫만 보는 시험).
	fin *finalizer
}

// laneWork 는 차선 작업의 몸체다 — 입력 값만 받아 차선 고루틴에서 돌고 결과를 값으로 돌려준다(캐시 · Dirty 를 만지지 않는다).
// 운영은 발행자 메서드를 그대로 끼운다(Tick · RenewFence · LoadLedger · Watch). 함수 칸이라 새 인터페이스가 없다(판단 J55 형).
type laneWork struct {
	tick  func(ctx context.Context, st publish.State, in publish.TickInput) publish.Outcome
	renew func(ctx context.Context, st publish.State, sessionID string) publish.Outcome
	load  func(ctx context.Context, in publish.LoadInput) publish.LoadOutcome
	watch func(ctx context.Context, probes []index.WatchProbe) publish.WatchOutcome
	// seal 은 L2′ 의 봉인이다(운영은 Publisher.Endlist — stall_seal.go).
	seal func(ctx context.Context, streamID, sessionID string) publish.FreezeOutcome
}

// rewindStream 은 루프가 스트림 하나에 드는 발행 몫의 상태다(판단 J89).
type rewindStream struct {
	// owner 는 아래 발행 상태가 속한 소유 회차다(cache.Owner — 판단 J71 ⑴). ownedSince 는 그 회차를 처음 소유 회차로 본 시각이고
	// (사다리 OwnedSince — 영값이면 안 된다 · ⓓ) publishedAt 은 그 회차의 발행 성공을 마지막으로 받은 시각이다(사다리
	// PublishedAt — 판단 J29).
	owner       string
	ownedSince  time.Time
	publishedAt time.Time
	// pub 은 owner 목록의 발행 상태고(작업이 돌려준 값으로 갈아 끼운다) ladder 는 사다리 상태다(판단 J34).
	pub    publish.State
	ladder publish.LadderState
	// unstoodGap 은 결과가 돌아왔는데 서지 않은 직전 GAP 틱의 후보다(사다리 UnstoodGapSeq — ⓐ).
	unstoodGap *int64
	// gapRun 은 L3 셈이다 — GAP 이 선 결과만 센다(settleGap · gap_run.go).
	gapRun gapRun
	// stallSeal 은 L2′ 의 봉인 · 종료 대기다(stall_seal.go).
	stallSeal stallSeal
	// prevTail 은 다음 boundary.Compute 의 prevTail 이다 — 평시 틱은 계산한 틱의 꼬리, GAP 틱은 GAP 이 선 뒤에만 겹침 꼬리다(J26).
	prevTail int64
	// running 은 이 스트림 차선에서 도는 작업이다(nil = 차선이 비었다 — 판단 J80 ⑷ 의 in-flight 표). pendingLoad 는 차선이 돌아
	// 병합된 적재(BeginLoad 를 마친 입력)다 — 결과를 받으면 낸다.
	running     *laneJob
	pendingLoad *publish.LoadInput
	// republish 는 직전 발행 틱이 실패했다는 표지다(republishPending — 판단 J80 ⑶) — 다음 holdTick 이 틱을 낸다.
	republish bool
	// lastErr 는 직전 발행 작업의 표 밖 오류다 — 같은 오류가 이어지면 한 줄만 남긴다(판단 J78 ⑴).
	lastErr string
	// redemandAt 은 반복 제한 갈래(④ P 대조 실패 · ② Playlist 거짓)가 마지막으로 요구한 시각이고, loadRetryAt 은 실패한 적재를
	// 다시 요구할 시각이다(판단 J79 ⑵ · J46 — 영값 = 없음).
	redemandAt  time.Time
	loadRetryAt time.Time
	// loadedAt 은 이 스트림의 적재를 마지막으로 적용한 시각이다(Forget 의 lastEnd — 커서 꼬리가 없을 때 · 계획 4.5 A3 결정 5).
	loadedAt time.Time
}

// laneJob 은 스트림 차선에서 도는 작업 하나다 — 결과를 받을 때 무엇이었는지 가른다.
type laneJob struct {
	kind laneJobKind
	// session 은 발행 작업의 소유 회차다(Playlist.Owner — 결과의 Revoked 를 반영할 회차).
	session string
	// window 는 틱이 계산한 창이다 — GAP 틱이면 겹침 창이고, GAP 이 서면 그 꼬리가 prevTail 이 된다(J26).
	window boundary.Window
	// gap 은 GAP 틱의 후보 k 다. nil 이면 평시 틱이다.
	gap *int64
}

// laneJobKind 는 스트림 차선 작업의 종류다 — 값이 1 부터인 것은 채우지 않은 칸이 어느 종류로도 읽히지 않게 하려는 것이다.
type laneJobKind int

const (
	jobTick   laneJobKind = iota + 1 // 발행 틱(평시 · GAP)
	jobRenew                         // lazy 갱신(RenewFence)
	jobLoad                          // 캐시 적재(LoadLedger)
	jobRevoke                        // 4D round_split 의 계승 해제(RevokeInheritance — M6-3b)
	jobSeal                          // L2′ 봉인(Endlist — M6-3b 커밋 ④b)
)

// holdTick 은 holdTicks case 의 되감기 몫이다 — 그 case 의 현재 시각 now 로 4.1 종료 전이를 돌리고, 발행 몫이 있으면 차례대로
// Dirty 반영 → 사다리 → 틱 발사 → (Ack) → 요구 발사 → lazy 갱신 → 감시다(체크리스트 474 A-4 — 순서가 계약이다: 사다리는 반영된
// ③ 를 보고, 틱은 사다리의 L2 를 GAP 틱으로 받으며, 감시 입력은 반영 뒤에 뜬다). 작업은 차선으로 내고 결과는 완료 case 가
// 받는다 — DB · 저장소를 기다리지 않는다(설계 3.3 — 4.1 의 EndLive 만 예외).
//
// 제어면 상태 몫은 그 앞뒤다(설계 M5 3.3 holdTick 순서 · 보완 2 ㉢ ㉤): 4.1 전이 뒤 받은편지함을 종료 항목에 반영하고(Drain),
// 발행 몫은 발행 몫이 있을 때만 돌며, 끝에서 Board 를 새로 만든다(publishStatus — 강등에서도 돈다 · 꺼짐 제외). 그래서 그 틱
// 스냅숏은 그 틱의 Drain · 개시 삭제를 본다.
//
// 정산 몫(M6 3.2 holdTick 순서의 정본)은 4.1 전이와 M5 Drain 사이다: endOfflineSessions → endGapRuns(L3) → endStallSeals(L2′ —
// 둘 다 스위치 켬일 때만) → 받은편지함 비우기(계승 해제 · 상태 표지 → 차선 발사) → finalizeTick(빠른 길 · 스캔 주기) → M5
// Drain → 발행 몫. 조기 반환 앞이라 발행 꺼짐 · 강등에서도 돈다(D56). 표식 EndLive · L3 · L2′ 가 남긴 표지는 같은 틱의
// 받은편지함 비우기에서 읽힌다. L2′ 의 봉인은 발행 몫의 사다리가 스트림 차선에 낸다(stall_seal.go).
func (r *rewindLoop) holdTick(ctx context.Context, now time.Time) {
	r.endOfflineSessions(ctx, now)
	r.endGapRuns(ctx, now)
	r.endStallSeals(ctx, now)
	r.drainInboxes(ctx)
	r.finalizeTick(ctx, now)
	r.drainFinals()
	if r.publishing() {
		events := r.peek()
		touched := r.reflectDirty(events)
		gaps := r.evaluateLadders(ctx, now)
		r.dispatchTicks(ctx, touched, gaps, now)
		r.ack(events)
		r.dispatchLoads(ctx, now)
		r.renewFences(ctx, now)
		r.watch(ctx)
	}
	r.publishStatus(now)
}

// publishing 은 발행 몫이 있는가다.
func (r *rewindLoop) publishing() bool { return r.pub != nil && r.lanes != nil }

// done 은 완료 case 의 채널이다 — 차선이 없으면 nil 이라 그 case 는 영구 비활성이다(판단 J69 — 훅 채널과 같은 규약). 조건은
// 발행 몫이 아니라 차선이다(M6 3.2) — 차선은 run 이 조립 밖에서 채우므로 발행 꺼짐 · 강등에서도 정산 · 상태 전달 결과를 받는다.
// 발행 결과(KindPublish · KindLoad · KindWatch)는 발행 몫이 있을 때만 온다.
func (r *rewindLoop) done() <-chan publish.Result {
	if r.lanes == nil {
		return nil
	}
	return r.lanes.Done()
}

// complete 는 완료 case 의 몫이다 — 차선 작업 하나의 결과 res 를 now 에 반영한다(체크리스트 474 A-5). 첫 줄은 Finish 이고, 그
// 반환은 작업이 도는 동안 병합된 요청의 재실행이다(판단 J80 ⑷ — 새 입력으로 한 번 더).
//
//	발행  publishDone — 발행 상태 · 캐시 반영 · 재발행 표지 · 오류 줄 · 결과 모름의 요구 발사 · 미룬 적재나 재실행 틱
//	적재  loadDone — CompleteLoad 또는 재요구 · 적재 뒤 틱
//	감시  watchDone — 대조 · 화해 · (c) · 부팅 목록 · Forget(재실행이면 새 입력으로 다시 감시)
func (r *rewindLoop) complete(ctx context.Context, res publish.Result, now time.Time) {
	rerun := r.lanes.Finish(res)
	switch res.Kind {
	case publish.KindPublish:
		r.publishDone(ctx, res.StreamID, res.Publish, rerun, now)
	case publish.KindLoad:
		r.loadDone(ctx, res.StreamID, res.Load, now)
	case publish.KindWatch:
		r.watchDone(ctx, res.Watch, now)
		if rerun {
			r.startWatch(ctx)
		}
	case publish.KindFinalizeScan:
		r.scanDone(ctx, res, rerun, now)
	case publish.KindFinalizeLoad:
		r.finalLoadDone(ctx, res, now)
	case publish.KindFinalize:
		r.finalStepDone(ctx, res, now)
	case publish.KindPlayback:
		r.playbackDone(ctx, res, rerun)
	case publish.KindRevoke:
		r.revokeDone(ctx, res, rerun, now)
	case publish.KindSeal:
		r.sealDone(ctx, res, now)
	}
}

// stream 은 스트림 s 의 발행 몫 상태다 — 없으면 빈 상태를 만든다.
func (r *rewindLoop) stream(s string) *rewindStream {
	rec := r.streams[s]
	if rec == nil {
		if r.streams == nil {
			r.streams = map[string]*rewindStream{}
		}
		rec = &rewindStream{}
		r.streams[s] = rec
	}
	return rec
}

// observeOwner 는 소유 회차 id 를 now 에 본 것으로 적는다 — 처음 보거나 바뀌었으면 그 회차의 발행 상태 · 발행 성공 시각 · 오류
// 가드를 영값에서 다시 시작하고 처음 본 시각을 now 로 둔다(publish.State 「소유 회차가 바뀌면 영값에서」 · 판단 J29). 적재 중 ·
// 뷰 없음으로 Owner 가 거짓인 동안은 부르지 않는다 — 그것은 소유 회차가 바뀐 것이 아니다(7a 검수 C7a-13).
func (s *rewindStream) observeOwner(id string, now time.Time) {
	if s.owner == id {
		return
	}
	s.owner, s.ownedSince, s.publishedAt, s.pub, s.lastErr = id, now, time.Time{}, publish.State{}, ""
}

// endOfflineSessions 는 4.1 종료 전이를 실행한다. holdTicks case 가 그 case 의 현재 시각 now 로 부르고, now 는 그
// 틱의 판정과 모든 결과 되돌림에 같이 쓴다.
//
//	점검 발사  판정이 점검 수집을 요구하면 반드시 발사한다 — 재점검 갈래는 판정이 이미 그 표시를 썼다. 결과는
//	           기존 CollectDone case 가 받는다.
//	전이       due 차례로 endLive 를 부른다. 1행이면 그 회차의 ending 을 캐시에 넘기고 업로더의 그 회차 메모리를 지우며(판단
//	           J86), 1행 · 0행 모두 그 꼬리에 한 번 가드를 쓴다.
//	실패       offlineFailed 가 받고 그 틱의 남은 전이를 멈춘다.
//
// 워커로 내리지 않는다 — 판정과 전이 사이에 INSERT 가 끼면 판정하지 않은 회차를 닫는다. 루프 점유는 전이 한 번에
// 되돌림 포함 최대 약 2 × index.TxnDeadline 이다(EndLive 의 대기 상한 — 실패 갈래의 되돌림이 새 시한을 받는다).
func (r *rewindLoop) endOfflineSessions(ctx context.Context, now time.Time) {
	due, collect := r.ix.OfflineDue(now)
	if collect {
		r.ix.StartCollect(ctx, r.root)
	}
	for _, streamID := range due {
		sessionID, err := r.endLive(ctx, streamID)
		if err != nil {
			r.offlineFailed(ctx, streamID, err, now)
			return
		}
		if sessionID != "" {
			r.liveEnded(streamID, sessionID, now)
		}
		r.ix.OfflineTried(streamID, true, now)
	}
}

// queueEnded 는 오프라인 전이가 끝낸 회차를 정산 몫에 넘긴다 — 빠른 길(다음 holdTick 부터 꼬리 정착 + 10초 상한)과 상태 표지
// (회차가 ending 으로 갔다 — 표식이 있었으면 같은 트랜잭션에서 회차도 닫혔다). 정산 몫이 없으면 아무것도 하지 않는다.
func (r *rewindLoop) queueEnded(streamID, sessionID string, now time.Time) {
	if r.fin == nil {
		return
	}
	r.fin.queueFast(streamID, sessionID, now)
	r.fin.inbox.Changed(streamID)
}

// offlineFailed 는 결과를 모르는 전이 실패다(시한 · lock_timeout · DB 오류 — 서버가 커밋했을 수 있다). ERROR 한
// 줄을 남기고, 그 스트림에 뷰나 진행 중인 적재가 있으면 적재를 요구하고(힌트 없음 — 커밋됐으면 적재가 캐시
// 회차를 ending 으로 맞춘다), offlineRetryAfter 동안 어느 스트림도 시도하지 않게 판정에 되돌린다. 한 번 가드는
// 쓰지 않는다 — 쉼 뒤 같은 꼬리로 다시 시도한다.
//
// 부른 쪽 ctx 가 끝나 난 실패는 루프가 끝나는 중이라 아무것도 남기지 않는다 — ctx 가 끊은 실패는 DB 의 상태를
// 말하지 않는다(발행 층 stopped 관례).
func (r *rewindLoop) offlineFailed(ctx context.Context, streamID string, err error, now time.Time) {
	if ctx.Err() != nil {
		return
	}
	r.log.Error("session_offline", "stream_id", streamID, "result", "failed", "err", err)
	r.demandAfterUnknown(streamID)
	r.ix.OfflineTried(streamID, false, now)
}

// forgetUploaderSession 은 끝난 회차 sessionID 의 업로더 메모리를 지운다(판단 J86 — 분리 · 오프라인 1행). 손잡이가 없으면
// 아무것도 하지 않는다 — TD 분할은 루프가 관측하지 못해 업로더의 수명(SessionTTL)이 치운다.
func (r *rewindLoop) forgetUploaderSession(sessionID string) {
	if r.forgetSession != nil {
		r.forgetSession(sessionID)
	}
}

// peek 은 Dirty 원소의 사본이다(손잡이가 없으면 없다).
func (r *rewindLoop) peek() []upload.DirtyEvent {
	if r.peekDirty == nil {
		return nil
	}
	return r.peekDirty()
}

// ack 는 반영을 마친 원소를 지운다(판단 J81 — 틱 발사 뒤 같은 holdTick).
func (r *rewindLoop) ack(events []upload.DirtyEvent) {
	if r.ackDirty == nil || len(events) == 0 {
		return
	}
	r.ackDirty(events)
}

// reflectDirty 는 Dirty 원소를 캐시에 반영하고 원소가 닿은 스트림을 돌려준다(체크리스트 474 A-4 2 · 계획 4.5 A2 결정 8 · 판단
// J80 ⑴). 적재 중인 스트림이면 push 는 캐시 로그에 적히고 그것을 반영으로 본다(A3 결정 2).
//
//	③ 업로드     ApplyPlaybackUploaded
//	init 업로드  ApplyInitUploaded(해제 칸 그대로) — 해제 참이면 WARN session_inherit_declined(confirmed — CAS 가 실제로 푼
//	             확정 · 회차마다 많아야 한 번 · 판단 J83)
//	분리         ApplySessionEnding · 업로더의 그 회차 메모리를 지운다(판단 J86)
func (r *rewindLoop) reflectDirty(events []upload.DirtyEvent) map[string]bool {
	touched := map[string]bool{}
	for _, e := range events {
		switch {
		case e.Kind == upload.DirtySplit:
			r.cache.ApplySessionEnding(e.StreamID, e.SessionID)
			r.forgetUploaderSession(e.SessionID)
		case e.Axis == index.AxisInit:
			r.cache.ApplyInitUploaded(e.StreamID, e.SessionID, e.InheritRevoked)
			if e.InheritRevoked {
				r.log.Warn("session_inherit_declined", "stream_id", e.StreamID, "session_id", e.SessionID, "confirmed", true)
			}
		case e.Axis == index.AxisPlayback:
			r.cache.ApplyPlaybackUploaded(e.StreamID, e.Seq)
		}
		touched[e.StreamID] = true
	}
	return touched
}

// evaluateLadders 는 소유 회차가 있는 스트림마다 정체 사다리를 한 번 평가해 돌려준 상태로 갈아 끼운다(체크리스트 474 A-4 3 ·
// 계획 4.5 A0 결정 6 · 판단 J29 · J34) — 소유 회차가 live 가 아니어도 평가한다(C3 WARN 이 거기서 남는다 — ⓘ). 입력의 창은 겹침이
// 아닌 캐시 스냅숏으로 잰다(ⓑ). L1 이면 그 행의 ③ 백오프를 당기고, L2 면 그 행을 GAP 후보로 돌려준다 — 틱 발사가 그 스트림에
// 평시 틱 대신 GAP 틱을 낸다(판단 J80 ⑵). L2′ 면 그 스트림 차선에 봉인을 낸다(startSeal).
func (r *rewindLoop) evaluateLadders(ctx context.Context, now time.Time) map[string]int64 {
	gaps := map[string]int64{}
	for _, s := range r.cache.Streams() {
		owner, ok := r.cache.Owner(s)
		if !ok {
			continue
		}
		rec := r.stream(s)
		rec.observeOwner(owner.SessionID, now)
		snap := r.cache.Snapshot(s)
		w, _ := boundary.Compute(snap, rec.prevTail)
		var step publish.LadderStep
		rec.ladder, step = r.pub.EvaluateLadder(ctx, rec.ladder, publish.LadderInput{
			StreamID: s, Snapshot: snap, Window: w, Owner: owner.RewindSession, Publish: rec.pub,
			UnstoodGapSeq: rec.unstoodGap, PublishedAt: rec.publishedAt, OwnedSince: rec.ownedSince, Now: now,
		})
		if step.Expedite && r.expedite != nil {
			r.expedite(s, step.Seq)
		}
		if step.Gap {
			gaps[s] = step.Seq
		}
		if step.Seal {
			r.startSeal(ctx, s, owner.SessionID, now)
		}
	}
	return gaps
}

// renewFences 는 lazy 갱신을 낸다(체크리스트 474 A-4 6 · 계획 커밋 순서 7 〔r51〕 〔r51a〕 · 판단 J80 ⑷) — 틱을 내는 소유
// 회차(ShouldTick)마다 fence 를 쥐었고 마지막 갱신에서 lazy 간격이 지났으며 차선이 빈 스트림에만 낸다. 도는 작업에 병합하지
// 않는다 — 병합되면 끝난 뒤 틱이 한 번 더 돌아 P0 왕복이 는다(도는 틱의 P0 이 lease 를 이미 늘린다).
func (r *rewindLoop) renewFences(ctx context.Context, now time.Time) {
	for s, rec := range r.streams {
		if rec.running != nil || !rec.pub.FenceHeld || now.Sub(rec.pub.RenewedAt) < r.lazyRenewAfter {
			continue
		}
		owner, ok := r.cache.Owner(s)
		if !ok || !publish.ShouldTick(owner.RewindSession, r.cache.Loading(s)) {
			continue
		}
		st, renew, sid := rec.pub, r.work.renew, owner.SessionID
		if r.lanes.Start(ctx, s, func(ctx context.Context) publish.Result {
			return publish.Result{Kind: publish.KindPublish, Publish: renew(ctx, st, sid)}
		}) {
			rec.running = &laneJob{kind: jobRenew, session: sid}
		}
	}
}

// watch 는 30초 감시를 낸다(체크리스트 474 A-4 7 · 판단 J85 ⑴) — 발행 갈래를 도는 첫 holdTick(부팅 첫 감시 — 계획 4.5 A3 결정 6)과
// 그 뒤 watchEvery ÷ holdEvery 번째마다 한 번이다. 조립이 주기를 넘기지 않았으면 아무것도 하지 않는다.
func (r *rewindLoop) watch(ctx context.Context) {
	if r.holdEvery <= 0 {
		return
	}
	n := int(r.watchEvery / r.holdEvery)
	if n <= 0 {
		return
	}
	if r.ticks%n == 0 {
		r.startWatch(ctx)
	}
	r.ticks++
}

// startWatch 는 감시 작업을 프로세스 차선에 낸다 — 입력은 지금 뜬 드리프트 대조 입력이다(holdTick 이면 그 Dirty 반영 뒤 — 419
// Q-2). 감시가 돌고 있으면 Lanes 가 병합하고 결과를 받은 뒤 새 입력으로 다시 낸다(complete).
func (r *rewindLoop) startWatch(ctx context.Context) {
	probes, watch := r.cache.DriftProbes(), r.work.watch
	r.lanes.StartProcess(ctx, func(ctx context.Context) publish.Result {
		return publish.Result{Kind: publish.KindWatch, Watch: watch(ctx, probes)}
	})
}
