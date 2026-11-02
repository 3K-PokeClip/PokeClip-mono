package publish

// VOD 본문의 조각 단위 펼치기 · 동일성 — POK-525 설계 r7 4-9 「동일성 검사(조각 단위)」.
//
// 옛 판(사본 없는 이미지 · ③ 원본 /dvr URI)과 새 판(사본 /vod URI) VOD 문서가 「같은 조각을 같은 순서로 가리키는가」를
// 본다. 바이트를 비교하지 않는다 — 교체 이미지에는 PDT 재설계 같은 다른 렌더 변경이 함께 실릴 수 있어 머리 태그 · PDT ·
// EXTINF 줄 꼴이 달라도 같은 VOD 다(E98). 옛 판 채택(vod.go adopt)과 옛 판 이관이 함께 쓴다.

import (
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
)

// VOD 본문 조각 · MAP URI 의 앞머리(BaseURL + "/" 뒤) — 본문 하나에 하나다.
const (
	vodNSLegacy  = "dvr/" // 옛 판 — ③ 원본 키를 그대로 실은 VOD 문서(사본 이전 이미지)
	vodNSCurrent = "vod/" // 새 판 — 사본 키(playback.VODPath)를 실은 VOD 문서
)

// errVODMixedNamespace 는 한 VOD 본문의 조각 · MAP URI 앞머리가 섞였다는 뜻이다 — 옛 판도 새 판도 아니다.
var errVODMixedNamespace = errors.New("publish: VOD 본문의 조각 · MAP URI 앞머리가 섞였다")

// vodUnit 은 VOD 본문의 조각 하나를 판과 무관하게 적은 것이다(설계 r7 4-9 의 비교 열).
type vodUnit struct {
	seq    int64  // MEDIA-SEQUENCE + 본문 안 위치
	key    string // 앞머리를 뺀 조각 키({stream}/seg/{seq}.m4s)
	mapKey string // 그 조각에 적용되는 MAP 의 키(앞머리 뺌 — 런의 init)
	gap    bool   // 앞에 EXT-X-GAP 이 있다
	disc   bool   // 앞에 EXT-X-DISCONTINUITY 가 있다
}

// vodSegments 는 VOD 본문 하나를 조각 단위로 편 것이다.
type vodSegments struct {
	ns    string // vodNSLegacy · vodNSCurrent
	units []vodUnit
}

// sameSegments 는 두 본문이 같은 조각 열(seq · 키 · MAP · GAP · DISCONTINUITY)인가다 — 앞머리는 보지 않는다.
func (s vodSegments) sameSegments(o vodSegments) bool { return slices.Equal(s.units, o.units) }

// parseVODSegments 는 VOD 본문 body 를 조각 단위로 편다. URI 는 baseURL + "/" + 앞머리 + 키여야 하고 앞머리는 본문 전체에
// 하나다(섞이면 errVODMixedNamespace). MEDIA-SEQUENCE 가 첫 조각 앞에 없거나 · MAP 없는 조각이 있거나 · 조각이 없으면
// 오류다. 머리 태그 · PDT · EXTINF · 그 밖의 태그 줄은 보지 않는다.
func parseVODSegments(body []byte, baseURL string) (vodSegments, error) {
	var (
		out       vodSegments
		next      int64
		hasMSN    bool
		mapKey    string
		gap, disc bool
	)
	for _, line := range strings.Split(string(body), "\n") {
		var err error
		switch {
		case strings.HasPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"):
			next, err = strconv.ParseInt(strings.TrimPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"), 10, 64)
			hasMSN = err == nil
		case line == "#EXT-X-DISCONTINUITY":
			disc = true
		case line == "#EXT-X-GAP":
			gap = true
		case strings.HasPrefix(line, `#EXT-X-MAP:URI="`):
			mapKey, err = out.strip(strings.TrimSuffix(strings.TrimPrefix(line, `#EXT-X-MAP:URI="`), `"`), baseURL)
		case line == "" || strings.HasPrefix(line, "#"):
		case !hasMSN || mapKey == "":
			return vodSegments{}, fmt.Errorf("publish: VOD 본문 조각 %.64q 앞에 MEDIA-SEQUENCE · MAP 이 없다", line)
		default:
			var key string
			if key, err = out.strip(line, baseURL); err == nil {
				out.units = append(out.units, vodUnit{seq: next, key: key, mapKey: mapKey, gap: gap, disc: disc})
				next, gap, disc = next+1, false, false
			}
		}
		if err != nil {
			return vodSegments{}, fmt.Errorf("publish: VOD 본문 줄 %.64q 를 읽을 수 없다: %w", line, err)
		}
	}
	if len(out.units) == 0 {
		return vodSegments{}, errors.New("publish: VOD 본문에 조각이 없다")
	}
	return out, nil
}

// strip 은 URI 에서 baseURL + "/" 와 앞머리를 떼어 키를 돌려준다. 앞머리는 처음 본 것으로 정하고 다르면
// errVODMixedNamespace 다.
func (s *vodSegments) strip(uri, baseURL string) (string, error) {
	rest, ok := strings.CutPrefix(uri, baseURL+"/")
	if !ok {
		return "", fmt.Errorf("publish: URI 가 %q 아래가 아니다", baseURL)
	}
	ns, key, ok := strings.Cut(rest, "/")
	ns += "/"
	switch {
	case !ok || (ns != vodNSLegacy && ns != vodNSCurrent):
		return "", fmt.Errorf("publish: URI 앞머리가 %q · %q 가 아니다", vodNSLegacy, vodNSCurrent)
	case s.ns == "":
		s.ns = ns
	case s.ns != ns:
		return "", errVODMixedNamespace
	}
	return key, nil
}
