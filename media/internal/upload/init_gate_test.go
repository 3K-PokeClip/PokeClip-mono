package upload

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 재접속 계승의 stsd 호환 게이트(계획 2.3 registry 행 ⑸ⓑ · 4.5 B #3 · 판단 J63) — init 작업이 첫 init CAS 에 넘기는
// 계승 비호환 여부($5)와 그 신호를 잰다. 판별은 계승 후보(InheritsSession)이고, 후보면 새 회차 조각과 직전 회차 첫
// 조각의 stsd 지문을 견준다. 장부는 가짜라 넘긴 값과 로그만 본다 — 장부의 해제까지 관통하는 종단은
// sweep_pg_test.go 가 잰다. 생산자도 가짜다(재포장은 게이트와 무관하다 — 자기 조각의 지문은 입력 파일에서 뜬다).

// 실물 조각 둘 — 같은 녹화기(상류 1.19.3) · 같은 해상도지만 avcC 가 다르다(stsd 크기는 같고 바이트가 다르다).
const fixtureTailPath = "../playback/testdata/segment_tail_2s.mp4"

// copyFile 은 src 를 <루트>/<streamID>/<name> 에 복사하고 경로와 크기를 돌려준다.
func copyFile(t *testing.T, src, dir, streamID, name string) (string, int64) {
	t.Helper()
	raw, err := os.ReadFile(src)
	if err != nil {
		t.Fatalf("원본 읽기 실패 %s: %v", src, err)
	}
	sub := filepath.Join(dir, streamID)
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	path := filepath.Join(sub, name)
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		t.Fatalf("복사 실패 %s: %v", path, err)
	}
	return path, int64(len(raw))
}

// TestInitGateDecidesIncompatibility 는 게이트의 판정 표다. 비후보는 지문을 뜨지 않고 거짓이다(직전 경로가 엉터리여도
// 신호가 없다 — 실운영 대다수가 이 갈래라 방송마다 경보가 뜨면 안 된다). 후보면 지문이 같을 때만 거짓이고, 직전
// 조각을 견줄 수 없으면(경로 빈 값 · 루트 밖 · 다른 스트림 디렉토리 · 파일 부재 · 판독 불가) 참과 WARN
// session_inherit_declined 한 줄이다(fail-closed). 지문이 달라 참인 갈래는 신호가 없다(계획 문언 침묵 — 미확인 4).
//
// 잡는 결함: 판별을 PrevFirstLocalPath 로 되돌리면 경로 빈 후보가 비후보로 읽혀 비호환 계승이 게이트를 건너뛰고,
// 경로 부재를 호환으로 읽으면 같은 일이 janitor 삭제 뒤에 일어난다. 직전 경로를 검증 없이 열면 장부의 경로가 루트
// 밖 파일을 게이트 입력으로 만든다.
func TestInitGateDecidesIncompatibility(t *testing.T) {
	type row struct {
		name             string
		inherits         string
		prev             func(t *testing.T, dir string) string // 직전 회차 첫 조각 경로
		own              func(t *testing.T, dir string) (string, int64)
		wantIncompatible bool
		wantReason       string // "" 면 신호가 없다
	}
	sameStsd := func(t *testing.T, dir string) string {
		path, _ := copyFile(t, fixture4sPath, dir, "demo", "prev-first.mp4")
		return path
	}
	realOwn := func(t *testing.T, dir string) (string, int64) {
		return copyFile(t, fixture4sPath, dir, "demo", "new-first.mp4")
	}
	tests := []row{
		{"비후보", "", func(t *testing.T, dir string) string { return "/그런/경로/없음.mp4" }, realOwn, false, ""},
		{"후보_stsd_같음", "S-prev", sameStsd, realOwn, false, ""},
		{"후보_stsd_다름", "S-prev", func(t *testing.T, dir string) string {
			path, _ := copyFile(t, fixtureTailPath, dir, "demo", "prev-first.mp4")
			return path
		}, realOwn, true, ""},
		{"후보_경로_빈_값", "S-prev", func(*testing.T, string) string { return "" }, realOwn, true, "prev_path_empty"},
		{"후보_루트_밖", "S-prev", func(t *testing.T, dir string) string {
			outside, _ := copyFile(t, fixture4sPath, t.TempDir(), "demo", "outside.mp4") // 지문은 같은 파일이다
			return outside
		}, realOwn, true, "prev_root_escape"},
		{"후보_다른_스트림_디렉토리", "S-prev", func(t *testing.T, dir string) string {
			path, _ := copyFile(t, fixture4sPath, dir, "other", "prev-first.mp4") // 지문은 같은 파일이다
			return path
		}, realOwn, true, "prev_dir_mismatch"},
		{"후보_직전_파일_부재", "S-prev", func(t *testing.T, dir string) string {
			return filepath.Join(dir, "demo", "janitor-deleted.mp4")
		}, realOwn, true, "prev_open_failed"},
		{"후보_직전_조각_판독_불가", "S-prev", func(t *testing.T, dir string) string {
			return writeSegment(t, dir, "demo", "prev-garbage.mp4", 64)
		}, realOwn, true, "prev_unreadable"},
		{"후보_자기_조각_판독_불가", "S-prev", sameStsd, func(t *testing.T, dir string) (string, int64) {
			return writeSegment(t, dir, "demo", "new-garbage.mp4", 64), 64
		}, true, "own_unreadable"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			st := &fakeUploadStore{}
			u, cap, dir, _ := newPlaybackUploader(t, st, &fakePutter{}, &fakeProducer{}, nil)
			ownPath, ownSize := tt.own(t, dir)
			target := index.UploadTarget{
				StreamID: "demo", Axis: index.AxisInit, SessionID: "S-new", LocalPath: ownPath, Bytes: ownSize,
				InheritsSession: tt.inherits, PrevFirstLocalPath: tt.prev(t, dir),
			}

			if got := runLive(u, target); got != outcomeSuccess {
				t.Fatalf("outcome = %v, want success — 게이트는 업로드를 막지 않는다 (%s)", got, cap.dump())
			}

			calls := st.initCalls()
			if len(calls) != 1 || calls[0].incompatible != tt.wantIncompatible {
				t.Errorf("MarkInitUploaded 의 incompatible = %+v, want 1회 %v", calls, tt.wantIncompatible)
			}
			declined := cap.find("session_inherit_declined")
			switch {
			case tt.wantReason == "" && len(declined) != 0:
				t.Errorf("session_inherit_declined = %d줄, want 0줄", len(declined))
			case tt.wantReason != "" && (len(declined) != 1 || declined[0].level != slog.LevelWarn ||
				declined[0].attrs["reason"] != tt.wantReason || declined[0].attrs["session_id"] != "S-new" ||
				declined[0].attrs["stream_id"] != "demo"):
				t.Errorf("session_inherit_declined = %+v, want WARN 1줄 reason=%s session_id=S-new stream_id=demo", declined, tt.wantReason)
			}
		})
	}
}
