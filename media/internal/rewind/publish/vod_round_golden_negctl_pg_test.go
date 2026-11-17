package publish

// 회차 VOD 골든(rewind/testdata/vod_round_td_split.m3u8 — POK-549 실험 E1)의 장부 되살리기 도우미다(계획 C4). 골든은 prod 의
// TD 분할 회차 하나 — 앞 사슬(seq 4–1626 · TD 6 · GAP 줄 셋)과 마지막 사슬(1627–1733 · TD 8)의 두 VOD 문서를 렌더 코드 밖에서
// 이어 붙여(접합 oracle) 스트림 id · 호스트만 익명화한 것이다. 기존 기호만 부른다 — 제품 코드를 기준(a9c39331)으로 되돌린
// 음성 대조 트리에서도 컴파일돼야 한다(K9 · K23).

import (
	"bytes"
	"fmt"
	"log/slog"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// 골든의 식별자 — 익명화한 값 그대로다(E1).
const (
	goldenStream  = "round-golden"
	goldenBaseURL = "https://cdn.test"
	// goldenFront 는 앞 사슬 세션이자 회차 머리(회차 id)다.
	goldenFront = "S-20261104-031754-round-golden-4"
	// goldenLast 는 마지막 사슬 세션이다(TD 분할로 끊겨 계승 없음).
	goldenLast = "S-20261104-050857-round-golden-1627"
	// goldenRoundKey 는 회차 문서 키다(K-c — vod/{stream}/{round}.round/index.m3u8).
	goldenRoundKey = "vod/round-golden/S-20261104-031754-round-golden-4.round/index.m3u8"
	// goldenFrontKey 는 앞 사슬 문서 키다(머리 세션).
	goldenFrontKey = "vod/round-golden/S-20261104-031754-round-golden-4/index.m3u8"
)

// goldenTD 는 세션별 target_duration 이다 — 두 prod 문서 머리의 값이다(E1).
var goldenTD = map[string]int32{goldenFront: 6, goldenLast: 8}

// goldenStarted 는 세션별 started_at 이다 — 세션 id 의 개시 시각이다.
var goldenStarted = map[string]time.Time{
	goldenFront: time.Date(2026, 11, 4, 3, 17, 54, 0, time.UTC),
	goldenLast:  time.Date(2026, 11, 4, 5, 8, 57, 0, time.UTC),
}

// goldenSeg 는 골든의 조각 하나다 — 장부 행 값과 GAP 줄인가다.
type goldenSeg struct {
	row boundary.Row
	gap bool
}

// readRoundGolden 은 골든 본문과 그 조각들(seq 순)이다.
func readRoundGolden(t *testing.T) ([]byte, []goldenSeg) {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("..", "testdata", "vod_round_td_split.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}
	segs, err := parseRoundGolden(body)
	if err != nil {
		t.Fatalf("골든 해석 실패: %v", err)
	}
	return body, segs
}

// parseRoundGolden 은 골든 본문을 조각들로 읽는다 — 세션은 직전 MAP 의 init 키, 값은 PDT · EXTINF · URI 줄이다. seq 는 1씩
// 이어져야 한다(렌더 계약 — 빈자리는 GAP 줄이 메운다).
func parseRoundGolden(body []byte) ([]goldenSeg, error) {
	mapPrefix := `#EXT-X-MAP:URI="` + goldenBaseURL + "/dvr/" + goldenStream + "/init/"
	var (
		segs []goldenSeg
		cur  goldenSeg
	)
	for n, line := range strings.Split(string(body), "\n") {
		var err error
		switch {
		case strings.HasPrefix(line, mapPrefix):
			cur.row.SessionID = strings.TrimSuffix(strings.TrimPrefix(line, mapPrefix), `.mp4"`)
		case line == "#EXT-X-GAP":
			cur.gap = true
		case strings.HasPrefix(line, "#EXT-X-PROGRAM-DATE-TIME:"):
			cur.row.PlaybackPDT, err = time.Parse(time.RFC3339Nano, strings.TrimPrefix(line, "#EXT-X-PROGRAM-DATE-TIME:"))
		case strings.HasPrefix(line, "#EXTINF:"):
			cur.row.DurationMS, err = extinfMS(line)
		case line != "" && !strings.HasPrefix(line, "#"):
			cur.row, err = withSegURI(cur.row, line)
			if err == nil && len(segs) > 0 && cur.row.Seq != segs[len(segs)-1].row.Seq+1 {
				err = fmt.Errorf("seq %d 다음에 %d", segs[len(segs)-1].row.Seq, cur.row.Seq)
			}
			segs = append(segs, cur)
			cur = goldenSeg{row: boundary.Row{SessionID: cur.row.SessionID}}
		}
		if err != nil {
			return nil, fmt.Errorf("%d행 %q: %w", n+1, line, err)
		}
	}
	return segs, nil
}

// extinfMS 는 EXTINF 줄(초 · 소수 셋째 자리)의 밀리초다.
func extinfMS(line string) (int32, error) {
	sec, ms, ok := strings.Cut(strings.TrimSuffix(strings.TrimPrefix(line, "#EXTINF:"), ","), ".")
	if !ok || len(ms) != 3 {
		return 0, fmt.Errorf("EXTINF 꼴이 초.밀리초 셋째 자리가 아니다")
	}
	v, err := strconv.ParseInt(sec+ms, 10, 32)
	return int32(v), err
}

// withSegURI 는 조각 URI 줄의 ③ 키 · seq 를 r 에 채운 값이다.
func withSegURI(r boundary.Row, uri string) (boundary.Row, error) {
	key, ok := strings.CutPrefix(uri, goldenBaseURL+"/")
	if !ok {
		return r, fmt.Errorf("URI 앞머리가 %s 가 아니다", goldenBaseURL)
	}
	seq, err := strconv.ParseInt(strings.TrimSuffix(path.Base(key), ".m4s"), 10, 64)
	r.Seq, r.PlaybackS3Key, r.PlaybackUploaded = seq, key, true
	return r, err
}

// newRoundGolden 은 골든 장부를 넣은 픽스처다 — 회차(머리 goldenFront · 닫힘)의 두 사슬이 모두 ending · DVR 동결 끝 · VOD
// freezing 이다. GAP 줄 조각은 ③ 미착지(pending)와 DVR GAP 원장으로, 나머지는 ③ 확정으로 넣는다. 컷오프는 0 이다.
func newRoundGolden(t *testing.T, segs []goldenSeg) (*Publisher, *fakeStore) {
	t.Helper()
	pool := newPool(t)
	for _, id := range []string{goldenFront, goldenLast} {
		exec(t, pool, `
			INSERT INTO stream_sessions (session_id, stream_id, started_at, state, init_uploaded_at, target_duration,
			                             dvr_state, vod_state, round_id, round_closed_at)
			VALUES ($1, $2, $3, 'ending', now(), $4, 'frozen', 'freezing', $5, $6)`,
			id, goldenStream, goldenStarted[id], goldenTD[id], goldenFront, goldenStarted[goldenLast].Add(time.Hour))
	}
	for _, s := range segs {
		state := "uploaded"
		if s.gap {
			state = "pending"
			exec(t, pool, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ($1, $2, $3, 'upload_stall')`,
				goldenStream, s.row.Seq, s.row.SessionID)
		}
		exec(t, pool, `
			INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
			                             session_id, playback_pdt, playback_s3_key, playback_upload_state)
			VALUES ($1, $2, 0, now(), $3, $4, $5, $6, $7, $8)`,
			goldenStream, s.row.Seq, s.row.DurationMS, fmt.Sprintf("archive/%s/%06d.mp4", goldenStream, s.row.Seq),
			s.row.SessionID, s.row.PlaybackPDT, s.row.PlaybackS3Key, state)
	}
	exec(t, pool, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
		VALUES ($1, 0, 'live_ingress', 'watcher')`, goldenStream)
	store := newFakeStore(t)
	opt := DefaultOptions("w-me", goldenBaseURL)
	opt.Log = slog.New(&logRecorder{})
	p, err := New(pool, store, opt)
	if err != nil {
		t.Fatalf("New 실패: %v", err)
	}
	return p, store
}

// goldenChain 은 골든 회차의 사슬 하나(세션 id 하나)다.
func goldenChain(id string) index.Chain {
	return index.Chain{StreamID: goldenStream, RoundID: goldenFront, Head: id, Tail: id, SessionIDs: []string{id}}
}

// wantGoldenBody 는 store 의 회차 문서가 골든과 바이트로 같은지 보고 그 본문을 돌려준다. 다르면 첫 차이 줄을 싣는다(계획
// P10 — 원인 조사의 출발점).
func wantGoldenBody(t *testing.T, store *fakeStore, golden []byte) []byte {
	t.Helper()
	body, _ := stored(store, goldenRoundKey)
	if body == nil {
		var keys []string
		for _, c := range store.putCalls() {
			keys = append(keys, c.key)
		}
		t.Fatalf("회차 문서 %s 가 없다 — PUT 키 %q", goldenRoundKey, keys)
	}
	if bytes.Equal(body, golden) {
		return body
	}
	got, want := strings.Split(string(body), "\n"), strings.Split(string(golden), "\n")
	for i := range min(len(got), len(want)) {
		if got[i] != want[i] {
			t.Errorf("회차 문서가 골든과 %d행에서 갈린다: got %q, want %q", i+1, got[i], want[i])
			return body
		}
	}
	t.Errorf("회차 문서 %d행 · %dB, want 골든 %d행 · %dB(앞 줄은 같다)", len(got), len(body), len(want), len(golden))
	return body
}

// wantGoldenSplice 는 설계 10.1 판별이다 — 골든(회차 문서)은 앞 사슬 문서 front 와 마지막 사슬 문서를 이은 것이고 차이는
// 정확히 셋이다: ① 머리 TARGETDURATION 8(앞 문서 6 · 나머지 머리 줄은 한 벌 그대로) ② 앞 문서 ENDLIST 와 마지막 사슬 문서
// 머리가 빠짐 ③ 마지막 사슬 MAP 앞 DISCONTINUITY 한 줄. 그래서 골든을 그 DISCONTINUITY 줄 앞에서 자르고 TD 를 6 으로 돌린 뒤
// ENDLIST 를 붙이면 앞 사슬 문서와 바이트로 같아야 한다(R20 — 앞 범위 줄이 앞 문서와 바이트 동일).
func wantGoldenSplice(t *testing.T, front, golden []byte) {
	t.Helper()
	lines := strings.Split(string(golden), "\n")
	cut := -1
	for i, line := range lines {
		if line == "#EXT-X-DISCONTINUITY" {
			if cut >= 0 {
				t.Fatalf("골든 DISCONTINUITY 가 %d행 · %d행 둘 이상이다", cut+1, i+1)
			}
			cut = i
		}
	}
	lastMAP := `#EXT-X-MAP:URI="` + goldenBaseURL + "/dvr/" + goldenStream + "/init/" + goldenLast + `.mp4"`
	if cut < 0 || lines[2] != "#EXT-X-TARGETDURATION:8" || lines[cut+1] != lastMAP {
		t.Fatalf("골든 꼴 — DISCONTINUITY %d행 · 머리 TD 줄 %q · 다음 줄 %q, want 하나 · TD 8 · 마지막 사슬 MAP", cut+1, lines[2], lines[cut+1])
	}
	head := append([]string{}, lines[:cut]...)
	head[2] = "#EXT-X-TARGETDURATION:6"
	want := strings.Join(append(head, "#EXT-X-ENDLIST", ""), "\n")
	if string(front) != want {
		t.Errorf("앞 사슬 문서 %dB 가 골든 앞 범위(%d행 · TD 6 · ENDLIST) %dB 와 다르다", len(front), cut, len(want))
	}
}
