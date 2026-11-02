package rewind_test

// 목록 종류마다 조각 · MAP URI 의 이름공간(POK-525 설계 r7 3-2 objectURI · 결함 예측 E22).
//
// 되감기 목록(KindSliding)은 /dvr/… 를, VOD 목록(KindVOD)은 /vod/… 사본(playback.VODPath)을 싣는다 —
// 「세그먼트 정규 URL 영구 고정 — 이름공간(/dvr 되감기 · /vod VOD 사본)마다 하나, 한 목록 안에서 바뀌지
// 않는다」(프로필 4절). 렌더와 발행 전 검사(Validate)가 같은 규칙을 써야 한다 — 갈리면 VOD 가 /dvr 를
// 가리킨 채 나가거나(변이 M15 — VOD 도 키 그대로), 렌더한 VOD 본문이 검사에 걸려 동결이 멈춘다(변이 M16 —
// 검사만 옛 규칙).

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// dvrPrefix · vodPrefix 는 픽스처 목록(baseURL)에서 두 이름공간의 URI 앞머리다.
const (
	dvrPrefix = baseURL + "/dvr/"
	vodPrefix = baseURL + "/vod/"
)

// TestG6F2VODRenderMatchesGoldenInVODNamespace 는 g6_f2 를 VOD 목록으로 닫아 렌더한 본문이 골든에서 조각 ·
// MAP URI 의 /dvr/ 만 /vod/ 로 바꾸고 PLAYLIST-TYPE:VOD 한 줄(머리)과 ENDLIST 한 줄(끝)을 더한 바이트와
// 같은지 본다. 이름공간 말고는 키가 그대로라(VODPath 는 앞머리만 바꾼다) 슬라이딩 골든에서 기대값을 만든다.
// 바꾼 줄 수 = 조각 900 + MAP 2(두 회차 첫 줄)다.
func TestG6F2VODRenderMatchesGoldenInVODNamespace(t *testing.T) {
	golden, err := os.ReadFile(filepath.Join("testdata", "g6_f2.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}
	sliding := string(golden)
	if n := strings.Count(sliding, dvrPrefix); n != 902 {
		t.Fatalf("골든의 %q 개수 = %d, want 902(조각 900 + MAP 2)", dvrPrefix, n)
	}
	const discSeq = "#EXT-X-DISCONTINUITY-SEQUENCE:3\n"
	want := strings.Replace(strings.ReplaceAll(sliding, dvrPrefix, vodPrefix), discSeq, discSeq+playlistTypeVOD, 1) +
		"#EXT-X-ENDLIST\n"

	p := f2Playlist()
	p.Kind, p.Ended = rewind.KindVOD, true
	got := mustRender(t, p)

	if got != want {
		t.Fatalf("Render(f2 KindVOD Ended) 가 /vod 골든과 다르다 — %s", firstDiff(got, want))
	}
}

// TestRenderObjectURIFollowsKind 는 조각 줄과 MAP 줄 전부가 목록 종류의 이름공간 하나만 가리키는지 본다 —
// 한 목록 안에서 이름공간이 섞이지 않는다.
func TestRenderObjectURIFollowsKind(t *testing.T) {
	tests := []struct {
		name  string
		kind  rewind.Kind
		want  string
		other string
	}{
		{"되감기_목록은_dvr", rewind.KindSliding, dvrPrefix, vodPrefix},
		{"VOD_목록은_vod", rewind.KindVOD, vodPrefix, dvrPrefix},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := prefixed()
			p.Kind = tt.kind
			_, segs := mustParse(t, mustRender(t, p))

			var uris []string
			for _, s := range segs {
				uris = append(uris, s.uri)
				for _, m := range s.tag("#EXT-X-MAP:URI=") {
					uris = append(uris, strings.Trim(strings.TrimPrefix(m, "#EXT-X-MAP:URI="), `"`))
				}
			}
			if len(uris) != len(p.Rows)+2 {
				t.Fatalf("URI %d개(%q), want 조각 %d + MAP 2", len(uris), uris, len(p.Rows))
			}
			for _, u := range uris {
				if !strings.HasPrefix(u, tt.want) || strings.Contains(u, tt.other) {
					t.Errorf("kind=%d URI %q — %q 이름공간이어야 한다", tt.kind, u, tt.want)
				}
			}
		})
	}
}

// TestRenderVODRejectsKeyOutsideDVRNamespace 는 VOD 목록에서 사본 경로로 옮길 수 없는 장부 키(③ 조각 키 문법
// 밖)를 본문 없이 오류로 내는지 본다 — 틀린 /vod URI 를 조용히 싣지 않는다.
func TestRenderVODRejectsKeyOutsideDVRNamespace(t *testing.T) {
	for _, key := range []string{"vod/str/seg/000040.m4s", "archive/str/000040.mp4", "dvr/str/seg/40.m4s"} {
		p := prefixed()
		p.Kind = rewind.KindVOD
		p.Rows[0].PlaybackS3Key = key
		if body, err := rewind.Render(p); err == nil {
			t.Errorf("Render(KindVOD, 키 %q) = 본문 %d바이트, nil — want 오류", key, len(body))
		}
	}
}

// TestValidateObjectURIFollowsKind 는 발행 전 검사가 렌더와 같은 이름공간 규칙으로 조각 · MAP URI 를 대조하는지
// 본다. 렌더한 본문은 종류마다 통과하고, 종류와 다른 이름공간을 실은 본문은 걸린다 — 조각 URI 와 소유 회차의
// MAP 은 발행 중단(S5), 계승 접두의 MAP 만 어긋나면 계승 취소(S4)다(접두 MAP 의 값은 S4 가 본다 —
// checkInheritedPrefix).
func TestValidateObjectURIFollowsKind(t *testing.T) {
	sliding := prefixed()
	vod := prefixed()
	vod.Kind = rewind.KindVOD
	slidingBody := mustRender(t, sliding)
	vodBody := mustRender(t, vod)
	const (
		mapP  = `#EXT-X-MAP:URI="` + vodPrefix + `str/init/P.mp4"`
		mapS  = `#EXT-X-MAP:URI="` + vodPrefix + `str/init/S.mp4"`
		seg44 = vodPrefix + "str/seg/000044.m4s\n"
	)
	toDVR := func(s string) string { return strings.Replace(s, vodPrefix, dvrPrefix, 1) }
	tests := []struct {
		name string
		p    rewind.Playlist
		body string
		want string
	}{
		{"되감기_렌더_그대로", sliding, slidingBody, ""},
		{"VOD_렌더_그대로", vod, vodBody, ""},
		{"VOD_인데_URI_전부_dvr", vod, strings.ReplaceAll(vodBody, vodPrefix, dvrPrefix), "S5"},
		{"VOD_인데_소유_회차_조각_하나만_dvr", vod, strings.Replace(vodBody, seg44, toDVR(seg44), 1), "S5"},
		{"VOD_인데_소유_회차_MAP_만_dvr", vod, strings.Replace(vodBody, mapS, toDVR(mapS), 1), "S5"},
		{"VOD_인데_접두_MAP_만_dvr", vod, strings.Replace(vodBody, mapP, toDVR(mapP), 1), "S4"},
		{"되감기인데_URI_전부_vod", sliding, strings.ReplaceAll(slidingBody, dvrPrefix, vodPrefix), "S5"},
		{"되감기인데_조각_하나만_vod", sliding, strings.Replace(slidingBody, toDVR(seg44), seg44, 1), "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(tt.p, []byte(tt.body), 1, nil), tt.want)
		})
	}
}
