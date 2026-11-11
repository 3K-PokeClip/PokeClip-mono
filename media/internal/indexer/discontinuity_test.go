package indexer

// is_discontinuity 판정 시험(POK-196 PDT 생산자 계획 4절 c6 · r6 2.8 · r6 c9 시험 I15 · 설계 정합 대조 1). 기준 행은 q(seq
// 바로 앞 장부 행 = 커서 꼬리)이고, 같은 이어 계산 회차의 최신 행 p 가 아니다(변이 m-R6). q · k 둘 다 표식 · 번호가 있으면
// 칸으로, 하나라도 없으면 drift 1500ms 로 정하고, 훅 경계를 OR 로 합친다.
//
// 인덱서 경로 시험의 행은 모두 판 NULL 이다(가짜 저장소가 판을 고르지 않는다) — 판정이 판과 무관함을 그 행으로 본다(I15
// 「판 NULL 행에도 같은 판정」). 조각 사이 파일명 간격은 길이(4초)와 같게 둬 drift 가 0 이다 — drift 로도 참이 되는
// 입력이면 「칸 덕분에 참」 인지 가를 수 없다(hook_break_test.go 머리의 시각 설계 규칙과 같다).

import (
	"log/slog"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 이 파일의 녹화기 표식 X · Y 다. chain_test.go 의 recA · recB 와 따로 둔다 — 판정 시험이 사슬 시험의 이름에 기대지 않게.
var (
	markX = [16]byte{0x58}
	markY = [16]byte{0x59}
)

// facts 는 표식 rec · 번호 n 인 원래 사실 칸이다(영상 칸은 판정이 보지 않아 비운다).
func facts(rec [16]byte, n int64) index.TimingFacts {
	return index.TimingFacts{Recorder: &rec, Number: &n}
}

// 판정 함수 표 — 칸 우선 · drift 폴백 · 훅 OR · 신호 재료. q 는 장부 바로 앞 행이고, 그 행이 회차 없는 행(하한 거부 ·
// 비귀속)이어도 같은 규칙이다(계획 c6 시험 2 의 함수 단위 몫 — 도달 불가 논증은 인덱서 H3 의 사실이라 이 입력은 여전히
// 만들 수 있다).
func TestJudgeDiscontinuity(t *testing.T) {
	const tol = 1500
	recOnly := index.TimingFacts{Recorder: &markX} // 표식만 있고 번호 nil — 장부 CHECK 가 막는 꼴이지만 판정은 모름으로 본다
	tests := []struct {
		name    string
		q, k    index.TimingFacts
		driftMS int64
		hook    bool
		want    discontinuity
	}{
		{"표식 바뀜 · 훅 없음 · drift 0", facts(markX, 10), facts(markY, 0), 0, false,
			discontinuity{broken: true, byMarks: true}},
		{"같은 표식 · 번호 +1", facts(markX, 10), facts(markX, 11), 0, false,
			discontinuity{byMarks: true}},
		{"같은 표식 · 번호 +1 · 파일명 −2초", facts(markX, 10), facts(markX, 11), -2000, false,
			discontinuity{byMarks: true}},
		{"같은 표식 · 번호 +1 · +5001ms 도약", facts(markX, 10), facts(markX, 11), 5001, false,
			discontinuity{byMarks: true}},
		{"번호 건너뜀 1", facts(markX, 10), facts(markX, 12), 0, false,
			discontinuity{broken: true, byMarks: true, skipped: 1}},
		{"번호 건너뜀 3", facts(markX, 10), facts(markX, 14), 0, false,
			discontinuity{broken: true, byMarks: true, skipped: 3}},
		{"같은 표식 · 같은 번호(역행)", facts(markX, 10), facts(markX, 10), 0, false,
			discontinuity{broken: true, byMarks: true}},
		{"같은 표식 · 번호 +1 · 훅 경계", facts(markX, 10), facts(markX, 11), 0, true,
			discontinuity{broken: true, byMarks: true, hookMismatch: true}},
		{"표식 바뀜 · 훅 경계", facts(markX, 10), facts(markY, 0), 0, true,
			discontinuity{broken: true, byMarks: true}},
		{"하한 거부 q 의 표식이 다름", facts(markY, 0), facts(markX, 11), 0, false,
			discontinuity{broken: true, byMarks: true}},
		{"하한 거부 q 와 같은 표식 · 번호 +1", facts(markX, 11), facts(markX, 12), 0, false,
			discontinuity{byMarks: true}},
		{"q mtxi 없음 · drift 0", index.TimingFacts{}, facts(markX, 11), 0, false, discontinuity{}},
		{"q mtxi 없음 · drift 1500", index.TimingFacts{}, facts(markX, 11), 1500, false, discontinuity{}},
		{"q mtxi 없음 · drift 1501", index.TimingFacts{}, facts(markX, 11), 1501, false,
			discontinuity{broken: true}},
		{"q mtxi 없음 · drift −1501", index.TimingFacts{}, facts(markX, 11), -1501, false,
			discontinuity{broken: true}},
		{"k mtxi 없음 · drift 0", facts(markX, 10), index.TimingFacts{}, 0, false, discontinuity{}},
		{"k mtxi 없음 · drift 5001", facts(markX, 10), index.TimingFacts{}, 5001, false,
			discontinuity{broken: true}},
		{"q 번호 nil · drift 0", recOnly, facts(markX, 11), 0, false, discontinuity{}},
		{"둘 다 mtxi 없음 · 훅 경계", index.TimingFacts{}, index.TimingFacts{}, 0, true,
			discontinuity{broken: true}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := judgeDiscontinuity(tt.q, tt.k, tt.driftMS, tol, tt.hook); got != tt.want {
				t.Errorf("judgeDiscontinuity = %+v, want %+v", got, tt.want)
			}
		})
	}
}

// markReads 는 경로별 mtxi 판독 주입이다. 등록하지 않은 경로의 조각은 머리말에 mtxi 가 없다(결정적 부재).
type markReads map[string]playback.Mtxi

// injectMarks 는 시각 판독 주입점을 r 로 바꾼다. 픽스처를 reload 하면 다시 불러야 한다.
func (f *fixture) injectMarks(r markReads) {
	f.ix.timingFn = func(p string) (playback.SegmentTiming, error) {
		m, ok := r[p]
		if !ok {
			return playback.SegmentTiming{}, nil
		}
		return playback.SegmentTiming{Mtxi: &m}, nil
	}
}

// step 은 스트림 s1 에 넣을 조각 하나다. marked 가 거짓이면 mtxi 없는 조각이다. round 는 가짜 저장소의 귀속 대본(""
// = 회차 칸 NULL — 하한 거부 · 비귀속 행의 꼴)이다.
type step struct {
	at     time.Duration
	marked bool
	rec    [16]byte
	n      uint64
	round  string
}

// put 은 step 하나를 워처 경로로 넣고 그 행의 is_discontinuity 를 돌려준다.
func (f *fixture) put(r markReads, s step) bool {
	f.t.Helper()
	seg := f.segment("s1", segName(baseWall, s.at), 1000, recording.ReasonNextFile)
	if s.marked {
		r[seg.Path] = playback.Mtxi{RecorderID: s.rec, SegmentNumber: s.n, DTS: s.at, NTP: baseWall.Add(s.at)}
	}
	f.mustHandle(seg)
	return f.discont("s1", seg.Path)
}

// runSteps 는 steps 를 차례로 넣고 행마다 is_discontinuity 를 돌려준다. reload 면 행 사이마다 인덱서를 새로 만들어 커서를
// 장부에서 다시 읽는다(재기동 · 재스캔 뒤의 커서).
func runSteps(t *testing.T, steps []step, reload bool) []bool {
	t.Helper()
	f := newFixture(t, 4000)
	for _, s := range steps {
		f.store.scriptSessions(s.round)
	}
	r := markReads{}
	f.injectMarks(r)
	var out []bool
	for _, s := range steps {
		if reload {
			f.reload()
			f.injectMarks(r)
		}
		out = append(out, f.put(r, s))
	}
	return out
}

// 계획 c6 시험 2 · 3 — 기준 행은 seq 바로 앞 행 q 다. 회차 칸 NULL 행 u(하한 거부 꼴)가 끼면 k2 는 u 와의 관계이고, 회차가
// 바뀌어도 회차와 무관하다. 같은 회차의 최신 행 p 로 판정하면(변이 m-R6) 세 줄 모두 답이 뒤집힌다.
func TestDiscontinuityIsRelativeToLedgerRowBeforeSeq(t *testing.T) {
	tests := []struct {
		name  string
		steps []step
		want  []bool
	}{
		{"u 표식 다름 → k2 참", []step{
			{0, true, markX, 10, "R1"}, {4 * time.Second, true, markY, 0, ""}, {8 * time.Second, true, markX, 11, "R1"},
		}, []bool{false, true, true}},
		{"u 같은 표식 · 번호 +1 → k2 거짓", []step{
			{0, true, markX, 10, "R1"}, {4 * time.Second, true, markX, 11, ""}, {8 * time.Second, true, markX, 12, "R1"},
		}, []bool{false, false, false}},
		{"다른 회차의 q · 같은 녹화기 이어짐 → 거짓", []step{
			{0, true, markX, 10, "R1"}, {4 * time.Second, true, markX, 11, "R2"},
		}, []bool{false, false}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := runSteps(t, tt.steps, false); !slices.Equal(got, tt.want) {
				t.Errorf("is_discontinuity = %v, want %v", got, tt.want)
			}
		})
	}
}

// 계획 c6 시험 4 · I15 「재기동 · 정기 스캔 뒤」 — 행 사이마다 커서를 장부에서 다시 읽어도 같은 답이다. q 의 칸은 메모리가
// 아니라 장부 꼬리 칸(LoadCursor)에서 온다.
func TestDiscontinuitySameAnswerAfterCursorReload(t *testing.T) {
	steps := []step{
		{0, true, markX, 10, "R1"},
		{4 * time.Second, true, markX, 11, "R1"},
		{8 * time.Second, true, markX, 13, "R1"},  // 번호 건너뜀
		{12 * time.Second, true, markY, 0, ""},    // 표식 바뀜
		{16 * time.Second, false, markY, 0, "R2"}, // mtxi 없음 → drift 폴백
		{20 * time.Second, true, markY, 2, "R2"},
		{24 * time.Second, true, markY, 3, "R2"},
	}
	want := []bool{false, false, true, true, false, false, false}
	if got := runSteps(t, steps, false); !slices.Equal(got, want) {
		t.Fatalf("이어서 넣은 답 = %v, want %v", got, want)
	}
	if got := runSteps(t, steps, true); !slices.Equal(got, want) {
		t.Errorf("행마다 재적재한 답 = %v, want %v", got, want)
	}
}

// I15 「꼬리 local_path NULL → 두 갈래」 — q 의 파일이 없어도(업로드 뒤 지워진 꼬리) 판정은 장부 칸으로 선다. 칸이 있으면
// 표식 · 번호로(+5001ms 도약이어도 거짓), 칸이 없는 행(출시 이미지가 쓴 행)이면 drift 로(같은 도약이 참) 정한다.
func TestDiscontinuityReadsTailFactsNotTailFile(t *testing.T) {
	tests := []struct {
		name string
		tail index.TimingFacts
		want bool
	}{
		{"꼬리 칸 있음 → 표식 · 번호", facts(markX, 10), false},
		{"꼬리 칸 없음(출시 이미지 행) → drift", index.TimingFacts{}, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000)
			f.store.seed(index.Record{
				StreamID: "s1", Seq: 0, StartWallUTC: baseWall, DurationMS: 4000,
				UploadState: index.UploadStateUploaded, Timing: index.TimingInput{Facts: tt.tail},
			})
			f.reload()
			r := markReads{}
			f.injectMarks(r)
			if got := f.put(r, step{at: 4*time.Second + 5001*time.Millisecond, marked: true, rec: markX, n: 11}); got != tt.want {
				t.Errorf("is_discontinuity = %v, want %v", got, tt.want)
			}
		})
	}
}

// I15 「번호 건너뜀(poisoned)」 · 계획 c6 시험 1 — 가운데 조각이 poison 으로 장부에 못 들면 다음 조각은 꼬리와 번호가 둘
// 벌어져 참이고, segment_number_skipped(건너뛴 수 1)를 남긴다. drift 는 톨러런스 안이다(칸을 무시하면 거짓 — 변이 m-R3).
func TestDiscontinuityNumberSkipAfterPoison(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.insertErrs = []error{nil, &pgconn.PgError{Code: "22001", Message: "값이 너무 길다"}}
	r := markReads{}
	f.injectMarks(r)

	f.put(r, step{at: 0, marked: true, rec: markX, n: 10})
	seg := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	r[seg.Path] = playback.Mtxi{RecorderID: markX, SegmentNumber: 11, DTS: 4 * time.Second, NTP: baseWall.Add(4 * time.Second)}
	f.mustHandle(seg) // poison — 행 없음 · 커서 그대로
	if got := f.put(r, step{at: 5 * time.Second, marked: true, rec: markX, n: 12}); !got {
		t.Fatal("번호가 건너뛴 조각의 is_discontinuity = false, want true")
	}

	if n := f.logs.count(slog.LevelWarn, "segment_number_skipped"); n != 1 {
		t.Fatalf("segment_number_skipped = %d건, want 1", n)
	}
	attrs := f.logs.attrs("segment_number_skipped")
	if attrs["prev_number"] != int64(10) || attrs["number"] != int64(12) || attrs["skipped"] != int64(1) || attrs["seq"] != int64(1) {
		t.Errorf("segment_number_skipped 속성 = %v, want prev_number 10 · number 12 · skipped 1 · seq 1", attrs)
	}
	if attrs["recorder_id"] != "58000000000000000000000000000000" {
		t.Errorf("recorder_id = %v, want 표식 X 의 16진", attrs["recorder_id"])
	}
}

// 판정 신호는 장부에 들어간 행의 것만 남는다(c6 검수 cc L1 · cx M). poison 으로 행이 안 든 시도(X#12 — 꼬리 X#10 과 번호
// 건너뜀)는 신호가 없고, 다음 조각 X#13 이 실제로 든 행의 건너뜀(2)을 남긴다. 잡는 결함: INSERT 전에 신호를 내면 장부에 없는
// 시도의 segment_number_skipped(number 12 · seq 1)가 한 줄 더 남고, 운영자는 seq 1 행을 두 번 건너뛴 것으로 읽는다.
func TestDiscontinuitySignalSkipsPoisonedAttempt(t *testing.T) {
	f := newFixture(t, 4000)
	f.store.insertErrs = []error{nil, &pgconn.PgError{Code: "22001", Message: "값이 너무 길다"}}
	r := markReads{}
	f.injectMarks(r)

	f.put(r, step{at: 0, marked: true, rec: markX, n: 10})
	seg := f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonNextFile)
	r[seg.Path] = playback.Mtxi{RecorderID: markX, SegmentNumber: 12, DTS: 4 * time.Second, NTP: baseWall.Add(4 * time.Second)}
	f.mustHandle(seg) // poison — 행 없음
	f.put(r, step{at: 8 * time.Second, marked: true, rec: markX, n: 13})

	all := f.logs.attrsAll("segment_number_skipped")
	if len(all) != 1 || all[0]["number"] != int64(13) || all[0]["skipped"] != int64(2) || all[0]["seq"] != int64(1) {
		t.Errorf("segment_number_skipped = %v, want 한 건(number 13 · skipped 2 · seq 1)", all)
	}
}

// seq 충돌 재적재로 판정이 바뀌면 신호도 재적재 뒤 판정의 것이다(c6 검수 cx M 의 반례). 메모리 꼬리는 X#10 인데 다른 쓰기자가
// X#11 을 seq 1 로 먼저 넣었다 → X#12 의 첫 시도는 X#10 과 견줘 건너뜀이지만 seq 충돌로 안 들고, 재적재한 꼬리 X#11 과 견준
// 재시도는 같은 녹화기 이어짐이다. 잡는 결함: 첫 시도의 신호가 남으면 장부에 없는 건너뜀(seq 1)이 경고로 뜬다.
func TestDiscontinuitySignalFollowsSeqConflictReload(t *testing.T) {
	f := newFixture(t, 4000)
	r := markReads{}
	f.injectMarks(r)
	f.put(r, step{at: 0, marked: true, rec: markX, n: 10})
	f.store.seed(index.Record{StreamID: "s1", Seq: 1, StartWallUTC: baseWall.Add(4 * time.Second), DurationMS: 4000,
		LocalPath: "/다른/쓰기자.mp4", UploadState: index.UploadStatePending, Bytes: 10,
		Timing: index.TimingInput{Facts: facts(markX, 11)}})

	if f.put(r, step{at: 8 * time.Second, marked: true, rec: markX, n: 12}) {
		t.Error("재적재한 꼬리(X#11)의 같은 녹화기 이어짐인 행의 is_discontinuity = true, want false")
	}

	if n := f.logs.count(slog.LevelWarn, "segment_number_skipped"); n != 0 {
		t.Errorf("segment_number_skipped = %d건(%v), want 0 — 장부에는 건너뜀이 없다", n, f.logs.attrsAll("segment_number_skipped"))
	}
}

// I15 「번호 건너뜀(H3)」 — 꼬리보다 이른 파일명 시각의 X#11 이 H3 늦은 조각 검사에서 빠지면, drift 0 인 X#12 는 꼬리 X#10 과
// 번호가 둘 벌어져 참이고 segment_number_skipped(1)를 남긴다(c6 검수 cx L). 잡는 결함(변이 m-R3 — 늘 drift): drift 0 이라
// 거짓이 된다.
func TestDiscontinuityNumberSkipAfterLateSegment(t *testing.T) {
	f := newFixture(t, 4000)
	r := markReads{}
	f.injectMarks(r)
	f.put(r, step{at: 4 * time.Second, marked: true, rec: markX, n: 10})
	late := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonNextFile)
	r[late.Path] = playback.Mtxi{RecorderID: markX, SegmentNumber: 11, NTP: baseWall}
	f.mustHandle(late)
	if n := f.logs.count(slog.LevelError, "late_segment_skipped"); n != 1 {
		t.Fatalf("late_segment_skipped = %d건, want 1(전제 — X#11 은 H3 에서 빠진다)", n)
	}

	if !f.put(r, step{at: 8 * time.Second, marked: true, rec: markX, n: 12}) {
		t.Fatal("번호가 건너뛴 조각의 is_discontinuity = false, want true")
	}
	attrs := f.logs.attrs("segment_number_skipped")
	if attrs["prev_number"] != int64(10) || attrs["number"] != int64(12) || attrs["skipped"] != int64(1) || attrs["seq"] != int64(1) {
		t.Errorf("segment_number_skipped 속성 = %v, want prev_number 10 · number 12 · skipped 1 · seq 1", attrs)
	}
}

// 계획 c6 시험 5 · I15 — 표식 같음 + 번호 +1 인데 훅 경계를 소비하면 참이고(훅 OR), 칸과 훅이 어긋났다는
// discontinuity_hook_mismatch 를 남긴다. 표식이 바뀐 훅 경계(칸과 훅이 같은 답)는 남기지 않는다.
func TestDiscontinuityHookMismatchSignal(t *testing.T) {
	tests := []struct {
		name     string
		rec      [16]byte
		n        uint64
		mismatch int
	}{
		{"같은 녹화기 이어짐 + 훅", markX, 11, 1},
		{"표식 바뀜 + 훅", markY, 0, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newHookFixture(t)
			r := markReads{}
			f.injectMarks(r)
			f.put(r, step{at: 0, marked: true, rec: markX, n: 10})
			f.arm("s1", seg4s, seg4s+900*time.Millisecond)

			if got := f.put(r, step{at: seg4s + 900*time.Millisecond, marked: true, rec: tt.rec, n: tt.n}); !got {
				t.Fatal("훅 경계를 소비한 조각의 is_discontinuity = false, want true")
			}
			if n := f.logs.count(slog.LevelWarn, "discontinuity_hook_mismatch"); n != tt.mismatch {
				t.Fatalf("discontinuity_hook_mismatch = %d건, want %d", n, tt.mismatch)
			}
			if tt.mismatch == 0 {
				return
			}
			attrs := f.logs.attrs("discontinuity_hook_mismatch")
			if attrs["prev_number"] != int64(10) || attrs["number"] != int64(11) || attrs["seq"] != int64(1) {
				t.Errorf("discontinuity_hook_mismatch 속성 = %v, want prev_number 10 · number 11 · seq 1", attrs)
			}
		})
	}
}

// 칸으로 같은 녹화기 이어짐인 조각은 신호 둘을 남기지 않는다 — 정상 경로가 Warn 을 내면 그 신호가 소음에 묻힌다.
func TestDiscontinuityQuietOnSameInstance(t *testing.T) {
	f := newFixture(t, 4000)
	r := markReads{}
	f.injectMarks(r)
	for i, at := range []time.Duration{0, 4 * time.Second, 8 * time.Second} {
		if f.put(r, step{at: at, marked: true, rec: markX, n: uint64(10 + i)}) {
			t.Fatalf("%d번째 조각의 is_discontinuity = true, want false", i)
		}
	}
	for _, msg := range []string{"segment_number_skipped", "discontinuity_hook_mismatch"} {
		if n := f.logs.count(slog.LevelWarn, msg); n != 0 {
			t.Errorf("%s = %d건, want 0", msg, n)
		}
	}
}
