package rewind_test

// 판 태그(POK-196 PDT 생산자 c7a — 계약3 7-6 「판 태그」 · 설계 r7 2.12 · 계획 c7a 태그 규칙 3항)의 렌더 골든이다.
//
//	1. 첫 행이 판 2 면 첫 조각의 MAP 줄 뒤 · GAP 줄 앞에 `#EXT-X-POKECLIP-TIMING:2` 한 줄
//	2. 목록 안에서 앞 행과 판이 다른 조각에는 그 조각 판의 태그 한 줄(판 NULL 이면 `:0`)
//	3. 판 NULL 목록에는 태그가 없다 — 옛 목록과 같은 바이트(g6_f2 골든도 판 NULL 목록이다)
//
// 기대 본문은 손으로 적은 리터럴이다(렌더 코드로 만들지 않았다).

import (
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// timingVersion2 는 판 2(계약3 7-6 — 영상 첫 표본 PDT)다.
const timingVersion2 int16 = 2

// withVersion 은 행들의 판을 v 로 바꾼 사본이다.
func withVersion(rows []boundary.Row, v int16) []boundary.Row {
	out := make([]boundary.Row, len(rows))
	for i, r := range rows {
		r.TimingVersion = v
		out[i] = r
	}
	return out
}

// soloTiming 은 회차 V(비계승 · TD 4) seq 10..12 의 목록이다 — 행의 판은 v 다.
func soloTiming(v int16) rewind.Playlist {
	s := rewind.Session{ID: "V", TargetDuration: 4, MinSeq: 10, InitUploaded: true}
	return playlist("V", []rewind.Session{s}, withVersion(run("V", 10, 12, day), v))
}

// mixedTiming 은 판이 목록 안에서 바뀌는 계승 목록이다 — 회차 P(판 NULL) seq 10·11 의 접두 뒤에 P 를 계승한 회차 S 의
// seq 12..15 가 온다. S 의 판은 12 = 2 · 13 = NULL · 14 = 2(GAP 행) · 15 = 2 다. 한 회차 안에서 판이 바뀌는 일은 평시에
// 없다(판 고정) — 이 목록은 태그 자리 규칙만 본다.
func mixedTiming() rewind.Playlist {
	p := rewind.Session{ID: "P", TargetDuration: 4, MinSeq: 10, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 4, MinSeq: 12, InitUploaded: true}
	rows := append(run("P", 10, 11, day), run("S", 12, 15, day.Add(8*time.Second))...)
	for _, i := range []int{2, 4, 5} {
		rows[i].TimingVersion = timingVersion2
	}
	rows[4].PlaybackUploaded, rows[4].IsGap = false, true
	return playlist("S", []rewind.Session{p, s}, rows)
}

func TestRenderTimingTagGolden(t *testing.T) {
	tests := []struct {
		name string
		p    rewind.Playlist
		want string
	}{
		{"판_2_목록_첫_조각_MAP_뒤_한_줄", soloTiming(timingVersion2), `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:10
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/V.mp4"
#EXT-X-POKECLIP-TIMING:2
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000010.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000011.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:08.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000012.m4s
`},
		// 옛 렌더(develop)가 이 입력에 내던 바이트 그대로다 — 판 NULL 목록은 태그 줄이 없다.
		{"판_NULL_목록은_옛_바이트", soloTiming(0), `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:10
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/V.mp4"
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000010.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000011.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:08.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000012.m4s
`},
		{"판이_바뀌는_조각마다_한_줄", mixedTiming(), `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:10
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/P.mp4"
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000010.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000011.m4s
#EXT-X-DISCONTINUITY
#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/S.mp4"
#EXT-X-POKECLIP-TIMING:2
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:08.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000012.m4s
#EXT-X-POKECLIP-TIMING:0
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:12.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000013.m4s
#EXT-X-POKECLIP-TIMING:2
#EXT-X-GAP
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:16.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000014.m4s
#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:20.000Z
#EXTINF:4.000,
https://media.pokeclip.com/dvr/str/seg/000015.m4s
`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := mustRender(t, tt.p); got != tt.want {
				t.Errorf("Render 가 골든과 다르다 — %s\n본문:\n%s", firstDiff(got, tt.want), got)
			}
		})
	}
}

// 판 태그 값은 행의 판 그대로다 — 판 2 가 아닌 판(지금 쓰는 판은 2 뿐이다)도 그 값으로 적는다. 플레이어는 2 만 판 2 로
// 읽고 그 밖은 판 NULL 로 읽는다(계약3 7-6). 렌더가 값을 2 로 굳히면 장부와 본문이 갈린다.
func TestRenderTimingTagCarriesRowVersion(t *testing.T) {
	got := mustRender(t, soloTiming(3))

	_, segs := mustParse(t, got)
	if tags := segs[0].tag("#EXT-X-POKECLIP-TIMING"); !slices.Equal(tags, []string{"#EXT-X-POKECLIP-TIMING:3"}) {
		t.Errorf("판 3 목록 첫 조각의 태그 줄 = %q, want [#EXT-X-POKECLIP-TIMING:3]\n%s", tags, got)
	}
}
