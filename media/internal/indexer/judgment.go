package indexer

// 판정 묶음 — 두 가지를 판정한다. 들어온 조각을 무엇으로 볼 것인가(관측 접기 · 주조 입력 ·
// ⓐ2 자격 · 세션 연산 · 주조 채널)와 방송이 끝났는가(4.1 종료 판정 — 판정 상태 포함, 계획 4.1).
// 판정만 하고 실행(INSERT · 세션 결정 SQL · 점검 수집 발사 · 전이 UPDATE)은 호출자가 한다.
// 앞의 묶음은 indexer.go 에서 옮겼다(README M3 이관 기록의 부채 행 — 그 묶음을 실제로 건드리는
// M4 발행 축 판정 편입과 같은 커밋에서 옮긴다).

import (
	"slices"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// observation 은 폴러의 관측을 판정부가 쓰는 형태로 접는다(mtxstate → index).
//
// **등급(tier)을 여유(EPOCH_SLACK) 값으로 바꾸는 자리가 여기 하나다**(계획 3절):
// 판정부(session.Registry)는 여유를 값으로만 받으므로 등급 체계가 늘어도 부등식은
// 그대로다. 아는 등급이 아니면 EpochKnown 을 내려 fail-closed 한다 — 모르는 등급에
// 여유를 지어내면 옛 방송의 잔존물이 새 세션을 열 수 있다.
func (ix *Indexer) observation(streamID string) index.SessionObservation {
	o := ix.observer.Latest(streamID)
	obs := index.SessionObservation{
		Publishing:     o.Publishing,
		ObservedAt:     o.ObservedAt.UTC(),
		EpochStartedAt: o.EpochStartedAt.UTC(),
	}
	// tier ⓘ(onlineTime 그대로) = 여유 0. M3 폴러가 산출하는 유일한 등급이다.
	if o.EpochKnown && o.Tier == mtxstate.TierOnlineTime {
		obs.EpochKnown = true
		obs.EpochSlack = 0
	}
	return obs
}

// buildSeed 는 이 조각의 주조 판정 입력(ⓐ 비시간 항)과 **ⓐ2 자격**을 함께 만든다 — ADR-062.
//
//	ⓐ1(실시간 유입 방증): Reason ∈ {NextFile, Idle, Hook} — 파일·훅 유입 자체가 "지금
//	    방송이 흐른다"의 방증이다. 앵커 쌍 = (start_wall_utc, LIVE_FRESH).
//	ⓐ2(상태 방증): ⓐ1 이 불성립일 때(스캔 유입) 또는 ⓐ1 앵커가 늙어 갈 때 쓴다.
//	    앵커 쌍 = (ObservedAt, OBS_FRESH).
//	ReasonRegrown·ReasonUnknown: 어느 방증도 쓰지 않는다(s3_unknown_declines).
//
// **반환값이 둘인 것이 계약이다**(계획 4.4 — 이중 판정 금지): ⓐ2 자격은 주조 쌍 선택과
// 세션 개시 권한 둘 다의 입력인데, 두 자리에서 따로 재면 같은 조각이 "주조는 ⓐ2 인데
// 세션은 못 여는" 식으로 갈린다. 여기서 한 번 재서 호출자가 둘에 나눠 싣는다.
//
// **now 를 인자로 받는 이유**: 아래 두 로컬 예비판정이 시계를 본다. 인자로 두면
// 경계값을 sleep 없이 잴 수 있고, 판정 시각의 출처가 호출자에게 드러난다.
func (ix *Indexer) buildSeed(seg recording.Segment, obs index.SessionObservation, now time.Time) (index.Seed, bool) {
	s := index.Seed{
		Reason:    index.SeedReasonLiveIngress,
		Channel:   seedChannel(seg.Reason),
		AnchorUTC: seg.StartWall.UTC(),
		Freshness: liveFresh,
	}

	liveIngress := false
	switch seg.Reason {
	case recording.ReasonNextFile, recording.ReasonIdle, recording.ReasonHook:
		liveIngress = true
	case recording.ReasonScan:
		// ⓐ1 불성립 — 관측만이 방증이다.
	default:
		// 재성장·사유 미상은 ⓐ2 도 시도하지 않는다: 주조도 세션 개시도 하지 않는다.
		return s, false
	}

	corroborated := ix.corroborates(seg, obs)
	// 쌍 선택에는 자격 위에 **로컬 신선도**를 하나 더 건다: SQL 이 문장 실행 시점에
	// clock_timestamp() 로 재검하므로, 지금 이미 남은 창이 트랜잭션 상한보다 얇으면
	// 그 쌍으로는 어차피 탈락한다. 자격 자체(세션 축)는 이 판정에 걸리지 않는다.
	usableAnchor := corroborated && !obs.ObservedAt.IsZero() &&
		now.Sub(obs.ObservedAt) <= ix.opt.ObsFresh-index.TxnDeadline

	if liveIngress {
		s.Eligible = ix.opt.SeedEnabled
		// ⓐ1 앵커가 늙어 가는 국면(백로그 추격 등)에서만 갈아탄다.
		// 불성립이면 ⓐ1 쌍 그대로 내려간다 — 현행과 같아 나빠지지 않는다.
		if usableAnchor && now.Sub(seg.StartWall) >= liveFresh-index.TxnDeadline {
			s = ix.withStateObs(s, obs)
		}
		return s, corroborated
	}

	// 스캔 유입 — ⓐ2 쌍을 못 고르면 방증이 없으므로 비적격이다(ⓐ1 쌍은 쓸 수 없다).
	if !usableAnchor {
		return s, corroborated
	}
	s = ix.withStateObs(s, obs)
	s.Eligible = ix.opt.SeedEnabled
	return s, corroborated
}

// corroborates 는 ⓐ2 자격의 **비시간 항 + 두 하한**이다(설계 6.5.2 ⓐ2 · 5.4.1 ⑵).
//
// 관측 신선도(시간 항)는 여기 없다 — 그것은 트랜잭션 안에서 시도마다 다시 재는 항이라
// session.Registry 가 재고, 주조 축은 SQL 이 clock_timestamp() 로 재검한다.
// 여기 있는 세 항은 호출당 피연산자가 고정이라 답이 바뀌지 않는다.
func (ix *Indexer) corroborates(seg recording.Segment, obs index.SessionObservation) bool {
	if !obs.Publishing || !obs.EpochKnown {
		return false
	}
	wall := seg.StartWall.UTC()
	// 백로그 하한 — 관측보다 한참 과거의 조각은 지금 방송의 증거가 될 수 없다(s2_4).
	if wall.Before(obs.ObservedAt.Add(-ix.opt.ObsBackfill)) {
		return false
	}
	// 에폭 하한 — 이번 송출이 시작되기 전의 조각은 옛 방송의 잔존물이다(s2_6c).
	return !wall.Before(obs.EpochStartedAt.Add(-obs.EpochSlack))
}

// withStateObs 는 방증 쌍을 ⓐ2 로 바꾼다. **채널은 건드리지 않는다** —
// seed_channel 은 언제나 유입 채널 그대로이고(계획 4.4), 방증 갈래는 seed_reason 이 진다.
func (ix *Indexer) withStateObs(s index.Seed, obs index.SessionObservation) index.Seed {
	s.Reason = index.SeedReasonStateObs
	s.AnchorUTC = obs.ObservedAt
	s.Freshness = ix.opt.ObsFresh
	return s
}

// sessionOp 은 유입 사유와 ⓐ2 자격을 세션 결정 연산으로 접는다(설계 3.3·5.4 유입 표).
//
// buildSeed 와 나란히 두는 이유: 둘 다 "이 유입을 무엇으로 볼 것인가"이고, 갈라지면
// 같은 조각이 주조 축과 세션 축에서 서로 다른 유입으로 취급된다.
//
// **CurrentOrOpenIfCorroborated 는 자격이 성립할 때만 지정한다**(session/registry.go 의
// 호출자 계약): 레지스트리는 시간 가변 항만 다시 재고 백로그 하한은 다시 재지 않는다.
// 자격 없이 지정하면 그 하한을 아무도 본 적 없는 채로 세션이 열려 교차 방송 오귀속이 된다.
//
// 재성장·사유 미상은 열지 않는 축이다 — 열지 않을 뿐 귀속은 한다(현 세션이 있으면
// 그 세션의 조각이 맞다). 여기서 비귀속으로 접으면 세션 중간에 NULL 구멍이 생기고
// 그 조각은 되감기 목록에서 사라진다.
func sessionOp(r recording.CompletionReason, corroborated bool) index.SessionOp {
	switch r {
	case recording.ReasonNextFile, recording.ReasonIdle, recording.ReasonHook:
		return index.SessionOpenOrCurrent
	case recording.ReasonScan:
		if corroborated {
			return index.SessionCurrentOrOpenIfCorroborated
		}
		return index.SessionCurrentOnly
	default:
		return index.SessionCurrentOnly
	}
}

// seedChannel 은 유입 사유를 stream_cutoffs.seed_channel 값으로 접는다.
func seedChannel(r recording.CompletionReason) index.SeedChannel {
	switch r {
	case recording.ReasonHook:
		return index.SeedChannelHook
	case recording.ReasonScan:
		return index.SeedChannelScan
	default:
		return index.SeedChannelWatcher
	}
}

// offlineRetryAfter 는 4.1 의 재시도 간격이다 — 결손으로 자격을 못 얻은 점검의 재점검(기다림마다
// 한 번)과 전이 실패 뒤 모든 스트림의 쉼이 이 값을 쓴다(계획 4.1 · 4.5 E 숫자 const).
const offlineRetryAfter = 30 * time.Second

// offlineState 는 스트림 하나의 4.1 판정 상태다. 기다림(since · rechecked · logged)은 송출 재개 ·
// 관측 낡음 · 전이 시도로 끝나고 since 뒤에 시작한 꼬리가 오면 다시 센다. 한 번 가드(tried ·
// triedSeq)는 기다림이 끝나도 남는다 — 같은 꼬리로는 다시 시도하지 않는다.
type offlineState struct {
	// since 는 이 기다림의 기준 시각이다 — !Publishing 을 처음 본 관측의 시각이거나, since 뒤에
	// 시작한 꼬리를 보고 기다림을 다시 센 틱의 시각이다. 영값이면 기다림이 없다.
	since time.Time
	// rechecked 는 이 기다림의 재점검을 썼다는 표시다. 그 뒤 판정 입력은 주기 수집과 워처
	// 재스캔 신호 수집이 만든다.
	rechecked bool
	// logged 는 이 기다림의 점검 대기 로그를 남겼다는 표시다(기다림 한 번에 한 줄).
	logged bool
	// tried · triedSeq 는 한 번 가드다 — 전이를 시도한(1행 · 0행) 꼬리의 seq. 실패는 쓰지 않는다.
	tried    bool
	triedSeq int64
}

// OfflineDue 는 4.1 종료 판정이다(계획 4.1). due 는 지금 live→ending 전이를 시도할 스트림이고
// (정렬됨), collect 가 참이면 점검 수집이 필요하다 — 호출자가 StartCollect 를 발사하고 결과는
// 기존 CollectDone 경로가 받는다. 한 발사가 기다리는 모든 스트림을 덮는다(수집은 트리 전체).
//
// 대상은 커서 꼬리가 있는 스트림 가운데 지금 꼬리로 전이를 아직 시도하지 않은 것이다. 전이
// 가능 = ① 관측 신선 ∧ !Publishing ∧ ② 유입 정지 ∧ ③ 점검 완주. 전이 실패 뒤
// offlineRetryAfter 동안은 due 를 비우되 판정 상태는 그대로 갱신한다. EpochKnown 은 쓰지
// 않는다(에폭 산출 전용).
//
// loop 단일 고루틴만 부른다(D10) — 판정과 전이 UPDATE 사이에 INSERT 가 끼지 않아야 판정한
// 회차를 닫는다.
func (ix *Indexer) OfflineDue(now time.Time) (due []string, collect bool) {
	// 점검 발사의 트리 전체 조건이다. 래치 트립 중에는 발사하지 않는다 — 래치는 주기 수집이나
	// 워처 재스캔 신호 수집이 푼다(ADR-063 결정 4). 비행 중이면 발사해도 단일 비행이 건너뛴다.
	canFire := !ix.fsLatch.Tripped() && !ix.collectInflight
	paused := now.Before(ix.offlinePausedUntil)
	for streamID, cur := range ix.cursors {
		if cur.Tail == nil {
			continue // 꼬리가 없으면 ② 를 잴 수 없다 — 판정하지 않는다
		}
		ready, fire := ix.judgeOffline(streamID, cur.Tail, now, canFire)
		if ready && !paused {
			due = append(due, streamID)
		}
		collect = collect || fire
	}
	slices.Sort(due)
	return due, collect
}

// judgeOffline 은 스트림 하나의 4.1 판정이다 — 전이할 수 있는가(ready)와 점검 수집을 부를
// 것인가(fire). 기다림을 열고 · 다시 세고 · 지우는 자리가 여기다(전이 시도로 끝내는 것은
// OfflineTried 다).
func (ix *Indexer) judgeOffline(streamID string, tail *index.TailRow, now time.Time, canFire bool) (ready, fire bool) {
	st := ix.offline[streamID]
	if st.tried && st.triedSeq == tail.Seq {
		return false, false // 한 번 가드 — 끝난 옛 방송은 점검도 전이도 부르지 않는다
	}
	// ① 관측 — 신선 = 스냅샷 있음 ∧ 나이 ≤ OBS_FRESH. 불성립이거나 송출 중이면 기다림을
	// 지운다(fail-closed · 한 번 가드는 남는다).
	obs := ix.observer.Latest(streamID)
	fresh := !obs.ObservedAt.IsZero() && now.Sub(obs.ObservedAt) <= ix.opt.ObsFresh
	if !fresh || obs.Publishing {
		if !st.since.IsZero() {
			ix.offline[streamID] = offlineState{tried: st.tried, triedSeq: st.triedSeq}
		}
		return false, false
	}
	if st.since.IsZero() {
		st.since = obs.ObservedAt
	}
	// 꼬리가 since 뒤에 시작했다 — !Publishing 을 본 뒤에 시작한 송출(관측 폴 사이에 끝난 짧은
	// 재송출)이 있었다. 앞서 자격을 준 점검은 since + IdleTimeout 뒤에 시작했으므로 그 점검 뒤에 생긴
	// 파일은 모두 since 뒤에 시작한 조각이고, 점검은 그 송출의 나머지 파일을 못 봤을 수 있다 — 기다림을
	// 이 틱부터 다시 센다(관측 시각은 그 조각보다 이를 수 있어 이 틱의 시각을 쓴다). since 앞에 시작한
	// 조각이 늦게 꼬리가 된 것(끝난 방송의 마지막 조각을 워처 Idle 이 넣는 평시 끝)은 기다림을 그대로
	// 둔다 — 평시 종료를 늦추지 않고, 점검 때 장부 밖이었다면 ③(b) 결손 ⅱ 가 이미 막았다.
	if tail.StartWallUTC.After(st.since) {
		st = offlineState{since: now, tried: st.tried, triedSeq: st.triedSeq}
	}
	stopped := ingestStopped(tail, now)
	checked := ix.offlineChecked(streamID, st.since)
	if stopped && !checked && !st.logged {
		st.logged = true
		ix.log.Info("session_offline", "stream_id", streamID, "result", "awaiting_check",
			"seq", tail.Seq, "wait_started_at", st.since)
	}
	if !checked && canFire {
		fire = ix.checkFire(&st, now)
	}
	ix.offline[streamID] = st
	return stopped && checked, fire
}

// ingestStopped 는 ② 유입 정지다 — 마지막 조각 끝으로부터 3 × 그 조각의 실제 길이가 지났다
// (kty ⑸ — 헤더값 TD 가 아니라 장부의 실제 길이다).
func ingestStopped(tail *index.TailRow, now time.Time) bool {
	d := time.Duration(tail.DurationMS) * time.Millisecond
	return now.Sub(tail.StartWallUTC.Add(d)) >= 3*d
}

// offlineChecked 는 ③ 점검 완주다 — (a) 가장 최근에 적용된 수집이 4.1 완주이고 since +
// IdleTimeout 뒤에 시작했고 (b) 그 수집에 이 스트림의 점검 결손이 없고 (c) 지금 FS
// 래치가 정상이다.
// 여유(IdleTimeout)는 점검이 마지막 파일을 워처에 넘기지 않을 만큼 늦게 시작하라는 것이다 —
// 넘겼으면 (b) 가 막는다.
func (ix *Indexer) offlineChecked(streamID string, since time.Time) bool {
	if ix.completedCollectStart.Before(since.Add(ix.opt.IdleTimeout)) {
		return false
	}
	if gap, ok := ix.checkGaps[streamID]; ok && !gap.Before(ix.completedCollectStart) {
		return false
	}
	return !ix.fsLatch.Tripped()
}

// checkFire 는 점검 발사 판정이다 — 첫 점검(여유가 지났는데 since + IdleTimeout 뒤에 시작한
// 수집이 아직 없다) 또는 재점검(그런 수집이 자격을 못 얻었고, 그 시작에서 offlineRetryAfter 가
// 지났고, 이 기다림에서 아직 안 썼다). 트리 전체 조건은 호출자가 걸렀으므로 참이면 곧
// 발사된다 — 그래서 재점검 갈래는 여기서 표시를 쓴다.
func (ix *Indexer) checkFire(st *offlineState, now time.Time) bool {
	margin := st.since.Add(ix.opt.IdleTimeout)
	last := ix.collectStart // 비행이 없으니 마지막으로 발사한 수집이 곧 마지막 수집이다
	if last.Before(margin) {
		return !now.Before(margin)
	}
	if st.rechecked || now.Sub(last) < offlineRetryAfter {
		return false
	}
	st.rechecked = true
	return true
}

// OfflineTried 는 전이 시도 결과를 판정 상태에 되돌린다. ok 는 전이 문장이 끝났다는 뜻이다(1행 ·
// 0행 모두 — 0행은 live 회차가 없다는 것이지 오류가 아니다): 그 꼬리에 한 번 가드를 쓰고
// 기다림을 끝낸다. !ok 는 서버 커밋 여부를 모르는 실패다: 가드를 쓰지 않고(쉼 뒤 같은 꼬리로
// 다시 시도한다) offlineRetryAfter 동안 어느 스트림도 due 에 올리지 않는다. 그 틱의 나머지
// 전이를 멈추는 것은 호출자 몫이다.
//
// OfflineDue 와 같은 select case 안에서 부른다(D10) — 사이에 INSERT 가 끼면 꼬리가 움직여
// 판정하지 않은 꼬리에 가드를 쓴다.
func (ix *Indexer) OfflineTried(streamID string, ok bool, now time.Time) {
	if !ok {
		ix.offlinePausedUntil = now.Add(offlineRetryAfter)
		return
	}
	cur := ix.cursors[streamID]
	if cur == nil || cur.Tail == nil {
		return // due 에 든 적 없는 스트림 — 가드를 쓸 꼬리가 없다
	}
	ix.offline[streamID] = offlineState{tried: true, triedSeq: cur.Tail.Seq}
}

// markCheckGap 은 지금 처리 중인 수집에서 streamID 에 점검 결손(③(b))이 났다고 적는다. 수집은
// 시작 시각으로 가린다 — 단일 비행이라 처리 중인 결과의 시작은 collectStart 다. 이 수집은 그
// 스트림의 점검 자격이 없다.
func (ix *Indexer) markCheckGap(streamID string) {
	ix.checkGaps[streamID] = ix.collectStart
}
