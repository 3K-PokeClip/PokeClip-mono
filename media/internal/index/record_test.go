package index

import (
	"testing"
	"time"
)

// 파생 3함수는 꼬리 행 하나에서만 계산된다. 필드로 중복 보관하지 않는 설계(2.3절)가
// 실제로 지켜지는지 — 꼬리를 바꾸면 셋이 동시에 따라오는지 — 를 확인한다.
func TestCursorDerivedValues(t *testing.T) {
	wall := time.Date(2026, 7, 25, 10, 0, 0, 0, time.UTC)

	t.Run("Tail_nil_이면_전부_영값", func(t *testing.T) {
		var c Cursor
		if got := c.NextPTSMS(); got != 0 {
			t.Errorf("NextPTSMS = %d, want 0", got)
		}
		if got := c.LastStartWall(); !got.IsZero() {
			t.Errorf("LastStartWall = %v, want zero", got)
		}
		if got := c.ExpectedNextWall(); !got.IsZero() {
			t.Errorf("ExpectedNextWall = %v, want zero", got)
		}
	})

	t.Run("Tail_이_있으면_꼬리에서_계산된다", func(t *testing.T) {
		c := Cursor{NextSeq: 3, Tail: &TailRow{
			Seq: 2, StartPTSMS: 8000, StartWallUTC: wall, DurationMS: 4000,
		}}
		if got := c.NextPTSMS(); got != 12000 {
			t.Errorf("NextPTSMS = %d, want 12000", got)
		}
		if got := c.LastStartWall(); !got.Equal(wall) {
			t.Errorf("LastStartWall = %v, want %v", got, wall)
		}
		if got, want := c.ExpectedNextWall(), wall.Add(4*time.Second); !got.Equal(want) {
			t.Errorf("ExpectedNextWall = %v, want %v", got, want)
		}
	})

	t.Run("꼬리의_DurationMS_만_고쳐도_파생_두_값이_함께_교정된다", func(t *testing.T) {
		c := Cursor{NextSeq: 3, Tail: &TailRow{
			Seq: 2, StartPTSMS: 8000, StartWallUTC: wall, DurationMS: 4000,
		}}
		c.Tail.DurationMS = 6000 // correctTail 이 하는 일과 같다

		if got := c.NextPTSMS(); got != 14000 {
			t.Errorf("NextPTSMS = %d, want 14000", got)
		}
		if got, want := c.ExpectedNextWall(), wall.Add(6*time.Second); !got.Equal(want) {
			t.Errorf("ExpectedNextWall = %v, want %v", got, want)
		}
	})
}

// S3Key 포맷 출처: 계약-세그먼트인덱스.md 의 예시
// streams/demo-stream/2026-07-25/10/seg_000123.m4s
func TestS3Key(t *testing.T) {
	tests := []struct {
		name     string
		streamID string
		seq      int64
		wall     time.Time
		want     string
	}{
		{
			name:     "계약_예시_그대로",
			streamID: "demo-stream",
			seq:      123,
			wall:     time.Date(2026, 7, 25, 10, 3, 12, 0, time.UTC),
			want:     "streams/demo-stream/2026-07-25/10/seg_000123.m4s",
		},
		{
			name:     "seq_0_은_여섯자리_0으로_채운다",
			streamID: "demo-stream",
			seq:      0,
			wall:     time.Date(2026, 7, 25, 0, 0, 0, 0, time.UTC),
			want:     "streams/demo-stream/2026-07-25/00/seg_000000.m4s",
		},
		{
			name:     "백만_이상은_자릿수가_늘어도_유일성은_유지된다",
			streamID: "demo-stream",
			seq:      1234567,
			wall:     time.Date(2026, 7, 25, 23, 0, 0, 0, time.UTC),
			want:     "streams/demo-stream/2026-07-25/23/seg_1234567.m4s",
		},
		{
			name:     "날짜와_시는_UTC_로_포맷한다",
			streamID: "demo-stream",
			seq:      7,
			// 로컬 시각으로는 다음 날이 되는 순간이라도 UTC 기준으로 적어야 한다.
			wall: time.Date(2026, 7, 25, 22, 30, 0, 0, time.FixedZone("KST", 9*3600)),
			want: "streams/demo-stream/2026-07-25/13/seg_000007.m4s",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := S3Key(tt.streamID, tt.seq, tt.wall); got != tt.want {
				t.Errorf("S3Key = %q, want %q", got, tt.want)
			}
		})
	}
}

// POK-30 — 계약-세그먼트인덱스 2절이 고정한 상태값 3개가 전부 선언돼 있는지 본다.
// 문자열 값 자체가 DB 에 그대로 들어가므로 오타가 나면 스위퍼 조회 조건
// (upload_state IN ('pending','failed'))과 조용히 어긋난다.
func TestUploadStateValues(t *testing.T) {
	cases := []struct {
		got  UploadState
		want string
	}{
		{UploadStatePending, "pending"},
		{UploadStateUploaded, "uploaded"},
		{UploadStateFailed, "failed"},
	}
	for _, c := range cases {
		if string(c.got) != c.want {
			t.Errorf("UploadState = %q, want %q", string(c.got), c.want)
		}
	}
}
