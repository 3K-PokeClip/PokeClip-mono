package recording

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// TestParseSegmentPath 는 계획 1단계의 5케이스를 테이블로 검증한다.
//
//	케이스1 정상 / 케이스2 확장자 없음 / 케이스3 %f 자리수 변화
//	케이스4 중첩 %path / 케이스5 비세그먼트 파일
//
// MediaMTX recordPath 는 infra/compose/mediamtx.yml 31행의
// /recordings/%path/%Y-%m-%d_%H-%M-%S-%f 이며, %path 는 슬래시를 포함할 수 있다.
func TestParseSegmentPath(t *testing.T) {
	const root = "/recordings"

	tests := []struct {
		name         string
		path         string
		wantStreamID string
		wantWall     time.Time
		wantErr      bool
	}{
		{
			name:         "케이스1_정상_마이크로초6자리",
			path:         "/recordings/demo-stream/2026-07-25_10-03-12-123456.mp4",
			wantStreamID: "demo-stream",
			wantWall:     time.Date(2026, 7, 25, 10, 3, 12, 123456000, time.UTC),
		},
		{
			name:         "케이스2_확장자_없음",
			path:         "/recordings/demo-stream/2026-07-25_10-03-12-123456",
			wantStreamID: "demo-stream",
			wantWall:     time.Date(2026, 7, 25, 10, 3, 12, 123456000, time.UTC),
		},
		{
			name:         "케이스3_f자리수_3자리",
			path:         "/recordings/demo-stream/2026-07-25_10-03-12-125.mp4",
			wantStreamID: "demo-stream",
			wantWall:     time.Date(2026, 7, 25, 10, 3, 12, 125000000, time.UTC),
		},
		{
			name:         "케이스3_f자리수_9자리",
			path:         "/recordings/demo-stream/2026-07-25_10-03-12-123456789.mp4",
			wantStreamID: "demo-stream",
			wantWall:     time.Date(2026, 7, 25, 10, 3, 12, 123456789, time.UTC),
		},
		{
			// 계획 1단계의 "중첩 %path" 케이스. 검수(보안 H1) 결정으로 판정이 뒤집혔다 —
			// 슬래시를 포함한 stream_id 는 거부한다. 케이스 번호는 추적을 위해 유지한다.
			name:    "케이스4_중첩_path는_거부한다",
			path:    "/recordings/live/kr/demo-stream/2026-07-25_10-03-12-000001.mp4",
			wantErr: true,
		},
		{
			name:    "케이스5_비세그먼트_파일",
			path:    "/recordings/demo-stream/notes.txt",
			wantErr: true,
		},
		{
			name:    "케이스5_루트_직하_파일은_스트림_디렉토리가_없다",
			path:    "/recordings/2026-07-25_10-03-12-123456.mp4",
			wantErr: true,
		},
		{
			name:    "케이스5_루트_밖_경로",
			path:    "/other/demo-stream/2026-07-25_10-03-12-123456.mp4",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ParseSegmentPath(root, tt.path)

			if tt.wantErr {
				if err == nil {
					t.Fatalf("에러를 기대했으나 nil, got=%+v", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("예상 밖 에러: %v", err)
			}
			if got.StreamID != tt.wantStreamID {
				t.Errorf("StreamID = %q, want %q", got.StreamID, tt.wantStreamID)
			}
			if got.Path != tt.path {
				t.Errorf("Path = %q, want %q", got.Path, tt.path)
			}
			if !got.StartWall.Equal(tt.wantWall) {
				t.Errorf("StartWall = %v, want %v", got.StartWall, tt.wantWall)
			}
			if got.Reason != ReasonUnknown {
				t.Errorf("Reason = %d, want ReasonUnknown(0) — 사유는 호출자가 채운다", got.Reason)
			}
		})
	}
}

// StartWall 은 항상 UTC 여야 한다(D6 — 계약이 UTC 강제, 로컬 TZ 저장 금지).
// 로컬 타임존이 UTC 가 아닌 환경에서도 Location 이 UTC 로 고정되는지 확인한다.
func TestParseSegmentPathStartWallIsUTC(t *testing.T) {
	seg, err := ParseSegmentPath("/recordings", "/recordings/demo/2026-07-25_10-03-12-123456.mp4")
	if err != nil {
		t.Fatalf("예상 밖 에러: %v", err)
	}
	if loc := seg.StartWall.Location(); loc != time.UTC {
		t.Fatalf("StartWall.Location() = %v, want UTC", loc)
	}
}

// stream_id 화이트리스트 (보안 H1).
// 파일 시스템에서 온 이름이 그대로 DB 값과 S3 키가 되므로, 받아들이는 모양을 좁게 못 박는다.
func TestParseSegmentPathRejectsUnsafeStreamID(t *testing.T) {
	const name = "2026-07-25_10-03-12-123456.mp4"

	bad := []struct {
		label    string
		streamID string
	}{
		{"슬래시_중첩", "live/kr/demo"},
		{"점_상대경로", "..hidden"},
		{"공백", "demo stream"},
		{"셸_메타문자", "demo;rm"},
		{"경로_구분자_유사문자", "demo\\stream"},
		{"한글", "데모"},
		{"64자_초과", strings.Repeat("a", 65)},
	}
	for _, tc := range bad {
		t.Run("거부_"+tc.label, func(t *testing.T) {
			p := "/recordings/" + tc.streamID + "/" + name
			seg, err := ParseSegmentPath("/recordings", p)
			if err == nil {
				t.Fatalf("에러를 기대했으나 nil (streamID=%q)", seg.StreamID)
			}
		})
	}

	good := []string{"demo-stream", "test-f5b788d1", "A_b-9", strings.Repeat("a", 64)}
	for _, streamID := range good {
		t.Run("허용_"+streamID[:min(len(streamID), 12)], func(t *testing.T) {
			p := "/recordings/" + streamID + "/" + name
			seg, err := ParseSegmentPath("/recordings", p)
			if err != nil {
				t.Fatalf("예상 밖 에러: %v", err)
			}
			if seg.StreamID != streamID {
				t.Fatalf("StreamID = %q, want %q", seg.StreamID, streamID)
			}
		})
	}
}

// 화이트리스트 위반은 다른 실패와 구분돼야 한다.
// 호출자가 "세그먼트 파일이 아님"(조용히 무시)과 "거부된 스트림"(WARN 1회)을 갈라 처리한다.
func TestInvalidStreamIDIsDistinguishable(t *testing.T) {
	_, err := ParseSegmentPath("/recordings", "/recordings/live/kr/demo/2026-07-25_10-03-12-123456.mp4")
	if !errors.Is(err, ErrInvalidStreamID) {
		t.Fatalf("err = %v, want ErrInvalidStreamID", err)
	}

	// 세그먼트 파일명이 아닌 것은 이 사유가 아니다.
	_, err = ParseSegmentPath("/recordings", "/recordings/demo/README.txt")
	if errors.Is(err, ErrInvalidStreamID) {
		t.Fatal("파일명 형식 불일치가 ErrInvalidStreamID 로 분류됐다")
	}
}

// ReasonUnknown 이 zero value 라는 사실 자체가 H0(사유 미설정 검출)의 존재 근거다.
func TestReasonUnknownIsZeroValue(t *testing.T) {
	var r CompletionReason
	if r != ReasonUnknown {
		t.Fatalf("CompletionReason zero value = %d, want ReasonUnknown(0)", r)
	}
}
