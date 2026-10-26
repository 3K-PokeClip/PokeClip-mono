package control

// Limiter(스트림별 1초 고정 창 계수기 — 설계 M5 3.4 · 보완 3 · 종결 정정 cc L8 · 8절 control_rate_limited)의 시험이다.
// 칸은 상한을 넘으면 가장 오래 쓰지 않은 칸부터 축출되고, 세움 상태로 축출되는 칸은 내림 줄을 쓴다(게이지가 1 로 굳지
// 않게). 60초 무초과 칸은 청소가 내리고 지운다.

import (
	"testing"
	"time"
)

func TestLimiterFixedWindow(t *testing.T) {
	c, logs := newClock(), &logSink{}
	l := NewLimiter(3, 16, c.Now, logs.logger())
	for i := range 4 {
		if got, want := l.Allow("s"), i < 3; got != want {
			t.Errorf("1초 창 %d번째 Allow = %v, want %v", i+1, got, want)
		}
	}
	if !l.Allow("t") {
		t.Error("다른 스트림 칸이 s 의 초과에 묶였다")
	}
	c.Advance(time.Second)
	if !l.Allow("s") {
		t.Error("다음 1초 창에서 Allow = false, want true")
	}
	if set := logs.countValue("control_rate_limited", 1); set != 1 {
		t.Errorf("세움 줄 = %d, want 1(첫 초과만)", set)
	}
}

// 칸 상한: 가장 오래 쓰지 않은 칸부터 축출한다. 세움 상태였던 칸은 축출 때 내림 줄을 쓴다.
func TestLimiterEvictsLeastRecentlyUsedAndClearsGauge(t *testing.T) {
	c, logs := newClock(), &logSink{}
	l := NewLimiter(1, 2, c.Now, logs.logger())
	l.Allow("a")
	l.Allow("a") // a 세움
	l.Allow("b")
	l.Allow("a") // a 를 다시 써서 b 가 가장 오래 쓰지 않은 칸
	l.Allow("c") // b 축출
	if n := cellCount(l); n != 2 {
		t.Fatalf("칸 = %d, want 2", n)
	}
	if !l.Allow("b") { // b 는 새 칸이라 첫 요청은 통과 — a 가 축출된다
		t.Error("축출 뒤 b 의 첫 요청 = false")
	}
	if clear := logs.events("control_rate_limited"); len(clear) != 2 || clear[1]["value"] != 0.0 || clear[1]["level"] != "WARN" || clear[1]["stream_id"] != "a" {
		t.Errorf("control_rate_limited 줄 = %v, want a 세움 · a WARN 내림(축출)", clear)
	}
}

// 청소: 60초 무초과면 내림 줄을 쓰고, 60초 쓰지 않은 칸은 지운다.
func TestLimiterSweep(t *testing.T) {
	c, logs := newClock(), &logSink{}
	l := NewLimiter(1, 16, c.Now, logs.logger())
	l.Allow("a")
	l.Allow("a") // 세움
	c.Advance(59 * time.Second)
	l.sweep(c.Now())
	if cellCount(l) != 1 || logs.countValue("control_rate_limited", 0) != 0 {
		t.Fatalf("59초 청소: 칸 %d · 내림 %d, want 1 · 0", cellCount(l), logs.countValue("control_rate_limited", 0))
	}
	c.Advance(time.Second)
	l.sweep(c.Now())
	if cellCount(l) != 0 || logs.countWarnValue("control_rate_limited", 0) != 1 {
		t.Errorf("60초 청소: 칸 %d · WARN 내림 %d, want 0 · 1", cellCount(l), logs.countWarnValue("control_rate_limited", 0))
	}
}
