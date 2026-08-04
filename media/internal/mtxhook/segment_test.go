package mtxhook

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

const segFileName = "2026-07-25_10-00-00-000000.mp4"

// segcompleteEvent 는 경로만 다른 segcomplete 이벤트를 만든다.
func segcompleteEvent(segmentPath string) Event {
	return Event{
		Kind:        KindSegmentComplete,
		StreamID:    "demo",
		At:          time.Unix(0, 1784000000000000000).UTC(),
		SegmentPath: segmentPath,
	}
}

// walkerPath 는 워처·Scan 이 실제로 만들어 내는 경로 문자열을 그대로 재현한다.
// filepath.WalkDir 가 준 path 가 곧 recording.Segment.Path 가 되기 때문이다
// (ParseSegmentPath 는 호출자 문자열을 그대로 담는다).
func walkerPath(t *testing.T, root string) string {
	t.Helper()
	var found string
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() {
			found = path
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk 실패: %v", err)
	}
	if found == "" {
		t.Fatal("walk 가 파일을 찾지 못했다")
	}
	return found
}

// makeSegmentFile 은 root/demo/<세그먼트명> 파일을 만든다.
func makeSegmentFile(t *testing.T) (root string) {
	t.Helper()
	root = t.TempDir()
	dir := filepath.Join(root, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, segFileName), []byte("x"), 0o600); err != nil {
		t.Fatalf("파일 생성 실패: %v", err)
	}
	return root
}

// T1 — GH7(멱등)의 최후 방어선인 UNIQUE(stream_id, local_path) 는 문자열 비교다.
// 두 채널이 같은 물리 파일에 대해 **바이트 동일한** 경로를 만들지 못하면 같은 4초에
// 행이 둘 생기고, seq 재사용·재정렬 금지 때문에 사후 정정이 불가능하다.
func TestToSegmentPathMatchesWatcherPathByteForByte(t *testing.T) {
	root := makeSegmentFile(t)
	watcherPath := walkerPath(t, root)

	watcherSeg, err := recording.ParseSegmentPath(root, watcherPath)
	if err != nil {
		t.Fatalf("워처 경로 파싱 실패: %v", err)
	}

	hookSeg, err := ToSegment(root, segcompleteEvent(watcherPath))
	if err != nil {
		t.Fatalf("ToSegment 실패: %v", err)
	}

	if hookSeg.Path != watcherSeg.Path {
		t.Errorf("local_path 불일치\n훅  = %q\n워처 = %q", hookSeg.Path, watcherSeg.Path)
	}
	if hookSeg.StreamID != watcherSeg.StreamID {
		t.Errorf("StreamID 불일치: 훅 %q, 워처 %q", hookSeg.StreamID, watcherSeg.StreamID)
	}
	if !hookSeg.StartWall.Equal(watcherSeg.StartWall) {
		t.Errorf("StartWall 불일치: 훅 %v, 워처 %v", hookSeg.StartWall, watcherSeg.StartWall)
	}
}

// T2 — MTX_SEGMENT_PATH 의 정확한 표기 형식은 미확인이다(문서 미기재).
// 어떤 표기로 오더라도 정본화 결과가 워처와 같은 한 문자열이어야 한다.
func TestToSegmentCanonicalizesPathVariants(t *testing.T) {
	root := makeSegmentFile(t)
	want := walkerPath(t, root)

	variants := map[string]string{
		"이중 슬래시":    filepath.Join(root, "demo") + "//" + segFileName,
		"점 세그먼트":    filepath.Join(root, "demo", ".", segFileName),
		"상위 되돌기":    filepath.Join(root, "demo", "sub", "..", segFileName),
		"후행 슬래시 루트": filepath.Join(root, "demo", segFileName),
	}
	for name, variant := range variants {
		t.Run(name, func(t *testing.T) {
			seg, err := ToSegment(root, segcompleteEvent(variant))
			if err != nil {
				t.Fatalf("ToSegment 실패: %v", err)
			}
			if seg.Path != want {
				t.Errorf("정본화 결과 = %q, want %q", seg.Path, want)
			}
		})
	}
}

// 루트 표기가 흔들려도(후행 슬래시·중복 슬래시) 결과는 한 문자열이어야 한다.
func TestToSegmentCanonicalizesRootVariants(t *testing.T) {
	root := makeSegmentFile(t)
	want := walkerPath(t, root)

	for _, rootVariant := range []string{root + "/", root + "//", filepath.Join(root, "demo", "..")} {
		seg, err := ToSegment(rootVariant, segcompleteEvent(want))
		if err != nil {
			t.Fatalf("ToSegment(root=%q) 실패: %v", rootVariant, err)
		}
		if seg.Path != want {
			t.Errorf("root=%q → Path = %q, want %q", rootVariant, seg.Path, want)
		}
	}
}

// 훅으로 들어온 세그먼트는 승격·학습 대상에서 빠져야 하므로 사유가 ReasonHook 이어야 한다.
func TestToSegmentSetsReasonHook(t *testing.T) {
	root := makeSegmentFile(t)
	seg, err := ToSegment(root, segcompleteEvent(walkerPath(t, root)))
	if err != nil {
		t.Fatalf("ToSegment 실패: %v", err)
	}
	if seg.Reason != recording.ReasonHook {
		t.Errorf("Reason = %d, want ReasonHook(%d)", seg.Reason, recording.ReasonHook)
	}
}

// T3(전반) — 형식 이상·루트 밖은 반드시 에러다. 조용히 통과하면 엉뚱한 stream_id 가
// 생기거나 루트 밖 파일이 인덱스에 오른다.
func TestToSegmentRejectsBadPaths(t *testing.T) {
	root := makeSegmentFile(t)

	tests := []struct {
		name string
		ev   Event
	}{
		{"루트 밖 절대 경로", segcompleteEvent("/etc/passwd")},
		{"루트 탈출 시도", segcompleteEvent(filepath.Join(root, "..", "elsewhere", "demo", segFileName))},
		{"루트 자신", segcompleteEvent(root)},
		{"스트림 디렉토리 없음", segcompleteEvent(filepath.Join(root, segFileName))},
		{"세그먼트 파일명이 아님", segcompleteEvent(filepath.Join(root, "demo", "README.txt"))},
		{"중첩 %path(화이트리스트 거부)", segcompleteEvent(filepath.Join(root, "live", "kr", segFileName))},
		{"경로가 빈 문자열", segcompleteEvent("")},
		{"segcomplete 가 아닌 종류", Event{Kind: KindOnline, StreamID: "demo", SegmentPath: filepath.Join(root, "demo", segFileName)}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			seg, err := ToSegment(root, tc.ev)
			if err == nil {
				t.Fatalf("에러를 기대했으나 통과했다: %+v", seg)
			}
			if seg.Path != "" || seg.StreamID != "" {
				t.Errorf("실패 시 zero Segment 를 기대했다: %+v", seg)
			}
		})
	}
}
