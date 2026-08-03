// Package mtxhook 는 MediaMTX 훅의 수신 어댑터다.
//
// recording 패키지가 파일 시스템 쪽을 담당하듯, 이 패키지는 훅 쪽을 담당한다.
// 둘 다 "완성된 세그먼트"를 밖으로 내보내기만 하고 판정은 하지 않는다 —
// seq·PTS·불연속 판정은 오직 indexer 하나가 소유한다.
package mtxhook

import (
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"time"
)

// Kind 는 훅 종류다.
//
// 구명칭 runOnReady 는 쓰지 않는다 — v1.19.3 에서 그것은 runOnAvailable 로 매핑되며
// "읽기 가능" 축이지 세션(Online) 축이 아니다.
type Kind uint8

const (
	// KindUnknown 은 Go 의 zero value 다. 즉 0 = "아무도 종류를 안 채웠다"는 사고 신호이며,
	// 이 값이 정상 경로에 존재하면 버그다. ParseLine 은 이 값을 절대 성공 반환하지 않는다.
	KindUnknown Kind = 0
	// KindOnline 은 runOnOnline — 송출 세션이 시작됐다.
	KindOnline Kind = 1
	// KindOffline 은 runOnOffline — 송출 세션이 끊겼다.
	KindOffline Kind = 2
	// KindSegmentComplete 는 runOnRecordSegmentComplete — 녹화 조각 하나가 닫혔다.
	KindSegmentComplete Kind = 3
)

// Event 는 스풀 1줄을 정규화한 값이다. 판정은 들어 있지 않다.
type Event struct {
	Kind Kind
	// StreamID 는 MTX_PATH 원문이다.
	//
	// 정본 stream_id 는 ToSegment 가 파일 경로에서 다시 뽑는다. 이 필드는 세션 훅의
	// 경계 큐 키로만 쓰이며 **어떤 형태로도 변형하지 않는다** — indexer/hook.go 의
	// breaks 키 계약 참조.
	StreamID string
	// SourceID 는 MTX_SOURCE_ID 다. 세션 식별에 쓰지만 판정에는 쓰지 않고 관측만 한다.
	SourceID string
	// At 은 훅 발화 시각(UTC)이다. 스풀에는 epoch 나노초 정수로 실린다 —
	// 문자열 시각을 쓰면 컨테이너 TZ 설정 하나로 값이 어긋난다.
	At time.Time

	// 아래 둘은 KindSegmentComplete 에만 채워진다.

	// SegmentPath 는 MTX_SEGMENT_PATH 원문이다. 그대로 DB 에 담지 않는다(ToSegment 참조).
	SegmentPath string
	// SegmentDurationMS 는 MTX_SEGMENT_DURATION 참고값이다.
	// 정본 길이는 fmp4meta 실측이므로 이 값이 없거나 해석 불가여도 이벤트를 버리지 않는다.
	SegmentDurationMS int64
}

// spoolLine 은 스풀 1줄의 JSON 형상이다. writer(cmd/mtxhookwrite)와의 계약이며
// MTX_* 키는 MediaMTX 가 훅 프로세스에 넘기는 환경변수 이름을 그대로 쓴다 —
// 이름을 바꾸면 스풀만 보고 무엇이 원본이었는지 알 수 없게 된다.
type spoolLine struct {
	Kind string `json:"kind"`
	// AtUnixNano 는 epoch 나노초 정수다. 문자열 시각은 쓰지 않는다(컨테이너 TZ 의존 제거).
	AtUnixNano int64 `json:"at_unix_nano"`
	// Path 는 MTX_PATH 원문이며 정규화 없이 Event.StreamID 가 된다(breaks 키 계약).
	Path     string `json:"MTX_PATH"`
	SourceID string `json:"MTX_SOURCE_ID"`

	SegmentPath string `json:"MTX_SEGMENT_PATH"`
	// SegmentDuration 은 형식 미확인이라 문자열로 받는다("4.008" 초 또는 "4.008s" 둘 다 흡수).
	SegmentDuration string `json:"MTX_SEGMENT_DURATION"`
}

// kindNames 는 스풀 문자열과 Kind 의 유일한 대응표다.
// 여기 없는 값은 "새 훅이 켜졌는데 어댑터가 모른다"는 뜻이므로 조용히 흘리지 않고 에러다.
var kindNames = map[string]Kind{
	"online":      KindOnline,
	"offline":     KindOffline,
	"segcomplete": KindSegmentComplete,
}

// ParseLine 은 스풀 1줄(개행 제외)을 Event 로 만든다. 순수 함수다.
//
// 스풀은 다른 컨테이너의 프로세스가 쓴 외부 입력이므로 여기가 검증 경계다.
// 필수 필드가 하나라도 비면 그 줄만 버린다(호출자가 hook_line_invalid WARN).
func ParseLine(line []byte) (Event, error) {
	var raw spoolLine
	if err := json.Unmarshal(line, &raw); err != nil {
		return Event{}, fmt.Errorf("훅 스풀 줄이 JSON 이 아니다: %w", err)
	}

	kind, ok := kindNames[raw.Kind]
	if !ok {
		return Event{}, fmt.Errorf("알 수 없는 훅 종류다: %q", raw.Kind)
	}
	if raw.AtUnixNano <= 0 {
		return Event{}, fmt.Errorf("at_unix_nano 가 없거나 양수가 아니다: %d", raw.AtUnixNano)
	}
	if raw.Path == "" {
		return Event{}, fmt.Errorf("MTX_PATH 가 비었다")
	}
	if kind == KindSegmentComplete && raw.SegmentPath == "" {
		return Event{}, fmt.Errorf("segcomplete 인데 MTX_SEGMENT_PATH 가 비었다 path=%q", raw.Path)
	}

	return Event{
		Kind:              kind,
		StreamID:          raw.Path,
		SourceID:          raw.SourceID,
		At:                time.Unix(0, raw.AtUnixNano).UTC(),
		SegmentPath:       raw.SegmentPath,
		SegmentDurationMS: durationMS(raw.SegmentDuration),
	}, nil
}

// durationMS 는 MTX_SEGMENT_DURATION 을 밀리초로 바꾼다. 해석 불가면 0(미상)이다.
//
// 값 형식이 미확인(문서 미기재)이라 두 표기를 모두 받는다. 실패를 에러로 올리지 않는 이유는
// 이 값이 참고값이기 때문이다 — 정본 길이는 fmp4meta 실측이고, 이 필드 때문에 이벤트를
// 통째로 버리면 그 조각이 훅 채널에서 사라진다(멀쩡한 세그먼트를 잃는 쪽이 더 나쁘다).
func durationMS(raw string) int64 {
	if raw == "" {
		return 0
	}
	if secs, err := strconv.ParseFloat(raw, 64); err == nil {
		ms := secs * 1000
		if math.Abs(ms) > math.MaxInt64 {
			return 0
		}
		return int64(math.Round(ms))
	}
	if d, err := time.ParseDuration(raw); err == nil {
		return d.Milliseconds()
	}
	return 0
}
