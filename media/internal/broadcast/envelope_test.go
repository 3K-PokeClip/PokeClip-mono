package broadcast

// 계약9 봉투 · eventId 의 순수 시험(POK-196 M6-8 커밋 ① · 설계 M6 15.3 · D48).

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

// 결정적 eventId — 재기동 뒤 재시도도 같은 id 여야 FIFO 중복 제거 · 소비자 멱등이 선다(계약9 2절 · D35).
func TestEventIDIsDeterministicPerRound(t *testing.T) {
	if got, want := StartedEventID("S-1"), "S-1.started"; got != want {
		t.Errorf("StartedEventID(%q) = %q, want %q", "S-1", got, want)
	}
	if got, want := EndedEventID("S-1"), "S-1.ended"; got != want {
		t.Errorf("EndedEventID(%q) = %q, want %q", "S-1", got, want)
	}
}

// 봉투 JSON 은 계약9 3절의 칸 이름 · 순서다(clip LifecycleEnvelope 와 같은 열). traceId · platform 은 싣지 않는다
// (platform 은 Media 가 모른다 — POK-397 의존 한계). occurredAt 은 UTC ISO 다 — 지역 시간대로 들어와도 Z 로 낸다.
// 잡는 결함: 칸 이름이 어긋나면 clip 이 그 칸을 null 로 읽어 missing 으로 버린다(조용한 0행).
func TestEncodeWritesContractFieldsInOrder(t *testing.T) {
	seoul := time.FixedZone("KST", 9*60*60)
	e := Envelope{
		SchemaVersion:  SchemaVersion,
		EventID:        EndedEventID("S-1"),
		EventType:      EventEnded,
		OccurredAt:     time.Date(2026, 10, 26, 19, 0, 4, 500_000_000, seoul),
		StreamID:       "S-1",
		StreamerID:     "42",
		IngestStreamID: "abcdefghijklmnopqrstuvwxyz",
		Sequence:       2,
		Payload:        json.RawMessage(`{"segmentRange":{"firstSeq":3,"lastSeq":9}}`),
	}

	got, err := e.Encode()

	if err != nil {
		t.Fatalf("Encode() 오류 = %v, want nil", err)
	}
	want := `{"schemaVersion":1,"eventId":"S-1.ended","eventType":"broadcast.ended",` +
		`"occurredAt":"2026-10-26T10:00:04.5Z","streamId":"S-1","streamerId":"42",` +
		`"ingestStreamId":"abcdefghijklmnopqrstuvwxyz","sequence":2,` +
		`"payload":{"segmentRange":{"firstSeq":3,"lastSeq":9}}}`
	if string(got) != want {
		t.Errorf("Encode() =\n%s\nwant\n%s", got, want)
	}
}

// 빈 payload 는 {} 로 낸다(started — 15.3). null 을 내면 소비자 payload.get 이 터진다.
func TestEncodeWritesEmptyObjectForNilPayload(t *testing.T) {
	e := validEnvelope()
	e.Payload = nil

	got, err := e.Encode()

	if err != nil {
		t.Fatalf("Encode() 오류 = %v, want nil", err)
	}
	if !strings.Contains(string(got), `"payload":{}`) {
		t.Errorf("Encode() = %s, want payload {}", got)
	}
}

// D48 — 128자 초과 · 금지 문자는 생성 때 거른다. 소비자는 128자를 넘는 식별자를 too_long 으로 버리고(SqsIntakeRunner
// checkIdentifier), SNS 는 MessageGroupId · MessageDeduplicationId 에 128자 · 출력 가능 ASCII(공백 없음)만 받는다.
// 잡는 결함: 길이 검사를 빼면 129자 eventId 가 발행돼 소비자가 조용히 버린다. 문자 검사를 빼면 SNS 가 영영 거부해
// 같은 회차 뒤 이벤트가 막힌다.
func TestCheckRoundRejectsOverlongAndInvalidIDs(t *testing.T) {
	// ".started" 8자를 붙여 128자가 되는 회차 열쇠가 상한이다.
	longest := strings.Repeat("a", maxIDLength-len(".started"))
	tests := []struct {
		name  string
		round string
		want  error
	}{
		{"보통_열쇠", "0f8e2c1a-1111-4222-8333-944455556666", nil},
		{"eventId_정확히_128자", longest, nil},
		{"eventId_129자", longest + "a", errIDTooLong},
		{"streamId_129자", strings.Repeat("a", maxIDLength+1), errIDTooLong},
		{"빈_열쇠", "", errIDInvalid},
		{"공백", "S 1", errIDInvalid},
		{"제어_문자", "S\n1", errIDInvalid},
		{"비_ASCII", "회차", errIDInvalid},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := CheckRound(tt.round); !errors.Is(got, tt.want) {
				t.Errorf("CheckRound(%q) = %v, want %v", tt.round, got, tt.want)
			}
		})
	}
}

// Encode 는 내보내기 직전의 마지막 검사다 — 식별자 칸 넷의 길이 · 문자, streamerId 빈 값(K10 — 추측 금지)을 거른다.
func TestEncodeRejectsInvalidFields(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Envelope)
		want   error
	}{
		{"eventId_129자", func(e *Envelope) { e.EventID = strings.Repeat("a", maxIDLength+1) }, errIDTooLong},
		{"streamId_금지_문자", func(e *Envelope) { e.StreamID = "S 1" }, errIDInvalid},
		{"streamerId_없음", func(e *Envelope) { e.StreamerID = "" }, errStreamerMissing},
		{"streamerId_129자", func(e *Envelope) { e.StreamerID = strings.Repeat("1", maxIDLength+1) }, errIDTooLong},
		{"ingestStreamId_129자", func(e *Envelope) { e.IngestStreamID = strings.Repeat("a", maxIDLength+1) }, errIDTooLong},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e := validEnvelope()
			tt.mutate(&e)

			if _, err := e.Encode(); !errors.Is(err, tt.want) {
				t.Errorf("Encode() 오류 = %v, want %v", err, tt.want)
			}
		})
	}
}

// abandon 사유 이름은 신호 lifecycle_abandoned reason= 의 값이다(15.8).
func TestAbandonReasonNamesError(t *testing.T) {
	tests := []struct {
		err  error
		want string
	}{
		{errIDTooLong, "too_long"},
		{errIDInvalid, "invalid_id"},
		{errStreamerMissing, "streamer_missing"},
	}
	for _, tt := range tests {
		if got := abandonReason(tt.err); got != tt.want {
			t.Errorf("abandonReason(%v) = %q, want %q", tt.err, got, tt.want)
		}
	}
}

func validEnvelope() Envelope {
	return Envelope{
		SchemaVersion:  SchemaVersion,
		EventID:        StartedEventID("S-1"),
		EventType:      EventStarted,
		OccurredAt:     time.Date(2026, 10, 26, 10, 0, 0, 0, time.UTC),
		StreamID:       "S-1",
		StreamerID:     "42",
		IngestStreamID: "abcdefghijklmnopqrstuvwxyz",
		Sequence:       1,
	}
}
