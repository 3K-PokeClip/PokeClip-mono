package index

import (
	"encoding/csv"
	"encoding/hex"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 실측 1 — 문턱 재생 시험(설계 2.9 · 10.4 · 계획 4절 c3b · 9절). r6 실측 표를 mixV2 에 흘려 θ 후보마다
// (a)–(f) 를 낸다. 결정적이라 한 번 돌리면 답이 정해진다(-count=50 은 결정성 확인).
//
// 입력(testdata/pdt_replay/ — 원본 경로 · sha256 은 _workspace/pdt_c3b_bench/ 기록):
//   - 판정 입력: OBS 실송출 조각 표 obs_{x264,vt}_run_table.csv(60_pdt_r6_measure/obs/raw/*/out/
//     run_table.csv 사본 — 16 접속 · 232 조각). 접속 안은 같은 녹화기 이어짐, 접속 사이는 실제 틈이다.
//   - 주입표 injections.csv: 접속마다 소리 머리 가운데 행에 표식 바뀜 · mtxi 지움, 소리 머리 · 영상 머리
//     가운데 행에 구멍 g ∈ {1, 17, 30, 50}ms(뽑는 규칙은 bench tools/make_injections.py).
//   - 사건 단언 · 참고 입력: rec_t9*_table.csv(rec/raw/out 사본 — ffmpeg 합성) · t9vm_*_table.csv
//     (t9vm scan.jsonl 을 tools/t9vm_to_replay.py 로 run_table 꼴로 바꾼 것 — 머리는 트랙 첫 표본
//     시작에서 ReadTiming 과 같은 규칙으로 정했고 keep 대표 조각 8개의 ReadTiming 판독과 같다).
//   - 사건 단언표 events.csv.
//
// 겹침 갈래는 K1 = (나) 1초 미만 부분 겹침 · 한 번 당김 999ms 다(kty 2026-11-03).

// replayThetas 는 θ 후보다(설계 2.9).
var replayThetas = []int64{50, 80, 100, 120, 140, 150, 160, 180}

// replayDir 는 재생 입력 자리다.
const replayDir = "testdata/pdt_replay"

// replaySeg 는 조각 표 한 행이다. 축 값은 녹화기 인스턴스 누적 DTS(ns)다.
type replaySeg struct {
	file      string
	conn      int
	recorder  [16]byte
	number    int64
	headVideo bool
	hasVideo  bool
	wiped     bool // mtxi 지움 주입 — 원래 사실 칸 넷을 비운다
	aNS, bNS  int64
	wMS       int64
	legacyMS  int32
}

// loadReplay 는 run_table 꼴 표를 읽는다. legacy 열(mvhd ms)이 없으면 영상 길이를 반올림해 쓴다 —
// 영상이 있는 행은 mvhd 를 쓰지 않는다(설계 2.6).
func loadReplay(t *testing.T, name string) []replaySeg {
	t.Helper()
	f, err := os.Open(filepath.Join(replayDir, name))
	if err != nil {
		t.Fatalf("재생 입력 열기: %v", err)
	}
	defer f.Close()
	recs, err := csv.NewReader(f).ReadAll()
	if err != nil || len(recs) < 2 {
		t.Fatalf("재생 입력 %s 읽기: %v(행 %d)", name, err, len(recs))
	}
	col := map[string]int{}
	for i, h := range recs[0] {
		col[h] = i
	}
	get := func(r []string, k string) string {
		if i, ok := col[k]; ok {
			return r[i]
		}
		return ""
	}
	segs := make([]replaySeg, 0, len(recs)-1)
	for _, r := range recs[1:] {
		s, err := parseReplaySeg(get(r, "file"), get(r, "conn"), get(r, "stream_id"), get(r, "number"),
			get(r, "head"), get(r, "a"), get(r, "v_dur"), get(r, "w"), get(r, "legacy"))
		if err != nil {
			t.Fatalf("재생 입력 %s 행 %v: %v", name, r[:1], err)
		}
		segs = append(segs, s)
	}
	return segs
}

// parseReplaySeg 는 표 한 행의 글자 칸을 값으로 바꾼다.
func parseReplaySeg(file, conn, streamID, number, head, a, vDur, w, legacy string) (replaySeg, error) {
	s := replaySeg{file: file, headVideo: head == "V"}
	var err error
	if s.conn, err = strconv.Atoi(conn); err != nil {
		return s, fmt.Errorf("conn: %w", err)
	}
	if s.number, err = strconv.ParseInt(number, 10, 64); err != nil {
		return s, fmt.Errorf("number: %w", err)
	}
	id, err := hex.DecodeString(streamID)
	if err != nil || len(id) == 0 || len(id) > 16 {
		return s, fmt.Errorf("stream_id %q: %v", streamID, err)
	}
	copy(s.recorder[:], id)
	wf, err := strconv.ParseFloat(w, 64)
	if err != nil {
		return s, fmt.Errorf("w: %w", err)
	}
	s.wMS = int64(math.Floor(wf))
	if a != "" {
		af, err1 := strconv.ParseFloat(a, 64)
		df, err2 := strconv.ParseFloat(vDur, 64)
		if err1 != nil || err2 != nil {
			return s, fmt.Errorf("a · v_dur: %v %v", err1, err2)
		}
		s.hasVideo = true
		s.aNS, s.bNS = int64(math.Round(af*1e6)), int64(math.Round((af+df)*1e6))
		s.legacyMS = int32(math.Round(df))
	}
	if legacy != "" {
		l, err := strconv.ParseInt(legacy, 10, 32)
		if err != nil {
			return s, fmt.Errorf("legacy: %w", err)
		}
		s.legacyMS = int32(l)
	}
	return s, nil
}

// input 은 조각 표 한 행을 섞기 재료로 바꾼다. 원래 사실 칸 규칙(설계 2.3): mtxi 가 없으면 표식 ·
// 번호 · 영상 칸이 모두 nil 이다.
func (s replaySeg) input() TimingInput {
	in := TimingInput{Facts: TimingFacts{Wall: time.UnixMilli(s.wMS)}, HeadVideo: s.headVideo, LegacyDurationMS: s.legacyMS}
	if s.hasVideo {
		in.VideoOwnMS = int32(s.bNS/1e6 - s.aNS/1e6)
	}
	if s.wiped {
		return in
	}
	rec, num := s.recorder, s.number
	in.Facts.Recorder, in.Facts.Number = &rec, &num
	if s.hasVideo {
		a, b := time.Duration(s.aNS), time.Duration(s.bNS)
		in.Facts.VideoFirst, in.Facts.VideoEnd = &a, &b
	}
	return in
}

// replayRow 는 재생 한 행의 입력 · 장부 행 · 결과다.
type replayRow struct {
	seg replaySeg
	row explainRow
	out TimingV2
	e   int64 // 같은 회차 앞 행의 끝 E(회차 첫 행이면 0)
}

// replayMode 는 머리를 어떻게 주는가다. 참고 재생 둘은 출력만 한다(설계 2.9 「참고 재생」).
type replayMode int

const (
	modeHead      replayMode = iota // 표의 머리 그대로
	modeAll                         // 참고 — 모든 조각 견줌
	modeEstimated                   // 참고 — x 부호 교대로 머리 추정
)

// replay 는 표 전체를 한 스트림 · 한 회차로 흘린다(재접속은 같은 회차를 잇는다). 앞 행 p = q = 바로
// 앞 결과 행이다.
func replay(segs []replaySeg, thetaMS int64, mode replayMode) []replayRow {
	rows := make([]replayRow, 0, len(segs))
	heads := replayHeads(segs, mode)
	for i, s := range segs {
		in := s.input()
		in.HeadVideo = heads[i]
		v := LedgerView{Round: &mixRound, NewRound: i == 0}
		var e int64
		if i > 0 {
			prev := rows[i-1].row
			v.Prev, v.Last = &prev.PrevRow, mixLast(prev.StartPTSMS, prev.DurationMS)
			e = *prev.PDTMS + int64(prev.DurationMS)
		}
		out := mixV2(v, in, time.Duration(thetaMS)*time.Millisecond)
		pdt := out.PDTMS
		row := explainRow{PrevRow: PrevRow{Seq: int64(i), StartPTSMS: out.StartPTSMS, DurationMS: out.DurationMS,
			PDTMS: &pdt, Version: new(versionV2), Facts: in.Facts}, Round: &mixRound, LegacyMS: in.LegacyDurationMS}
		rows = append(rows, replayRow{seg: s, row: row, out: out, e: e})
	}
	return rows
}

// replayHeads 는 행마다 섞기에 넘길 머리(영상인가)다. modeEstimated 는 같은 녹화기 이웃의 x =
// (w(k) − w(k−1)) − (a(k) − a(k−1)) 가 −90ms 보다 작으면 영상 머리로, +90ms 보다 크면 소리 머리로
// 바뀌었다고 본다(머리 교대 톱니 ±105–391ms — 설계 2.9 · t9vm REPORT). 새 녹화기 첫 조각은 소리다.
func replayHeads(segs []replaySeg, mode replayMode) []bool {
	heads := make([]bool, len(segs))
	video := false
	for i, s := range segs {
		switch mode {
		case modeHead:
			heads[i] = s.headVideo
			continue
		case modeAll:
			heads[i] = true
			continue
		}
		if i == 0 || segs[i-1].recorder != s.recorder || !s.hasVideo || !segs[i-1].hasVideo {
			video = false
		} else {
			x := (s.wMS - segs[i-1].wMS) - (s.aNS-segs[i-1].aNS)/1e6
			if x < -90 {
				video = true
			} else if x > 90 {
				video = false
			}
		}
		heads[i] = video
	}
	return heads
}

// injection 은 주입표 한 행이다.
type injection struct {
	table, kind, file string
	gMS               int64
}

// loadInjections 는 주입표를 읽는다.
func loadInjections(t *testing.T) []injection {
	t.Helper()
	f, err := os.Open(filepath.Join(replayDir, "injections.csv"))
	if err != nil {
		t.Fatalf("주입표 열기: %v", err)
	}
	defer f.Close()
	recs, err := csv.NewReader(f).ReadAll()
	if err != nil {
		t.Fatalf("주입표 읽기: %v", err)
	}
	var out []injection
	for _, r := range recs[1:] {
		g, err := strconv.ParseInt(r[3], 10, 64)
		if err != nil {
			t.Fatalf("주입표 g_ms %q: %v", r[3], err)
		}
		out = append(out, injection{table: r[0], kind: r[1], file: r[2], gMS: g})
	}
	return out
}

// inject 는 segs 의 사본에 주입을 건다(원본은 그대로 둔다).
//   - marker: 그 행부터 접속 끝까지 새 녹화기(표식 바뀜 · 번호 0 부터) · 틈 0(드리프트 재설정 흉내).
//   - wipe: 그 행의 표식 · 번호 · 영상 칸을 비운다(mtxi 없음).
//   - hole: 그 행부터 접속 끝까지 a · b · w 를 g 만큼 민다 — 앞 조각 끝과 g 의 구멍이 생기고, 빠진
//     매체만큼 벽시각도 지났으므로 짐작도 함께 민다.
func inject(segs []replaySeg, injs []injection) []replaySeg {
	out := append([]replaySeg(nil), segs...)
	for _, inj := range injs {
		at := -1
		for i, s := range out {
			if s.file == inj.file {
				at = i
			}
		}
		if at < 0 {
			continue
		}
		conn, base := out[at].conn, out[at].number
		for i := at; i < len(out) && out[i].conn == conn; i++ {
			switch inj.kind {
			case "marker":
				out[i].recorder[15] ^= 0xA5
				out[i].number -= base
			case "hole":
				g := inj.gMS
				out[i].aNS, out[i].bNS, out[i].wMS = out[i].aNS+g*1e6, out[i].bNS+g*1e6, out[i].wMS+g
			}
		}
		if inj.kind == "wipe" {
			out[at].wiped = true
		}
	}
	return out
}

// replayVariant 는 판정 입력 한 판(표 × 주입)이다.
type replayVariant struct {
	table, name string
	segs        []replaySeg
}

// judgingVariants 는 OBS 두 표 × {주입 없음 · 표식 바뀜 · mtxi 지움 · 구멍 넷} 이다.
func judgingVariants(t *testing.T) []replayVariant {
	t.Helper()
	injs := loadInjections(t)
	var out []replayVariant
	for _, table := range []string{"obs_x264", "obs_vt"} {
		segs := loadReplay(t, table+"_run_table.csv")
		pick := func(kind string, g int64) []injection {
			var sel []injection
			for _, in := range injs {
				if in.table == table && in.kind == kind && in.gMS == g {
					sel = append(sel, in)
				}
			}
			if len(sel) == 0 {
				t.Fatalf("주입표에 %s %s g=%d 없음", table, kind, g)
			}
			return sel
		}
		out = append(out,
			replayVariant{table, "base", segs},
			replayVariant{table, "marker", inject(segs, pick("marker", 0))},
			replayVariant{table, "wipe", inject(segs, pick("wipe", 0))})
		for _, g := range []int64{1, 17, 30, 50} {
			out = append(out, replayVariant{table, fmt.Sprintf("hole%d", g), inject(segs, pick("hole", g))})
		}
	}
	return out
}

// replayEvent 는 사건 단언표 한 행이다(설계 2.9 「사건 단언」). at = row 면 그 행, next_compared 면 그 행
// 뒤 같은 녹화기 안 첫 견줄 조각을 본다.
type replayEvent struct {
	table, file, at, event string
	want, wantBy           string
	wantDur                int32
}

// loadEvents 는 사건 단언표를 읽는다.
func loadEvents(t *testing.T) []replayEvent {
	t.Helper()
	f, err := os.Open(filepath.Join(replayDir, "events.csv"))
	if err != nil {
		t.Fatalf("사건 단언표 열기: %v", err)
	}
	defer f.Close()
	recs, err := csv.NewReader(f).ReadAll()
	if err != nil {
		t.Fatalf("사건 단언표 읽기: %v", err)
	}
	var out []replayEvent
	for _, r := range recs[1:] {
		ev := replayEvent{table: r[0], file: r[1], at: r[2], event: r[3], want: r[4], wantBy: r[5]}
		if r[6] != "" {
			d, err := strconv.ParseInt(r[6], 10, 32)
			if err != nil {
				t.Fatalf("사건 단언표 want_dur %q: %v", r[6], err)
			}
			ev.wantDur = int32(d)
		}
		out = append(out, ev)
	}
	return out
}

// eventFailures 는 θ 하나에서 어긋난 사건을 문장으로 돌려준다.
func eventFailures(t *testing.T, events []replayEvent, theta int64) []string {
	t.Helper()
	var bad []string
	for _, ev := range events {
		rows := replay(loadReplay(t, ev.table), theta, modeHead)
		r, ok := findEventRow(rows, ev)
		if !ok {
			bad = append(bad, fmt.Sprintf("%s %s: 행 없음", ev.table, ev.event))
			continue
		}
		want, wantBy := ev.want, ev.wantBy
		if string(r.out.Branch) != want || r.out.LimitedBy != wantBy || (ev.wantDur != 0 && r.out.DurationMS != ev.wantDur) {
			bad = append(bad, fmt.Sprintf("θ=%d %s: %s %s = %s · dur %d, want %s %s · dur %d",
				theta, ev.event, ev.table, r.seg.file, mixShow(r.out), r.out.DurationMS, want, wantBy, ev.wantDur))
		}
	}
	return bad
}

// findEventRow 는 사건이 가리키는 재생 행이다.
func findEventRow(rows []replayRow, ev replayEvent) (replayRow, bool) {
	for i, r := range rows {
		if r.seg.file != ev.file {
			continue
		}
		if ev.at == "row" {
			return r, true
		}
		for _, n := range rows[i+1:] {
			if n.out.Compared {
				return n, true
			}
		}
	}
	return replayRow{}, false
}

// 판정 입력(OBS 표 × 주입)을 시험 초기값 θ = MixThreshold 로 흘리면 거짓 새 장 (a) = 0 · 왕복 (d) = 0
// 이고, 접속 경계(실제 틈)는 모두 new_chapter_gap 이며, 모든 θ 후보에서 장부가 오라클로 설명된다.
func TestReplayJudgingInput(t *testing.T) {
	variants := judgingVariants(t)
	for _, v := range variants {
		for _, theta := range replayThetas {
			rows := replay(v.segs, theta, modeHead)
			if bad := explainV2(replayLedger(rows), time.Duration(theta)*time.Millisecond); len(bad) != 0 {
				t.Errorf("%s/%s θ=%d: 오라클 위반 %d 건 — 첫째 %s", v.table, v.name, theta, len(bad), bad[0])
			}
			if theta != MixThreshold.Milliseconds() {
				continue
			}
			st := measure(rows, theta)
			if st.falseChapters != 0 || st.roundTrips != 0 || st.connFirstGap != st.connFirst {
				t.Errorf("%s/%s θ=%d: (a) %d · (d) %d · 접속 경계 gap %d/%d, want 0 · 0 · 전부",
					v.table, v.name, theta, st.falseChapters, st.roundTrips, st.connFirstGap, st.connFirst)
			}
		}
	}
}

// 알려진 경계(사건 단언표)에서 θ 후보 모두가 기대 갈래를 낸다(예측표 P5 · P7 몫 포함).
func TestReplayEvents(t *testing.T) {
	events := loadEvents(t)
	for _, theta := range replayThetas {
		for _, msg := range eventFailures(t, events, theta) {
			t.Error(msg)
		}
	}
}

// t9vm 의 녹화기 재시작 경계(표식 바뀜)는 모두 실제 틈이라 θ · 겹침 식과 무관하게 new_chapter_gap
// 이다(t9vm REPORT — 겹침 모두 음수 −1936 … −9567ms).
func TestReplayRestartBoundaries(t *testing.T) {
	tables := []string{"t9vm_t9slow96_table.csv", "t9vm_t9slow98_table.csv", "t9vm_t9slowM2_table.csv", "t9vm_t9slowP2_table.csv"}
	n := 0
	for _, table := range tables {
		segs := loadReplay(t, table)
		for _, theta := range replayThetas {
			rows := replay(segs, theta, modeHead)
			for i := 1; i < len(rows); i++ {
				if rows[i].seg.recorder == rows[i-1].seg.recorder {
					continue
				}
				n++
				if rows[i].out.Branch != BranchNewChapterGap {
					t.Errorf("%s θ=%d %s: %s, want new_chapter_gap", table, theta, rows[i].seg.file, mixShow(rows[i].out))
				}
			}
		}
	}
	if want := 19 * len(replayThetas); n != want {
		t.Errorf("재시작 경계 %d 개(θ 후보 곱), want %d(19 × %d)", n, want, len(replayThetas))
	}
}

// 참고 재생(설계 2.9 — 출력만): 합성 · 서버 시계 · T-ovl 2차 실물 입력을 실제 머리 · 모든 조각 견줌 · x 부호 추정 머리로
// 흘린다. 판정에 쓰지 않으므로 오라클만 단언하고 값은 θ 후보표와 함께 보고한다.
func TestReplayReferenceExplained(t *testing.T) {
	for _, table := range replayReferenceTables {
		segs := loadReplay(t, table)
		for _, mode := range []replayMode{modeHead, modeAll, modeEstimated} {
			for _, theta := range replayThetas {
				rows := replay(segs, theta, mode)
				if bad := explainV2(replayLedger(rows), time.Duration(theta)*time.Millisecond); len(bad) != 0 {
					t.Errorf("%s 모드 %d θ=%d: 오라클 위반 %d 건 — 첫째 %s", table, mode, theta, len(bad), bad[0])
				}
			}
		}
	}
}

// replayReferenceTables 는 참고 재생 입력이다.
var replayReferenceTables = []string{
	"t9vm_t9clkB_table.csv", "t9vm_t9slow96_table.csv", "t9vm_t9slow98_table.csv", "t9vm_t9slowM2_table.csv", "t9vm_t9slowP2_table.csv",
	"rec_t9P05_table.csv", "rec_t9P2_table.csv", "rec_t9P6_table.csv", "rec_t9R05_table.csv", "rec_t9R2_table.csv", "rec_t9R6_table.csv",
	"tovl2_x264_table.csv", "tovl2_vt_table.csv", "tovl2_vtb_table.csv",
}

// replayLedger 는 재생 결과의 장부 행만이다.
func replayLedger(rows []replayRow) []explainRow {
	out := make([]explainRow, len(rows))
	for i, r := range rows {
		out[i] = r.row
	}
	return out
}

// θ 후보표(실측 1 결과 — 출력). PDT_REPLAY_OUT 이 있으면 그 파일에 마크다운으로 쓴다. 고르는 규칙(설계
// 2.9): 판정 입력에서 (a) = 0 ∧ (d) = 0 ∧ θ ≥ (f) + 10 ∧ 닻 띠(111.5–133) 밖 ∧ 사건 단언 전부 통과 —
// 가장 작은 θ. 실측 2 상한(조건 3)은 아직 없어 이 표가 고르지 않는다.
func TestReplayThetaTable(t *testing.T) {
	variants := judgingVariants(t)
	var b strings.Builder
	b.WriteString(thetaCandidates(t, variants))
	b.WriteString("\n#### 판별 칸(θ = MixThreshold)\n\n")
	b.WriteString(statsTableHeader("판", ""))
	for _, v := range variants {
		st := measure(replay(v.segs, MixThreshold.Milliseconds(), modeHead), MixThreshold.Milliseconds())
		fmt.Fprintf(&b, "| %s/%s %s\n", v.table, v.name, st.row())
	}
	b.WriteString("\n#### 참고 재생(합성 · 서버 시계 · T-ovl 2차 실물 — 판정 아님, θ = MixThreshold)\n\n")
	b.WriteString(statsTableHeader("입력 · 머리", ""))
	for _, table := range replayReferenceTables {
		segs := loadReplay(t, table)
		for _, m := range []struct {
			name string
			mode replayMode
		}{{"실제 머리", modeHead}, {"참고 · 모든 조각 견줌", modeAll}, {"참고 · 추정 머리", modeEstimated}} {
			st := measure(replay(segs, MixThreshold.Milliseconds(), m.mode), MixThreshold.Milliseconds())
			fmt.Fprintf(&b, "| %s · %s %s\n", table, m.name, st.row())
		}
	}
	t.Log(b.String())
	if path := os.Getenv("PDT_REPLAY_OUT"); path != "" {
		if err := os.WriteFile(path, []byte(b.String()), 0o644); err != nil {
			t.Fatalf("θ 후보표 쓰기: %v", err)
		}
	}
}

// thetaCandidates 는 θ 후보마다 판정 입력 합의 값과 고르는 규칙의 판정을 담은 표다.
func thetaCandidates(t *testing.T, variants []replayVariant) string {
	t.Helper()
	events := loadEvents(t)
	var b strings.Builder
	b.WriteString("\n### 판정 입력 합(OBS x264 · vt × 주입 7판)\n\n")
	b.WriteString(statsTableHeader("θ", "사건 실패 | 고름"))
	chosen := false
	for _, theta := range replayThetas {
		var sum, inj replayStats
		for _, v := range variants {
			st := measure(replay(v.segs, theta, modeHead), theta)
			sum = sum.add(st)
			if v.name == "marker" || v.name == "wipe" {
				inj = inj.add(st)
			}
		}
		evBad := len(eventFailures(t, events, theta))
		ok := sum.falseChapters == 0 && sum.roundTrips == 0 && evBad == 0 && (theta < 112 || theta > 133) &&
			(inj.audioDeltaN == 0 || theta >= inj.audioDeltaMax+10)
		mark := ""
		switch {
		case ok && !chosen:
			mark, chosen = "가장 작은 통과", true
		case ok:
			mark = "통과"
		}
		fmt.Fprintf(&b, "| %d %s %d | %s |\n", theta, sum.row(), evBad, mark)
	}
	return b.String()
}

// statsTableHeader 는 첫 칸 이름 first 와 덧붙일 칸 extra(「a | b」 꼴 · 비면 없음)를 단 표 머리 두 줄이다.
func statsTableHeader(first, extra string) string {
	cols := append([]string{first}, statsColumns...)
	if extra != "" {
		cols = append(cols, strings.Split(extra, " | ")...)
	}
	return "| " + strings.Join(cols, " | ") + " |\n|" + strings.Repeat("---|", len(cols)) + "\n"
}
