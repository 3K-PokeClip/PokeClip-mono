package upload

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ③ 사슬 복원 시험대(POK-196 PDT 계획 4절 c10b · T-14). 장부는 메모리 가짜(fakeLedger — 조회 둘의 뜻만 흉내), S3 는 메모리
// 가짜(fakeObjects — Putter · Getter), ② 는 실물 녹화 조각의 mtxi 만 바꿔 쓴 파일이다. 재포장은 실구현(playback.Remuxer)
// 이다 — 바이트 대조가 시험의 판정이다.

// restoreSession 회차 · 스트림 · 녹화기 표식.
const (
	benchStream  = "demo"
	benchSession = "S-1"
)

var (
	recA = [16]byte{0xaa}
	recB = [16]byte{0xbb}
)

// withMtxi 는 실물 조각 raw 의 mtxi 상자(표식 · 번호 · 누적 DTS)만 바꾼 사본이다 — 상자 크기가 같아 나머지 바이트는 그대로다.
func withMtxi(t *testing.T, raw []byte, rec [16]byte, number uint64, dts time.Duration) []byte {
	t.Helper()
	i := bytes.Index(raw, []byte("mtxi"))
	if i < 0 {
		t.Fatal("픽스처에 mtxi 가 없다")
	}
	out := append([]byte(nil), raw...)
	p := i + 4 + 4 // 형식 뒤 FullBox 머리
	copy(out[p:], rec[:])
	binary.BigEndian.PutUint64(out[p+16:], number)
	binary.BigEndian.PutUint64(out[p+24:], uint64(dts))
	return out
}

// withoutMtxi 는 머리말의 mtxi 상자를 free 로 바꾼 사본이다 — 녹화기가 mtxi 를 쓰지 않은 조각(결정적 부재)의 꼴이다.
func withoutMtxi(t *testing.T, raw []byte) []byte {
	t.Helper()
	i := bytes.Index(raw, []byte("mtxi"))
	if i < 0 {
		t.Fatal("픽스처에 mtxi 가 없다")
	}
	out := append([]byte(nil), raw...)
	copy(out[i:], "free")
	return out
}

// fakeLedger 는 ChainLedger 의 메모리 가짜다 — 구멍 조회는 index.LoadPlaybackHoles 와 같은 뜻(③ pending · failed · 닻 = 앞선
// ③ uploaded 최신 행), 사슬 행 조회는 seq 구간이다.
type fakeLedger struct {
	mu       sync.Mutex
	rows     map[int64]index.PlaybackChainRow
	holesErr error
	queries  int
	// beforeCutoff 는 구멍 조회의 자격 밖 행이다(컷오프 앞 — 구멍으로도 닻으로도 나오지 않는다).
	beforeCutoff map[int64]bool
	// ranges 는 사슬 행 조회가 받은 구간이다(from · to).
	ranges [][2]int64
}

func (l *fakeLedger) PlaybackHoles(_ context.Context, sessionID string) ([]index.PlaybackHole, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.queries++
	if l.holesErr != nil {
		return nil, l.holesErr
	}
	var out []index.PlaybackHole
	var anchor *int64
	for _, seq := range l.seqsLocked() {
		r := l.rows[seq]
		if r.SessionID != sessionID || l.beforeCutoff[seq] {
			continue
		}
		switch r.PlaybackUploadState {
		case index.UploadStateUploaded:
			s := seq
			anchor = &s
		case index.UploadStatePending, index.UploadStateFailed:
			out = append(out, index.PlaybackHole{Seq: seq, State: r.PlaybackUploadState, AnchorSeq: anchor})
		}
	}
	return out, nil
}

func (l *fakeLedger) PlaybackChainRows(_ context.Context, _ string, from, to int64) ([]index.PlaybackChainRow, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.queries++
	l.ranges = append(l.ranges, [2]int64{from, to})
	var out []index.PlaybackChainRow
	for _, seq := range l.seqsLocked() {
		if seq >= from && seq <= to {
			out = append(out, l.rows[seq])
		}
	}
	return out, nil
}

func (l *fakeLedger) seqsLocked() []int64 {
	seqs := make([]int64, 0, len(l.rows))
	for s := range l.rows {
		seqs = append(seqs, s)
	}
	sort.Slice(seqs, func(i, j int) bool { return seqs[i] < seqs[j] })
	return seqs
}

// ledgerStore 는 업로더에 넘길 장부다 — UploadStore 에 ChainLedger 가 붙은 꼴(cmd 배선과 같다).
type ledgerStore struct {
	*fakeUploadStore
	*fakeLedger
}

// fakeObjects 는 S3 의 메모리 가짜다(Putter · Getter). getErr 가 있으면 GET · HEAD 가 그 오류다(접근 거부 · 시한 주입).
type fakeObjects struct {
	mu      sync.Mutex
	objs    map[string][]byte
	getErr  error
	gets    []string
	heads   []string
	putKeys []string
}

func newFakeObjects() *fakeObjects { return &fakeObjects{objs: map[string][]byte{}} }

func (o *fakeObjects) Put(_ context.Context, key string, body io.Reader, size int64, _ index.Axis) error {
	b, err := io.ReadAll(io.LimitReader(body, size))
	if err != nil {
		return err
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	o.objs[key] = b
	o.putKeys = append(o.putKeys, key)
	return nil
}

func (o *fakeObjects) Get(_ context.Context, key string) ([]byte, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.gets = append(o.gets, key)
	if o.getErr != nil {
		return nil, o.getErr
	}
	b, ok := o.objs[key]
	if !ok {
		return nil, fmt.Errorf("GET key=%q: %w", key, ErrObjectNotFound)
	}
	return append([]byte(nil), b...), nil
}

func (o *fakeObjects) Head(_ context.Context, key string) (int64, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.heads = append(o.heads, key)
	if o.getErr != nil {
		return 0, false, o.getErr
	}
	b, ok := o.objs[key]
	return int64(len(b)), ok, nil
}

func (o *fakeObjects) set(key string, b []byte) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.objs[key] = b
}

func (o *fakeObjects) getCount() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return len(o.gets)
}

// restoreBench 는 한 시험의 업로더 · 장부 · S3 · 녹화 디렉터리다.
type restoreBench struct {
	t      *testing.T
	u      *Uploader
	cap    *logCapture
	dir    string
	store  *fakeUploadStore
	ledger *fakeLedger
	objs   *fakeObjects
	raw    []byte
	// srcs 는 행마다 ② 바이트다 — 시험이 기대값(실시간 사슬)을 계산하는 재료다.
	srcs map[int64][]byte
}

func newRestoreBench(t *testing.T) *restoreBench {
	t.Helper()
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatalf("실물 조각 읽기 실패: %v", err)
	}
	b := &restoreBench{t: t, store: &fakeUploadStore{}, ledger: &fakeLedger{rows: map[int64]index.PlaybackChainRow{}},
		objs: newFakeObjects(), raw: raw, srcs: map[int64][]byte{}}
	b.u, b.cap, b.dir, _ = newPlaybackUploader(t, ledgerStore{b.store, b.ledger}, b.objs, playback.Remuxer{}, nil)
	return b
}

// benchRow 는 시험대 행 하나의 대본이다.
type benchRow struct {
	seq      int64
	session  string // "" 이면 비귀속 행
	rec      [16]byte
	number   uint64
	dts      time.Duration
	playback index.UploadState // ③ 상태(빈 값 = pending)
	archive  index.UploadState // ② 상태(빈 값 = pending)
	noLocal  bool              // 로컬 ② 를 지운다(외부 삭제)
	src      []byte            // nil 이면 withMtxi(raw, rec, number, dts)
	nullFact bool              // 장부 원래 사실 칸을 비운다(판독 포기 행)
}

// timing 은 ② 바이트의 시각 사실이다(장부 칸 · 길이의 재료).
func (b *restoreBench) timing(src []byte) playback.SegmentTiming {
	b.t.Helper()
	st, err := playback.ReadTiming(bytes.NewReader(src))
	if err != nil {
		b.t.Fatalf("ReadTiming 실패: %v", err)
	}
	return st
}

// step 은 영상 칸이 맞닿게 잇는 mtxi 간격(영상 끝 − 영상 시작)이다.
func (b *restoreBench) step() time.Duration {
	st := b.timing(b.raw)
	return st.Video.End - st.Video.Start
}

// add 는 행 하나를 장부 · 디스크 · S3 에 놓는다. ③ uploaded 행의 객체는 실시간 사슬의 도장(want — 부르는 쪽이 정한다)으로
// 만들어 put 으로 넣는다(setPlaybackObject).
func (b *restoreBench) add(r benchRow) {
	b.t.Helper()
	src := r.src
	if src == nil {
		src = withMtxi(b.t, b.raw, r.rec, r.number, r.dts)
	}
	b.srcs[r.seq] = src
	name := fmt.Sprintf("seg%d.mp4", r.seq)
	path := filepath.Join(b.dir, benchStream, name)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		b.t.Fatal(err)
	}
	if !r.noLocal {
		if err := os.WriteFile(path, src, 0o644); err != nil {
			b.t.Fatal(err)
		}
	}
	size := int64(len(src))
	row := index.PlaybackChainRow{Seq: r.seq, SessionID: r.session, LocalPath: path, Bytes: &size,
		S3Key:       fmt.Sprintf("streams/%s/2026-11-04/05/seg_%06d.m4s", benchStream, r.seq),
		UploadState: stateOr(r.archive), PlaybackS3Key: segKeyOf(b.t, benchStream, r.seq),
		PlaybackUploadState: stateOr(r.playback), DurationMS: 4000}
	if st, err := playback.ReadTiming(bytes.NewReader(src)); err == nil && st.Video != nil {
		row.DurationMS = int32((st.Video.End - st.Video.Start).Milliseconds())
		if st.Mtxi != nil && !r.nullFact {
			rec, n := st.Mtxi.RecorderID, int64(st.Mtxi.SegmentNumber)
			first, end := st.Mtxi.DTS+st.Video.Start, st.Mtxi.DTS+st.Video.End
			row.Timing = index.TimingFacts{Recorder: &rec, Number: &n, VideoFirst: &first, VideoEnd: &end}
		}
	}
	if row.UploadState == index.UploadStateUploaded {
		b.objs.set(row.S3Key, src)
	}
	b.ledger.mu.Lock()
	b.ledger.rows[r.seq] = row
	b.ledger.mu.Unlock()
}

func stateOr(s index.UploadState) index.UploadState {
	if s == "" {
		return index.UploadStatePending
	}
	return s
}

// produce 는 행 seq 의 ② 를 pos 로 재포장한 ③ 조각이다.
func (b *restoreBench) produce(seq int64, pos time.Duration) []byte {
	b.t.Helper()
	out, err := playback.Remuxer{}.Produce(context.Background(), playback.Request{Input: bytes.NewReader(b.srcs[seq]), PlaybackPos: pos})
	if err != nil {
		b.t.Fatalf("seq %d 재포장 실패: %v", seq, err)
	}
	return out.Seg
}

// setPlaybackObject 는 행 seq 의 ③ 객체를 pos 로 만든 바이트로 놓는다.
func (b *restoreBench) setPlaybackObject(seq int64, pos time.Duration) {
	b.t.Helper()
	b.objs.set(segKeyOf(b.t, benchStream, seq), b.produce(seq, pos))
}

// liveChain 은 실시간 사슬이 그 행들에서 낼 링크다 — 회차 첫 행부터 같은 접기(playback.RestoreChain)를 같은 재료(장부 칸 ·
// 길이, 이음 끝 = 앞 행 ② 의 먼저 끝난 트랙 끝)로 돌린다. 실시간 사슬과 이 접기가 같음은 indexer 의 접기 대조 시험이 본다.
func (b *restoreBench) liveChain(passed ...int64) map[int64]playback.Link {
	b.t.Helper()
	pass := map[int64]bool{}
	for _, s := range passed {
		pass[s] = true
	}
	var rows []playback.SourceTiming
	b.ledger.mu.Lock()
	for _, seq := range b.ledger.seqsLocked() {
		r := b.ledger.rows[seq]
		src := playback.SourceTiming{Seq: seq, SessionID: r.SessionID, Recorder: r.Timing.Recorder, Number: r.Timing.Number,
			VideoFirst: r.Timing.VideoFirst, VideoEnd: r.Timing.VideoEnd, Dur: time.Duration(r.DurationMS) * time.Millisecond}
		if st, err := playback.ReadTiming(bytes.NewReader(b.srcs[seq])); err == nil && !pass[seq] {
			src.Mtxi = st.Mtxi
		}
		rows = append(rows, src)
	}
	b.ledger.mu.Unlock()
	rule := func(prev playback.Link) time.Duration {
		ends, err := playback.TrackEnds(bytes.NewReader(b.srcs[prev.Seq]))
		if err != nil {
			return prev.Pos + prev.Dur
		}
		return prev.Pos + min(ends.Video, ends.Audio)
	}
	out := map[int64]playback.Link{}
	for _, l := range playback.RestoreChain(nil, rows, rule, sameInstanceLink) {
		out[l.Seq] = l
	}
	return out
}

// queued 는 큐에 든 ③ 작업을 꺼내 seq 순으로 돌려준다.
func (b *restoreBench) queued() []index.UploadTarget {
	var out []index.UploadTarget
	for {
		select {
		case j := <-b.u.queue:
			b.u.gate.releaseInflight(j.key())
			out = append(out, j.target)
		default:
			sort.Slice(out, func(i, k int) bool { return out[i].Seq < out[k].Seq })
			return out
		}
	}
}

// restore 는 그 회차의 복원을 한 번 돈다(고루틴 없이 — 시험이 결과를 그 자리에서 본다).
func (b *restoreBench) restore() {
	b.u.restoreSession(restoreWake{streamID: benchStream, sessionID: benchSession})
}

// offset 은 표의 seq 보정값이다(없으면 거짓).
func (b *restoreBench) offset(seq int64) (time.Duration, bool) {
	return b.u.sessions.offsetAt(benchSession, seq)
}
