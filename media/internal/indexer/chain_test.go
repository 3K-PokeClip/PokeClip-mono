package indexer

// ③ 시간 도장 사슬(chain.go)의 단위 검증이다 — 계획 4.2-R R3 「보관값 전진 규칙」 ①~⑥.
// mtxi·트랙 끝 판독은 mtxiFn·trackEndsFn 주입으로 대신한다(파일 I/O 없이 산식만 잰다).
// 실물 조각을 fsop 관문으로 읽는 배선은 TestChainReadsRecorderMtxiThroughFsopGate 하나가 본다.

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// 녹화기 인스턴스 표식 둘 — 송출자 재접속·드리프트 리셋이면 표식이 바뀐다.
var (
	recA = [16]byte{0xaa}
	recB = [16]byte{0xbb}
)

var (
	errNoMtxi = errors.New("머리말에 mtxi 상자가 없다(주입)")
	errNoEnds = errors.New("트랙 끝을 읽지 못했다(주입)")
)

// fakeReads 는 경로별 mtxi·트랙 끝 판독 결과다. 등록하지 않은 경로는 판독 실패다.
type fakeReads struct {
	mtxi map[string]playback.Mtxi
	ends map[string]playback.Ends
	// endsCalls 는 트랙 끝을 다시 읽은 경로다 — "리셋 때만 직전 파일을 다시 연다"를 본다.
	endsCalls []string
}

// injectReads 는 두 판독 주입점을 가짜로 바꾼다. 픽스처를 reload 하면 다시 불러야 한다.
func (f *fixture) injectReads() *fakeReads {
	r := &fakeReads{mtxi: map[string]playback.Mtxi{}, ends: map[string]playback.Ends{}}
	f.ix.mtxiFn = func(p string) (playback.Mtxi, error) {
		m, ok := r.mtxi[p]
		if !ok {
			return playback.Mtxi{}, errNoMtxi
		}
		return m, nil
	}
	f.ix.trackEndsFn = func(p string) (playback.Ends, error) {
		r.endsCalls = append(r.endsCalls, p)
		e, ok := r.ends[p]
		if !ok {
			return playback.Ends{}, errNoEnds
		}
		return e, nil
	}
	return r
}

// mtxiOf 는 녹화기 표식과 누적 DTS 로 판독값을 만든다.
func mtxiOf(rec [16]byte, dts time.Duration) *playback.Mtxi {
	return &playback.Mtxi{RecorderID: rec, DTS: dts}
}

// row 는 at(baseWall 기준) 자리에 조각 파일을 만들어 훅 유입으로 처리한다. m 이 nil 이면 그 조각의
// mtxi 판독은 실패한다. 길이는 픽스처 프로브가 차례로 준다(훅 유입은 재프로브가 없다).
func (f *fixture) row(r *fakeReads, at time.Duration, m *playback.Mtxi) recording.Segment {
	f.t.Helper()
	seg := f.segment("s1", segName(baseWall, at), 1000, recording.ReasonHook)
	if m != nil {
		r.mtxi[seg.Path] = *m
	}
	f.mustHandle(seg)
	return seg
}

// playbackOf 는 seq 의 ③ 요청(마지막 것)을 찾는다. 없으면 테스트를 멈춘다.
func (f *fixture) playbackOf(seq int64) index.UploadTarget {
	f.t.Helper()
	var found *index.UploadTarget
	for _, t := range f.upload.targetsOf(index.AxisPlayback) {
		if t.Seq == seq {
			found = &t
		}
	}
	if found == nil {
		f.t.Fatalf("seq %d 의 ③ 요청이 없다", seq)
	}
	return *found
}

// mtxi_read_stall_trips_latch_without_commit_or_adopt(뮤테이션 60) — mtxi 판독이 상한을 넘기면
// probeT 와 같은 관문 규약으로 래치를 세우고, 이 이벤트는 커밋도 Adopt 도 없이 끝난다
// (stat_failed 와 같다 — 미기록으로 남고 래치 해제 뒤 다음 수집이 다시 찾는다. m3c 불변).
func TestMtxiReadStallTripsLatchWithoutCommitOrAdopt(t *testing.T) {
	f := newFixture(t, 4000)
	seg := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook)
	calls := 0
	f.ix.mtxiFn = func(p string) (playback.Mtxi, error) {
		calls++
		return playback.Mtxi{}, fmt.Errorf("%w: op=read path=%q", fsop.ErrStalled, p)
	}

	if err := f.handle(seg); err != nil {
		t.Fatalf("mtxi 판독 정지 국면의 Handle 이 에러다(격리 장치가 사망 장치가 된다): %v", err)
	}
	if calls != 1 {
		t.Fatalf("mtxi 판독 %d회, want 1회 — 크기 안정 뒤 한 번 읽어야 한다", calls)
	}
	if !f.ix.fsLatch.Tripped() {
		t.Fatal("mtxi 판독 정지가 래치를 세우지 않았다")
	}
	got := f.logs.attrs("fs_op_stalled")
	if got["op"] != "read" || got["site"] != "mtxi" {
		t.Errorf("fs_op_stalled 라벨 = op %v · site %v, want read · mtxi", got["op"], got["site"])
	}
	if n := len(f.store.records("s1")); n != 0 {
		t.Errorf("판독 정지 국면에서 INSERT 가 %d행 났다 — 도장 재료 없이 커밋했다", n)
	}
	if n := f.adopter.count(); n != 0 {
		t.Errorf("판독 정지 국면에서 Adopt %d회 — Adopt 는 정확히 3곳뿐이다(m3c)", n)
	}
}

// 두 판독 관문은 래치가 서 있으면 워커를 만들지 않고 ErrStalled 로 즉답한다 — 같은 이벤트 안에서
// 앞선 FS 호출이 트립시킨 뒤에도 새 워커가 생기지 않게 하는 구조 강제다(statT·probeT 와 같다).
func TestReadGatesRefuseWhileLatched(t *testing.T) {
	f := newFixture(t)
	f.ix.mtxiFn = func(string) (playback.Mtxi, error) {
		t.Error("래치가 섰는데 mtxi 판독 워커를 만들었다")
		return playback.Mtxi{}, nil
	}
	f.ix.trackEndsFn = func(string) (playback.Ends, error) {
		t.Error("래치가 섰는데 트랙 끝 판독 워커를 만들었다")
		return playback.Ends{}, nil
	}
	f.ix.fsLatch.Trip("/p", "measure")

	if _, err := f.ix.mtxiT("/p"); !errors.Is(err, fsop.ErrStalled) {
		t.Errorf("mtxiT 오류 = %v, want ErrStalled", err)
	}
	if _, err := f.ix.trackEndsT("/p"); !errors.Is(err, fsop.ErrStalled) {
		t.Errorf("trackEndsT 오류 = %v, want ErrStalled", err)
	}
	if n := f.logs.count(slog.LevelError, "fs_op_stalled"); n != 0 {
		t.Errorf("이미 선 래치에서 fs_op_stalled 가 %d건 더 났다", n)
	}
}

// pos_is_segment_mtxi_without_reset — 평소 도장은 조각 자기 mtxi 그대로다(offset 0 · 결정 B′:
// 회차 첫 조각을 0 으로 빼지 않는다). 같은 연결 안의 1틱 어긋남은 구멍이 아니다.
func TestPosIsSegmentMtxiWithoutReset(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1")

	first := f.row(r, 0, mtxiOf(recA, 5_987_981_859))
	f.row(r, 4*time.Second, mtxiOf(recA, 9_987_981_859))
	f.row(r, 8*time.Second, mtxiOf(recA, 13_987_992_970)) // 영상 1틱(11µs) 어긋남

	for seq, want := range []time.Duration{5_987_981_859, 9_987_981_859, 13_987_992_970} {
		got := f.playbackOf(int64(seq))
		if got.PlaybackPos != want || got.StitchOffset != 0 || got.PosPinned {
			t.Errorf("seq %d 도장 = pos %v · offset %v · 고정 %v, want pos %v · offset 0 · 고정 false",
				seq, got.PlaybackPos, got.StitchOffset, got.PosPinned, want)
		}
	}
	// 작업 형상 — 키는 INSERT 가 커밋한 playback_s3_key, 기대 init 은 비운다(워커 sessionInit 몫).
	want := index.UploadTarget{
		StreamID: "s1", Axis: index.AxisPlayback, Seq: 0, SessionID: "S1",
		S3Key: "dvr/s1/seg/000000.m4s", LocalPath: first.Path, Bytes: 1000, IsTail: true,
		PlaybackPos: 5_987_981_859,
	}
	if got := f.playbackOf(0); !reflect.DeepEqual(got, want) {
		t.Errorf("③ 작업 = %+v\nwant %+v", got, want)
	}
	if len(r.endsCalls) != 0 {
		t.Errorf("리셋이 없는데 트랙 끝을 %d번 다시 읽었다 — 평소 경로는 머리말만 읽는다", len(r.endsCalls))
	}
}

// playback_request_absent_without_session — 비귀속 행(SeedResult.SessionID 빈 값)은 ③·init 을 만들지
// 않는다(settled 조건상 목록에 실릴 수 없다). ② 는 형제 분기라 그대로 요청된다.
func TestPlaybackRequestAbsentWithoutSession(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("", "")

	f.row(r, 0, mtxiOf(recA, 0))
	f.row(r, 4*time.Second, mtxiOf(recA, 4*time.Second))

	if got := f.upload.targetsOf(index.AxisPlayback); len(got) != 0 {
		t.Errorf("비귀속 행에 ③ 요청 %d건: %+v", len(got), got)
	}
	if got := f.upload.targetsOf(index.AxisInit); len(got) != 0 {
		t.Errorf("비귀속 행에 init 요청 %d건: %+v", len(got), got)
	}
	if got := f.upload.targetsOf(index.AxisArchive); len(got) != 2 {
		t.Errorf("② 요청 %d건, want 2건 — ③ 이 없다고 ② 가 서면 안 된다", len(got))
	}
}

// init_request_only_when_session_opened — init(MAP) 작업은 회차의 첫 행에서만 만들고, 한 프로세스
// 안에서는 세션을 연 행이 그 행이다(재기동 뒤 이어지는 회차는
// TestRestartedProcessRequestsInitOnceForContinuingSession). 원천은 그 행의 조각이고, 키는 비워
// 보낸다(업로더가 playback.InitKey 로 파생 — 스위퍼 init 행과 같은 형상).
func TestInitRequestOnlyWhenSessionOpened(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S2") // 셋째 행이 TD 분할로 새 세션을 연다

	s0 := f.row(r, 0, mtxiOf(recA, 0))
	f.row(r, 4*time.Second, mtxiOf(recA, 4*time.Second))
	s2 := f.row(r, 8*time.Second, mtxiOf(recA, 8*time.Second))

	want := []index.UploadTarget{
		{StreamID: "s1", Axis: index.AxisInit, SessionID: "S1", LocalPath: s0.Path, Bytes: 1000, IsTail: true},
		{StreamID: "s1", Axis: index.AxisInit, SessionID: "S2", LocalPath: s2.Path, Bytes: 1000, IsTail: true},
	}
	if got := f.upload.targetsOf(index.AxisInit); !reflect.DeepEqual(got, want) {
		t.Errorf("init 요청 = %+v\nwant %+v", got, want)
	}
}

// 재기동 뒤 이어지는 회차(cc r3 #2) — 개시 행은 옛 프로세스가 처리해 이 프로세스에는 SessionOpened 가
// 오지 않는다. 그래도 이 프로세스가 그 회차의 첫 행을 볼 때 init 을 한 번 요청해야 한다: 업로더의
// sessionInit 은 init CAS 가 확정할 때만 채워지고(장부에 이미 확정된 회차면 같은 송출 설정·같은 재포장 산출일 때 AlreadySame 으로 되살아난다),
// 없으면 그 회차의 실시간 ③ 은 전부 대조 보류로 샌다. 원천은 그 행의 조각이다 — 재포장 init 은 같은 송출 설정이면 회차 안
// 어느 조각에서 만들어도 같은 바이트다. 새 행의 ③ 보다 먼저 나가고, 같은 회차의 다음 행에서는 다시
// 나가지 않는다.
func TestRestartedProcessRequestsInitOnceForContinuingSession(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1")
	f.row(r, 0, mtxiOf(recA, 10*time.Second)) // 옛 프로세스 — 회차를 연 행

	f.reload() // 재기동: 사슬과 요청 기억은 메모리라 사라지고 장부는 남는다
	r = f.injectReads()
	before := len(f.upload.targets())
	s1 := f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))
	f.row(r, 8*time.Second, mtxiOf(recA, 18*time.Second))

	var inits []index.UploadTarget
	firstPlayback := -1
	for i, tg := range f.upload.targets()[before:] {
		switch {
		case tg.Axis == index.AxisInit:
			inits = append(inits, tg)
		case tg.Axis == index.AxisPlayback && firstPlayback < 0:
			firstPlayback = i
		}
	}
	want := []index.UploadTarget{
		{StreamID: "s1", Axis: index.AxisInit, SessionID: "S1", LocalPath: s1.Path, Bytes: 1000, IsTail: true},
	}
	if !reflect.DeepEqual(inits, want) {
		t.Fatalf("재기동 뒤 init 요청 = %+v\nwant %+v", inits, want)
	}
	if got := f.upload.targets()[before:]; firstPlayback < 0 || got[firstPlayback].Seq != 1 {
		t.Fatalf("재기동 뒤 ③ 요청 = %+v, want seq 1 부터(전제)", got)
	}
	for i, tg := range f.upload.targets()[before:] {
		if tg.Axis == index.AxisInit && i > firstPlayback {
			t.Errorf("init 요청이 그 회차의 첫 ③ 보다 늦다 — 워커가 하나라 ③ 이 대조 보류를 거친다")
		}
	}
}

// 재기동 init 요청이 접수에서 거부되면(기동 시 자격증명 부재로 브레이커가 열린 동안 · 큐 포화 · 백오프)
// 그 회차의 다음 행에서 다시 요청하고, 접수되면 멈춘다. 한 번으로 끝내면 이 프로세스에서 sessionInit 이
// 영영 되살아나지 않는다 — 스위퍼 init 벌은 init_uploaded_at IS NULL 인 회차만 집으므로 이미 확정된
// 회차를 회수하지 못한다.
func TestRejectedInitRequestRetriesOnNextRowUntilAdmitted(t *testing.T) {
	f := newFixture(t, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1", "S1", "S1")
	f.row(r, 0, mtxiOf(recA, 10*time.Second)) // 옛 프로세스 — 회차를 연 행

	f.reload()
	r = f.injectReads()
	before := len(f.upload.targets())
	f.upload.accept = false // 재기동 직후 — 접수가 전부 거부된다
	s1 := f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))
	s2 := f.row(r, 8*time.Second, mtxiOf(recA, 18*time.Second))
	f.upload.accept = true
	s3 := f.row(r, 12*time.Second, mtxiOf(recA, 22*time.Second)) // 여기서 접수된다
	f.row(r, 16*time.Second, mtxiOf(recA, 26*time.Second))

	var got []string
	for _, tg := range f.upload.targets()[before:] {
		if tg.Axis == index.AxisInit {
			got = append(got, filepath.Base(tg.LocalPath))
		}
	}
	want := []string{filepath.Base(s1.Path), filepath.Base(s2.Path), filepath.Base(s3.Path)}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("init 요청 원천 = %v, want %v — 거부된 동안 행마다 다시, 접수된 뒤로는 없음", got, want)
	}
}

// pos_stitches_on_mtxi_reset(뮤테이션 40) — 회차 안에서 녹화기 표식이 바뀌거나 mtxi 가 뒤로 가면
// 직전 조각 끝에 이어 붙인다. 잇지 않으면 도장이 뒤로 간다 — 회차 안에는 끊김 표시가 없으므로
// (결정 F) 플레이어가 그 역행을 알 길이 없다.
func TestPosStitchesOnMtxiReset(t *testing.T) {
	tests := []struct {
		name string
		next *playback.Mtxi
	}{
		{"녹화기_표식_변경", mtxiOf(recB, 0)},           // 재접속 — 새 연결의 mtxi 는 0 에서 시작한다
		{"mtxi_역행", mtxiOf(recA, 3*time.Second)}, // 표식은 같은데 누적 DTS 가 뒤로 갔다
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000, 4000)
			r := f.injectReads()
			f.store.scriptSessions("S1", "S1")
			prev := f.row(r, 0, mtxiOf(recA, 10*time.Second))
			r.ends[prev.Path] = playback.Ends{Video: 4 * time.Second, Audio: 4 * time.Second}

			f.row(r, 4*time.Second, tt.next)

			// end(0) = pos(0) + min(두 트랙 끝) = 10s + 4s. offset(1) = end(0) − mtxi(1).
			got := f.playbackOf(1)
			if got.PlaybackPos != 14*time.Second || got.StitchOffset != 14*time.Second-tt.next.DTS {
				t.Errorf("리셋 뒤 도장 = pos %v · offset %v, want pos 14s · offset %v",
					got.PlaybackPos, got.StitchOffset, 14*time.Second-tt.next.DTS)
			}
		})
	}
}

// pos_stitches_at_earliest_track_end(뮤테이션 43) — 이음 자리는 먼저 끝난 트랙의 끝이다. 끊기는 연결의
// 마지막 조각은 소리가 영상보다 0.19~0.22초 일찍 끝나고(실측 68_ 조각 59), 늦게 끝난 쪽에 이으면
// 그만큼 소리 빈틈이 남는다.
func TestPosStitchesAtEarliestTrackEnd(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1")
	prev := f.row(r, 0, mtxiOf(recA, 235*time.Second))
	r.ends[prev.Path] = playback.Ends{Video: 3_966_700_000, Audio: 3_747_600_000}

	f.row(r, 4*time.Second, mtxiOf(recB, 0))

	if got, want := f.playbackOf(1).PlaybackPos, 238_747_600_000*time.Nanosecond; got != want {
		t.Errorf("이음 도장 = %v, want %v(소리 끝) — 늦게 끝난 영상 끝 238.9667s 에 이었다면 소리 빈틈", got, want)
	}
	if len(r.endsCalls) != 1 || r.endsCalls[0] != prev.Path {
		t.Errorf("트랙 끝 재독 = %v, want 직전 조각 %q 1회(리셋을 감지한 그때만)", r.endsCalls, prev.Path)
	}
}

// pos_stitch_falls_back_to_ledger_duration_when_prev_unreadable — 직전 조각을 다시 읽지 못하면 장부
// 길이로 잇는다(최악 0.2초 소리 빈틈, 도장은 뒤로 가지 않는다). 시간 초과는 관문 규약대로 래치도 세운다.
func TestPosStitchFallsBackToLedgerDurationWhenPrevUnreadable(t *testing.T) {
	tests := []struct {
		name        string
		endsErr     error
		wantLatched bool
	}{
		{"판독_실패", errNoEnds, false},
		{"시간_초과", fmt.Errorf("%w: op=read", fsop.ErrStalled), true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 3960, 4000)
			r := f.injectReads()
			f.ix.trackEndsFn = func(string) (playback.Ends, error) { return playback.Ends{}, tt.endsErr }
			f.store.scriptSessions("S1", "S1")
			f.row(r, 0, mtxiOf(recA, 10*time.Second))

			f.row(r, 4*time.Second, mtxiOf(recB, 0))

			// end(0) = pos(0) + dur(0) = 10s + 3.96s(장부 duration_ms).
			if got, want := f.playbackOf(1).PlaybackPos, 13_960*time.Millisecond; got != want {
				t.Errorf("폴백 이음 도장 = %v, want %v", got, want)
			}
			if f.ix.fsLatch.Tripped() != tt.wantLatched {
				t.Errorf("래치 = %v, want %v", f.ix.fsLatch.Tripped(), tt.wantLatched)
			}
			if tt.wantLatched && f.logs.attrs("fs_op_stalled")["site"] != "track_ends" {
				t.Errorf("fs_op_stalled site = %v, want track_ends", f.logs.attrs("fs_op_stalled")["site"])
			}
		})
	}
}

// gapCase 는 같은 녹화기 안에서 조각 1 이 조각 0 의 예상 끝(mtxi 10s + 길이 4s)보다 gap 만큼 늦게
// 시작하는 국면이다. wantPos 는 조각 1 의 도장이다.
type gapCase struct {
	name    string
	gap     time.Duration
	wantPos time.Duration
}

func runGapCases(t *testing.T, tests []gapCase) {
	t.Helper()
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 4000, 4000)
			r := f.injectReads()
			f.store.scriptSessions("S1", "S1")
			f.row(r, 0, mtxiOf(recA, 10*time.Second))
			next := 14*time.Second + tt.gap

			f.row(r, 4*time.Second, mtxiOf(recA, next))

			got := f.playbackOf(1)
			if got.PlaybackPos != tt.wantPos || got.StitchOffset != tt.wantPos-next {
				t.Errorf("도장 = pos %v · offset %v, want pos %v · offset %v",
					got.PlaybackPos, got.StitchOffset, tt.wantPos, tt.wantPos-next)
			}
		})
	}
}

// pos_stitches_on_in_instance_gap(뮤테이션 44) — 같은 녹화기 안에서 미디어가 유실돼 mtxi 가 허용치를
// 넘게 앞으로 뛰면 그 구멍을 압축해 직전 조각 끝(pos + 장부 길이)에 잇는다(kty 결정 G = B — 실측
// 71_ ⑤: 표시 없는 3.9초 구멍에서 hls.js 1.5.15 가 멈췄다). 49ms 는 구멍이 아니다(음성 대조군).
func TestPosStitchesOnInInstanceGap(t *testing.T) {
	runGapCases(t, []gapCase{
		{name: "3.9초_구멍은_이어_붙인다", gap: 3900 * time.Millisecond, wantPos: 14 * time.Second},
		{name: "49ms_는_그대로_둔다", gap: 49 * time.Millisecond, wantPos: 14_049 * time.Millisecond},
	})
}

// pos_gap_threshold_boundary(뮤테이션 47) — 허용치 경계: 정확히 50ms 는 무보정, 넘으면 보정이다.
func TestPosGapThresholdBoundary(t *testing.T) {
	runGapCases(t, []gapCase{
		{name: "정확히_50ms_는_무보정", gap: 50 * time.Millisecond, wantPos: 14_050 * time.Millisecond},
		{name: "50ms_를_1ns_넘으면_보정", gap: 50*time.Millisecond + 1, wantPos: 14 * time.Second},
	})
}

// chain_advances_once_per_ledger_row_even_when_enqueue_rejected(뮤테이션 49) — 사슬은 접수 게이트보다
// 앞에서 장부 행마다 한 번 전진한다. 거부된 행에서 멈추면 다음 행이 "직전 행 ≠ 사슬 행"으로 보여
// 멀쩡한 조각을 리셋으로 오판한다(도장이 조각 0 의 끝으로 되돌아간다).
func TestChainAdvancesOncePerLedgerRowEvenWhenEnqueueRejected(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1")
	s0 := f.row(r, 0, mtxiOf(recA, 0))
	r.ends[s0.Path] = playback.Ends{Video: 4 * time.Second, Audio: 3_980 * time.Millisecond}

	f.upload.accept = false // 브레이커·격리·백오프·큐 포화가 전부 이 값으로 온다
	f.row(r, 4*time.Second, mtxiOf(recA, 4*time.Second))
	f.upload.accept = true
	f.row(r, 8*time.Second, mtxiOf(recA, 8*time.Second))

	if got := f.playbackOf(1); got.PlaybackPos != 4*time.Second {
		t.Errorf("거부된 행의 ③ 요청 도장 = %v, want 4s — 표 기록은 접수 결과와 무관하다", got.PlaybackPos)
	}
	if got := f.playbackOf(2); got.PlaybackPos != 8*time.Second || got.StitchOffset != 0 {
		t.Errorf("거부 다음 행 도장 = pos %v · offset %v, want pos 8s · offset 0", got.PlaybackPos, got.StitchOffset)
	}
}

// chain_uses_tail_corrected_duration(뮤테이션 50) — dur(k−1) 은 그 행이 꼬리에서 물러나는 순간(다음
// INSERT)의 장부 길이다. 접수 때 값을 쓰면 꼬리 교정(2s→4s) 차이만큼 거짓 구멍이 생긴다.
func TestChainUsesTailCorrectedDuration(t *testing.T) {
	f := newFixture(t, 2000, 4000, 4000) // 조각 0 첫 측정 · 교정 재측정 · 조각 1
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1")
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
	r.mtxi[first.Path] = playback.Mtxi{RecorderID: recA, DTS: 10 * time.Second}
	f.mustHandle(first)

	f.makeFile("s1", segName(baseWall, 0), 2500) // 유휴 판정 뒤에도 파일이 자랐다
	first.Reason = recording.ReasonRegrown
	f.mustHandle(first)
	if got := f.ix.cursors["s1"].Tail.DurationMS; got != 4000 {
		t.Fatalf("꼬리 교정 뒤 장부 길이 = %d, want 4000(전제)", got)
	}

	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second))

	if got := f.playbackOf(1); got.PlaybackPos != 14*time.Second || got.StitchOffset != 0 {
		t.Errorf("교정 뒤 다음 행 도장 = pos %v · offset %v, want pos 14s · offset 0 — 교정 전 길이면 2초 거짓 구멍",
			got.PlaybackPos, got.StitchOffset)
	}
}

// chain_advances_by_ledger_duration_when_mtxi_unreadable(뮤테이션 51) — mtxi 를 못 읽은 행도 사슬을
// 건너뛰지 않는다: 기대값(mtxi(k−1) + dur(k−1))·표식 유지로 전진한다. 건너뛰면 다음 행이 직전 행을
// 사슬 행으로 못 알아봐 리셋으로 오판한다.
func TestChainAdvancesByLedgerDurationWhenMtxiUnreadable(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1")
	s0 := f.row(r, 0, mtxiOf(recA, 10*time.Second))
	r.ends[s0.Path] = playback.Ends{Video: 4 * time.Second, Audio: 3_980 * time.Millisecond}

	f.row(r, 4*time.Second, nil) // 판독 실패
	f.row(r, 8*time.Second, mtxiOf(recA, 18*time.Second))

	if got := f.playbackOf(1); got.PlaybackPos != 14*time.Second || !got.PosPinned {
		t.Errorf("판독 실패 행 도장 = pos %v · 고정 %v, want pos 14s(직전 pos + 장부 길이) · 고정", got.PlaybackPos, got.PosPinned)
	}
	if got := f.playbackOf(2); got.PlaybackPos != 18*time.Second || got.StitchOffset != 0 || got.PosPinned {
		t.Errorf("다음 행 도장 = pos %v · offset %v · 고정 %v, want pos 18s · offset 0 · 고정 false",
			got.PlaybackPos, got.StitchOffset, got.PosPinned)
	}
}

// mtxi_missing_uses_pinned_pos_and_remains_resweepable(루프 쪽) — 직전 보관값이 있으면 판독 실패 행도
// ③ 을 막지 않고, 도장을 기대값에 고정한 작업을 보낸다(PosPinned — 업로더가 표에 고정 줄을 적어
// 스위퍼가 판독 없이 같은 값을 쓴다). 보정값은 그때 쓰던 값을 싣는다. 발동 신호는 segment_indexed 의
// mtxi_pinned 속성이다(새 로그 키 0).
func TestMtxiMissingUsesPinnedPosAndRemainsResweepable(t *testing.T) {
	f := newFixture(t, 4000, 4000, 3000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1", "S1")
	s0 := f.row(r, 0, mtxiOf(recA, 10*time.Second))
	r.ends[s0.Path] = playback.Ends{Video: 4 * time.Second, Audio: 4 * time.Second}
	f.row(r, 4*time.Second, mtxiOf(recB, 0)) // 재접속 리셋 → offset 14s

	f.row(r, 8*time.Second, nil)                          // 판독 실패(장부 길이 3s)
	f.row(r, 12*time.Second, mtxiOf(recB, 7*time.Second)) // 4s + 3s 뒤 — 연속

	if got := f.playbackOf(2); got.PlaybackPos != 18*time.Second || got.StitchOffset != 14*time.Second || !got.PosPinned {
		t.Errorf("고정 작업 = pos %v · offset %v · 고정 %v, want pos 18s · offset 14s · 고정",
			got.PlaybackPos, got.StitchOffset, got.PosPinned)
	}
	if got := f.playbackOf(3); got.PlaybackPos != 21*time.Second || got.StitchOffset != 14*time.Second || got.PosPinned {
		t.Errorf("다음 행 = pos %v · offset %v · 고정 %v, want pos 21s · offset 14s · 고정 false",
			got.PlaybackPos, got.StitchOffset, got.PosPinned)
	}
	pinned := map[int64]any{}
	for _, a := range f.logs.attrsAll("segment_indexed") {
		pinned[a["seq"].(int64)] = a["mtxi_pinned"]
	}
	if want := map[int64]any{0: false, 1: false, 2: true, 3: false}; !reflect.DeepEqual(pinned, want) {
		t.Errorf("segment_indexed 의 mtxi_pinned = %v, want %v", pinned, want)
	}
}

// first_row_unreadable_sends_no_playback_request(뮤테이션 57) — 회차의 보관값이 없는데 mtxi 를 못
// 읽으면 ③ 요청을 내지 않는다: 도장을 지어내지 않는다(결정 B′ — 회차 첫 조각을 0 으로 빼지 않는다,
// 부기 27 — TD 분할로 연결 도중 열린 회차의 첫 도장은 0 이 아니다). 사슬은 다음 정상 판독 행이
// offset 0 으로 연다. init 은 mtxi 와 무관하게 개시 행에서 나간다.
func TestFirstRowUnreadableSendsNoPlaybackRequest(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1")

	f.row(r, 0, nil)                                       // 연결 도중 개시 — 실제 mtxi 는 600s 쯤이다
	f.row(r, 4*time.Second, mtxiOf(recA, 604*time.Second)) // 첫 정상 판독 행

	var seqs []int64
	for _, tg := range f.upload.targetsOf(index.AxisPlayback) {
		seqs = append(seqs, tg.Seq)
	}
	if !reflect.DeepEqual(seqs, []int64{1}) {
		t.Fatalf("③ 요청 seq = %v, want [1] — 보관값 없는 판독 실패 행은 요청하지 않는다", seqs)
	}
	if got := f.playbackOf(1); got.PlaybackPos != 604*time.Second || got.StitchOffset != 0 || got.PosPinned {
		t.Errorf("첫 정상 판독 행 = pos %v · offset %v · 고정 %v, want pos 604s(자기 mtxi) · offset 0 · 고정 false",
			got.PlaybackPos, got.StitchOffset, got.PosPinned)
	}
	if n := len(f.upload.targetsOf(index.AxisInit)); n != 1 {
		t.Errorf("init 요청 %d건, want 1건 — init 은 도장과 무관하다", n)
	}
}

// chain_skips_null_session_rows_and_stitches_across(뮤테이션 59) — 비귀속 행(귀속 하한 거부 등)은
// 사슬을 움직이지 않고 ③ 도 없다. 그 뒤 회차 행은 "직전 장부 행 ≠ 사슬 행"이므로 저장된 마지막 회차
// 행의 끝에 잇는다 — 비귀속 행의 미디어는 회차 밖이라 압축된다(결정 B 와 같은 형상).
func TestChainSkipsNullSessionRowsAndStitchesAcross(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "", "S1")
	s0 := f.row(r, 0, mtxiOf(recA, 10*time.Second))
	r.ends[s0.Path] = playback.Ends{Video: 4_012_411_111, Audio: 3_994_013_605}

	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second)) // 비귀속
	f.row(r, 8*time.Second, mtxiOf(recA, 18*time.Second))

	for _, tg := range f.upload.targetsOf(index.AxisPlayback) {
		if tg.Seq == 1 {
			t.Errorf("비귀속 행에 ③ 요청이 나갔다: %+v", tg)
		}
	}
	// 이음 = 조각 0 의 끝 = pos(0) + min(두 트랙 끝) = 10s + 3.994013605s.
	want := 13_994_013_605 * time.Nanosecond
	if got := f.playbackOf(2); got.PlaybackPos != want || got.StitchOffset != want-18*time.Second {
		t.Errorf("비귀속 행 건너 이음 = pos %v · offset %v, want pos %v · offset %v",
			got.PlaybackPos, got.StitchOffset, want, want-18*time.Second)
	}
}

// chain_survives_consecutive_null_rows(뮤테이션 62) — 비귀속 행이 연달아 끼어도 사슬의 dur 은 사슬 행
// 자기 장부 길이다. 두 번째 비귀속 행 때 그 행의 길이로 덮으면, 직전 조각을 다시 읽지 못해 장부
// 길이로 잇는 국면에서 이음 자리가 어긋난다.
func TestChainSurvivesConsecutiveNullRows(t *testing.T) {
	f := newFixture(t, 4000, 3000, 2000, 4000)
	r := f.injectReads() // 조각 0 의 트랙 끝은 등록하지 않는다 — 장부 길이 폴백
	f.store.scriptSessions("S1", "", "", "S1")
	f.row(r, 0, mtxiOf(recA, 10*time.Second))

	f.row(r, 4*time.Second, mtxiOf(recA, 14*time.Second)) // 비귀속, 길이 3s
	f.row(r, 7*time.Second, mtxiOf(recA, 17*time.Second)) // 비귀속, 길이 2s
	f.row(r, 9*time.Second, mtxiOf(recA, 30*time.Second))

	// 이음 = pos(0) + dur(0) = 10s + 4s — 비귀속 행의 3s·2s 가 아니다.
	if got := f.playbackOf(3); got.PlaybackPos != 14*time.Second {
		t.Errorf("연속 비귀속 뒤 이음 도장 = %v, want 14s", got.PlaybackPos)
	}
}

// 회차 전환 선판정(규칙 ⑥) — 행의 회차가 사슬 회차와 다르면(TD 분할·새 개시) 리셋·구멍 술어보다
// 회차 개시가 먼저다: offset 0 으로 새로 열고 도장은 그 행 자기 mtxi 다. 직전 회차의 offset 을
// 끌고 오면 새 회차의 도장이 옛 회차의 이음값만큼 밀린다.
func TestChainRestartsAtOwnMtxiOnSessionSwitch(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S2")
	s0 := f.row(r, 0, mtxiOf(recA, 10*time.Second))
	r.ends[s0.Path] = playback.Ends{Video: 4 * time.Second, Audio: 4 * time.Second}
	f.row(r, 4*time.Second, mtxiOf(recB, 0)) // 회차 안 리셋 → offset 14s

	f.row(r, 8*time.Second, mtxiOf(recB, 4*time.Second)) // TD 분할로 S2 개시 — 연결은 그대로다

	if got := f.playbackOf(2); got.PlaybackPos != 4*time.Second || got.StitchOffset != 0 || got.SessionID != "S2" {
		t.Errorf("새 회차 첫 도장 = pos %v · offset %v · 회차 %q, want pos 4s · offset 0 · S2",
			got.PlaybackPos, got.StitchOffset, got.SessionID)
	}
}

// G7 skew(부기 30) — skew(k) = (pdt(k) − pdt(k−1)) − (pos(k) − pos(k−1)) 를 같은 회차의 인접 쌍 중
// 보정값이 그대로인 쌍에서만 남긴다. 이어 붙인 쌍(리셋·구멍)의 차이는 압축한 유실·재접속 시간이지
// PDT 와 미디어 시계의 어긋남이 아니고, 회차 경계 쌍은 도장이 새로 시작한다.
func TestPDTMediaSkewRecordedOnlyForUnstitchedAdjacentPairs(t *testing.T) {
	f := newFixture(t, 4000, 4000, 4000, 4000, 4000)
	r := f.injectReads()
	f.store.scriptSessions("S1", "S1", "S1", "S1", "S2") // PDT 는 4초씩 전진한다(벽시계 = 장부 길이)
	f.row(r, 0, mtxiOf(recA, 10*time.Second))
	s1 := f.row(r, 4*time.Second, mtxiOf(recA, 13_990*time.Millisecond)) // 미디어가 10ms 덜 갔다
	r.ends[s1.Path] = playback.Ends{Video: 4 * time.Second, Audio: 3_980 * time.Millisecond}
	f.row(r, 8*time.Second, mtxiOf(recB, 0))                       // 재접속 리셋 — 이어 붙인 쌍
	f.row(r, 12*time.Second, mtxiOf(recB, 4_020*time.Millisecond)) // 미디어가 20ms 더 갔다
	f.row(r, 16*time.Second, mtxiOf(recB, 8*time.Second))          // S2 개시 — 회차 경계 쌍

	var got []map[string]any
	for _, a := range f.logs.attrsAll("rewind_pdt_media_skew_seconds") {
		got = append(got, map[string]any{"seq": a["seq"], "session_id": a["session_id"], "seconds": a["seconds"]})
	}
	want := []map[string]any{
		{"seq": int64(1), "session_id": "S1", "seconds": (10 * time.Millisecond).Seconds()},
		{"seq": int64(3), "session_id": "S1", "seconds": (-20 * time.Millisecond).Seconds()},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("skew 기록 = %v\nwant %v", got, want)
	}
	if n := f.logs.count(slog.LevelDebug, "rewind_pdt_media_skew_seconds"); n != len(want) {
		t.Errorf("skew 기록 Debug %d건, want %d건 — 분포 신호는 전례(loop_select_reentry_seconds)대로 Debug 다", n, len(want))
	}
}

// recorderFragment 는 playback/testdata 의 실물 녹화 조각을 at 자리의 세그먼트 파일로 놓는다.
func (f *fixture) recorderFragment(name string, at time.Duration) recording.Segment {
	f.t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "playback", "testdata", name))
	if err != nil {
		f.t.Fatalf("실물 조각 %s 읽기 실패: %v", name, err)
	}
	seg := f.segment("s1", segName(baseWall, at), len(raw), recording.ReasonHook)
	if err := os.WriteFile(seg.Path, raw, 0o600); err != nil {
		f.t.Fatalf("실물 조각 복사 실패: %v", err)
	}
	f.touch(seg.Path, time.Now().Add(-time.Hour))
	return seg
}

// 배선 — 기본 판독 관문(fsop.ReadT + playback.ReadMtxi·TrackEnds)이 실물 조각의 mtxi 와 트랙 끝을
// 읽어 도장을 만든다. 두 조각은 녹화기 표식이 달라(다른 연결) 둘째 조각이 첫 조각 끝에 이어 붙는다.
// 기대값은 playback 패키지의 실물 판독 테스트가 고정한 값(mtxi 5,987,981,859ns · 소리 끝 3,994,013,605ns)이다.
func TestChainReadsRecorderMtxiThroughFsopGate(t *testing.T) {
	f := newFixture(t, 4000, 2000)
	f.store.scriptSessions("S1", "S1")
	f.mustHandle(f.recorderFragment("segment_4s.mp4", 0))
	f.mustHandle(f.recorderFragment("segment_tail_2s.mp4", 4*time.Second)) // 녹화기 표식 cf6d… · mtxi 17,992,993,197ns

	if got := f.playbackOf(0); got.PlaybackPos != 5_987_981_859 || got.PosPinned {
		t.Errorf("실물 조각 도장 = pos %v · 고정 %v, want 5.987981859s · 고정 false", got.PlaybackPos, got.PosPinned)
	}
	want := time.Duration(5_987_981_859 + 3_994_013_605)
	if got := f.playbackOf(1); got.PlaybackPos != want || got.StitchOffset != want-17_992_993_197 {
		t.Errorf("다른 연결 조각 이음 = pos %v · offset %v, want pos %v · offset %v",
			got.PlaybackPos, got.StitchOffset, want, want-17_992_993_197)
	}
}
