// Package mtxstate 는 MediaMTX Control API 를 주기적으로 읽어 "지금 이 스트림이 송출
// 중인가"의 **관측**만 담당한다. 판정은 하지 않는다.
//
// 경계가 좁은 이유(설계 5.4.1·6.5.2): 주조 자격 ⓐ2 의 판정은 indexer 가 하고, 여기는
// 그 판정이 읽을 사실(Observation)만 만든다. 관측과 판정이 한 곳에 섞이면 "관측이 낡아서
// 못 정했다"와 "관측은 신선한데 조건이 안 맞았다"를 구분할 수 없게 된다.
//
// **fail-closed 가 기본값이다.** Observation 의 영값은 EpochKnown=false 이고, 그것이
// 곧 "ⓐ2 를 쓰지 않는다"이므로 폴러가 없거나(MTX_API_URL 미설정) 아직 한 번도 성공하지
// 못한 상태에서는 아무것도 주조되지 않는다. 관측 실패의 방향은 언제나 비주조다.
package mtxstate

import "time"

// Tier 는 EpochStartedAt(에폭 시작 시각)이 어디서 왔는지의 등급이다(설계 5.4.1 ⑵).
// 소비자(session.Registry)는 이 값으로 에폭 하한의 여유(EPOCH_SLACK)를 가른다.
type Tier int

const (
	// TierUnknown 은 에폭 시각이 없다는 뜻이다. EpochStartedAt 을 읽으면 안 된다.
	TierUnknown Tier = iota
	// TierOnlineTime 은 설계 5.4.1 의 tier ⓘ 다 — Control API 의 onlineTime 을 그대로 쓴다.
	// 여유는 0 이다(시각 자체가 정확하므로 보정할 것이 없다).
	//
	// **M3 폴러가 산출하는 tier 는 이것 하나뿐이다.** 실측(F-34)상 항목이 실재하면
	// onlineTime 이 언제나 함께 오고, 오지 않는 예상 밖 응답은 EpochKnown=false 로
	// fail-closed 된다. 설계 5.4.1 의 tier ⓘⓘ(전이 관측)·ⓘⓘⓘ(첫 관측이 이미 true)는
	// 산출 경로가 없어 상수를 두지 않는다 — 관측 축 구현은 M4 재판정으로 이관했다.
	TierOnlineTime
)

// Observation 은 한 스트림에 대한 관측 스냅샷의 조회 결과다.
//
// **필드가 서로 다른 축을 잰다** — 헷갈리면 판정이 뒤집힌다:
//   - Publishing = "지금 송출 중인가". 항목 부재(송출 종료)는 여기서 거짓이 된다.
//   - EpochKnown = "이 관측이 에폭에 대해 답할 수 있는가". 거짓인 경우는 **오직 둘**이다 —
//     ⑴ 관측 이력 0(부팅 직후·첫 성공 이전) ⑵ 항목 실재 ∧ onlineTime 부재.
//     **항목 부재는 여기가 아니라 Publishing 축이다**: 폴은 성공했고 답도 나왔다("송출
//     중이 아니다"). 부재를 EpochKnown 으로 표현하면 "관측 실패"와 구분이 사라진다.
//
// EpochStartedAt·Tier 는 Publishing 이 참일 때만 의미가 있다.
type Observation struct {
	// Publishing 은 항목 실재 ∧ online==true ∧ source non-nil 의 결합이다(F-34 실측).
	Publishing bool
	// ObservedAt 은 그 스냅샷을 만든 poll 의 **시작** 시각이다(완료 시각이 아니다).
	// 보수적인 쪽이다 — 신선도 판정이 실제보다 관측을 낡게 본다.
	ObservedAt time.Time
	// EpochStartedAt 은 현재 송출이 시작된 시각(Control API 의 onlineTime)이다.
	EpochStartedAt time.Time
	// EpochKnown 이 거짓이면 ⓐ2 는 성립하지 않는다(설계 5.4.1 ⑵ — fail-closed).
	EpochKnown bool
	// Tier 는 EpochStartedAt 의 출처 등급이다.
	Tier Tier
}

// snapshot 은 한 번의 성공한 poll 이 만든 원자 교체 단위다.
//
// 부분 갱신을 만들지 않는 이유(계획 3절 ⑴): Control API 는 요청마다 목록을 새로 만들어
// 정렬한 뒤 페이지를 자르므로 **여러 페이지는 서로 다른 시점의 스냅샷**이다. 그것을 이어
// 붙이면 살아 있는 경로가 빠지고 사라진 경로가 남는 조합이 조용히 만들어진다.
type snapshot struct {
	observedAt time.Time
	// items 의 키는 MediaMTX 경로 이름이고 우리 어휘로는 streamID 다.
	items map[string]pathState
}

// pathState 는 한 경로에 대해 스냅샷이 기억하는 전부다.
type pathState struct {
	publishing bool
	// onlineTime 은 영값이면 "응답에 없었다"는 뜻이다 — EpochKnown=false 로 간다.
	onlineTime time.Time
}

// Latest 는 streamID 의 최신 관측을 돌려준다. 폴러를 멈추지 않는 순수 조회다.
//
// 소비자(indexer·session)가 보는 유일한 계약이며, 아래 세 갈래가 전부다:
//
//	관측 이력 0            → 영값(EpochKnown=false) = ⓐ2 fail-closed
//	항목 부재              → Publishing=false ∧ EpochKnown=true (정상 음성)
//	항목 실재              → Publishing 3 항 결합 ∧ onlineTime 유무가 EpochKnown 을 가른다
func (p *Poller) Latest(streamID string) Observation {
	snap := p.snap.Load()
	if snap == nil {
		return Observation{}
	}
	obs := Observation{ObservedAt: snap.observedAt, EpochKnown: true}
	st, ok := snap.items[streamID]
	if !ok {
		return obs
	}
	obs.Publishing = st.publishing
	if st.onlineTime.IsZero() {
		obs.EpochKnown = false
		return obs
	}
	obs.EpochStartedAt = st.onlineTime
	obs.Tier = TierOnlineTime
	return obs
}
