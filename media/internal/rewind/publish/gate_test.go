package publish

// 발행 억제 게이트 · 틱 디스패치 판정(계획 4.5 A0 결정 3 · A2 결정 9 · 체크리스트 363 A-2 · 419 A-1 4 · 판단 J16 ·
// J31 · J37) — 순수 함수라 PG 없이 돈다. 이 시험은 판정 값을 단언하고, 「틱 0 · P0 왕복 0」 종단 단언은 루프가
// 판정을 부르는 커밋 7 이다.

import (
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// ending_session_publishes_no_more(계획 4.5 A0 테스트) — ending 전이가 캐시에 닿으면 그 소유 회차에는 틱을 내지
// 않는다. init 이 올라가 있어도 그렇다(뮤테이션 75 의 게이트 쪽 변형 — 판정이 ending 을 참으로 본다). 대조군: live
// 회차는 틱을 낸다.
func TestEndingSessionPublishesNoMore(t *testing.T) {
	tests := []struct {
		state string
		want  bool
	}{
		{"live", true},
		{"ending", false},
		{"ended", false},
	}
	for _, tt := range tests {
		owner := index.RewindSession{SessionID: "S", State: tt.state, InitUploaded: true}
		if got := ShouldTick(owner, false); got != tt.want {
			t.Errorf("ShouldTick(state %s · init 확정 · 적재 중 아님) = %v, want %v", tt.state, got, tt.want)
		}
	}
}

// 발행 억제 게이트(계획 4.5 A2 결정 9 · G5 캐시 비트 · 뮤테이션 12) — init 이 올라가지 않은 live 소유 회차에는
// 틱을 내지 않는다. 커밋 3 의 P0 가드(init_uploaded_at IS NOT NULL — 뮤테이션 107)가 PUT 을 막아도 이 판정이
// 없으면 P0 왕복이 헛돈다 — 그래서 「PUT 0」이 아니라 판정 값으로 단언한다(J31).
func TestUninitializedSessionGetsNoTick(t *testing.T) {
	tests := []struct {
		initUploaded bool
		want         bool
	}{
		{false, false},
		{true, true},
	}
	for _, tt := range tests {
		owner := index.RewindSession{SessionID: "S", State: "live", InitUploaded: tt.initUploaded}
		if got := ShouldTick(owner, false); got != tt.want {
			t.Errorf("ShouldTick(live · init 확정 %v · 적재 중 아님) = %v, want %v", tt.initUploaded, got, tt.want)
		}
	}
}

// should_tick_false_while_loading(판단 J37 · 체크리스트 419 A-1 4 · 장부 421 P-3) — 그 스트림이 적재 중이면 owner 가
// live 이고 init 이 확정이어도 틱을 내지 않는다. 적재 중에는 캐시 Playlist 도 거짓이라 틱 입력이 서지 않는다(이중
// 방어 — 루프 몫은 커밋 7). 적재 중이 아니면 판정은 그대로다(위 두 시험의 행).
func TestShouldTickFalseWhileLoading(t *testing.T) {
	owner := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true}
	for _, tt := range []struct {
		loading bool
		want    bool
	}{
		{true, false},
		{false, true},
	} {
		if got := ShouldTick(owner, tt.loading); got != tt.want {
			t.Errorf("ShouldTick(live · init 확정 · 적재 중 %v) = %v, want %v", tt.loading, got, tt.want)
		}
	}
}
