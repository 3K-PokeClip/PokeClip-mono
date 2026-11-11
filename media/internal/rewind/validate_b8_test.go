package rewind_test

// 발행 전 검사 S7 의 판 2 갈래 — B8 세 조건(POK-196 PDT 생산자 c7b · 설계 r7 2.7 · 2.10 (나) · P11 · 계약 r24 초안
// 6-6-3 「이웃 세 조건」). 판 2 이웃 k−1 · k 는 발행되는 PDT 줄(ms 로 자른 값)로 셋을 모두 지켜야 한다.
//
//	조건 1  시작이 앞선다       PDT(k) > PDT(k−1)
//	조건 2  겹침이 1초 미만이다  PDT(k) > PDT(k−1) + dur(k−1) − 1000ms
//	조건 3  끝이 앞선다         PDT(k) + dur(k) > PDT(k−1) + dur(k−1)
//
// 생산자는 겹침 갈래에서 max(w, E − 999, E − dur(k) + 1, S + 1) 을 쓴다(index.MixV2 — K1 = (나) · K1-c 999ms).
// 그 네 항이 셋을 함께 만족하는지는 생산자 출력을 그대로 렌더해 Validate 에 넣어 본다(TestValidateAcceptsMixV2Overlap).
// 판 NULL 이웃과 판이 바뀌는 이웃은 지금 규칙(1ms 여유) 그대로다 — 세 조건은 같은 판 2 목록 안에서만 선다.

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// pairB8 는 회차 W(TD 6)의 두 조각 seq 70(PDT t0 · 길이 dPrev) · 71(PDT pdt · 길이 dCur) 목록이다. 두 행의 판은 v 다.
func pairB8(v int16, dPrev int32, pdt time.Time, dCur int32) rewind.Playlist {
	w := rewind.Session{ID: "W", TargetDuration: 6, MinSeq: 70, InitUploaded: true}
	rows := []boundary.Row{row("W", 70, clock(12, 0, 0, 0), dPrev), row("W", 71, pdt, dCur)}
	return playlist("W", []rewind.Session{w}, withVersion(rows, v))
}

// wantB8 는 S7 판정과 까닭에 적힌 조건 번호를 본다. cond 가 0 이면 통과를 바란다.
func wantB8(t *testing.T, err error, cond int) {
	t.Helper()
	if cond == 0 {
		wantVerdict(t, err, "")
		return
	}
	wantVerdict(t, err, "S7")
	var v *rewind.Violation
	if errors.As(err, &v) && !strings.Contains(v.Reason, fmt.Sprintf("B8 조건 %d", cond)) {
		t.Errorf("까닭 = %q, want 「B8 조건 %d」 를 적는다", v.Reason, cond)
	}
}

// 세 조건과 경계(±1ms)를 하나씩 깬다. 입력은 재귀식을 거치지 않은 검사 경계 값이다 — 앞 조각 seq 70 은 12:00:00.000 에
// 시작한다. 짧은 뒤 조각(앞 [0, 4000) · w = 2500 · dur = 167)은 설계 2.7 의 예다: 생산자는 셋째 항으로 PDT = 3834 를
// 낸다. 변이 m3′(그 항 제거)이면 PDT = max(2500, 4000 − 999) = 3001 이라 끝 3168 < 4000 → 조건 3 위반이다.
func TestValidatePDTB8ThreeConditions(t *testing.T) {
	t0 := clock(12, 0, 0, 0)
	ms := func(n int) time.Time { return t0.Add(time.Duration(n) * time.Millisecond) }
	tests := []struct {
		name  string
		dPrev int32
		pdt   time.Time
		dCur  int32
		cond  int
	}{
		{"이어짐", 4000, ms(4000), 4000, 0},
		{"앞으로_뜀", 4000, ms(12000), 4000, 0},
		{"겹침_999ms", 4000, ms(3001), 4000, 0},
		{"겹침_1000ms", 4000, ms(3000), 4000, 2},
		{"겹침_999ms_빼기_1µs_발행_줄은_1000ms", 4000, ms(3001).Add(-time.Microsecond), 4000, 2},
		{"짧은_뒤_조각_끝이_1ms_앞섬", 4000, ms(3834), 167, 0},
		{"짧은_뒤_조각_끝이_같음", 4000, ms(3833), 167, 3},
		{"m3′_출력_끝이_832ms_모자람", 4000, ms(3001), 167, 3},
		{"뒤_조각이_앞_조각_안에_통째로", 4000, ms(3500), 400, 3},
		{"시작이_1ms_앞섬", 500, ms(1), 4000, 0},
		{"시작이_같음", 500, t0, 4000, 1},
		{"시작이_같음_발행_줄", 500, t0.Add(500 * time.Microsecond), 4000, 1},
		{"시작이_뒤로", 500, ms(-1), 4000, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validate(t, pairB8(timingVersion2, tt.dPrev, tt.pdt, tt.dCur), 1, nil)

			wantB8(t, err, tt.cond)
		})
	}
}

// 판 NULL 이웃과 판이 바뀌는 이웃은 세 조건이 아니라 지금 규칙(겹침 1ms 까지)이다 — 판 NULL 장부는 재귀식이라 겹치지
// 않고(pdtSlack 주석), 판이 바뀌는 이웃은 한 목록에 들지 않는다(I20 · 계승은 앞 세션 판). 판 2 에서 통과하는 999ms
// 겹침이 여기서는 멈춘다.
func TestValidatePDTOverlapOutsideV2KeepsOneMillisecond(t *testing.T) {
	t0 := clock(12, 0, 0, 0)
	overlap999 := t0.Add(3001 * time.Millisecond)
	toV2 := pairB8(0, 4000, overlap999, 4000)
	toV2.Rows[1].TimingVersion = timingVersion2
	fromV2 := pairB8(timingVersion2, 4000, overlap999, 4000)
	fromV2.Rows[1].TimingVersion = 0
	tests := []struct {
		name string
		p    rewind.Playlist
		want string
	}{
		{"판_NULL_겹침_1ms", pairB8(0, 4000, t0.Add(3999*time.Millisecond), 4000), ""},
		{"판_NULL_겹침_999ms", pairB8(0, 4000, overlap999, 4000), "S7"},
		{"판_NULL_에서_판_2_로_겹침_999ms", toV2, "S7"},
		{"판_2_에서_판_NULL_로_겹침_999ms", fromV2, "S7"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validate(t, tt.p, 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// 세션이 바뀌는 판 2 이웃(계승 접두 P 의 끝 seq 42 → P 를 계승한 S 의 첫 seq 43)도 세 조건으로 잰다 — 검사는 판만 보고
// 세션을 가르지 않는다(c7b cc M1). 계약 r24 6-6-3 은 세 조건을 「같은 판 · 같은 회차의 목록 안」 에 두는데, 여기서
// 회차는 세션이 아니라 timing_round_id 다. 한 목록에 두 세션이 실리는 것은 계승 접두뿐이고 계승은 앞 세션의 회차를
// 잇는다(session.openFromPrevious 5행 — 회차 없는 M6 전 세션을 계승하면 새 회차지만 그 세션은 판 NULL 이라 계승도 판
// NULL 이다). 그래서 이 이웃은 같은 회차 안이고, 앞 회차 끝보다 뒤로 갈 수 있는 새 회차 첫 행(E24)은 한 목록에 들지
// 않는다. 앞 세션 끝 E 에서 500ms 겹친 첫 행은 지나고, 1초 겹친 첫 행 · 앞 조각 시작까지 뒤로 간 첫 행은 멈춘다.
func TestValidatePDTB8AcrossRounds(t *testing.T) {
	end := day.Add(12 * time.Second) // P seq 42 는 day+8s 에서 4초
	tests := []struct {
		name string
		pdt  time.Time
		cond int
	}{
		{"앞_회차_끝에서_500ms_겹침", end.Add(-500 * time.Millisecond), 0},
		{"앞_회차_끝에서_1000ms_겹침", end.Add(-time.Second), 2},
		{"앞_조각_시작까지_뒤로", day.Add(8 * time.Second), 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := prefixed()
			p.Rows = withVersion(p.Rows, timingVersion2)
			p.Rows[3].PlaybackPDT = tt.pdt

			err := validate(t, p, 1, nil)

			wantB8(t, err, tt.cond)
		})
	}
}

// 겹침 행 렌더 → Validate(계획 9절 c7b 음성 대조 대상). 생산자 섞기 식(index.MixV2)이 겹침 갈래에서 낸 PDT · 길이를
// 그대로 목록 행으로 렌더하면 S7 을 통과한다 — 생산자 B8 과 소비자 S7 이 같은 조건이다(설계 P11). 짐작 w 는 앞 끝 E
// 에서 θ 넘게 이른 범위(Δ = −θ−1 부터 앞 시작 S 보다 1.5초 이른 곳까지)의 점이다 — 네 항이 바뀌는 자리(E − 999 ·
// E − dur(k) + 1 · S + 1)의 ±1ms 와 범위 양끝, 그리고 그 사이 37ms 간격. 앞 길이 · 뒤 길이는 짧은 조각(1 · 2 ·
// 167ms)과 1초 경계(999 · 1000ms)를 넣는다. 한 칸이라도 겹침 갈래가 아니면 입력이 시험을 벗어난 것이라 멈춘다.
func TestValidateAcceptsMixV2Overlap(t *testing.T) {
	const pdtPrev int64 = 1_800_000_000_000
	theta := index.MixThreshold.Milliseconds()
	round := "R"
	for _, dPrev := range []int32{1, 2, 167, 999, 1000, 4000} {
		for _, dCur := range []int32{1, 2, 167, 999, 1000, 4000} {
			e := pdtPrev + int64(dPrev)
			for _, w := range overlapWalls(pdtPrev, e, theta, dCur) {
				out := mixOverlap(t, pdtPrev, dPrev, w, dCur, &round)
				if t.Failed() {
					return
				}
				p := pairB8(timingVersion2, dPrev, time.UnixMilli(out.PDTMS).UTC(), out.DurationMS)
				p.Rows[0].PlaybackPDT = time.UnixMilli(pdtPrev).UTC()
				if err := rewind.Validate(p, []byte(mustRender(t, p)), 1, nil); err != nil {
					t.Fatalf("dPrev %d · dCur %d · w = E%+d → PDT = E%+d(%s) 가 S7 에 걸렸다: %v",
						dPrev, dCur, w-e, out.PDTMS-e, out.LimitedBy, err)
				}
			}
		}
	}
}

// overlapWalls 는 앞 행(시작 s · 끝 e) 뒤 겹침 갈래로 가는 짐작 w 의 점들이다 — [s − 1500, e − θ − 1] 안에서 범위
// 양끝 · 겹침 식 항이 바뀌는 자리의 ±1ms · 37ms 간격.
func overlapWalls(s, e, theta int64, dCur int32) []int64 {
	lo, hi := s-1500, e-theta-1
	ws := []int64{lo, hi}
	for _, at := range []int64{e - 999, e - int64(dCur) + 1, s + 1} {
		ws = append(ws, at-1, at, at+1)
	}
	for w := hi; w > lo; w -= 37 {
		ws = append(ws, w)
	}
	return slices.DeleteFunc(ws, func(w int64) bool { return w < lo || w > hi })
}

// mixOverlap 은 판 2 앞 행(PDT pdtPrev · 길이 dPrev · 녹화기 표식 없음 — 늘 견줌) 뒤에 짐작 w · 영상 길이 dCur 인 조각을
// MixV2 로 섞는다. 겹침 갈래가 아니면 시험을 멈춘다.
func mixOverlap(t *testing.T, pdtPrev int64, dPrev int32, w int64, dCur int32, round *string) index.TimingV2 {
	t.Helper()
	v2 := timingVersion2
	prev := &index.PrevRow{Seq: 70, DurationMS: dPrev, PDTMS: &pdtPrev, Version: &v2}
	view := index.LedgerView{Prev: prev, Round: round}
	in := index.TimingInput{
		Facts:            index.TimingFacts{Wall: time.UnixMilli(w)},
		HeadVideo:        true,
		VideoOwnMS:       dCur,
		LegacyDurationMS: dCur,
	}
	out := index.MixV2(view, in)
	if out.Branch != index.BranchNewChapterOverlap || out.DurationMS != dCur {
		t.Errorf("MixV2(dPrev %d · w = E%+d · dCur %d) = 갈래 %s · 길이 %d, want 겹침 · 길이 %d",
			dPrev, w-pdtPrev-int64(dPrev), dCur, out.Branch, out.DurationMS, dCur)
	}
	return out
}
