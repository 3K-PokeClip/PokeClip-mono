package rewind

import (
	"bytes"
	"errors"
	"slices"
)

// endlistLine 은 목록을 닫는 태그 줄이다(RFC 8216bis-22 4.4.3.4) — 이 줄이 붙은 목록에는 더 이상
// 조각이 붙지 않고, 갱신 의무(1.5 × TARGETDURATION 안에 새 조각)도 사라진다.
const endlistLine = "#EXT-X-ENDLIST"

// ErrAlreadySealed 는 SealEndlist 에 이미 닫힌 본문이 왔다는 뜻이다. 호출자(설계 4.6.4 E2)는 이를
// 「이미 닫힘 — 멱등 성공(메타 terminal 만 맞춘다)」으로 읽는다.
var ErrAlreadySealed = errors.New("rewind: 이미 닫힌 목록이다")

// SealEndlist 는 발행된 목록 본문 끝에 EXT-X-ENDLIST 한 줄을 붙여 목록을 닫는다 — 설계 4.6.4 E3
// 정체 마감의 봉인이며, Render·Validate 를 타지 않는 유일한 특권 경로다(설계 3.2).
//
// 본문을 다시 렌더하지 않는 이유: 발행된 줄은 바꿀 수 없고, 허용된 변경 가운데 하나가 ENDLIST
// 추가다(RFC 8216bis-22 6.2.1). 그래서 입력 바이트를 한 글자도 바꾸지 않고 끝에 한 줄만 더한다.
// 결과는 새 바이트다 — 입력(캐시가 쥔 마지막 발행 본문일 수 있다)은 건드리지 않는다.
//
// 이미 ENDLIST 가 있는 본문이면 ErrAlreadySealed 다. 한 번 더 붙이면 「Media Playlist 태그는
// 종류마다 하나」(4.4.3)를 어긴다. 목록 본문이 아니면(첫 줄이 #EXTM3U 가 아니거나 줄바꿈으로 끝나지
// 않으면) 오류다 — 줄바꿈이 없으면 ENDLIST 가 마지막 URI 줄에 붙어 그 URI 를 깨뜨린다.
func SealEndlist(body []byte) ([]byte, error) {
	if !bytes.HasPrefix(body, []byte(headerLine)) {
		return nil, errors.New("rewind: 목록 본문이 아니다 — 첫 줄이 #EXTM3U 가 아니다")
	}
	if !bytes.HasSuffix(body, []byte("\n")) {
		return nil, errors.New("rewind: 목록 본문이 줄바꿈으로 끝나지 않는다 — ENDLIST 가 마지막 줄에 붙는다")
	}
	if hasEndlist(body) {
		return nil, ErrAlreadySealed
	}
	return slices.Concat(body, []byte(endlistLine+"\n")), nil
}

// hasEndlist 는 본문에 EXT-X-ENDLIST 줄이 있는가다 — 목록이 닫혔는가(메타 pc-terminal)의 판정이다.
// 이 태그는 본문 어디에나 올 수 있어서(4.4.3.4 「It MAY occur anywhere」) 끝 줄만 보지 않는다.
func hasEndlist(body []byte) bool {
	for line := range bytes.Lines(body) {
		if string(bytes.TrimSuffix(line, []byte("\n"))) == endlistLine {
			return true
		}
	}
	return false
}
