// Package playback 은 ③(재생 렌디션) 축의 키와 바이트를 만든다.
//
// **내부 패키지를 하나도 임포트하지 않는다.** 키는 장부(index)와 업로더(upload) 양쪽이
// 쓰는 값이라, 어느 한쪽에 두면 다른 쪽이 그쪽을 임포트해야 하고 순환이 생긴다.
// 키는 문자열 파생뿐이며 상태도 부작용도 없다.
//
// ③ 바이트는 생산자(Producer — Go 재포장, producer.go)가 만들고, 시간 도장의 재료(mtxi·트랙
// 끝 시각)는 mp4box.go 가 읽는다. 둘 다 파일을 열지 않고 호출자가 연 리더만 받는다.
// 패키지 init 이 abema/go-mp4 전역 상자 표에 mtxi 정의를 등록한다 — 이 패키지의 유일한 전역 부작용이다.
package playback

import (
	"fmt"
	"regexp"
	"strconv"
)

// urlSafe 는 키 성분이 되감기 URL 경로에 그대로 실릴 수 있는 문자만 쓰는지 본다.
//
// 경로 구분자(`/`)·상위 참조(`..`)·공백·질의 문자가 들어오면 **다른 객체를 가리키거나**
// URL 이 깨진다. 파생 실패를 오류로 알리는 이유가 이것이다 — 조용히 이상한 키를 만들면
// PUT 은 성공하고 재생만 404 가 된다.
var urlSafe = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// checkComponent 는 키 한 성분의 안전성을 본다. `..` 는 정규식을 통과하므로 따로 막는다.
func checkComponent(name, value string) error {
	if !urlSafe.MatchString(value) {
		return fmt.Errorf("playback: %s=%q 는 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 허용)", name, value)
	}
	if value == "." || value == ".." {
		return fmt.Errorf("playback: %s=%q 는 키에 쓸 수 없다(상위·현재 경로 참조)", name, value)
	}
	return nil
}

// SegKey 는 조각 하나의 ③ 키다 — 설계 5.2 의 형상 그대로다.
//
//	dvr/{streamID}/seg/{NNNNNN}.m4s
//
// **세션 축이 없는 것이 결정이다**(설계 5.2 D1): 계약 ⓑ 가 "s3_key 에서 결정적 파생"을
// 요구하는데 s3_key 에는 세션이 없다. seq 가 스트림 안에서 단조·재사용 금지라
// (stream_id, seq) 만으로 유일성이 물리 보증된다.
//
// 자릿수는 index.S3Key 와 같은 %06d 다 — 사전식 정렬이 seq 순서와 같아진다.
// 100만을 넘으면 자릿수가 늘어 정렬은 깨지지만 유일성은 유지된다(그쪽 주석의 전례).
func SegKey(streamID string, seq int64) (string, error) {
	if err := checkComponent("stream_id", streamID); err != nil {
		return "", err
	}
	if seq < 0 {
		return "", fmt.Errorf("playback: seq=%d 는 키에 쓸 수 없다(장부의 seq 는 0 부터 단조)", seq)
	}
	return fmt.Sprintf("dvr/%s/seg/%06d.m4s", streamID, seq), nil
}

// segKeyRe 는 SegKey 가 만드는 조각 키의 모양이다 — seq 는 십진 6자리 이상이다. 스트림 성분의 글자는 여기서 보지
// 않고 checkComponent 가 본다(SegKey 와 같은 규칙 하나).
var segKeyRe = regexp.MustCompile(`^dvr/([^/]+)/seg/([0-9]{6,})\.m4s$`)

// ParseSegKey 는 SegKey 의 역이다 — 조각 키 key 를 스트림과 seq 로 푼다.
//
//	dvr/{streamID}/seg/{십진 6자리 이상}.m4s
//
// 스트림 성분은 SegKey 와 같은 검사(checkComponent)를 받고, seq 는 int64 에 드는 값이어야 한다. 앞자리 0 으로 늘린
// seq(0000007)도 받는다. 모양이 다르면 오류다. ③ 키 문법은 이 한 벌이다 — 업로드 워커의 대상 검증과 목록 발행 전
// 검사 S5 가 함께 부른다(계획 B #10). 오류 문구에는 키를 64자까지만 싣는다(장부 값이라 길이 제한이 없다).
func ParseSegKey(key string) (streamID string, seq int64, err error) {
	m := segKeyRe.FindStringSubmatch(key)
	if m == nil {
		return "", 0, fmt.Errorf("playback: 조각 키 %.64q 는 dvr/{stream}/seg/{6자리 이상}.m4s 가 아니다", key)
	}
	if checkComponent("stream_id", m[1]) != nil {
		return "", 0, fmt.Errorf("playback: 조각 키 %.64q 의 스트림 성분은 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 · 상위 · 현재 경로 참조 금지)", key)
	}
	seq, err = strconv.ParseInt(m[2], 10, 64)
	if err != nil {
		return "", 0, fmt.Errorf("playback: 조각 키 %.64q 의 seq 가 int64 를 넘는다", key)
	}
	return m[1], seq, nil
}

// InitKey 는 세션 하나의 init(MAP) 키다 — 설계 5.2·5.3ⓐ.
//
//	dvr/{streamID}/init/{sessionID}.mp4
//
// 조각 키와 달리 **세션 축이다**: init 바이트는 세션마다 하나이고,
// ADR-044 가 금지한 것은 f(stream_id) 파생이지 세션 축이 아니다.
// sessionID 는 세션 결정자가 만든 값이지만 여기서 다시 검사한다 — 그 규칙이 바뀌어도
// URL 에 실을 수 없는 값이 키가 되지 않게 하는 마지막 그물이다.
func InitKey(streamID, sessionID string) (string, error) {
	if err := checkComponent("stream_id", streamID); err != nil {
		return "", err
	}
	if err := checkComponent("session_id", sessionID); err != nil {
		return "", err
	}
	return fmt.Sprintf("dvr/%s/init/%s.mp4", streamID, sessionID), nil
}
