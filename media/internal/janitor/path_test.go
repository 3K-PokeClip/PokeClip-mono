package janitor

import (
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// D14a · D14b · D38 · Q2 — 경로 검증은 어휘 판정이다(FS 를 보지 않는다). 루트 밖 · 다른 스트림
// 디렉터리 · 조각 이름이 아닌 마지막 요소는 거부하고, 디렉터리가 없어도 판정은 같다(부재는 삭제
// 단계의 ENOENT 가 받는다).
func TestValidatePathTable(t *testing.T) {
	const root = "/recordings"
	cases := []struct {
		name, stream, path  string
		wantRel, wantReason string
	}{
		{"정상", "s1", "/recordings/s1/2026-10-19_00-00-00-000000.mp4", "s1/2026-10-19_00-00-00-000000.mp4", ""},
		{"디렉터리 부재여도 어휘 판정은 통과(D38)", "gone", "/recordings/gone/2026-10-19_00-00-00-000000.mp4", "gone/2026-10-19_00-00-00-000000.mp4", ""},
		{"점점 이탈(D14a)", "s1", "/recordings/s1/../../etc/passwd", "", "root_escape"},
		{"루트 밖 절대 경로", "s1", "/etc/s1/a.mp4", "", "root_escape"},
		{"루트 자체", "s1", "/recordings", "", "root_escape"},
		{"상대 경로", "s1", "s1/a.mp4", "", "root_escape"},
		{"다른 스트림 디렉터리(D14b)", "s1", "/recordings/s2/a.mp4", "", "stream_mismatch"},
		{"스트림 디렉터리 아래 더 깊음", "s1", "/recordings/s1/x/a.mp4", "", "stream_mismatch"},
		{"루트 바로 아래 파일", "s1", "/recordings/a.mp4", "", "stream_mismatch"},
		{"스트림 식별자 문법 밖", "../s1", "/recordings/s1/a.mp4", "", "bad_stream_id"},
		// Q2(t2 r1) — 마지막 요소는 녹화 조각 이름(recording.ParseSegmentPath 규칙)이어야 한다.
		{"init 조각(Q2)", "s1", "/recordings/s1/init.mp4", "", "bad_name"},
		{"비세그먼트 파일(Q2)", "s1", "/recordings/s1/notes.txt", "", "bad_name"},
		{"스트림 아래 하위 디렉터리 이름(Q2)", "s1", "/recordings/s1/sub", "", "bad_name"},
		{"조각 이름 꼴이 어긋남(Q2)", "s1", "/recordings/s1/2026-10-19_00-00-00.mp4", "", "bad_name"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rel, reason := validatePath(root, index.LocalSegment{StreamID: c.stream, LocalPath: c.path})
			if rel != c.wantRel || reason != c.wantReason {
				t.Errorf("validatePath(%q, %q) = (%q, %q), want (%q, %q)", c.stream, c.path, rel, reason, c.wantRel, c.wantReason)
			}
		})
	}
}
