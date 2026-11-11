package edgebounds

// KVS 값 원문(하한)의 단위 검증(POK-525 설계 2-3 · 3-3 · E6 · 계획 Ⓒ-1).
// 엣지 함수와 공유 골든으로 맞추고, 줄 문법이 실을 수 없는 입력(빈 값 · 공백)과 Encode 의 경계는 여기서 직접 본다.

import (
	"math"
	"strconv"
	"testing"
)

// 공유 골든 testdata/value.golden.txt — Go 의 DecodeValue 와 엣지 함수(dvr-floor.js) 시험이 같은 파일을 읽는다. 줄
// 문법: `#` 줄과 빈 줄은 건너뛰고, 나머지 줄은 탭 두 개로 나뉜 세 열이다 — 원문 · 기대(ok 면 십진 값 · err 면 `-`) ·
// 판정(ok | err). 잡는 결함: 안전 정수 상한 · 1 이상 · 앞자리 0 금지 · 십진 숫자만 가운데 하나가 빠지거나 넓어지면 그
// 행이 갈린다 — Go 와 엣지가 같은 원문을 다르게 읽는다.
func TestDecodeValueMatchesSharedGolden(t *testing.T) {
	rows := readValueGolden(t)
	if len(rows) == 0 {
		t.Fatal("골든에 행이 없다")
	}
	for _, r := range rows {
		got, err := DecodeValue(r.text)
		switch r.verdict {
		case "ok":
			if err != nil {
				t.Errorf("줄 %d: DecodeValue(%q) = 오류 %v, want ok %d", r.line, r.text, err, r.want)
				continue
			}
			if got != r.want {
				t.Errorf("줄 %d: DecodeValue(%q) = %d, want %d", r.line, r.text, got, r.want)
			}
		case "err":
			if err == nil {
				t.Errorf("줄 %d: DecodeValue(%q) = %d, want err", r.line, r.text, got)
			}
		}
	}
}

// 골든 ok 행의 원문은 EncodeValue(기대)와 같다 — 한 값에 원문이 하나라 Writer 가 쓴 값을 엣지가 그대로 읽는다. 잡는
// 결함: Encode 가 앞자리 0 · 부호 · 다른 진법을 내면 그 행이 갈린다.
func TestEncodeValueMatchesSharedGolden(t *testing.T) {
	for _, r := range readValueGolden(t) {
		if r.verdict != "ok" {
			continue
		}
		got, err := EncodeValue(r.want)
		if err != nil || got != r.text {
			t.Errorf("줄 %d: EncodeValue(%d) = (%q, %v), want (%q, nil)", r.line, r.want, got, err, r.text)
		}
	}
}

// Encode 는 1 이상 · 안전 정수 이하만 받는다(CHECK dvr_floor >= 1 · 엣지 수의 정확 범위). 잡는 결함: 하한 0 · 음수
// 검사나 상한 검사가 빠지면 엣지가 거절할 원문(0 · -1 · 9007199254740992)을 Writer 가 KVS 에 쓴다.
func TestEncodeValueRejectsOutOfRange(t *testing.T) {
	for _, v := range []int64{0, -1, math.MinInt64, maxValue + 1, math.MaxInt64} {
		if got, err := EncodeValue(v); err == nil {
			t.Errorf("EncodeValue(%d) = %q, want err", v, got)
		}
	}
	if got, err := EncodeValue(maxValue); err != nil || got != "9007199254740991" {
		t.Errorf("EncodeValue(maxValue) = (%q, %v), want (%q, nil)", got, err, "9007199254740991")
	}
	if maxValue != 1<<53-1 {
		t.Errorf("maxValue = %d, want 2^53-1(JS Number.MAX_SAFE_INTEGER)", int64(maxValue))
	}
}

// 줄 문법이 실을 수 없는 원문 — 빈 값 · 앞뒤 공백 · 개행 · 탭 · NUL 은 거절이다. 잡는 결함: 원문을 다듬어(TrimSpace)
// 읽으면 엣지가 거절하는 원문을 Go 가 받는다.
func TestDecodeValueRejectsTextOutsideGoldenGrammar(t *testing.T) {
	for _, s := range []string{"", " ", " 1", "1 ", "1\n", "\t1", "1\x00", "1\r"} {
		if got, err := DecodeValue(s); err == nil {
			t.Errorf("DecodeValue(%q) = %d, want err", s, got)
		}
	}
}

// valueGoldenRow 는 값 골든의 한 행이다 — ok 행의 want 는 기대 값이다.
type valueGoldenRow struct {
	line          int
	text, verdict string
	want          int64
}

// readValueGolden 은 값 골든을 읽고 판정 짝을 본다 — 판정은 ok | err 이고, ok 는 십진 기대 값과, err 는 기대 `-` 와
// 짝이다. 어기면 시험 실패다(엣지 시험이 같은 짝을 읽는다).
func readValueGolden(t *testing.T) []valueGoldenRow {
	t.Helper()
	var rows []valueGoldenRow
	for _, r := range readGolden(t, "value.golden.txt") {
		row := valueGoldenRow{line: r.line, text: r.cols[0], verdict: r.cols[2]}
		switch {
		case row.verdict == "err" && r.cols[1] == "-":
		case row.verdict == "ok":
			v, err := strconv.ParseInt(r.cols[1], 10, 64)
			if err != nil {
				t.Fatalf("Setup: 골든 줄 %d: ok 의 기대 %q 가 십진 값이 아니다", r.line, r.cols[1])
			}
			row.want = v
		default:
			t.Fatalf("Setup: 골든 줄 %d: 기대 %q · 판정 %q — ok 는 기대 값과, err 는 기대 `-` 와 짝이어야 한다", r.line, r.cols[1], row.verdict)
		}
		rows = append(rows, row)
	}
	return rows
}
