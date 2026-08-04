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
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
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
	if raw.Path == "" {
		return Event{}, fmt.Errorf("MTX_PATH 가 비었다")
	}
	if err := checkAt(kind, raw.AtUnixNano); err != nil {
		return Event{}, err
	}
	// 세션 훅의 MTX_PATH 는 breaks·pendingOffline·lastOnlineAt 맵의 **키**가 된다.
	// 즉 외부 문자열이 그대로 맵 키가 되는 경로이므로 여기가 검증 지점이다.
	// 소비 측 stream_id 와 같은 규칙을 통과하지 못하면 무장해 봐야 영원히 소비되지 않는다.
	//
	// segcomplete 는 제외한다 — 그쪽 stream_id 는 MTX_PATH 가 아니라 파일 경로에서 뽑으며
	// ToSegment 안의 ParseSegmentPath 가 같은 화이트리스트로 이미 거른다.
	if kind != KindSegmentComplete && !recording.ValidStreamID(raw.Path) {
		return Event{}, fmt.Errorf("세션 훅의 MTX_PATH 가 허용된 stream_id 형태가 아니다: %q", raw.Path)
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

// ToSegment 는 KindSegmentComplete 를 완성 세그먼트로 바꾼다.
//
// 경로 정본화가 이 함수의 존재 이유다. MTX_SEGMENT_PATH 원문을 그대로 담지 않고
// ① Clean → ② Rel(root) 로 루트 내부인지 검증(".." 이면 거부) → ③ Join(root, rel) 로
// **워처와 동일한 방식으로 재조립**한 문자열을 Segment.Path 로 쓴다.
//
// 왜 이렇게까지 하는가: GH7(훅·파일 이중 인입 멱등)의 최후 방어선인
// UNIQUE (stream_id, local_path) 는 문자열 비교다. 이중 슬래시나 "./" 같은 표기 차이
// 하나만으로 같은 물리 파일에 행이 둘 생기고, 그러면 재생 타임라인에 4초가 중복되는데
// seq 재사용·재정렬 금지 때문에 **사후 정정이 불가능**하다.
// 워처 쪽 문자열은 filepath.WalkDir·fsnotify 가 주는 Join 형태이므로 그 형태에 맞춘다.
//
// 실패는 삼키지 않는다. 호출자가 hook_segment_path_rejected WARN 을 남기고 그 이벤트만 버린다.
func ToSegment(root string, ev Event) (recording.Segment, error) {
	if ev.Kind != KindSegmentComplete {
		return recording.Segment{}, fmt.Errorf("세그먼트 훅이 아니다: kind=%d", ev.Kind)
	}
	if ev.SegmentPath == "" {
		return recording.Segment{}, fmt.Errorf("MTX_SEGMENT_PATH 가 비었다 path=%q", ev.StreamID)
	}

	cleanRoot := filepath.Clean(root)
	rel, err := filepath.Rel(cleanRoot, filepath.Clean(ev.SegmentPath))
	if err != nil {
		return recording.Segment{}, fmt.Errorf(
			"루트 기준 상대 경로 계산 실패 root=%q path=%q: %w", root, ev.SegmentPath, err)
	}
	if rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return recording.Segment{}, fmt.Errorf("루트 %q 밖의 경로다: %q", cleanRoot, ev.SegmentPath)
	}

	// stream_id·start_wall 은 파일명이 정본이다 — MTX_PATH 를 믿지 않는다.
	// 그래야 워처 경로와 완전히 같은 규칙(화이트리스트·파일명 형식)을 통과한다.
	seg, err := recording.ParseSegmentPath(cleanRoot, filepath.Join(cleanRoot, rel))
	if err != nil {
		return recording.Segment{}, err
	}
	seg.Reason = recording.ReasonHook
	return seg, nil
}

// maxClockSkew 는 훅 시각이 현재보다 앞서 있어도 받아 줄 여유다.
//
// 상한이 필요한 이유: 먼 미래 시각(예: 2262년)이 online 으로 들어오면 watermark 가 그
// 값으로 올라가 **이후 모든 정상 offline 이 stale 로 버려진다**. 프로세스가 사는 동안
// 회복되지 않는 영구 오염이라 입력 경계에서 막는다.
// 5분은 컨테이너 간 시계 오차로 설명되는 범위의 넉넉한 상한이다.
const maxClockSkew = 5 * time.Minute

// checkAt 은 훅 시각이 쓸 수 있는 범위인지 본다.
func checkAt(kind Kind, nanos int64) error {
	if nanos <= 0 {
		return fmt.Errorf("at_unix_nano 가 없거나 양수가 아니다: %d", nanos)
	}
	if at := time.Unix(0, nanos); at.After(time.Now().Add(maxClockSkew)) {
		return fmt.Errorf("at_unix_nano 가 먼 미래다(시계 오차 허용 %v 초과): %s", maxClockSkew, at.UTC())
	}
	return nil
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
		// NaN·Inf 는 int64 변환 결과가 플랫폼마다 다르다(Go 명세상 미정의).
		// arm64 는 0 을 주지만 amd64 는 최소 int64 를 주므로 반드시 명시적으로 막는다.
		//
		// 상한 비교가 `>` 가 아니라 `>=` 인 이유: float64 로 올린 math.MaxInt64 는 2^63 인데
		// int64 의 최대는 2^63−1 이라 **그 경계값 자체가 이미 범위 밖**이다. `>` 면 정확히
		// 2^63 인 값만 검사를 빠져나가 위와 똑같은 플랫폼 의존 쓰레기가 된다.
		if math.IsNaN(ms) || math.IsInf(ms, 0) || math.Abs(ms) >= math.MaxInt64 {
			return 0
		}
		return int64(math.Round(ms))
	}
	if d, err := time.ParseDuration(raw); err == nil {
		return d.Milliseconds()
	}
	return 0
}
