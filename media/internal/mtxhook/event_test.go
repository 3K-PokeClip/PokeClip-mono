package mtxhook

import (
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
