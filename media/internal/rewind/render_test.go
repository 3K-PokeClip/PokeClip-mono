package rewind_test

// 되감기 목록 렌더(설계 4.8 매니페스트 실물 · 계획 4.2-R RF 끊김 표시)의 단위 검증 — POK-195 M4
// PR ⓑ 커밋 ②.
//
// g6_f2 의 기대값은 testdata/g6_f2.m3u8(900조각 전문)이다. 렌더 코드로 만들지 않았다 — 설계 4.8.1
// 수치만 읽는 독립 스크립트가 적었고, 설계 4.8.2 실물 발췌의 세 덩어리와는 축자로, 생략된 두
// 구간(631·264조각)과는 줄 수로 대조했다. 단 VERSION 줄만 kty 결정으로 6 이다(설계 4.8.2 의 9 에
// 대체 표식 · 계획 부기 32). 나머지 기대값은 손으로 적은 리터럴이다.
// 픽스처 번호는 설계가 이름을 준 G6 목록이다. G9 leaf 이름(f0_…·f6c_…)과 헷갈리지 않게 주석에
// g6_ 접두를 붙인다. 설계 r17 이 침묵한 번호는 설계 r7–r14 9절 G6 이 정의했던 것 가운데 판정이 같은
// 기존 테스트가 있는 f5·f8·f13·f14·f16 만 그 자리에 붙였다. f1·f3·f4(단일 세션 11조각 · GAP 1 · GAP
// 3연속의 골든 바이트)는 대응 테스트가 없어 비워 둔다.
//
// 외부 테스트 패키지로 두는 이유: 소비자(ⓒ 발행 · 커밋 ⑤ 축출 증분)가 쓰는 공개 계약만으로
// 성립하는지가 곧 렌더의 계약 검증이다.

import (
	"bytes"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// baseURL 은 픽스처 목록의 URI 앞머리다 — 설계 4.8.1 조각 URI 행의 호스트 그대로다.
const baseURL = "https://media.pokeclip.com"

// day 는 작은 픽스처 행의 PDT 기준 시각이다.
var day = time.Date(2026, 9, 24, 0, 0, 0, 0, time.UTC)

// row 는 settled 인 장부 행이다 — ③ 가 올라갔고 세 열(세션·PDT·③ 키)이 다 있다. 키는 장부가
// INSERT 때 예약해 둔 값의 모양(dvr/{stream}/seg/%06d.m4s)을 흉내 낸 픽스처 값이다.
func row(sessionID string, seq int64, pdt time.Time, durationMS int32) boundary.Row {
	return boundary.Row{
		Seq:              seq,
		SessionID:        sessionID,
		DurationMS:       durationMS,
		PlaybackPDT:      pdt,
		PlaybackS3Key:    fmt.Sprintf("dvr/str/seg/%06d.m4s", seq),
		PlaybackUploaded: true,
	}
}

// run 은 from 부터 to 까지(양끝 포함) 4초 조각 행이다. PDT 는 first 에서 4초씩 이어진다.
func run(sessionID string, from, to int64, first time.Time) []boundary.Row {
	var rows []boundary.Row
	for seq := from; seq <= to; seq++ {
		rows = append(rows, row(sessionID, seq, first.Add(time.Duration(seq-from)*4*time.Second), 4000))
	}
	return rows
}

// playlist 는 스트림 str 의 작은 목록이다(컷오프 0). 행 묶음은 seq 순서대로 넘긴다.
func playlist(owner string, sessions []rewind.Session, rows ...[]boundary.Row) rewind.Playlist {
	return rewind.Playlist{
		StreamID: "str",
		BaseURL:  baseURL,
		Owner:    owner,
		Sessions: sessions,
		Rows:     slices.Concat(rows...),
	}
}

// f2A·f2B 는 설계 4.8.1 의 두 회차다. B 는 120초 순단 뒤 300초 안에 다시 열려 A 를 계승했고
// A 의 discontinuity_base 3 을 그대로 옮겨 받았다(계획 PR ⓑ `discontinuity_base` 정의). A 는
// 계승하지 않은 회차다(착수 점검 (h) — 4.8.1 이 침묵해 픽스처가 못 박는다).
var (
	f2A = rewind.Session{ID: "S-20260831-0107", DiscontinuityBase: 3, TargetDuration: 6, MinSeq: 398}
	f2B = rewind.Session{
		ID: "S-20260831-0152", InheritsSession: "S-20260831-0107",
		DiscontinuityBase: 3, TargetDuration: 6, MinSeq: 1031,
	}
)

// f2Playlist 는 설계 4.8.1 전제 수치로 만든 B 회차의 목록이다 — 스트림 str_7a · 컷오프 12 ·
// A seq 398..1030(첫 PDT 01:07:59Z) · 120초 순단 · B seq 1031..1297(첫 PDT 01:52:11Z) · 4초 고정.
// seq 1297 은 ③ 가 올라가지 않았고 GAP 원장(upload_stall)에 있다. 창은 경계 테스트(g6_f2 경계 몫)가
// 센 [398, 1297] 그대로이고, 목록 첫 줄은 A 의 계승 백필이다.
func f2Playlist() rewind.Playlist {
	startA := time.Date(2026, 8, 31, 1, 7, 59, 0, time.UTC)
	startB := time.Date(2026, 8, 31, 1, 52, 11, 0, time.UTC)
	var rows []boundary.Row
	for seq := int64(398); seq <= 1297; seq++ {
		r := boundary.Row{
			Seq:              seq,
			DurationMS:       4000,
			PlaybackS3Key:    fmt.Sprintf("dvr/str_7a/seg/%06d.m4s", seq),
			PlaybackUploaded: true,
		}
		if seq <= 1030 {
			r.SessionID, r.PlaybackPDT = f2A.ID, startA.Add(time.Duration(seq-398)*4*time.Second)
		} else {
			r.SessionID, r.PlaybackPDT = f2B.ID, startB.Add(time.Duration(seq-1031)*4*time.Second)
		}
		rows = append(rows, r)
	}
	gap := &rows[len(rows)-1]
	gap.PlaybackUploaded, gap.IsGap = false, true
	return rewind.Playlist{
		StreamID: "str_7a",
		BaseURL:  baseURL,
		Cutoff:   12,
		Owner:    f2B.ID,
		Sessions: []rewind.Session{f2A, f2B},
		Rows:     rows,
	}
}

// mustRender 는 렌더가 성립해야 하는 입력을 렌더한다.
func mustRender(t *testing.T, p rewind.Playlist) string {
	t.Helper()
	got, err := rewind.Render(p)
	if err != nil {
		t.Fatalf("Render(stream=%q owner=%q, %d행) 오류: %v", p.StreamID, p.Owner, len(p.Rows), err)
	}
	return string(got)
}

// segment 는 렌더 본문의 조각 하나다 — URI 줄과 그 앞에 붙은 태그 줄들(순서 그대로).
type segment struct {
	tags []string
	uri  string
}

// headTags 는 목록 머리에만 서는 태그다(RFC 8216bis-22 4.4.1·4.4.3). 나머지 '#' 줄은 다음 URI
// 줄에 붙는 조각 태그로 읽는다(4.4.4).
var headTags = []string{
	"#EXTM3U", "#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:",
	"#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-DISCONTINUITY-SEQUENCE:",
}

// parse 는 렌더 본문을 머리 줄과 조각들로 가른다. 렌더 코드를 쓰지 않는 독립 해석이다 —
// '#' 로 시작하지 않는 줄이 URI 이고 그 앞의 태그 줄들이 그 조각에 붙는다(RFC 8216bis-22 4.1·4.4.4).
// 끝 줄바꿈 누락·빈 줄·조각 뒤의 머리 태그·URI 없이 끝나는 태그는 본문 결함으로 돌려준다.
func parse(body string) (head []string, segs []segment, err error) {
	if !strings.HasSuffix(body, "\n") {
		return nil, nil, fmt.Errorf("본문이 줄바꿈으로 끝나지 않는다")
	}
	var pending []string
	for i, line := range strings.Split(strings.TrimSuffix(body, "\n"), "\n") {
		isHead := slices.ContainsFunc(headTags, func(p string) bool { return strings.HasPrefix(line, p) })
		switch {
		case line == "":
			return nil, nil, fmt.Errorf("%d번째 줄이 비었다", i+1)
		case isHead && (len(segs) > 0 || len(pending) > 0):
			return nil, nil, fmt.Errorf("머리 태그 %q 가 조각 태그 뒤(%d번째 줄)에 나왔다", line, i+1)
		case isHead:
			head = append(head, line)
		case strings.HasPrefix(line, "#"):
			pending = append(pending, line)
		default:
			segs = append(segs, segment{tags: pending, uri: line})
			pending = nil
		}
	}
	if len(pending) > 0 {
		return nil, nil, fmt.Errorf("URI 없이 끝난 태그 %q", pending)
	}
	return head, segs, nil
}

// mustParse 는 렌더 본문을 해석한다. 해석 실패는 본문 결함이다.
func mustParse(t *testing.T, body string) ([]string, []segment) {
	t.Helper()
	head, segs, err := parse(body)
	if err != nil {
		t.Fatalf("렌더 본문 해석 실패: %v", err)
	}
	return head, segs
}

// tag 는 조각에 붙은 태그 가운데 prefix 로 시작하는 것들이다.
func (s segment) tag(prefix string) []string {
	var found []string
	for _, tg := range s.tags {
		if strings.HasPrefix(tg, prefix) {
			found = append(found, tg)
		}
	}
	return found
}

// seq 는 조각 URI 의 파일 이름(…/seg/NNNNNN.m4s)에서 seq 를 읽는다. 픽스처 키는 모두 이 모양이다.
func (s segment) seq(t *testing.T) int64 {
	t.Helper()
	n, err := strconv.ParseInt(strings.TrimSuffix(path.Base(s.uri), ".m4s"), 10, 64)
	if err != nil {
		t.Fatalf("조각 URI %q 에서 seq 를 못 읽었다: %v", s.uri, err)
	}
	return n
}

// taggedSeqs 는 EXT-X-DISCONTINUITY 가 붙은 조각의 seq 들이다.
func taggedSeqs(t *testing.T, segs []segment) []int64 {
	t.Helper()
	var seqs []int64
	for _, s := range segs {
		if slices.Contains(s.tags, "#EXT-X-DISCONTINUITY") {
			seqs = append(seqs, s.seq(t))
		}
	}
	return seqs
}

// headLine 은 머리 줄 가운데 prefix 로 시작하는 줄이다(없으면 "").
func headLine(head []string, prefix string) string {
	for _, l := range head {
		if strings.HasPrefix(l, prefix) {
			return l
		}
	}
	return ""
}

// firstDiff 는 두 본문이 처음 갈리는 줄을 적는다 — 900조각 전문을 통째로 찍지 않으려고.
func firstDiff(got, want string) string {
	g, w := strings.Split(got, "\n"), strings.Split(want, "\n")
	for i := range min(len(g), len(w)) {
		if g[i] != w[i] {
			return fmt.Sprintf("%d번째 줄 got %q, want %q (got %d줄, want %d줄)", i+1, g[i], w[i], len(g), len(w))
		}
	}
	return fmt.Sprintf("한쪽이 다른 쪽의 앞부분이다 (got %d줄, want %d줄)", len(g), len(w))
}

// g6_f2 — 설계 4.8.2 실물의 900조각 전문과 바이트 단위로 같다. 조각 URI 는 URL 이 영구히 고정되는
// 계약이라(프로필 4절) 형식이 아니라 바이트가 기대값이다 — 이 본문의 서식은 우리가 소유한다.
func TestG6F2RenderMatchesGolden(t *testing.T) {
	want, err := os.ReadFile(filepath.Join("testdata", "g6_f2.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}

	got := mustRender(t, f2Playlist())

	if !bytes.Equal([]byte(got), want) {
		t.Errorf("Render(f2) 가 골든 testdata/g6_f2.m3u8 과 다르다 — %s", firstDiff(got, string(want)))
	}
}

// g6_f2 — 설계 4.8.1 전제 수치를 렌더 본문에서 다시 센다(계획 6.2 f2 행 · 9절 G6 "전건 재검산").
// 골든과 별개로 의미를 잰다: MSN 398 · DISC-SEQ 3 · 900조각 · 조각마다 PDT · 4.000초 × 900 =
// 3,600.000초 · MAP 2(A·B 회차 첫 줄) · DISCONTINUITY 1(seq 1031 앞) · GAP 1(seq 1297 앞) ·
// 조각 URI 에 세션 축 없음(D1) · `#PC-` 줄 0 · 빈 줄 0(해석기가 잡는다).
// PDT 검산 네 점: 398 = 01:07:59 · 1030 = 01:07:59 + 632×4 = 01:50:07 · 1031 = 01:50:11 + 120초 =
// 01:52:11 · 1297 = 01:52:11 + 266×4 = 02:09:55.
func TestG6F2RenderMatchesDesignFigures(t *testing.T) {
	head, segs := mustParse(t, mustRender(t, f2Playlist()))

	wantHead := []string{
		"#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:6",
		"#EXT-X-MEDIA-SEQUENCE:398", "#EXT-X-DISCONTINUITY-SEQUENCE:3",
	}
	if !slices.Equal(head, wantHead) {
		t.Errorf("Render(f2) 머리 = %q, want %q", head, wantHead)
	}
	if len(segs) != 900 {
		t.Fatalf("Render(f2) 조각 수 = %d, want 900", len(segs))
	}
	wantPDT := map[int64]string{
		398:  "#EXT-X-PROGRAM-DATE-TIME:2026-08-31T01:07:59.000Z",
		1030: "#EXT-X-PROGRAM-DATE-TIME:2026-08-31T01:50:07.000Z",
		1031: "#EXT-X-PROGRAM-DATE-TIME:2026-08-31T01:52:11.000Z",
		1297: "#EXT-X-PROGRAM-DATE-TIME:2026-08-31T02:09:55.000Z",
	}
	var maps, discs, gaps []int64
	for i, s := range segs {
		seq := int64(398 + i)
		if want := fmt.Sprintf("https://media.pokeclip.com/dvr/str_7a/seg/%06d.m4s", seq); s.uri != want {
			t.Errorf("조각 %d URI = %q, want %q", seq, s.uri, want)
		}
		pdt := s.tag("#EXT-X-PROGRAM-DATE-TIME:")
		if len(pdt) != 1 {
			t.Errorf("조각 %d PDT 줄 = %q, want 정확히 한 줄", seq, pdt)
		} else if want, ok := wantPDT[seq]; ok && pdt[0] != want {
			t.Errorf("조각 %d PDT = %q, want %q", seq, pdt[0], want)
		}
		if inf := s.tag("#EXTINF:"); !slices.Equal(inf, []string{"#EXTINF:4.000,"}) {
			t.Errorf("조각 %d EXTINF = %q, want [#EXTINF:4.000,]", seq, inf)
		}
		if pc := s.tag("#PC-"); len(pc) > 0 {
			t.Errorf("조각 %d 앞에 #PC- 줄 %q — 본문은 순수 HLS 여야 한다", seq, pc)
		}
		if len(s.tag("#EXT-X-MAP:")) > 0 {
			maps = append(maps, seq)
		}
		if slices.Contains(s.tags, "#EXT-X-DISCONTINUITY") {
			discs = append(discs, seq)
		}
		if slices.Contains(s.tags, "#EXT-X-GAP") {
			gaps = append(gaps, seq)
		}
	}
	if want := []int64{398, 1031}; !slices.Equal(maps, want) {
		t.Errorf("MAP 이 붙은 조각 = %v, want %v", maps, want)
	}
	if got, want := segs[0].tag("#EXT-X-MAP:"), `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str_7a/init/S-20260831-0107.mp4"`; !slices.Equal(got, []string{want}) {
		t.Errorf("seq 398 MAP = %q, want [%s]", got, want)
	}
	if got, want := segs[1031-398].tag("#EXT-X-MAP:"), `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str_7a/init/S-20260831-0152.mp4"`; !slices.Equal(got, []string{want}) {
		t.Errorf("seq 1031 MAP = %q, want [%s]", got, want)
	}
	if want := []int64{1031}; !slices.Equal(discs, want) {
		t.Errorf("DISCONTINUITY 가 붙은 조각 = %v, want %v", discs, want)
	}
	if want := []int64{1297}; !slices.Equal(gaps, want) {
		t.Errorf("GAP 이 붙은 조각 = %v, want %v", gaps, want)
	}
}

// render_no_tag_inside_session_even_if_is_discontinuity(계획 4.2-R RF · 뮤테이션 7) — 회차 안에서는
// 끊김이 있어도 EXT-X-DISCONTINUITY 를 달지 않는다(kty 결정 F). 끊김은 PDT 가 뛴 것으로만 드러난다.
//
// 장부의 is_discontinuity 는 같은 회차 안 재접속(훅 판정)이나 벽시계 점프(허용치 초과 drift)에서
// 선다(indexer buildRecord). 렌더 입력 boundary.Row 에는 그 열이 아예 없어(커밋 ①) 렌더가 근거로
// 쓸 길이 타입으로 막혀 있다. 그래서 이 테스트는 그 열이 true 로 섰을 장부 국면 — 같은 회차 안에서
// 벽시계가 120초 뛴 seq 22 — 을 입력으로 준다. 회차 S 는 계승 회차지만 첫 조각(seq 10)이 이미 창에서
// 빠져 있어, 표시가 설 자리가 이 목록에 없다.
func TestRenderNoTagInsideSessionEvenIfIsDiscontinuity(t *testing.T) {
	s := rewind.Session{ID: "S", InheritsSession: "P", DiscontinuityBase: 1, TargetDuration: 6, MinSeq: 10}
	rows := slices.Concat(
		run("S", 20, 21, day),                      // 00:00:00 · 00:00:04
		run("S", 22, 24, day.Add(128*time.Second)), // 00:00:08 이어야 할 자리에 120초 점프
	)

	_, segs := mustParse(t, mustRender(t, playlist("S", []rewind.Session{s}, rows)))

	if len(segs) != 5 {
		t.Fatalf("조각 수 = %d, want 5", len(segs))
	}
	if got := taggedSeqs(t, segs); len(got) != 0 {
		t.Errorf("DISCONTINUITY 가 붙은 조각 = %v, want 없음(회차 안 끊김은 표시하지 않는다)", got)
	}
	if got, want := segs[2].tag("#EXT-X-PROGRAM-DATE-TIME:"), "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:02:08.000Z"; !slices.Equal(got, []string{want}) {
		t.Errorf("seq 22 PDT = %q, want [%s] — 끊김은 PDT 점프로 남아야 한다", got, want)
	}
}

// render_tag_only_at_session_boundary(계획 4.2-R RF) — 표시는 계승 회차의 첫 조각에만 선다.
//
//	DISCONTINUITY(k) ⟺ k = 자기 회차의 첫 조각 ∧ 그 회차의 inherits_session ≠ NULL
//	회차 첫 조각 = max(회차 최소 seq, cutoff)
//
// 회차 P = 비계승(최소 seq 10) · S = P 를 계승(최소 seq 13). 행 단독 술어라 S 의 첫 조각은 P 접두 뒤에
// 있든 목록 머리에 있든 같은 표시를 받는다(뮤테이션 55 — 목록 문맥 술어면 머리에서 표시가 빠진다).
// 컷오프가 회차 중간이면 회차 첫 조각은 컷오프 행이다(뮤테이션 63 — 컷오프 아래 행으로 판정하면
// 재기동 전후 판정이 갈린다).
func TestRenderTagOnlyAtSessionBoundary(t *testing.T) {
	p := rewind.Session{ID: "P", TargetDuration: 6, MinSeq: 10}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 6, MinSeq: 13}
	tests := []struct {
		name       string
		cutoff     int64
		playlist   rewind.Playlist
		wantTagged []int64
	}{
		{"계승_접두_뒤_계승_회차_첫_조각", 0,
			playlist("S", []rewind.Session{p, s}, run("P", 10, 12, day), run("S", 13, 15, day.Add(time.Minute))),
			[]int64{13}},
		{"비계승_회차_첫_조각", 0,
			playlist("P", []rewind.Session{p}, run("P", 10, 12, day)),
			nil},
		{"계승_회차_첫_조각이_목록_머리", 0,
			playlist("S", []rewind.Session{s}, run("S", 13, 15, day)),
			[]int64{13}},
		{"계승_회차_첫_조각이_창에서_빠짐", 0,
			playlist("S", []rewind.Session{s}, run("S", 14, 16, day)),
			nil},
		{"컷오프가_회차_중간", 14,
			playlist("S", []rewind.Session{s}, run("S", 14, 16, day)),
			[]int64{14}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.playlist.Cutoff = tt.cutoff

			_, segs := mustParse(t, mustRender(t, tt.playlist))

			if got := taggedSeqs(t, segs); !slices.Equal(got, tt.wantTagged) {
				t.Errorf("DISCONTINUITY 가 붙은 조각 = %v, want %v", got, tt.wantTagged)
			}
		})
	}
}

// render_tag_is_row_local(계획 4.2-R RF · 뮤테이션 55) — 표시는 행과 그 행의 회차만 보고 정해진다.
// 같은 행은 어느 목록에 실려도 같은 표시를 받는다. 그 행은 직전 회차 P 의 목록과 P 를 계승한 S 의
// 목록(P 접두 + S) 양쪽에 실리는 P 의 첫 조각 seq 10 이다. 입력은 300초 안 재접속이 두 번 난 연쇄
// 계승 O←P←S 이고 P 의 첫 조각이 창 안에 있다 — 그래서 S 목록에는 표시가 둘(P·S 의 첫 조각) 선다.
// 술어에 목록 소유 회차를 넘기면 S 목록의 seq 10 표시가 빠지고, 커밋 ⑤ 축출 증분(행의 회차로 센다)이
// 나간 표시 없이 DISC-SEQ 를 올린다.
// P·S 의 base 가 같은 0 인 것은 실입력 그대로다 — 계승 개시는 직전 회차의 현재 base 를 복사하고
// (계획 PR ⓑ `discontinuity_base` 정의) base 는 컷오프 뒤 발행된 목록에서 표시가 빠질 때만 오른다.
// O 의 base 는 0 이다(컷오프 0 뒤 seq 10 앞까지는 한 시간이 안 돼 어느 목록에서도 표시가 빠진 적이
// 없다). P 의 표시 조각은 아직 창 안이라 S 가 열린 뒤에도 빠진 표시가 없다. 그래서 두 머리의
// DISC-SEQ 도 같은 값이고, 머리가 소유 회차 값을 읽는지는 TestRenderHeaderComesFromOwnerSession 이 가른다.
func TestRenderTagIsRowLocal(t *testing.T) {
	p := rewind.Session{ID: "P", InheritsSession: "O", TargetDuration: 6, MinSeq: 10}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 6, MinSeq: 13}
	prefix := run("P", 10, 12, day)

	pHead, pSegs := mustParse(t, mustRender(t, playlist("P", []rewind.Session{p}, prefix)))
	sHead, sSegs := mustParse(t, mustRender(t,
		playlist("S", []rewind.Session{p, s}, prefix, run("S", 13, 15, day.Add(time.Minute)))))

	pTagged, sTagged := taggedSeqs(t, pSegs), taggedSeqs(t, sSegs)
	if inP, inS := slices.Contains(pTagged, 10), slices.Contains(sTagged, 10); !inP || !inS {
		t.Errorf("seq 10 표시 — P 목록 %v · S 목록 %v, want 둘 다 true(같은 행 = 같은 표시)", inP, inS)
	}
	if want := []int64{10}; !slices.Equal(pTagged, want) {
		t.Errorf("P 목록 DISCONTINUITY 조각 = %v, want %v", pTagged, want)
	}
	if want := []int64{10, 13}; !slices.Equal(sTagged, want) {
		t.Errorf("S 목록 DISCONTINUITY 조각 = %v, want %v", sTagged, want)
	}
	if len(sSegs) != 6 {
		t.Fatalf("S 목록 조각 수 = %d, want 6", len(sSegs))
	}
	var maps []string
	for _, sg := range sSegs {
		maps = append(maps, sg.tag("#EXT-X-MAP:")...)
	}
	wantMaps := []string{
		`#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/P.mp4"`,
		`#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/S.mp4"`,
	}
	if !slices.Equal(maps, wantMaps) || len(sSegs[0].tag("#EXT-X-MAP:")) != 1 || len(sSegs[3].tag("#EXT-X-MAP:")) != 1 {
		t.Errorf("S 목록 MAP = %q, want seq 10 · 13 앞에 %q", maps, wantMaps)
	}
	if got, want := headLine(pHead, "#EXT-X-DISCONTINUITY-SEQUENCE:"), "#EXT-X-DISCONTINUITY-SEQUENCE:0"; got != want {
		t.Errorf("P 목록 머리 %q, want %q(소유 회차 P 의 base)", got, want)
	}
	if got, want := headLine(sHead, "#EXT-X-DISCONTINUITY-SEQUENCE:"), "#EXT-X-DISCONTINUITY-SEQUENCE:0"; got != want {
		t.Errorf("S 목록 머리 %q, want %q(소유 회차 S 의 base)", got, want)
	}
}

// 목록 머리의 TD·DISC-SEQ 는 목록 소유 회차(되감기 URL 의 그 회차)의 값이다 — 목록 첫 줄이 계승 백필로
// 직전 회차 조각이어도 그렇다(계획 PR ⓑ in: cc r3 #3). DISC-SEQ 는 base 그대로이고 렌더는 아무것도
// 더하지 않는다. MSN 은 목록 첫 줄 조각의 seq 다(설계 4.8).
// 픽스처는 장부 쓰기 규칙대로다. 직전 회차 P 는 seq 49 의 8초 조각이 TD 분할로 연 회차다(계승 없음 ·
// TD 8 · base 는 직전 회차 값 복사). 방증이 낡아 미뤄진 컷오프는 seq 50 이 주조했다 — 49 는 컷오프
// 아래라 목록에 없고, 컷오프 전에는 목록이 없어 base 가 오른 적이 없다(P 는 0 을 복사했다). 소유 회차
// S 는 P 가 끝나고 48초 뒤 P 를 계승해 열렸다(P 의 base 0 복사 · 첫 조각 7초 → TD 7).
// 직전 회차 TD 8 이 소유 회차 TD 7 보다 커서, 첫 줄 회차의 값이나 회차 목록 전체의 최댓값으로 적은
// 머리가 드러난다 — 그런 머리는 접두가 창에서 빠질 때 TD 가 바뀐다(목록 수명 중 TD 불변 —
// Session.TargetDuration). 소유 회차 TD 7 은 DDL 기본 6 과도 겹치지 않는다. 두 회차의 base 가 같은
// 것은 실입력 그대로다 — P 는 계승 회차가 아니라 표시가 없고 S 의 표시 조각 53 은 아직 목록에 있다.
// base 를 기본값 0 이 아닌 값으로 읽는지는 g6_f2(DISC-SEQ 3)가 가른다. 둘째 경우는
// 소유 회차의 첫 조각이 아직 settled 가 아니어서 P 접두만 실린 목록이다 — 목록의 마지막 행도 P 다.
func TestRenderHeaderComesFromOwnerSession(t *testing.T) {
	p := rewind.Session{ID: "P", TargetDuration: 8, MinSeq: 49}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 7, MinSeq: 53}
	sRows := slices.Concat([]boundary.Row{row("S", 53, day.Add(time.Minute), 7000)}, run("S", 54, 55, day.Add(67*time.Second)))
	tests := []struct {
		name string
		rows [][]boundary.Row
	}{
		{"계승_접두_뒤_소유_회차_행", [][]boundary.Row{run("P", 50, 52, day), sRows}},
		{"소유_회차_행_없이_접두만", [][]boundary.Row{run("P", 50, 52, day)}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			head, _ := mustParse(t, mustRender(t, withCutoff(playlist("S", []rewind.Session{p, s}, tt.rows...), 50)))

			want := []string{
				"#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:7",
				"#EXT-X-MEDIA-SEQUENCE:50", "#EXT-X-DISCONTINUITY-SEQUENCE:0",
			}
			if !slices.Equal(head, want) {
				t.Errorf("Render(owner=S) 머리 = %q, want %q", head, want)
			}
		})
	}
}

// GAP 원장에 있는 행은 EXT-X-GAP 을 달고, PDT·EXTINF·URI 줄은 그대로 싣는다(설계 4.8.2 seq 1297 ·
// RFC 8216bis-22 6.2.1 — GAP 조각도 길이를 채워 EXTINF 시간축을 잇는다). ③ 가 뒤늦게 올라갔어도
// 원장에 있으면 GAP 이다(설계 4.5.4 "GAP 원장 우선" — 이미 발행한 줄은 바꿀 수 없다).
func TestRenderGapLineKeepsPDTAndURI(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 30}
	tests := []struct {
		name     string
		uploaded bool
	}{
		{"미업로드_GAP", false},
		{"업로드_뒤에도_GAP_원장_우선", true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := run("N", 30, 32, day)
			rows[1].PlaybackUploaded, rows[1].IsGap = tt.uploaded, true

			_, segs := mustParse(t, mustRender(t, playlist("N", []rewind.Session{n}, rows)))

			if len(segs) != 3 {
				t.Fatalf("조각 수 = %d, want 3", len(segs))
			}
			want := segment{
				tags: []string{"#EXT-X-GAP", "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z", "#EXTINF:4.000,"},
				uri:  "https://media.pokeclip.com/dvr/str/seg/000031.m4s",
			}
			if got := segs[1]; !slices.Equal(got.tags, want.tags) || got.uri != want.uri {
				t.Errorf("seq 31 조각 = %+v, want %+v", got, want)
			}
			for _, i := range []int{0, 2} {
				if slices.Contains(segs[i].tags, "#EXT-X-GAP") {
					t.Errorf("seq %d 에 GAP 이 붙었다 — 원장에 없는 행이다", 30+i)
				}
			}
		})
	}
}

// EXTINF 는 duration_ms 를 초로 적되 소수 셋째 자리까지 그대로 싣는다 — 반올림·절삭 없이 ms 가 곧
// 자릿수다. 4012·2042 는 playback 실물 조각(segment_4s·segment_tail_2s) 길이다. PDT 도 ms 까지
// 싣는다(RFC 8216bis-22 4.4.4.6 "to at least millisecond accuracy").
// 회차 N 은 seq 1 의 10,001ms 조각이 연 회차라 TD 10(= max(6, round(10.001)))이고, 뒤 조각은
// 반올림 길이가 모두 10 이하라 TD 분할이 없다(장부 개시·분할 규칙).
func TestRenderExtinfIsMillisecondsToThreeDecimals(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 10, MinSeq: 1}
	durations := []int32{10001, 4012, 2042, 4000, 999}
	wantExtinf := []string{"#EXTINF:10.001,", "#EXTINF:4.012,", "#EXTINF:2.042,", "#EXTINF:4.000,", "#EXTINF:0.999,"}
	wantPDT := []string{
		"#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.000Z",
		"#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:10.001Z",
		"#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:14.013Z",
		"#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:16.055Z",
		"#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:20.055Z",
	}
	var rows []boundary.Row
	pdt := day
	for i, d := range durations {
		rows = append(rows, row("N", int64(1+i), pdt, d))
		pdt = pdt.Add(time.Duration(d) * time.Millisecond)
	}

	_, segs := mustParse(t, mustRender(t, playlist("N", []rewind.Session{n}, rows)))

	if len(segs) != len(durations) {
		t.Fatalf("조각 수 = %d, want %d", len(segs), len(durations))
	}
	for i, s := range segs {
		if got := s.tag("#EXTINF:"); !slices.Equal(got, wantExtinf[i:i+1]) {
			t.Errorf("duration_ms %d 의 EXTINF = %q, want %q", durations[i], got, wantExtinf[i])
		}
		if got := s.tag("#EXT-X-PROGRAM-DATE-TIME:"); !slices.Equal(got, wantPDT[i:i+1]) {
			t.Errorf("seq %d PDT = %q, want %q", i+1, got, wantPDT[i])
		}
	}
}

// PDT 는 장부 값의 시간대(Location)와 무관하게 UTC 로 싣는다 — 같은 행이면 프로세스 시간대가 달라도
// 본문 바이트가 같아야 한다. 픽스처는 같은 순간을 +09:00 시간대로 준다.
func TestRenderPDTIsUTCWhateverTheRowLocation(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 1}
	kst := time.FixedZone("KST", 9*60*60)
	rows := []boundary.Row{row("N", 1, time.Date(2026, 9, 24, 9, 0, 0, 250_000_000, kst), 4000)}

	_, segs := mustParse(t, mustRender(t, playlist("N", []rewind.Session{n}, rows)))

	if len(segs) != 1 {
		t.Fatalf("조각 수 = %d, want 1", len(segs))
	}
	if got, want := segs[0].tag("#EXT-X-PROGRAM-DATE-TIME:"), "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:00.250Z"; !slices.Equal(got, []string{want}) {
		t.Errorf("PDT(+09:00 입력) = %q, want [%s]", got, want)
	}
}

// 조각 URI = base URL + '/' + 장부의 playback_s3_key 그대로, MAP URI = base URL + '/' + init 키
// (dvr/{stream}/init/{session}.mp4)다. 조각 키를 렌더가 다시 만들지 않는 것이 계약이다 — ③ PUT 은
// INSERT 때 예약한 키를 재계산 없이 쓰므로(index.UploadTarget.S3Key) 목록이 가리킬 객체도 그 키다.
// 그래서 픽스처 키는 SegKey 가 만들지 않는 모양(seq 7 → "7.m4s")으로 준다.
func TestRenderJoinsBaseURLAndStoredKey(t *testing.T) {
	r := row("S-1", 7, day, 4000)
	r.PlaybackS3Key = "dvr/str_7a/seg/7.m4s"
	p := rewind.Playlist{
		StreamID: "str_7a",
		BaseURL:  "https://cdn.example.test",
		Owner:    "S-1",
		Sessions: []rewind.Session{{ID: "S-1", TargetDuration: 6, MinSeq: 7}},
		Rows:     []boundary.Row{r},
	}

	_, segs := mustParse(t, mustRender(t, p))

	if len(segs) != 1 {
		t.Fatalf("조각 수 = %d, want 1", len(segs))
	}
	if want := "https://cdn.example.test/dvr/str_7a/seg/7.m4s"; segs[0].uri != want {
		t.Errorf("조각 URI = %q, want %q", segs[0].uri, want)
	}
	if got, want := segs[0].tag("#EXT-X-MAP:"), `#EXT-X-MAP:URI="https://cdn.example.test/dvr/str_7a/init/S-1.mp4"`; !slices.Equal(got, []string{want}) {
		t.Errorf("MAP = %q, want [%s]", got, want)
	}
}

// 렌더할 수 없는 입력은 본문 없이 오류다 — 조용히 이상한 목록을 만들면 발행 뒤 되돌릴 수 없다
// (발행된 줄은 바꿀 수 없고 조각 URL 은 영구 고정이다). 기준 목록은 P 접두 + S(계승) 여섯 행이고
// 각 경우는 한 곳만 바꾼다.
func TestRenderRejectsUnrenderableInput(t *testing.T) {
	p := rewind.Session{ID: "P", TargetDuration: 6, MinSeq: 10}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 6, MinSeq: 13}
	base := func() rewind.Playlist {
		return playlist("S", []rewind.Session{p, s}, run("P", 10, 12, day), run("S", 13, 15, day.Add(time.Minute)))
	}
	tests := []struct {
		name   string
		change func(pl *rewind.Playlist)
	}{
		// MSN 은 목록 첫 줄 조각의 seq 다(설계 4.8) — 줄이 없으면 정의되지 않는다.
		{"빈_목록", func(pl *rewind.Playlist) { pl.Rows = nil }},
		// 상대 URI 를 쓰지 않는다(설계 4.8.2 D1 ②).
		{"base_URL_없음", func(pl *rewind.Playlist) { pl.BaseURL = "" }},
		// '//' 가 끼면 다른 객체 경로가 된다.
		{"base_URL_끝_슬래시", func(pl *rewind.Playlist) { pl.BaseURL = baseURL + "/" }},
		{"소유_회차가_회차_목록에_없음", func(pl *rewind.Playlist) { pl.Owner = "X" }},
		{"행의_회차가_회차_목록에_없음", func(pl *rewind.Playlist) { pl.Sessions = []rewind.Session{s} }},
		{"행의_회차_NULL", func(pl *rewind.Playlist) { pl.Rows[4].SessionID = "" }},
		// 목록 안 seq 는 이어져야 한다(설계 4.8 MSN 성립 조건 ② — GAP 이 메운다).
		{"seq_건너뜀", func(pl *rewind.Playlist) { pl.Rows = slices.Delete(pl.Rows, 3, 4) }},
		{"seq_역행", func(pl *rewind.Playlist) { pl.Rows[4], pl.Rows[5] = pl.Rows[5], pl.Rows[4] }},
		// init 키 파생 실패(URL 에 실을 수 없는 스트림 ID).
		{"MAP_키_파생_실패", func(pl *rewind.Playlist) { pl.StreamID = "a/b" }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pl := base()
			tt.change(&pl)

			got, err := rewind.Render(pl)

			if err == nil || got != nil {
				t.Errorf("Render(%s) = %d바이트, %v; want nil, 오류", tt.name, len(got), err)
			}
		})
	}
}
