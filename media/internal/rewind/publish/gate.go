package publish

// 발행 억제 게이트 · 틱 디스패치 판정 — 계획 4.5 A0 결정 3 · A2 결정 9 · 체크리스트 A-2 · 판단 J16. 판정은 이 순수
// 함수이고, 루프가 그것을 불러 틱을 내거나 내지 않는 것은 커밋 7 이다(발행 틱을 내보내는 자리가 거기에 생긴다).

import "github.com/3K-PokeClip/pokeclip-mono/media/internal/index"

// ShouldTick 은 소유 회차 owner 의 목록에 발행 틱(평시 틱 · GAP 틱)을 낼 것인가다 — owner 가 live 이고 그 init
// 이 올라갔을 때만 참이다. 거짓이면 루프는 틱을 내지 않는다(틱 0 · P0 왕복 0).
//
// 입력은 루프 캐시의 회차 값이다 — DB 를 묻지 않는다(프로필 4절 「평시 DB 조회 0」). DB 하드 가드(P0 의 state =
// 'live' · init_uploaded_at IS NOT NULL)는 커밋 3 에 그대로 있다. 이 판정은 그 가드가 거절할 P0 을 보내지 않는
// 캐시 쪽 짝이다 — ending 회차에 헛도는 P0 왕복을 막고(A0 결정 3), 계승 해제의 순서 논증(A2 결정 8 · 11 —
// init 이 확정되지 않은 회차에는 틱이 없다)이 여기에 기댄다. 적재 중인 스트림 · 목록을 만들 수 없는 스트림의
// 조건은 뒤 커밋이 더한다(판단 J16).
func ShouldTick(owner index.RewindSession) bool {
	return owner.State == "live" && owner.InitUploaded
}
