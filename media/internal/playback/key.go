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
	"strings"
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

// initKeyRe 는 InitKey 가 만드는 init 키의 모양이다. 성분의 글자는 segKeyRe 와 같이 여기서 보지 않고 checkComponent 가 본다.
var initKeyRe = regexp.MustCompile(`^dvr/([^/]+)/init/([^/]+)\.mp4$`)

// parseInitKey 는 InitKey 의 역이다 — init 키 key 를 스트림과 세션으로 푼다. 두 성분 모두 InitKey 와 같은 검사를 받는다.
func parseInitKey(key string) (streamID, sessionID string, err error) {
	m := initKeyRe.FindStringSubmatch(key)
	if m == nil {
		return "", "", fmt.Errorf("playback: init 키 %.64q 는 dvr/{stream}/init/{session}.mp4 가 아니다", key)
	}
	if checkComponent("stream_id", m[1]) != nil || checkComponent("session_id", m[2]) != nil {
		return "", "", fmt.Errorf("playback: init 키 %.64q 의 성분은 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 · 상위 · 현재 경로 참조 금지)", key)
	}
	return m[1], m[2], nil
}

// ③ 키와 VOD 사본 키의 앞머리다. 사본 키는 앞머리만 다르고 나머지는 ③ 키 그대로다.
const (
	dvrPrefix = "dvr/"
	vodPrefix = "vod/"
)

// VODPath 는 ③ 조각 · init 키를 VOD 사본 키로 옮긴다 — 앞머리 dvr/ 를 vod/ 로 바꿀 뿐
// 나머지는 그대로다. 사본 키는 저장 키이자 URL 경로다(/vod 비헤이비어는 경로 = 키).
// 조각 키는 ParseSegKey 가 받는 것, init 키는 InitKey 문법이다. 둘 다 아니면 오류다.
//
//	dvr/{streamID}/seg/{NNNNNN}.m4s     → vod/{streamID}/seg/{NNNNNN}.m4s
//	dvr/{streamID}/init/{sessionID}.mp4 → vod/{streamID}/init/{sessionID}.mp4
//
// 받는 꼴은 공유 골든 testdata/vod_path.golden.txt 가 못 박는다 — clip 의 탈퇴 정리(Java)가 같은 파일로 같은 사본 키를
// 파생하는지 본다(POK-525 E21). seq 자릿수를 정규화하지 않는다(앞자리 0 으로 늘린 seq 도 그대로 옮긴다).
func VODPath(key string) (string, error) {
	rest, ok := strings.CutPrefix(key, dvrPrefix)
	if !ok {
		return "", fmt.Errorf("playback: %.64q 는 dvr/ 로 시작하지 않아 사본 키로 옮길 수 없다", key)
	}
	if _, err := parseObject(key); err != nil {
		return "", err
	}
	return vodPrefix + rest, nil
}

// VODObject 는 VOD 사본 키 하나가 가리키는 것이다(그 원본 ③ 키와 같다) — 조각 사본이면 (StreamID, Seq), init 사본이면
// (StreamID, SessionID).
type VODObject struct {
	StreamID  string
	Seq       int64  // 조각 사본의 seq. init 사본이면 0 이다.
	SessionID string // init 사본의 세션. 조각 사본이면 빈 값이다.
}

// IsInit 은 init 사본인지다. 세션 성분은 비어 있을 수 없으므로(checkComponent) 빈 세션 = 조각 사본이다.
func (o VODObject) IsInit() bool { return o.SessionID != "" }

// ParseVODPath 는 VODPath 의 역이다 — 사본 키 path 를 스트림과 seq(조각) 또는 세션(init)으로 푼다.
//
//	vod/{streamID}/seg/{십진 6자리 이상}.m4s → VODObject{StreamID, Seq}
//	vod/{streamID}/init/{sessionID}.mp4    → VODObject{StreamID, SessionID}
//
// 앞머리만 vod/ 이고 나머지는 ParseSegKey · InitKey 와 같은 문법 한 벌이다. VOD 목록 문서(vod/{s}/{head}/index.m3u8)와
// 퍼센트 인코딩된 채인 키는 사본 키가 아니라 오류다 — 사본 키를 지우는 쪽(⑤ 대사)이 쓰므로 엄격하게 거절한다.
func ParseVODPath(path string) (VODObject, error) {
	rest, ok := strings.CutPrefix(path, vodPrefix)
	if !ok {
		return VODObject{}, fmt.Errorf("playback: 사본 키 %.64q 는 vod/ 로 시작하지 않는다", path)
	}
	obj, err := parseObject(dvrPrefix + rest)
	if err != nil {
		return VODObject{}, fmt.Errorf("playback: 사본 키 %.64q 는 vod/{stream}/seg/{6자리 이상}.m4s 도 vod/{stream}/init/{session}.mp4 도 아니다", path)
	}
	return obj, nil
}

// parseObject 는 ③ 조각 · init 키 key 가 가리키는 것을 푼다 — VODPath 와 ParseVODPath 가 함께 쓰는 문법 한 벌이다.
func parseObject(key string) (VODObject, error) {
	if streamID, seq, err := ParseSegKey(key); err == nil {
		return VODObject{StreamID: streamID, Seq: seq}, nil
	}
	if streamID, sessionID, err := parseInitKey(key); err == nil {
		return VODObject{StreamID: streamID, SessionID: sessionID}, nil
	}
	return VODObject{}, fmt.Errorf("playback: %.64q 는 ③ 조각 키(dvr/{stream}/seg/{6자리 이상}.m4s)도 init 키(dvr/{stream}/init/{session}.mp4)도 아니다", key)
}
