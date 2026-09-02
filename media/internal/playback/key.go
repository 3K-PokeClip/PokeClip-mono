// Package playback 은 ③(재생 렌디션) 축의 키를 만든다.
//
// **내부 패키지를 하나도 임포트하지 않는다.** 키는 장부(index)와 업로더(upload) 양쪽이
// 쓰는 값이라, 어느 한쪽에 두면 다른 쪽이 그쪽을 임포트해야 하고 순환이 생긴다.
// 여기 있는 것은 문자열 파생뿐이며 상태도 부작용도 없다.
//
// ③ 바이트를 실제로 만드는 생산자(Producer)는 M4 다 — 이 패키지는 그때 함께 자란다.
package playback

import (
	"fmt"
	"regexp"
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
