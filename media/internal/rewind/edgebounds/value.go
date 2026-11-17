package edgebounds

import (
	"fmt"
	"regexp"
	"strconv"
)

// maxValue 는 KVS 값의 상한 2^53-1(JS Number.MAX_SAFE_INTEGER)이다 — 엣지 함수는 값을 배정밀도 수로 읽어 seq 와
// 견주므로, 이보다 큰 값은 정확히 비교되지 않는다. seq 는 4초 조각마다 1 씩이라 정상 값은 닿지 않는다.
const maxValue = 1<<53 - 1

// valueText 는 KVS 값 원문의 모양이다 — 앞자리 0 없는 십진 숫자뿐이라 한 값에 원문이 하나다(부호 · 공백 · 소수점 ·
// 지수 · 진법 접두 · 밑줄 · 비 ASCII 숫자 없음). 1 이상인 것도 이 모양이 보증한다.
var valueText = regexp.MustCompile(`^[1-9][0-9]*$`)

// checkValue 는 하한 값 v 가 1 이상 · maxValue 이하인지 본다 — Encode 와 Decode 가 같은 범위 하나를 쓴다.
func checkValue(v int64) error {
	if v < 1 || v > maxValue {
		return fmt.Errorf("edgebounds: 하한 %d 는 범위 밖이다(1 이상 %d 이하)", v, int64(maxValue))
	}
	return nil
}

// EncodeValue 는 하한 v 의 KVS 값 원문(앞자리 0 없는 십진)이다. v 가 1 보다 작거나 안전 정수 상한을 넘으면 오류다 —
// 원장 CHECK(dvr_floor >= 1)와 엣지 함수가 받는 범위가 그것이다. 공유 골든 testdata/value.golden.txt 의 ok 행 원문과
// 같은 글자를 낸다.
func EncodeValue(v int64) (string, error) {
	if err := checkValue(v); err != nil {
		return "", err
	}
	return strconv.FormatInt(v, 10), nil
}

// DecodeValue 는 EncodeValue 의 역이다 — KVS 값 원문 s 를 하한으로 푼다. 모양(앞자리 0 없는 십진)이 다르거나 범위
// 밖이면 오류다. 원문을 다듬지 않는다(앞뒤 공백도 거절) — 엣지 함수와 같은 원문을 같은 값으로 읽기 위해서이고, 그
// 판정은 공유 골든으로 맞춘다. 오류 문구에는 원문을 32자까지만 싣는다(KVS 값이라 길이 제한이 넉넉하다).
func DecodeValue(s string) (int64, error) {
	if !valueText.MatchString(s) {
		return 0, fmt.Errorf("edgebounds: 값 원문 %.32q 는 앞자리 0 없는 십진 정수가 아니다", s)
	}
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("edgebounds: 값 원문 %.32q 가 int64 를 넘는다", s)
	}
	if err := checkValue(v); err != nil {
		return 0, err
	}
	return v, nil
}
