package publish

// 판 자기기술의 닫힘(M6 설계 5절 tick.go 행 · 9절 D32) — PG 없이 돈다.

import (
	"crypto/sha256"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// TestDescribeTerminalFollowsEnded 는 메타 pc-terminal 이 올릴 본문의 닫힘(Playlist.Ended — 본문 끝 ENDLIST)과
// 같은 값인지 본다. 닫힌 판의 메타가 거짓이면 다음 판의 S6(닫힘은 거짓 → 참으로만)이 닫힌 목록 뒤 열린 목록을
// 막지 못한다. 닫힘 밖의 일곱 값은 열린 판과 같다.
func TestDescribeTerminalFollowsEnded(t *testing.T) {
	start := time.Date(2026, 10, 26, 0, 0, 0, 0, time.UTC)
	var rows []boundary.Row
	for seq := int64(10); seq <= 12; seq++ {
		rows = append(rows, boundary.Row{Seq: seq, SessionID: "S", DurationMS: 4000,
			PlaybackPDT: start.Add(time.Duration(seq-10) * 4 * time.Second), PlaybackUploaded: true})
	}
	open := rewind.Playlist{StreamID: "str", Owner: "S", Rows: rows,
		Sessions: []rewind.Session{{ID: "S", DiscontinuityBase: 3, TargetDuration: 6, MinSeq: 10}}}
	ended := open
	ended.Ended = true
	var sum [sha256.Size]byte

	got, want := describe(ended, 9, sum), describe(open, 9, sum)
	if want.Terminal {
		t.Fatalf("열린 판의 Terminal = true, want false")
	}
	if !got.Terminal {
		t.Fatalf("닫힌 판(Ended)의 Terminal = false, want true")
	}
	want.Terminal = true
	if got != want {
		t.Fatalf("닫힌 판의 자기기술 = %+v, want 열린 판 + Terminal %+v", got, want)
	}
}
