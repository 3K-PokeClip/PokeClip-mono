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

// maxComponentLen 은 키 한 성분의 길이 상한이다 — 엣지 함수(dvr-floor.js)의 회차 정규식 `{1,128}` 과 같은 값이다(POK-525
// 설계 3-1 · E22). 회차 id 는 많아야 102자(고정 19 + 스트림 64 + seq 19)라 정상 값은 닿지 않는다. 상한이 갈리면 Go 가 쓴
// 키를 엣지가 하한 판정 없이 통과시킨다.
const maxComponentLen = 128

// checkComponent 는 키 한 성분의 안전성을 본다. `..` 는 정규식을 통과하므로 따로 막는다.
func checkComponent(name, value string) error {
	if !urlSafe.MatchString(value) {
		return fmt.Errorf("playback: %s=%q 는 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 허용)", name, value)
	}
	if value == "." || value == ".." {
		return fmt.Errorf("playback: %s=%q 는 키에 쓸 수 없다(상위·현재 경로 참조)", name, value)
	}
	if len(value) > maxComponentLen {
		return fmt.Errorf("playback: %s 의 길이 %d 는 상한 %d 를 넘는다", name, len(value), maxComponentLen)
	}
	return nil
}

// SegKey 는 조각 하나의 **옛 꼴** ③ 키다 — 설계 5.2 의 형상 그대로다.
//
//	dvr/{streamID}/seg/{NNNNNN}.m4s
//
// **세션 축이 없는 것이 결정이다**(설계 5.2 D1): 계약 ⓑ 가 "s3_key 에서 결정적 파생"을
// 요구하는데 s3_key 에는 세션이 없다. seq 가 스트림 안에서 단조·재사용 금지라
// (stream_id, seq) 만으로 유일성이 물리 보증된다.
//
// 회차 성분이 든 새 꼴은 RoundSegKey 다(POK-525 설계 2-1). 이 함수는 회차가 없는 세션과 새 꼴 쓰기를 끈 상자의 키로
// 남는다 — 이름을 바꾸지 않는 것은 다른 호출자(PDT 생산자 등)를 흔들지 않기 위해서다.
//
// 자릿수는 index.S3Key 와 같은 %06d 다 — 사전식 정렬이 seq 순서와 같아진다.
// 100만을 넘으면 자릿수가 늘어 정렬은 깨지지만 유일성은 유지된다(그쪽 주석의 전례).
func SegKey(streamID string, seq int64) (string, error) {
	if err := checkComponent("stream_id", streamID); err != nil {
		return "", err
	}
	if err := checkSeq(seq); err != nil {
		return "", err
	}
	return fmt.Sprintf("dvr/%s/seg/%06d.m4s", streamID, seq), nil
}

// RoundSegKey 는 조각 하나의 회차 꼴 ③ 키다(POK-525 설계 2-1 · 3-1).
//
//	dvr/{streamID}/{roundID}/seg/{NNNNNN}.m4s
//
// roundID 는 세션의 round_id(회차 머리 세션 id)다. 회차 성분은 엣지가 하한을 찾는 열쇠이자 접근 경계라 유일성에는
// 쓰이지 않는다 — 유일성은 SegKey 와 같이 (stream_id, seq) 가 보증한다. 성분은 SegKey 와 같은 검사를 받고, 128자를
// 넘는 roundID 는 오류다(엣지 정규식과 같은 상한). 빈 roundID 도 오류다 — 회차가 없으면 호출자가 SegKey 를 고른다.
func RoundSegKey(streamID, roundID string, seq int64) (string, error) {
	if err := checkComponent("stream_id", streamID); err != nil {
		return "", err
	}
	if err := checkComponent("round_id", roundID); err != nil {
		return "", err
	}
	if err := checkSeq(seq); err != nil {
		return "", err
	}
	return fmt.Sprintf("dvr/%s/%s/seg/%06d.m4s", streamID, roundID, seq), nil
}

// checkSeq 는 키에 쓸 seq 를 본다 — 자릿수 채움이 부호를 먹어 조용히 이상한 키가 되는 것을 막는다.
func checkSeq(seq int64) error {
	if seq < 0 {
		return fmt.Errorf("playback: seq=%d 는 키에 쓸 수 없다(장부의 seq 는 0 부터 단조)", seq)
	}
	return nil
}

// SegRef 는 ③ 조각 키 하나가 가리키는 것이다. RoundID 가 빈 값이면 옛 꼴(dvr/{s}/seg/{seq})이다.
type SegRef struct {
	StreamID string
	RoundID  string
	Seq      int64
}

// segKeyRe 는 SegKey 가 만드는 옛 꼴 조각 키의 모양이고, roundSegKeyRe 는 RoundSegKey 가 만드는 회차 꼴의 모양이다 —
// seq 는 십진 6자리 이상이다. 성분의 글자 · 길이는 여기서 보지 않고 checkComponent 가 본다(만드는 쪽과 같은 규칙
// 하나). 성분 수(넷 · 다섯)가 달라 두 모양은 겹치지 않는다.
var (
	segKeyRe      = regexp.MustCompile(`^dvr/([^/]+)/seg/([0-9]{6,})\.m4s$`)
	roundSegKeyRe = regexp.MustCompile(`^dvr/([^/]+)/([^/]+)/seg/([0-9]{6,})\.m4s$`)
)

// ParseSegKey 는 SegKey · RoundSegKey 의 역이다 — 조각 키 key 를 두 꼴 모두 풀어 SegRef 로 돌려준다.
//
//	dvr/{streamID}/seg/{십진 6자리 이상}.m4s            옛 꼴 — RoundID 빈 값
//	dvr/{streamID}/{roundID}/seg/{십진 6자리 이상}.m4s  회차 꼴
//
// 성분은 만드는 쪽과 같은 검사(checkComponent — 128자 상한 포함)를 받고, seq 는 int64 에 드는 값이어야 한다. 앞자리 0
// 으로 늘린 seq(0000007)도 받는다. 모양이 다르면 오류다. ③ 키 문법은 이 한 벌이다 — 업로드 워커의 대상 검증과 목록
// 발행 전 검사 S5 가 함께 부른다(계획 B #10). 호출자는 키를 문자열 접두로 따로 풀지 않는다(POK-525 E41). 엣지 함수와는
// 공유 골든 testdata/seg_key.golden.txt 로 맞춘다. 오류 문구에는 키를 64자까지만 싣는다(장부 값이라 길이 제한이 없다).
func ParseSegKey(key string) (SegRef, error) {
	var ref SegRef
	var digits string
	if m := segKeyRe.FindStringSubmatch(key); m != nil {
		ref.StreamID, digits = m[1], m[2]
	} else if m := roundSegKeyRe.FindStringSubmatch(key); m != nil {
		ref.StreamID, ref.RoundID, digits = m[1], m[2], m[3]
	} else {
		return SegRef{}, fmt.Errorf("playback: 조각 키 %.64q 는 dvr/{stream}/seg/{6자리 이상}.m4s 도 dvr/{stream}/{round}/seg/{6자리 이상}.m4s 도 아니다", key)
	}
	if checkComponent("stream_id", ref.StreamID) != nil {
		return SegRef{}, fmt.Errorf("playback: 조각 키 %.64q 의 스트림 성분은 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 · 상위 · 현재 경로 참조 금지 · %d자 이하)", key, maxComponentLen)
	}
	if ref.RoundID != "" && checkComponent("round_id", ref.RoundID) != nil {
		return SegRef{}, fmt.Errorf("playback: 조각 키 %.64q 의 회차 성분은 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 · 상위 · 현재 경로 참조 금지 · %d자 이하)", key, maxComponentLen)
	}
	seq, err := strconv.ParseInt(digits, 10, 64)
	if err != nil {
		return SegRef{}, fmt.Errorf("playback: 조각 키 %.64q 의 seq 가 int64 를 넘는다", key)
	}
	ref.Seq = seq
	return ref, nil
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
