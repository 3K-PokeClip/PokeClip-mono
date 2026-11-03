package index

import (
	"fmt"
	"testing"
	"time"
)

// 섞기 표 시험(설계 2.5 · 2.6 · 2.7 · 10.4 단위). 겹침 갈래는 K1 = (나) 1초 미만 부분 겹침 ·
// 한 번 당김 999ms 다(kty 2026-11-03).

// mixP 는 시험의 같은 회차 앞 행 p 의 PDT(ms)다 — 시각 값 자체는 뜻이 없고 차만 본다.
const mixP int64 = 1_800_000_000_000

// mixRound 는 시험 회차 이름이다.
var mixRound = "round-R"

// mixPrev 는 판 2 앞 행 p 다 — seq 10 · 시작 100000 · 길이 dur · PDT mixP.
func mixPrev(dur int32, facts TimingFacts) *PrevRow {
	pdt := mixP
	return &PrevRow{Seq: 10, StartPTSMS: 100_000, DurationMS: dur, PDTMS: &pdt, Version: new(versionV2), Facts: facts}
}

// mixIn 은 지금 조각 재료다. 짐작은 ms 값 wall 이다.
func mixIn(wall int64, facts TimingFacts, headVideo bool) TimingInput {
	facts.Wall = time.UnixMilli(wall)
	return TimingInput{Facts: facts, HeadVideo: headVideo, LegacyDurationMS: 4010}
}

// mixLast 는 q(seq < k 최신 행)의 위치 재료다.
func mixLast(start int64, dur int32) *struct {
	StartPTSMS int64
	DurationMS int32
} {
	return &struct {
		StartPTSMS int64
		DurationMS int32
	}{start, dur}
}

// mixView 는 판 2 앞 행 p 가 있는 회차 안 장부 모습이다(q = p).
func mixView(p *PrevRow) LedgerView {
	return LedgerView{Prev: p, Last: mixLast(p.StartPTSMS, p.DurationMS), Round: &mixRound}
}

// 같은 녹화기 X 의 10번(p) · 11번(k) 조각. p 의 영상은 [36s, 40s) 다.
var (
	mixPrevFacts = withVideo(marked(recorderX, 10), 36*time.Second, 40*time.Second)
	mixNextFacts = withVideo(marked(recorderX, 11), 40*time.Second, 44*time.Second)
)

// 회차 첫 행의 까닭 넷 · 하한 거부 위치 · timing_round_broken 두 까닭(설계 2.5 위 네 줄).
// 회차 첫 행은 늘 PDT = w 다. 위치만 하한 거부(q.start)와 나머지(q 끝)가 갈린다 — 「하한 거부
// 위치」 행이 변이 m24′(하한 거부 행이 위치를 나아감)를 잡는다.
func TestMixV2RoundFirst(t *testing.T) {
	const w = mixP + 4000
	in := mixIn(w, mixNextFacts, true)
	// 앞 회차 끝 행 — 래칫 앞섬 L = 5000 이라 그 끝(E = P + 9000)은 w(P + 4000)와 다르다(변이 m6 입력).
	v2Prev := mixPrev(4000, mixPrevFacts)
	led := mixP + 5000
	v2Prev.PDTMS = &led
	legacyPrev := mixPrev(4000, mixPrevFacts)
	legacyPrev.Version = nil
	tests := []struct {
		name       string
		view       LedgerView
		wantStart  int64
		wantReason string
		wantBroken string
	}{
		{"하한_거부_위치는_q_시작", LedgerView{Last: mixLast(5000, 4000), FloorRejected: true}, 5000, "floor_rejected", ""},
		{"하한_거부_q_없음", LedgerView{FloorRejected: true}, 0, "floor_rejected", ""},
		{"회차_없음", LedgerView{Last: mixLast(5000, 4000)}, 9000, "no_round", ""},
		{"새_회차", LedgerView{Last: mixLast(5000, 4000), Round: &mixRound, NewRound: true}, 9000, "new_round", ""},
		{"새_회차_스트림_첫_행", LedgerView{Round: &mixRound, NewRound: true}, 0, "stream_first", ""},
		{"회차_있는데_p_없음", LedgerView{Last: mixLast(5000, 4000), Round: &mixRound}, 9000, "", "prev_missing"},
		{"회차_있는데_p_판_NULL", LedgerView{Prev: legacyPrev, Last: mixLast(5000, 4000), Round: &mixRound}, 9000, "", "version_mismatch"},
		// 새 회차 첫 행은 Prev 가 와도 잇지 않는다(변이 m6 — 회차 첫 행이 앞 행 끝 PDT 를 승계).
		{"새_회차는_앞_행을_잇지_않음", LedgerView{Prev: v2Prev, Last: mixLast(5000, 4000), Round: &mixRound, NewRound: true}, 9000, "new_round", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := MixV2(tt.view, in)
			if got.Branch != BranchRoundFirst || got.PDTMS != w || got.StartPTSMS != tt.wantStart {
				t.Errorf("MixV2 = %s, want round_first · PDT %d · start %d", mixShow(got), w, tt.wantStart)
			}
			if got.ResetReason != tt.wantReason || got.Broken != tt.wantBroken {
				t.Errorf("MixV2 까닭 = (%q, %q), want (%q, %q)", got.ResetReason, got.Broken, tt.wantReason, tt.wantBroken)
			}
			if got.Compared || got.SameInstance {
				t.Errorf("MixV2 = %s, 회차 첫 행은 견주지 않는다", mixShow(got))
			}
		})
	}
}

// 겹침이 아닌 섞기 세 갈래(continue · join · new_chapter_gap)와 견줄 조각 술어(설계 2.5 · 2.1
// comparable). θ 경계 앞뒤는 공개 MixV2 가 MixThreshold 를 쓰는지도 함께 본다.
func TestMixV2NonOverlapBranches(t *testing.T) {
	const e = mixP + 4000
	theta := MixThreshold.Milliseconds()
	hole := withVideo(marked(recorderX, 11), 40*time.Second+45*time.Millisecond, 44*time.Second)
	tiny := withVideo(marked(recorderX, 11), 40*time.Second, 40*time.Second+500*time.Microsecond)
	tests := []struct {
		name         string
		in           TimingInput
		want         Branch
		wantPDT      int64
		wantCompared bool
	}{
		{"소리_머리_같은_녹화기는_잇는다", mixIn(e+3000, mixNextFacts, false), BranchContinue, e, false},
		{"영상_머리_문턱_안_늦음", mixIn(e+theta, mixNextFacts, true), BranchJoin, e, true},
		{"영상_머리_문턱_안_이름", mixIn(e-theta, mixNextFacts, true), BranchJoin, e, true},
		{"영상_머리_문턱_넘게_늦음", mixIn(e+theta+1, mixNextFacts, true), BranchNewChapterGap, e + theta + 1, true},
		// 변이 m1(견줄 조각을 「영상 머리 ∨ ¬맞닿음」 으로) — 같은 녹화기 안 소리 머리 구멍은 견주지 않는다.
		{"소리_머리_구멍_45ms_도_잇는다", mixIn(e+148, hole, false), BranchContinue, e, false},
		{"다른_녹화기는_소리_머리도_견준다", mixIn(e+theta+1, withVideo(marked(recorderY, 0), 0, 4*time.Second), false), BranchNewChapterGap, e + theta + 1, true},
		{"번호_건너뜀은_견준다", mixIn(e+theta+1, withVideo(marked(recorderX, 12), 44*time.Second, 48*time.Second), false), BranchNewChapterGap, e + theta + 1, true},
		{"영상_1ms_미만은_견준다", mixIn(e+theta+1, tiny, false), BranchNewChapterGap, e + theta + 1, true},
		{"영상_없음은_견준다", mixIn(e+theta+1, marked(recorderX, 11), false), BranchNewChapterGap, e + theta + 1, true},
		// 영상 구간이 int32 ms 를 넘으면 영상 길이를 모른다(cx H1 — c5 video_span_invalid 와 같은 경계).
		{"영상_2^31ms_는_견준다", mixIn(e+theta+1, withVideo(marked(recorderX, 11), 40*time.Second, 40*time.Second+(1<<31)*time.Millisecond), false), BranchNewChapterGap, e + theta + 1, true},
		// 칸 NULL 조합(예측표 P18): 지금 조각 mtxi 없음.
		{"지금_조각_표식_없음은_견준다", mixIn(e+theta+1, TimingFacts{}, false), BranchNewChapterGap, e + theta + 1, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := MixV2(mixView(mixPrev(4000, mixPrevFacts)), tt.in)
			if got.Branch != tt.want || got.PDTMS != tt.wantPDT || got.Compared != tt.wantCompared {
				t.Errorf("MixV2 = %s, want %s · PDT %d · compared %v", mixShow(got), tt.want, tt.wantPDT, tt.wantCompared)
			}
			// 위치 보폭 = PDT 보폭(설계 2.11) — 회차 안 기준점 PDT − start 가 하나다.
			if want := int64(100_000) + got.PDTMS - mixP; got.StartPTSMS != want {
				t.Errorf("MixV2 start = %d, want %d(p.start + PDT − S)", got.StartPTSMS, want)
			}
			if got.Compared && got.DeltaMS != tt.in.Facts.Wall.UnixMilli()-e {
				t.Errorf("MixV2 Δ = %d, want w − E = %d", got.DeltaMS, tt.in.Facts.Wall.UnixMilli()-e)
			}
		})
	}
}

// 앞 행 칸이 NULL(출시 이미지 행 · mtxi 없는 행)이면 같은 녹화기로 보지 않고 견준다(P18).
// 소리 머리 · w = E − 500 이라 견주면 겹침 갈래(PDT = w)다. 변이 m21(nil 표식끼리 같다고 봄)이면
// continue(PDT = E)로 접힌다.
func TestMixV2NullFactsAreCompared(t *testing.T) {
	const w = mixP + 3500
	for _, prevFacts := range []TimingFacts{{}, mixPrevFacts} {
		got := MixV2(mixView(mixPrev(4000, prevFacts)), mixIn(w, TimingFacts{}, false))
		if !got.Compared || got.SameInstance || got.Branch != BranchNewChapterOverlap || got.PDTMS != w {
			t.Errorf("MixV2(앞 칸 %s) = %s, want 견줌 · 다른 녹화기 · overlap · PDT w", describe(prevFacts), mixShow(got))
		}
	}
}

// 길이(설계 2.6): 맞닿음이면 B(k) − B(p), 아니면 B(k) − A(k)(ns 칸의 ms 내림), mtxi 가 없으면
// 파일 안 영상 길이, 고른 영상 길이가 1ms 미만이면 mvhd. 판 2 의 모든 행이 1 이상이다. 앞 행 영상 끝을
// 39.9998s 로 두어 B(p) = 39999 와 A(k) = 40000 이 갈리게 한다 — 맞닿음을 무시하면 1ms 차가 난다.
func TestMixV2Duration(t *testing.T) {
	ms := time.Millisecond
	prevFacts := withVideo(marked(recorderX, 10), 36*time.Second, 40*time.Second-200*time.Microsecond)
	tests := []struct {
		name string
		in   TimingInput
		want int32
	}{
		{"맞닿음은_앞_끝부터", mixIn(mixP+4000, withVideo(marked(recorderX, 11), 40*time.Second+300*time.Microsecond, 44*time.Second+700*time.Microsecond), false), 4001},
		{"맞닿지_않으면_자기_길이", mixIn(mixP+4000, withVideo(marked(recorderX, 11), 40*time.Second+45*ms, 44*time.Second), false), 3955},
		{"다른_녹화기는_자기_길이_ms_내림", mixIn(mixP+4000, withVideo(marked(recorderY, 0), 1*ms+900*time.Microsecond, 4001*ms+100*time.Microsecond), false), 4000},
		// B 프레임 송출의 인스턴스 첫 조각은 영상 첫 DTS 가 음수다(T-ovl 2차 vtb) — 0 쪽이 아니라 아래로 내린다.
		{"음수_첫_DTS_는_아래로_내림", mixIn(mixP+4000, withVideo(marked(recorderY, 0), -11*time.Microsecond, 5833*ms+300*time.Microsecond), false), 5834},
		{"mtxi_없음은_파일_안_영상_길이", TimingInput{Facts: TimingFacts{Wall: time.UnixMilli(mixP + 4000)}, VideoOwnMS: 3967, LegacyDurationMS: 4010}, 3967},
		{"영상_1ms_미만은_mvhd", mixIn(mixP+4000, withVideo(marked(recorderY, 0), 0, 500*time.Microsecond), false), 4010},
		// 영상 길이를 int32 로 좁히기 전에 상한을 본다(cx H1). 2^31 − 1 까지는 영상 길이, 넘으면 mvhd —
		// 좁혀서 감기면 2^31 은 음수, 2^32 + 1 은 1 이 되어 dur ≥ 1 · B8 끝 앞섬이 깨진다.
		{"영상_2^31−1ms_는_그대로", mixIn(mixP+4000, withVideo(marked(recorderY, 0), 0, (1<<31-1)*ms), false), 1<<31 - 1},
		{"영상_2^31ms_는_mvhd", mixIn(mixP+4000, withVideo(marked(recorderY, 0), 0, (1<<31)*ms), false), 4010},
		{"영상_2^32+1ms_는_mvhd", mixIn(mixP+4000, withVideo(marked(recorderY, 0), 0, (1<<32+1)*ms), false), 4010},
		{"파일_안_영상_길이만_있어도_같은_경계", TimingInput{Facts: TimingFacts{Wall: time.UnixMilli(mixP + 4000)}, VideoOwnMS: 1<<31 - 1, LegacyDurationMS: 4010}, 1<<31 - 1},
		{"영상_없음은_mvhd", TimingInput{Facts: marked(recorderY, 0), LegacyDurationMS: 225}, 225},
		{"mvhd_0_이어도_1_이상", TimingInput{Facts: marked(recorderY, 0)}, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.in.Facts.Wall.IsZero() {
				tt.in.Facts.Wall = time.UnixMilli(mixP + 4000)
			}
			got := MixV2(mixView(mixPrev(4000, prevFacts)), tt.in)
			if got.DurationMS != tt.want {
				t.Errorf("MixV2 길이 = %d, want %d(%s)", got.DurationMS, tt.want, mixShow(got))
			}
		})
	}
}

// 영상 표본 하나(0.5ms)뿐인 조각 뒤의 join 행(예측표 P7 · 변이 m9 — 1ms 하한 제거). 하한이
// 없으면 그 조각 길이가 0 이 되어 다음 행 PDT 가 앞 행 PDT 와 같아진다(B8 첫 조건 위반).
func TestMixV2TinyVideoKeepsStartAdvancing(t *testing.T) {
	tinyFacts := withVideo(marked(recorderX, 11), 40*time.Second, 40*time.Second+500*time.Microsecond)
	tinyIn := mixIn(mixP+4000, tinyFacts, true)
	tinyIn.LegacyDurationMS = 1
	k := MixV2(mixView(mixPrev(4000, mixPrevFacts)), tinyIn)
	if k.DurationMS < 1 {
		t.Fatalf("MixV2(0.5ms 조각) 길이 = %d, want 1 이상", k.DurationMS)
	}
	pdt := k.PDTMS
	kRow := &PrevRow{Seq: 11, StartPTSMS: k.StartPTSMS, DurationMS: k.DurationMS, PDTMS: &pdt, Version: new(versionV2), Facts: tinyFacts}
	nextFacts := withVideo(marked(recorderX, 12), 40*time.Second+500*time.Microsecond, 44*time.Second)
	next := MixV2(mixView(kRow), mixIn(k.PDTMS+int64(k.DurationMS), nextFacts, true))
	if next.Branch != BranchJoin || next.PDTMS <= k.PDTMS {
		t.Errorf("다음 행 = %s, want join · PDT > %d", mixShow(next), k.PDTMS)
	}
}

// 판별 입력(변이 m8 — p 를 seq 바로 앞 행 q 로, 확인 패스 cx M1). 새 회차 첫 행이면 NewRound
// 갈래가 먼저 걸려 p 가 무엇이든 같은 답이 나오므로, 회차 한가운데 하한 거부 행 u 가 q 로 낀
// 꼴(예측표 P22)을 쓴다. 바른 p(k1)면 같은 녹화기로 잇고, q(u)를 p 로 넣으면 다른 갈래 · 위치가
// 나와 이 시험의 단언이 깨져야 한다.
func TestMixV2PrevIsRoundLatestNotSeqLatest(t *testing.T) {
	k1 := mixPrev(4000, mixPrevFacts)
	uPDT := mixP - 3000
	u := &PrevRow{Seq: 11, StartPTSMS: k1.StartPTSMS, DurationMS: 4000, PDTMS: &uPDT, Version: new(versionV2),
		Facts: withVideo(marked(recorderY, 7), 0, 4*time.Second)}
	k2 := mixIn(mixP+4000, mixNextFacts, true)
	view := LedgerView{Prev: k1, Last: mixLast(u.StartPTSMS, u.DurationMS), Round: &mixRound}

	got := MixV2(view, k2)
	if got.Branch != BranchJoin || got.PDTMS != mixP+4000 || got.StartPTSMS != k1.StartPTSMS+4000 || !got.SameInstance {
		t.Errorf("MixV2(p = k1) = %s, want join · PDT P+4000 · start k1.start+4000 · 같은 녹화기", mixShow(got))
	}
	// 판별력 확인: q 를 p 로 넣으면 위 단언이 깨진다(이 입력이 m8 을 가른다는 증거).
	wrong := MixV2(LedgerView{Prev: u, Last: view.Last, Round: &mixRound}, k2)
	if wrong.Branch == got.Branch && wrong.StartPTSMS == got.StartPTSMS {
		t.Errorf("MixV2(p = u) = %s, 바른 p 와 같은 답 — 입력이 m8 을 가르지 못한다", mixShow(wrong))
	}
}

// 겹침 갈래(K1 (나))의 네 항과 B8 세 조건(설계 2.5 · 2.7). 이긴 항마다 한 행이고, 행마다 세 조건
// 과 위치 보폭을 함께 단언한다.
//   - 둘째 항(E − 999): rec P2 Δ = −1976 — 변이 m3.
//   - 셋째 항(E − dur(k) + 1): 앞 [0, 4000) · w = 2500 · dur(k) = 167 → 3834 — 변이 m3′.
//   - 넷째 항(S + 1): p = 225ms 영상 없는 행 · w = S − 3000 · dur(k) 4000 — 변이 m2.
//   - 위치에 앞 끝 하한이 남으면 start ≥ p.start + p.dur 로 기준점이 둘이 된다 — 변이 m4.
func TestMixV2OverlapPartial(t *testing.T) {
	const e = mixP + 4000
	shortNext := withVideo(marked(recorderX, 11), 40*time.Second, 40*time.Second+167*time.Millisecond)
	tests := []struct {
		name    string
		prevDur int32
		in      TimingInput
		wantPDT int64
		wantBy  string
	}{
		{"짐작이_이김", 4000, mixIn(e-500, mixNextFacts, true), e - 500, "w"},
		{"겹침_상한이_이김", 4000, mixIn(e-1976, mixNextFacts, true), e - 999, "overlap_cap"},
		{"끝_앞섬이_이김", 4000, mixIn(mixP+2500, shortNext, true), e - 166, "end_lead"},
		{"시작_앞섬이_이김", 225, mixIn(mixP-3000, withVideo(marked(recorderY, 0), 0, 4*time.Second), false), mixP + 1, "start_lead"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := mixPrev(tt.prevDur, mixPrevFacts)
			got := MixV2(mixView(p), tt.in)
			if got.Branch != BranchNewChapterOverlap || got.PDTMS != tt.wantPDT || got.LimitedBy != tt.wantBy {
				t.Fatalf("MixV2 = %s, want overlap · PDT %d · %s", mixShow(got), tt.wantPDT, tt.wantBy)
			}
			mixCheckB8(t, p, got)
			if want := p.StartPTSMS + got.PDTMS - mixP; got.StartPTSMS != want {
				t.Errorf("start = %d, want %d(p.start + PDT − S · 앞 끝 하한 없음)", got.StartPTSMS, want)
			}
		})
	}
}

// mixCheckB8 은 p · k 이웃의 B8 세 조건이다(설계 2.7) — 시작이 앞선다 · 겹침 1초 미만 · 끝이
// 앞선다. PDT 축과 start 축 둘 다 본다(같은 보폭).
func mixCheckB8(t *testing.T, p *PrevRow, k TimingV2) {
	t.Helper()
	axes := []struct {
		name  string
		prevS int64
		curS  int64
	}{
		{"PDT", *p.PDTMS, k.PDTMS},
		{"start", p.StartPTSMS, k.StartPTSMS},
	}
	for _, a := range axes {
		prevE := a.prevS + int64(p.DurationMS)
		if a.curS <= a.prevS {
			t.Errorf("B8 첫 조건(%s): %d ≤ 앞 시작 %d", a.name, a.curS, a.prevS)
		}
		if a.curS <= prevE-1000 {
			t.Errorf("B8 둘째 조건(%s): %d ≤ 앞 끝 − 1000 = %d", a.name, a.curS, prevE-1000)
		}
		if a.curS+int64(k.DurationMS) <= prevE {
			t.Errorf("B8 셋째 조건(%s): 끝 %d ≤ 앞 끝 %d", a.name, a.curS+int64(k.DurationMS), prevE)
		}
	}
}

// mixShow 는 실패 메시지용 요약이다. PDT 는 mixP 에서 뺀 값이다.
func mixShow(v TimingV2) string {
	name := string(v.Branch)
	for _, s := range []string{v.ResetReason, v.Broken, v.LimitedBy} {
		if s != "" {
			name += "/" + s
		}
	}
	return fmt.Sprintf("%s PDT=P%+d start=%d dur=%d Δ=%d same=%v cmp=%v",
		name, v.PDTMS-mixP, v.StartPTSMS, v.DurationMS, v.DeltaMS, v.SameInstance, v.Compared)
}
