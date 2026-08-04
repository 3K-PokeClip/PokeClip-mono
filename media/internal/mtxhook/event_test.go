package mtxhook

import (
	"strconv"
	"testing"
	"time"
)

// KindUnknown 이 zero value 라는 사실이 "종류를 못 정했다"를 사고 신호로 만든다.
// recording.ReasonUnknown 과 같은 규약이다.
func TestKindUnknownIsZeroValue(t *testing.T) {
	var k Kind
	if k != KindUnknown {
		t.Fatalf("Kind zero value = %d, want KindUnknown(0)", k)
	}
}

func TestParseLineAcceptsThreeKinds(t *testing.T) {
	tests := []struct {
		name     string
		line     string
		wantKind Kind
	}{
		{
			name:     "online",
			line:     `{"kind":"online","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SOURCE_ID":"src-a"}`,
			wantKind: KindOnline,
		},
		{
			name:     "offline",
			line:     `{"kind":"offline","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SOURCE_ID":"src-a"}`,
			wantKind: KindOffline,
		},
		{
			name:     "segcomplete",
			line:     `{"kind":"segcomplete","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SEGMENT_PATH":"/recordings/demo/2026-07-25_10-00-00-000000.mp4","MTX_SEGMENT_DURATION":"4.008"}`,
			wantKind: KindSegmentComplete,
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ev, err := ParseLine([]byte(tc.line))
			if err != nil {
				t.Fatalf("ParseLine 실패: %v", err)
			}
			if ev.Kind != tc.wantKind {
				t.Errorf("Kind = %d, want %d", ev.Kind, tc.wantKind)
			}
			if ev.StreamID != "demo" {
				t.Errorf("StreamID = %q, want %q", ev.StreamID, "demo")
			}
		})
	}
}

// 시각은 epoch 나노초 정수로만 실린다. UTC 로 복원되지 않으면 훅 시각과 파일명 시각의
// 비교(H3 소비 조건)가 컨테이너 TZ 설정에 좌우된다.
func TestParseLineConvertsAtUnixNanoToUTC(t *testing.T) {
	const nanos = 1784000000123456789
	ev, err := ParseLine([]byte(`{"kind":"online","at_unix_nano":1784000000123456789,"MTX_PATH":"demo"}`))
	if err != nil {
		t.Fatalf("ParseLine 실패: %v", err)
	}
	if ev.At.UnixNano() != nanos {
		t.Errorf("At.UnixNano() = %d, want %d", ev.At.UnixNano(), nanos)
	}
	if ev.At.Location() != time.UTC {
		t.Errorf("At.Location() = %v, want UTC", ev.At.Location())
	}
}

func TestParseLineFillsSegmentFields(t *testing.T) {
	const line = `{"kind":"segcomplete","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SEGMENT_PATH":"/recordings/demo/x.mp4","MTX_SEGMENT_DURATION":"4.008"}`
	ev, err := ParseLine([]byte(line))
	if err != nil {
		t.Fatalf("ParseLine 실패: %v", err)
	}
	if ev.SegmentPath != "/recordings/demo/x.mp4" {
		t.Errorf("SegmentPath = %q", ev.SegmentPath)
	}
	if ev.SegmentDurationMS != 4008 {
		t.Errorf("SegmentDurationMS = %d, want 4008", ev.SegmentDurationMS)
	}
}

// MTX_SEGMENT_DURATION 은 참고값이다(정본은 fmp4meta 실측). 형식이 미확인이므로
// 해석에 실패해도 이벤트 자체를 버리면 안 된다 — 버리면 그 조각이 훅 채널에서 사라진다.
func TestParseLineKeepsEventWhenDurationUnparsable(t *testing.T) {
	const line = `{"kind":"segcomplete","at_unix_nano":1784000000000000000,"MTX_PATH":"demo","MTX_SEGMENT_PATH":"/recordings/demo/x.mp4","MTX_SEGMENT_DURATION":"뭐지"}`
	ev, err := ParseLine([]byte(line))
	if err != nil {
		t.Fatalf("참고값 해석 실패로 이벤트를 버렸다: %v", err)
	}
	if ev.SegmentDurationMS != 0 {
		t.Errorf("SegmentDurationMS = %d, want 0(미상)", ev.SegmentDurationMS)
	}
}

func TestParseLineRejectsBadLines(t *testing.T) {
	tests := []struct {
		name string
		line string
	}{
		{"JSON 깨짐", `{"kind":"online","at_unix_nano":178`},
		{"빈 줄", ``},
		{"kind 누락", `{"at_unix_nano":1784000000000000000,"MTX_PATH":"demo"}`},
		{"kind 미상", `{"kind":"ready","at_unix_nano":1784000000000000000,"MTX_PATH":"demo"}`},
		{"at_unix_nano 누락", `{"kind":"online","MTX_PATH":"demo"}`},
		{"at_unix_nano 가 0", `{"kind":"online","at_unix_nano":0,"MTX_PATH":"demo"}`},
		{"MTX_PATH 누락", `{"kind":"online","at_unix_nano":1784000000000000000}`},
		{"segcomplete 인데 MTX_SEGMENT_PATH 누락", `{"kind":"segcomplete","at_unix_nano":1784000000000000000,"MTX_PATH":"demo"}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ev, err := ParseLine([]byte(tc.line))
			if err == nil {
				t.Fatalf("에러를 기대했으나 통과했다: %+v", ev)
			}
			if ev.Kind != KindUnknown {
				t.Errorf("실패 시 Kind = %d, want KindUnknown", ev.Kind)
			}
		})
	}
}

// ev.StreamID 는 MTX_PATH 원문이어야 한다 — indexer 의 breaks 키 계약(결정 ①)이
// 이 등식에 걸려 있다. 공백 제거·소문자화 같은 정규화를 넣는 순간 조용한 미탐이 된다.
func TestParseLineKeepsStreamIDVerbatim(t *testing.T) {
	ev, err := ParseLine([]byte(`{"kind":"online","at_unix_nano":1784000000000000000,"MTX_PATH":"Demo_Stream-01"}`))
	if err != nil {
		t.Fatalf("ParseLine 실패: %v", err)
	}
	if ev.StreamID != "Demo_Stream-01" {
		t.Errorf("StreamID = %q, want %q (원문 그대로)", ev.StreamID, "Demo_Stream-01")
	}
}

// 세션 훅의 MTX_PATH 는 breaks 맵의 **키**가 되므로 소비 측 stream_id 와 같은 규칙을
// 통과해야 한다. 통과 못 할 문자열로 무장하면 영원히 소비되지 않는 큐 항목이 되고,
// 외부 문자열이 그대로 맵 키가 되는 경로이기도 하다.
//
// 이 거부는 결정 ①의 "변형 금지"와 무관하다 — 변형이 아니라 검증이다.
func TestParseLineValidatesSessionStreamID(t *testing.T) {
	bad := []string{
		"경로에 한글",
		"live/kr/demo",  // 슬래시 = 중첩 %path
		"../etc/passwd", // 경로 탈출 모양
		"has space",
		"sixty-five-chars-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}
	for _, id := range bad {
		t.Run(id, func(t *testing.T) {
			line := `{"kind":"online","at_unix_nano":1784000000000000000,"MTX_PATH":"` + id + `"}`
			if ev, err := ParseLine([]byte(line)); err == nil {
				t.Fatalf("허용되지 않는 MTX_PATH 를 통과시켰다: %+v", ev)
			}
		})
	}

	// 허용되는 형태는 그대로 통과해야 한다(과잉 거부 방지).
	for _, id := range []string{"demo", "Demo_Stream-01", "a", "ABC_123-xyz"} {
		line := `{"kind":"online","at_unix_nano":1784000000000000000,"MTX_PATH":"` + id + `"}`
		ev, err := ParseLine([]byte(line))
		if err != nil {
			t.Errorf("정상 MTX_PATH %q 를 거부했다: %v", id, err)
			continue
		}
		if ev.StreamID != id {
			t.Errorf("StreamID = %q, want %q (원문 그대로)", ev.StreamID, id)
		}
	}
}

// 먼 미래 시각은 거부한다. 2262년 같은 값이 online 으로 들어오면 watermark 가 그 값으로
// 올라가 **이후 모든 정상 offline 이 stale 로 버려진다**(영구 오염).
func TestParseLineRejectsFarFutureTimestamp(t *testing.T) {
	far := time.Now().Add(10 * time.Minute).UnixNano()
	line := `{"kind":"online","at_unix_nano":` + strconv.FormatInt(far, 10) + `,"MTX_PATH":"demo"}`
	if ev, err := ParseLine([]byte(line)); err == nil {
		t.Fatalf("먼 미래 시각을 통과시켰다: %+v", ev)
	}

	// 시계 오차 수준(가까운 미래)은 받아들인다 — 컨테이너 간 미세 차이는 정상이다.
	near := time.Now().Add(30 * time.Second).UnixNano()
	line = `{"kind":"online","at_unix_nano":` + strconv.FormatInt(near, 10) + `,"MTX_PATH":"demo"}`
	if _, err := ParseLine([]byte(line)); err != nil {
		t.Errorf("가까운 미래 시각을 거부했다: %v", err)
	}
}

// NaN·Inf 는 int64 변환이 정의되지 않아 쓰레기 값이 된다.
func TestDurationMSGuardsAgainstNaNAndInf(t *testing.T) {
	for _, raw := range []string{"NaN", "Inf", "-Inf", "+Inf"} {
		if got := durationMS(raw); got != 0 {
			t.Errorf("durationMS(%q) = %d, want 0", raw, got)
		}
	}
}
