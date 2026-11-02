package publish

// VOD 본문의 조각 단위 펼치기 · 동일성 시험이다(POK-525 설계 r7 4-9 「동일성 검사(조각 단위)」 · 계획 ①-10 · E85 · E98).

import (
	"errors"
	"strings"
	"testing"
)

// vodDocTemplate 은 사슬 P ← S 의 VOD 본문 꼴이다 — {ns} 자리에 앞머리(dvr · vod)가 들어간다. seq 5 는 P 의 런 첫
// 조각, 6 은 GAP, 7 은 계승 경계(DISCONTINUITY) 뒤 S 의 런 첫 조각이다.
const vodDocTemplate = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:7
#EXT-X-MEDIA-SEQUENCE:5
#EXT-X-DISCONTINUITY-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MAP:URI="https://media.pokeclip.com/{ns}/str/init/P.mp4"
#EXT-X-PROGRAM-DATE-TIME:2026-11-03T00:00:00.000Z
#EXTINF:4.000,
https://media.pokeclip.com/{ns}/str/seg/000005.m4s
#EXT-X-GAP
#EXT-X-PROGRAM-DATE-TIME:2026-11-03T00:00:04.000Z
#EXTINF:4.000,
https://media.pokeclip.com/{ns}/str/seg/000006.m4s
#EXT-X-DISCONTINUITY
#EXT-X-MAP:URI="https://media.pokeclip.com/{ns}/str/init/S.mp4"
#EXT-X-PROGRAM-DATE-TIME:2026-11-03T00:00:08.000Z
#EXTINF:4.000,
https://media.pokeclip.com/{ns}/str/seg/000007.m4s
#EXT-X-ENDLIST
`

// vodDoc 은 앞머리 ns 의 VOD 본문에 edits(옛 문자열 · 새 문자열 쌍)를 차례로 한 번씩 적용한 것이다.
func vodDoc(t *testing.T, ns string, edits ...string) []byte {
	t.Helper()
	doc := strings.ReplaceAll(vodDocTemplate, "{ns}", ns)
	for i := 0; i+1 < len(edits); i += 2 {
		if !strings.Contains(doc, edits[i]) {
			t.Fatalf("고칠 문자열 %q 가 본문에 없다", edits[i])
		}
		doc = strings.Replace(doc, edits[i], edits[i+1], 1)
	}
	return []byte(doc)
}

// parseVODDoc 은 parseVODSegments 가 오류 없이 펴야 하는 본문을 편다.
func parseVODDoc(t *testing.T, body []byte) vodSegments {
	t.Helper()
	s, err := parseVODSegments(body, fxBaseURL)
	if err != nil {
		t.Fatalf("parseVODSegments 실패: %v\n%s", err, body)
	}
	return s
}

// 본문은 조각마다 (seq · 앞머리 뺀 조각 키 · 런의 MAP 키 · GAP · DISCONTINUITY) 로 펴지고, 앞머리는 본문 전체에 하나다.
func TestParseVODSegmentsUnfoldsEachSegment(t *testing.T) {
	got := parseVODDoc(t, vodDoc(t, "dvr"))

	want := []vodUnit{
		{seq: 5, key: "str/seg/000005.m4s", mapKey: "str/init/P.mp4"},
		{seq: 6, key: "str/seg/000006.m4s", mapKey: "str/init/P.mp4", gap: true},
		{seq: 7, key: "str/seg/000007.m4s", mapKey: "str/init/S.mp4", disc: true},
	}
	if got.ns != vodNSLegacy || len(got.units) != len(want) {
		t.Fatalf("parseVODSegments = %+v, want 앞머리 %q · 조각 %+v", got, vodNSLegacy, want)
	}
	for i := range want {
		if got.units[i] != want[i] {
			t.Errorf("조각 %d = %+v, want %+v", i, got.units[i], want[i])
		}
	}
	if cur := parseVODDoc(t, vodDoc(t, "vod")); cur.ns != vodNSCurrent {
		t.Errorf("새 판 앞머리 = %q, want %q", cur.ns, vodNSCurrent)
	}
}

// 판을 가를 수 없는 본문은 오류다 — 앞머리가 섞임(errVODMixedNamespace) · BaseURL 밖 URI · 판 밖 앞머리 · MSN 없음 ·
// MAP 없는 조각 · 조각 없음.
func TestParseVODSegmentsRejectsUnreadableBody(t *testing.T) {
	cases := []struct {
		name      string
		body      []byte
		wantMixed bool
	}{
		{"조각 하나만 다른 앞머리", vodDoc(t, "dvr", "dvr/str/seg/000006", "vod/str/seg/000006"), true},
		{"MAP 만 다른 앞머리", vodDoc(t, "vod", "vod/str/init/S.mp4", "dvr/str/init/S.mp4"), true},
		{"BaseURL 밖 URI", vodDoc(t, "dvr", "https://media.pokeclip.com/dvr/str/seg/000007", "https://other.example/dvr/str/seg/000007"), false},
		{"판 밖 앞머리", vodDoc(t, "seg"), false},
		{"MSN 없음", vodDoc(t, "dvr", "#EXT-X-MEDIA-SEQUENCE:5\n", ""), false},
		{"MAP 없는 첫 조각", vodDoc(t, "dvr", "#EXT-X-MAP:URI=\"https://media.pokeclip.com/dvr/str/init/P.mp4\"\n", ""), false},
		{"조각 없음", []byte("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-ENDLIST\n"), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := parseVODSegments(c.body, fxBaseURL)
			if err == nil {
				t.Fatalf("parseVODSegments 오류 없음, want 오류\n%s", c.body)
			}
			if got := errors.Is(err, errVODMixedNamespace); got != c.wantMixed {
				t.Errorf("errors.Is(%v, errVODMixedNamespace) = %v, want %v", err, got, c.wantMixed)
			}
		})
	}
}

// 동일성은 조각 열만 본다 — 앞머리 · 머리 태그 · PDT 값 · EXTINF 가 달라도 같은 조각을 같은 순서로 가리키면 같고(E98),
// 조각 하나 · 수 · 순서 · seq · GAP · MAP · DISCONTINUITY 가 하나라도 다르면 다르다(E85 · 설계 r7 10절 동일성 시험).
func TestSameVODSegments(t *testing.T) {
	current := parseVODDoc(t, vodDoc(t, "vod"))
	cases := []struct {
		name   string
		legacy []byte
		want   bool
	}{
		{"앞머리만 다름", vodDoc(t, "dvr"), true},
		{"머리 태그 · PDT · EXTINF 만 다름", vodDoc(t, "dvr",
			"#EXT-X-VERSION:6\n", "#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n",
			"#EXT-X-TARGETDURATION:7", "#EXT-X-TARGETDURATION:6",
			"00:00:04.000Z", "00:00:04.137Z",
			"#EXTINF:4.000,\nhttps://media.pokeclip.com/dvr/str/seg/000007", "#EXTINF:3.968,\nhttps://media.pokeclip.com/dvr/str/seg/000007"), true},
		{"조각 하나 다름", vodDoc(t, "dvr", "seg/000006", "seg/000009"), false},
		{"조각 수 다름", vodDoc(t, "dvr",
			"#EXT-X-PROGRAM-DATE-TIME:2026-11-03T00:00:08.000Z\n#EXTINF:4.000,\nhttps://media.pokeclip.com/dvr/str/seg/000007.m4s\n", ""), false},
		{"조각 순서 다름", vodDoc(t, "dvr", "seg/000005", "seg/X", "seg/000006", "seg/000005", "seg/X", "seg/000006"), false},
		{"seq 다름", vodDoc(t, "dvr", "MEDIA-SEQUENCE:5", "MEDIA-SEQUENCE:4"), false},
		{"GAP 다름", vodDoc(t, "dvr", "#EXT-X-GAP\n", ""), false},
		{"MAP 다름", vodDoc(t, "dvr", "init/S.mp4", "init/X.mp4"), false},
		{"DISCONTINUITY 다름", vodDoc(t, "dvr", "#EXT-X-DISCONTINUITY\n", ""), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			legacy := parseVODDoc(t, c.legacy)

			if got := legacy.sameSegments(current); got != c.want {
				t.Errorf("sameSegments = %v, want %v\n옛 판 %+v\n새 판 %+v", got, c.want, legacy.units, current.units)
			}
		})
	}
}
