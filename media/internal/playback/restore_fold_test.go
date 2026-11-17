package playback

import (
	"errors"
	"reflect"
	"testing"
	"time"
)

// 이 파일은 복원(E18 — POK-196 PDT 계획 4절 c10b)이 쓰는 두 조각을 본다 — 한 걸음씩 접기(ChainFold)와, ③ 객체가 ② 조각을
// 어느 도장 위치로 재포장한 것인지 되짚기(StampOf · 설계 r6 2.10 「닻 되살리기」 · 「이미 있는 객체는 검증한 뒤 채택」).

// ChainFold 를 한 걸음씩 부른 결과는 RestoreChain 과 같다 — RestoreChain 이 그 위에 선다.
func TestChainFoldStepsLikeRestoreChain(t *testing.T) {
	recA, recB := [16]byte{0xaa}, [16]byte{0xbb}
	anchor := Link{Seq: 3, SessionID: "S1", Recorder: &recA, Number: int64Ptr(3), Mtxi: 10 * time.Second,
		Dur: 4 * time.Second, Pos: 10 * time.Second}
	opening := source(8, recB, 1, 30*time.Second)
	opening.SessionID = "S2"
	rows := []SourceTiming{source(4, recA, 4, 14*time.Second), {Seq: 5, Dur: 4 * time.Second},
		source(6, recA, 5, 22*time.Second), {Seq: 7, SessionID: "S2", Dur: 4 * time.Second}, opening}
	rule := stitchAt(17_500*time.Millisecond, new([]int64))
	want := RestoreChain(&anchor, rows, rule, sameRecorder)

	fold := NewChainFold(&anchor, rule, sameRecorder)
	var got []Link
	for _, cur := range rows {
		if l, ok := fold.Step(cur); ok {
			got = append(got, l)
		}
	}

	if !reflect.DeepEqual(got, want) {
		t.Errorf("한 걸음씩 = %+v\nwant RestoreChain %+v", got, want)
	}
}

// Replace 는 방금 접은 행의 링크를 바꾼다 — 다음 행은 바꾼 링크에서 잇는다(이미 있는 ③ 객체를 채택하면 그 객체의 도장에서
// 잇는다). 잡는 결함: 채택한 객체의 도장을 버리고 접은 값으로 이으면, 채택한 행과 다음 행 사이에 틈 · 겹침이 남는다.
func TestChainFoldReplaceContinuesFromReplacedLink(t *testing.T) {
	rec := [16]byte{0xaa}
	anchor := Link{Seq: 3, SessionID: "S1", Recorder: &rec, Number: int64Ptr(3), Mtxi: 10 * time.Second,
		Dur: 4 * time.Second, Pos: 10 * time.Second}
	fold := NewChainFold(&anchor, stitchAt(0, new([]int64)), sameRecorder)
	l4, _ := fold.Step(source(4, rec, 4, 14*time.Second))
	l4.Pos, l4.Offset = 16*time.Second, 2*time.Second

	fold.Replace(l4)
	l5, ok := fold.Step(source(5, rec, 5, 18*time.Second))

	if !ok || l5.Pos != 20*time.Second || l5.Offset != 2*time.Second {
		t.Errorf("바꾼 뒤 다음 행 = (%+v, %v), want pos 20s · offset 2s", l5, ok)
	}
}

// StampOf 는 ③ 객체가 ② 조각을 재포장한 도장 위치의 범위를 돌려준다 — 실제 위치가 범위 안이고, 범위는 틱 반올림 폭(영상
// 90kHz 반 틱 ≈ 5.6µs)보다 넓지 않다. 같은 범위 안의 어느 위치로 다시 재포장해도 객체와 같은 바이트다.
func TestStampOfRecoversProducedPosition(t *testing.T) {
	raw := readFixture(t, fixture4s)
	for _, pos := range []time.Duration{0, 5_987_981_859, 17_500_123_457, 3*time.Hour + 7*time.Millisecond + 333} {
		out := produce(t, raw, pos)

		w, err := StampOf(raw, out.Seg)

		if err != nil {
			t.Fatalf("pos %v: StampOf 오류 %v", pos, err)
		}
		if !w.Contains(pos) || w.Hi-w.Lo > 12*time.Microsecond {
			t.Errorf("pos %v: 범위 [%v, %v], want pos 를 품고 폭 ≤ 12µs", pos, w.Lo, w.Hi)
		}
		mid, _ := w.Pick()
		for _, p := range []time.Duration{w.Lo, w.Hi, mid} {
			if again := produce(t, raw, p); string(again.Seg) != string(out.Seg) {
				t.Errorf("pos %v: 범위 안 %v 로 다시 재포장한 바이트가 다르다", pos, p)
			}
		}
	}
}

// 다른 조각에서 만든 객체(표본 열이 다르다)는 되짚을 수 없다 — 오류다. 잡는 결함: 첫 traf 틱 차만 보고 받아들이면 다른
// 판본의 입력으로 만든 객체를 닻으로 삼는다.
func TestStampOfRejectsObjectFromOtherSource(t *testing.T) {
	other := produce(t, readFixture(t, fixtureTail), 4*time.Second)

	_, err := StampOf(readFixture(t, fixture4s), other.Seg)

	if !errors.Is(err, ErrStampMismatch) {
		t.Errorf("err = %v, want ErrStampMismatch", err)
	}
}

// 도장(트랙별 첫 BaseTime 차)이 맞아도 표본 내용이 다르면 같은 원천의 재포장이 아니다 — 오류다. 잡는 결함: 틱 차만 보면 같은
// 길이로 자란 다른 판본의 입력(내용이 다름)으로 만든 객체를 닻 · 채택 대상으로 받는다.
func TestStampOfRejectsObjectWithAlteredSamples(t *testing.T) {
	raw := readFixture(t, fixture4s)
	obj := produce(t, raw, 5*time.Second).Seg
	tampered := append([]byte(nil), obj...)
	tampered[len(tampered)-1] ^= 0xff // 마지막 mdat 의 표본 한 바이트

	_, err := StampOf(raw, tampered)

	if !errors.Is(err, ErrStampMismatch) {
		t.Errorf("err = %v, want ErrStampMismatch", err)
	}
}

// Pick 은 힌트가 범위 안이면 그 힌트를, 아니면 범위 가운데를 고른다 — 힌트는 보정값 후보(0 · 표의 줄)로 만든 위치다. 범위
// 안의 어느 값이든 그 행의 바이트는 같지만, 다음 행들의 보정값은 힌트여야 실시간과 같은 값이 된다.
func TestStampWindowPickPrefersHintInside(t *testing.T) {
	w := StampWindow{Lo: 100, Hi: 110}

	if got, exact := w.Pick(90, 104, 108); got != 104 || !exact {
		t.Errorf("Pick(90, 104, 108) = (%v, %v), want 첫 범위 안 힌트 (104, 실제 값)", got, exact)
	}
	if got, exact := w.Pick(90); got != 105 || exact {
		t.Errorf("Pick(90) = (%v, %v), want 가운데 (105, 실제 값 아님)", got, exact)
	}
}
