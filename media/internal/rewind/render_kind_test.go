package rewind_test

// 목록 종류(Kind)와 닫힘(Ended)의 렌더 — POK-196 M6 설계 2절 rewind 행 · 4.1 R1 · 9절 D32.
//
// 슬라이딩 목록(/dvr)은 지금과 바이트 단위로 같아야 하고 PLAYLIST-TYPE 을 싣지 않는다 — 닫을 때 없던
// 머리 태그를 더하면 RFC 8216bis-22 6.2.1 허용 변경 밖이다. VOD 목록(/vod)만 PLAYLIST-TYPE:VOD 한 줄을
// 더한다. 닫힘은 본문 끝 ENDLIST 한 줄이다 — 봉인 경로(SealEndlist)가 같은 행의 열린 본문에 더한 결과와
// 같은 바이트여야 정상 동결(F)과 2단 봉인(E)이 같은 목록을 낸다.

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// playlistTypeVOD 는 VOD 목록 머리에만 서는 줄이다(RFC 8216bis-22 4.4.3.5).
const playlistTypeVOD = "#EXT-X-PLAYLIST-TYPE:VOD\n"

// TestRenderSlidingKindIsByteIdenticalToGolden 은 KindSliding 을 밝혀 적은 목록이 골든(종류 필드가 없던
// 판의 렌더)과 같은 바이트이고 PLAYLIST-TYPE 줄이 없는지 본다. 영값이 KindSliding 이라 기존 호출자는 그대로다.
func TestRenderSlidingKindIsByteIdenticalToGolden(t *testing.T) {
	want, err := os.ReadFile(filepath.Join("testdata", "g6_f2.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}
	p := f2Playlist()
	p.Kind = rewind.KindSliding
	got := mustRender(t, p)
	if got != string(want) {
		t.Fatalf("Render(KindSliding) 가 골든과 다르다 — %s", firstDiff(got, string(want)))
	}
	if strings.Contains(got, "#EXT-X-PLAYLIST-TYPE") {
		t.Fatal("KindSliding 본문에 PLAYLIST-TYPE 줄이 있다")
	}
}

// TestRenderVODKindAddsOnlyPlaylistTypeLine 은 KindVOD 가 슬라이딩 본문에 PLAYLIST-TYPE:VOD 한 줄만
// 머리(DISCONTINUITY-SEQUENCE 다음 · 첫 조각 앞)에 더하는지 본다.
func TestRenderVODKindAddsOnlyPlaylistTypeLine(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, DiscontinuityBase: 2, MinSeq: 10, InitUploaded: true}
	sliding := playlist("N", []rewind.Session{n}, run("N", 10, 14, day))
	vod := sliding
	vod.Kind = rewind.KindVOD

	open := mustRender(t, sliding)
	head, rest, ok := strings.Cut(open, "#EXT-X-DISCONTINUITY-SEQUENCE:2\n")
	if !ok {
		t.Fatalf("슬라이딩 본문에 DISC-SEQ 줄이 없다:\n%s", open)
	}
	want := head + "#EXT-X-DISCONTINUITY-SEQUENCE:2\n" + playlistTypeVOD + rest
	if got := mustRender(t, vod); got != want {
		t.Fatalf("Render(KindVOD) 가 슬라이딩 본문 + PLAYLIST-TYPE 한 줄과 다르다 — %s", firstDiff(got, want))
	}
}

// TestRenderEndedEqualsSealedOpenBody 는 Ended 목록이 같은 행의 열린 본문을 SealEndlist 로 닫은 것과 같은
// 바이트인지 본다 — 종류마다. 머리 줄은 하나도 바뀌지 않고 끝에 ENDLIST 한 줄만 붙는다.
func TestRenderEndedEqualsSealedOpenBody(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, DiscontinuityBase: 4, MinSeq: 100, InitUploaded: true}
	for _, kind := range []rewind.Kind{rewind.KindSliding, rewind.KindVOD} {
		open := playlist("N", []rewind.Session{n}, run("N", 100, 109, day.Add(time.Minute)))
		open.Kind = kind
		ended := open
		ended.Ended = true

		sealed, err := rewind.SealEndlist([]byte(mustRender(t, open)))
		if err != nil {
			t.Fatalf("kind=%d SealEndlist 오류: %v", kind, err)
		}
		if got := mustRender(t, ended); got != string(sealed) {
			t.Errorf("kind=%d Render(Ended) 가 봉인한 열린 본문과 다르다 — %s", kind, firstDiff(got, string(sealed)))
		}
	}
}
