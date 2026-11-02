package rewind_test

// 동결 판의 머리 대조(M6 설계 9절 D32 · 4.1 R1) — 닫는 판이 머리 태그를 바꾸거나 PLAYLIST-TYPE 을 새로
// 붙이면 RFC 8216bis-22 6.2.1 허용 변경 밖이라 재생기가 멈출 수 있다.
//
// 정상 동결(F)은 평시 틱과 같은 산식으로 다시 렌더한다. 그래서 꼬리 조각이 더해져 머리가 축출되면 MSN 은
// 오를 수 있다(단조는 S1 · S2 · S6 이 본다). 렌더가 지키는 것은 둘이다 — TARGETDURATION 은 소유 회차 값
// 그대로이고, 되감기 목록은 닫혀도 PLAYLIST-TYPE 을 싣지 않는다. DISC-SEQ 는 렌더가 소유 회차 base 를
// 그대로 적는다(증분은 발행 층의 산식 — 발행 층 F 시험의 몫).

import (
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// headTagLines 는 본문의 목록 머리 태그 줄이다 — VERSION · TD · MSN · DISC-SEQ · PLAYLIST-TYPE.
func headTagLines(body string) []string {
	var head []string
	for _, line := range strings.Split(body, "\n") {
		for _, tag := range []string{"#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:",
			"#EXT-X-DISCONTINUITY-SEQUENCE:", "#EXT-X-PLAYLIST-TYPE:"} {
			if strings.HasPrefix(line, tag) {
				head = append(head, line)
			}
		}
	}
	return head
}

// TestRenderFrozenSlidingKeepsHeader 는 되감기 목록 직전 판 P 와 동결 판 F 의 머리를 대조한다. F 는 P 의 행에
// 꼬리 조각 하나가 더해져 머리 하나가 축출된 창이고 닫혀 있다(Ended). 머리 넷 가운데 MSN 만 축출 수(1)만큼
// 오르고 VERSION · TD · DISC-SEQ 줄은 P 와 같아야 하며, PLAYLIST-TYPE 줄은 어느 판에도 없어야 한다.
func TestRenderFrozenSlidingKeepsHeader(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, DiscontinuityBase: 7, MinSeq: 1, InitUploaded: true}
	all := run("N", 1, 901, day)
	prev := playlist("N", []rewind.Session{n}, all[:900])
	frozen := playlist("N", []rewind.Session{n}, all[1:])
	frozen.Ended = true

	p, f := headTagLines(mustRender(t, prev)), headTagLines(mustRender(t, frozen))
	want := []string{"#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:6", "#EXT-X-MEDIA-SEQUENCE:2", "#EXT-X-DISCONTINUITY-SEQUENCE:7"}
	if !slices.Equal(f, want) {
		t.Fatalf("동결 판 머리 = %q, want %q (직전 판 머리 %q)", f, want, p)
	}
	if p[1] != f[1] || p[3] != f[3] {
		t.Fatalf("동결 판의 TD · DISC-SEQ 줄 %q · %q 가 직전 판 %q · %q 와 다르다", f[1], f[3], p[1], p[3])
	}
}

// TestRenderFrozenHeaderIsUnchangedByEnded 는 같은 행을 열고 닫은 두 판의 머리가 종류마다 한 글자도 다르지
// 않은지 본다 — 닫힘은 본문 끝 한 줄이지 머리 변경이 아니다(2단 봉인 E 와 같은 머리).
func TestRenderFrozenHeaderIsUnchangedByEnded(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 4, DiscontinuityBase: 1, MinSeq: 50, InitUploaded: true}
	for _, kind := range []rewind.Kind{rewind.KindSliding, rewind.KindVOD} {
		open := playlist("N", []rewind.Session{n}, run("N", 50, 59, day.Add(time.Hour)))
		open.Kind = kind
		ended := open
		ended.Ended = true
		if o, e := headTagLines(mustRender(t, open)), headTagLines(mustRender(t, ended)); !slices.Equal(o, e) {
			t.Errorf("kind=%d 닫은 판 머리 %q 가 열린 판 %q 와 다르다", kind, e, o)
		}
	}
}
