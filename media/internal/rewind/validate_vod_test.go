package rewind_test

// VOD 본문 상한(M6 설계 9절 D29 · D57) — S5 의 크기 상한을 목록 종류마다 따로 둔다.
//
// 8시간 VOD 는 4초 조각 7,200개라 본문이 약 830KB 다(조각 115B · 머리 약 140B). 되감기 목록의 512KiB
// 상한을 함께 쓰면 VOD 가 영구히 발행 중단된다. VOD 상한은 4MiB(4초 조각으로 약 36,000개 · 약 40시간)이고,
// 넘으면 발행 중단의 한 갈래 ErrBodyTooLarge 다 — 발행 층이 freeze_failed reason=too_large 로 가른다.

import (
	"errors"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// longPlaylist 는 회차 하나의 4초 조각 n개 목록이다(seq 100000 부터 — 키의 seq 자리가 여섯 자리로 같다).
func longPlaylist(kind rewind.Kind, n int) rewind.Playlist {
	const id = "S-20260926-000000-str-100000"
	s := rewind.Session{ID: id, TargetDuration: 6, MinSeq: 100000, InitUploaded: true}
	rows := make([]boundary.Row, 0, n)
	for i := range n {
		rows = append(rows, row(id, 100000+int64(i), day.Add(time.Duration(i)*4*time.Second), 4000))
	}
	p := playlist(id, []rewind.Session{s}, rows)
	p.Kind = kind
	return p
}

// TestValidateBodyLimitDependsOnKind 는 같은 행이라도 종류마다 상한이 다른지 본다. 8시간분(7,200조각)은
// VOD 로는 통과하고 되감기 목록으로는 512KiB 를 넘어 ErrBodyTooLarge 다. VOD 는 4MiB 를 넘을 때만 걸린다.
func TestValidateBodyLimitDependsOnKind(t *testing.T) {
	tests := []struct {
		name     string
		kind     rewind.Kind
		n        int
		overVOD  bool
		wantFail bool
	}{
		{"VOD_8시간_7200조각_통과", rewind.KindVOD, 7200, false, false},
		{"되감기_목록_7200조각_512KiB_초과", rewind.KindSliding, 7200, false, true},
		{"VOD_36000조각_4MiB_안_통과", rewind.KindVOD, 36000, false, false},
		{"VOD_36600조각_4MiB_초과", rewind.KindVOD, 36600, true, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := longPlaylist(tt.kind, tt.n)
			body := []byte(mustRender(t, p))
			if over := len(body) > rewind.MaxVODBodyBytes; over != tt.overVOD {
				t.Fatalf("픽스처 본문 %d바이트 — VOD 상한 %d 초과 여부 %v, want %v(렌더 형식이 바뀌었으면 조각 수를 다시 센다)",
					len(body), rewind.MaxVODBodyBytes, over, tt.overVOD)
			}

			err := rewind.Validate(p, body, 1, nil)
			if !tt.wantFail {
				if err != nil {
					t.Fatalf("Validate(%d바이트) = %v, want nil", len(body), err)
				}
				return
			}
			var v *rewind.Violation
			if !errors.As(err, &v) || v.Check != "S5" {
				t.Fatalf("Validate(%d바이트) = %v, want S5 위반", len(body), err)
			}
			if !errors.Is(err, rewind.ErrBodyTooLarge) || !errors.Is(err, rewind.ErrHaltPublication) {
				t.Fatalf("Validate(%d바이트) = %v — ErrBodyTooLarge 이자 발행 중단이어야 한다", len(body), err)
			}
		})
	}
}

// TestValidateOtherS5ViolationsAreNotTooLarge 는 크기 밖의 S5 위반(첫 줄 · 우리 용도의 줄)이 ErrBodyTooLarge 로
// 읽히지 않는지 본다 — too_large 는 크기 갈래에만 선다.
func TestValidateOtherS5ViolationsAreNotTooLarge(t *testing.T) {
	p := longPlaylist(rewind.KindVOD, 10)
	body := []byte(mustRender(t, p))
	bad := append([]byte("#EXTM3U\n#PC-GEN:1\n"), body[len("#EXTM3U\n"):]...)

	err := rewind.Validate(p, bad, 1, nil)
	if err == nil || errors.Is(err, rewind.ErrBodyTooLarge) {
		t.Fatalf("Validate(#PC- 줄) = %v — 크기 밖 위반이 ErrBodyTooLarge 로 읽힌다", err)
	}
}
