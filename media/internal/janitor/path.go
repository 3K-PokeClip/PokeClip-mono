package janitor

import (
	"path/filepath"
	"strings"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// validatePath 는 장부의 local_path 를 루트 기준 상대 경로로 바꾼다. 거부면 rel 이 비고 reason 이
// 그 까닭이다(bad_stream_id · root_escape · stream_mismatch · bad_name).
//
// 어휘 판정이다 — FS 를 보지 않는다. 그래서 스트림 디렉터리가 없어도(MediaMTX 가 빈 디렉터리를
// 지운 뒤 — D38) 통과하고, 부재는 삭제의 ENOENT 가 나이 규칙(K7)으로 받는다. 루트 밖 탈출은
// os.Root 도 막지만 다른 스트림 디렉터리(D14b)는 Root 가 못 막으므로 여기서 거부한다. 어휘로는
// 못 보는 디렉터리 심링크(s1 -> s2)는 삭제 통로 fsop.RemoveT 가 거부한다(stream_symlink — row.go act).
// 규칙은 업로더(upload/worker.go 의 root_escape · dir_mismatch)와 같다 — upload 는 무접촉이라
// 공용으로 꺼내지 않는다(세 번째 소비자가 생길 때 꺼낸다).
func validatePath(segmentRoot string, seg index.LocalSegment) (rel, reason string) {
	if !recording.ValidStreamID(seg.StreamID) {
		return "", "bad_stream_id"
	}
	rel, err := filepath.Rel(filepath.Clean(segmentRoot), filepath.Clean(seg.LocalPath))
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", "root_escape"
	}
	if filepath.Dir(rel) != seg.StreamID {
		return "", "stream_mismatch"
	}
	// 마지막 요소는 녹화 조각 이름이어야 한다 — 장부 값이 오염되면 같은 스트림의 init · 다른 파일 ·
	// 하위 디렉터리(Root.Remove 는 빈 디렉터리도 지운다)가 지워질 수 있다(t2 r1 Q2). 이름 규칙의
	// 소유자는 recording 이다.
	if _, err := recording.ParseSegmentPath(segmentRoot, seg.LocalPath); err != nil {
		return "", "bad_name"
	}
	return rel, ""
}
