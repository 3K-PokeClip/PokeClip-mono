// Package broadcast 는 계약9(방송 생명주기 이벤트)의 생산자다 — 봉투 조립 · outbox 문장 · 발행 고루틴(설계 M6 15절).
//
// 이 패키지는 session 을 임포트하지 않고 session 도 이 패키지를 임포트하지 않는다(15.6 — 순환 · 결합 방지). 장부
// 트랜잭션 쪽은 함수 칸으로 받고 cmd 가 묶어 끼운다.
package broadcast

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// SchemaVersion 은 계약9 6절의 봉투 판이다.
const SchemaVersion = 1

// 계약9 3절의 eventType 값 둘이다.
const (
	EventStarted = "broadcast.started"
	EventEnded   = "broadcast.ended"
)

// maxIDLength 는 식별자 칸의 상한이다 — 계약9 6절 128자 · clip VARCHAR(128) · SNS MessageGroupId ·
// MessageDeduplicationId 상한이 모두 128 이다.
const maxIDLength = 128

// startedSuffix · endedSuffix 는 회차 열쇠 뒤에 붙는 eventId 꼬리다(15.3 — {round_id}.started · .ended).
const (
	startedSuffix = ".started"
	endedSuffix   = ".ended"
)

// StartedEventID 는 회차의 started eventId 다. 결정적이라 재기동 뒤 재시도도 같은 id 다(계약9 2절 「새 이벤트를 만들지
// 않는다」).
func StartedEventID(round string) string { return round + startedSuffix }

// EndedEventID 는 회차의 ended eventId 다.
func EndedEventID(round string) string { return round + endedSuffix }

// Envelope 는 계약9 3절 봉투다. 칸 순서 · JSON 이름은 clip LifecycleEnvelope 와 같다. traceId(선택)는 싣지 않고,
// platform(선택)도 싣지 않는다 — Media 는 플랫폼을 모른다(15.3 · 15.13 — POK-397 의존 한계).
type Envelope struct {
	SchemaVersion  int             `json:"schemaVersion"`
	EventID        string          `json:"eventId"`
	EventType      string          `json:"eventType"`
	OccurredAt     time.Time       `json:"occurredAt"`
	StreamID       string          `json:"streamId"`
	StreamerID     string          `json:"streamerId"`
	IngestStreamID string          `json:"ingestStreamId"`
	Sequence       int64           `json:"sequence"`
	Payload        json.RawMessage `json:"payload"`
}

var (
	errIDTooLong       = errors.New("broadcast: 식별자가 128자를 넘는다")
	errIDInvalid       = errors.New("broadcast: 식별자가 비었거나 출력 가능 ASCII 밖의 글자가 있다")
	errStreamerMissing = errors.New("broadcast: streamerId 가 없다")
)

// Encode 는 내보내기 직전 검사를 한 뒤 봉투 JSON 을 만든다. eventId · streamId 는 SNS 의 MessageDeduplicationId ·
// MessageGroupId 로도 쓰여 길이와 글자를 함께 보고, streamerId · ingestStreamId 는 소비자 칸 상한(128자)만 본다.
// streamerId 가 비면 거른다 — 추측한 값을 싣지 않는다(K10). occurredAt 은 UTC 로 낸다. payload 가 비면 {} 다.
func (e Envelope) Encode() ([]byte, error) {
	if err := checkID(e.EventID); err != nil {
		return nil, fmt.Errorf("eventId: %w", err)
	}
	if err := checkID(e.StreamID); err != nil {
		return nil, fmt.Errorf("streamId: %w", err)
	}
	if e.StreamerID == "" {
		return nil, errStreamerMissing
	}
	if len(e.StreamerID) > maxIDLength {
		return nil, fmt.Errorf("streamerId: %w", errIDTooLong)
	}
	if len(e.IngestStreamID) > maxIDLength {
		return nil, fmt.Errorf("ingestStreamId: %w", errIDTooLong)
	}
	out := e
	out.OccurredAt = e.OccurredAt.UTC()
	if len(out.Payload) == 0 {
		out.Payload = json.RawMessage(`{}`)
	}
	return json.Marshal(out)
}

// CheckRound 는 회차 열쇠로 만들 식별자(streamId · 두 eventId)가 상한 · 글자 규칙 안인지 본다. started 꼬리가 ended
// 보다 길어 started eventId 하나로 둘을 함께 잰다.
func CheckRound(round string) error {
	if err := checkID(round); err != nil {
		return err
	}
	return checkID(StartedEventID(round))
}

// checkID 는 SNS 식별자 규칙이다 — 1–128자 · 출력 가능 ASCII(0x21–0x7E, 공백 없음).
func checkID(s string) error {
	if s == "" {
		return errIDInvalid
	}
	for i := 0; i < len(s); i++ {
		if s[i] < 0x21 || s[i] > 0x7e {
			return errIDInvalid
		}
	}
	if len(s) > maxIDLength {
		return errIDTooLong
	}
	return nil
}

// abandonReason 은 버리는 까닭을 신호 lifecycle_abandoned 의 reason 값으로 바꾼다(15.8).
func abandonReason(err error) string {
	switch {
	case errors.Is(err, errIDTooLong):
		return "too_long"
	case errors.Is(err, errIDInvalid):
		return "invalid_id"
	case errors.Is(err, errStreamerMissing):
		return "streamer_missing"
	}
	return "invalid"
}
