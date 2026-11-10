package indexer

// 판 NULL 귀속 · E28 신호(POK-196 PDT 설계 r7 8절 「r6 그대로」 · r6 2.16 E28 신호) — 둘 다 Info slog 한 줄이고 장부 칸은 없다.
//
//   - legacy_unattributed_sum: 판 NULL 귀속 행의 PDT 식이 더한 U(InsertCommit.LegacyUnattributedMS) — U 가 0 이 아닐 때만.
//   - unattributed_run: 스트림마다 「지난 귀속 행 뒤 비귀속 행 묶음」 을 메모리에 모아, 다음 귀속 행 k 의 INSERT 때 한 줄을 내고
//     비운다. 표식(녹화기 인스턴스) 값마다 행 수와 길이를 따로 세어 줄을 낼 때 k · 앞 귀속 행의 표식과 견주어 셋으로 접는다
//     (r6 cx-N10). 묶음 뒤 귀속 행이 끝내 오지 않으면(방송 종료) 줄이 없다 — E28 은 회차가 있는 컷의 제외량이다.
//
// 분류는 로그 집계가 한다(r6 2.16): same_round 참이면 duration_ms 가 그 회차의 E28 제외 길이(세션 사이), 거짓이면
// marker_next_ms 가 새 회차 앞부분 · marker_prev_ms 가 닫힌 회차 꼬리 · marker_other_ms 가 어느 쪽인지 모르는 길이다.

import "github.com/3K-PokeClip/pokeclip-mono/media/internal/index"

// unattributedTally 는 한 스트림의 E28 누산기다. loop 단일 고루틴만 만진다.
type unattributedTally struct {
	// seen 은 이 프로세스가 이 스트림의 귀속 행을 본 적이 있는가다 — 없으면 아래 last 칸이 빈 값이다.
	seen bool
	// lastSession · lastRound · lastMarker 는 지난 귀속 행의 세션 · 이어 계산 회차 · 녹화기 표식이다(nil = 모름).
	lastSession, lastRound string
	lastMarker             *[16]byte
	// run 은 지난 귀속 행 뒤 비귀속 행 묶음이다. nil 이면 묶음이 없다.
	run *unattributedRun
}

// unattributedRun 은 비귀속 행 묶음이다. byMarker 는 표식 값마다 · unknown 은 표식 모름 행의 행 수와 길이 합이다.
type unattributedRun struct {
	firstSeq, lastSeq int64
	total             runLength
	byMarker          map[[16]byte]runLength
	unknown           runLength
	// partial 은 묶음이 이 프로세스가 앞 귀속 행을 보기 전부터 이어졌을 수 있는가다(재기동 뒤 첫 묶음 — 재적재한 꼬리가
	// 귀속 행인지는 커서가 싣지 않아 모름 쪽으로 둔다).
	partial bool
}

// runLength 는 행 수와 길이 합(ms)이다.
type runLength struct{ count, ms int64 }

func (l runLength) plus(o runLength) runLength { return runLength{l.count + o.count, l.ms + o.ms} }

// tallyUnattributed 는 커밋된 행 하나를 E28 누산기에 반영하고 두 신호를 낸다. hadRow 는 이 INSERT 앞에 그 스트림의 장부
// 행이 있었는가(커서 꼬리)다.
func (ix *Indexer) tallyUnattributed(streamID string, hadRow bool, rec index.Record, res index.InsertCommit) {
	t := ix.unattributed[streamID]
	if t == nil {
		t = &unattributedTally{}
		ix.unattributed[streamID] = t
	}
	marker := rec.Timing.Facts.Recorder
	length := runLength{1, int64(res.DurationMS)}
	if res.SessionID == "" {
		t.add(rec.Seq, marker, length, hadRow && !t.seen)
		return
	}
	if res.TimingVersion == nil && res.LegacyUnattributedMS != 0 {
		ix.log.Info("legacy_unattributed_sum", "stream_id", streamID, "seq", rec.Seq, "session_id", res.SessionID,
			"u_ms", res.LegacyUnattributedMS)
	}
	if t.run != nil {
		ix.logRun(streamID, t, res, marker)
	}
	*t = unattributedTally{seen: true, lastSession: res.SessionID, lastRound: res.TimingRoundID, lastMarker: marker}
}

// add 는 비귀속 행 하나를 묶음에 더한다. partial 은 묶음을 새로 열 때만 본다.
func (t *unattributedTally) add(seq int64, marker *[16]byte, l runLength, partial bool) {
	if t.run == nil {
		t.run = &unattributedRun{firstSeq: seq, byMarker: map[[16]byte]runLength{}, partial: partial}
	}
	r := t.run
	r.lastSeq = seq
	r.total = r.total.plus(l)
	if marker == nil {
		r.unknown = r.unknown.plus(l)
		return
	}
	r.byMarker[*marker] = r.byMarker[*marker].plus(l)
}

// logRun 은 묶음을 귀속 행 k(res · 표식 next)에서 닫으며 unattributed_run 한 줄을 낸다. 표식이 k 와 같은 행은 next, 지난 귀속
// 행과 같은 행은 prev(둘 다 같으면 next — 같은 녹화기가 회차를 이어 간다), 그 밖 · 모름은 other 다.
func (ix *Indexer) logRun(streamID string, t *unattributedTally, res index.InsertCommit, next *[16]byte) {
	r := t.run
	var toNext, toPrev runLength
	toOther := r.unknown
	for m, l := range r.byMarker {
		switch {
		case next != nil && m == *next:
			toNext = toNext.plus(l)
		case t.lastMarker != nil && m == *t.lastMarker:
			toPrev = toPrev.plus(l)
		default:
			toOther = toOther.plus(l)
		}
	}
	ix.log.Info("unattributed_run", "stream_id", streamID,
		"count", r.total.count, "duration_ms", r.total.ms, "first_seq", r.firstSeq, "last_seq", r.lastSeq,
		"prev_session_id", t.lastSession, "next_session_id", res.SessionID,
		"same_round", t.lastRound != "" && t.lastRound == res.TimingRoundID,
		"marker_next_count", toNext.count, "marker_next_ms", toNext.ms,
		"marker_prev_count", toPrev.count, "marker_prev_ms", toPrev.ms,
		"marker_other_count", toOther.count, "marker_other_ms", toOther.ms,
		"partial", r.partial)
}
