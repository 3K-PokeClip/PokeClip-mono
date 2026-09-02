package main

// 세션 결정자 어댑터의 단위 검증(POK-195 M3 — 계획 4.6 조립 지점).
//
// 이 어댑터는 두 패키지의 타입을 잇는 얇은 층이라 로직이 없다시피 하지만, **매핑이
// 어긋나면 조용히 다른 갈래로 흐른다** — 스캔 유입이 OpenOrCurrent 로 접히면 옛 잔존물이
// 세션을 연다. 그래서 값 대조를 여기서 못 박는다.

import (
	"context"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

func TestSessionOpMapsOneToOne(t *testing.T) {
	for _, c := range []struct {
		in   index.SessionOp
		want session.Op
	}{
		{index.SessionOpenOrCurrent, session.OpenOrCurrent},
		{index.SessionCurrentOrOpenIfCorroborated, session.CurrentOrOpenIfCorroborated},
		{index.SessionCurrentOnly, session.CurrentOnly},
	} {
		got, err := sessionOpOf(c.in)
		if err != nil {
			t.Fatalf("연산 %d 매핑 실패: %v", c.in, err)
		}
		if got != c.want {
			t.Errorf("연산 %d → %d, want %d", c.in, got, c.want)
		}
	}
}

// 영값은 "안 채웠다"는 사고 신호다 — 조용히 어느 갈래로 접히면 배선 누락이 새어 나간다.
func TestSessionOpRejectsUnspecified(t *testing.T) {
	if got, err := sessionOpOf(0); err == nil {
		t.Fatalf("미지정 연산이 %d 로 통과했다", got)
	}
}

// Open 은 자기가 Decide 에서 만든 불투명 값만 받는다 — 다른 결정의 값으로 세션을 열면
// 그 조각이 아닌 조각의 규칙으로 세션 행이 굳는다.
func TestSessionWireOpenRejectsForeignPlan(t *testing.T) {
	d := sessionDecider{reg: session.New(session.Options{})}
	_, err := d.Open(context.Background(), nil,
		index.SessionDecision{Opens: true, Plan: "남의 계획"}, time.Now())
	if err == nil {
		t.Fatal("남의 불투명 값으로 세션 개시가 시도됐다")
	}
}
