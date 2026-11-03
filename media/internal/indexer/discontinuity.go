package indexer

// H8 끊김 판정 — is_discontinuity 의 재료(POK-196 PDT 생산자 계획 4절 c6 · r6 2.8 승계 · 설계 정합 대조 1).
//
// 기준 행은 q(seq 바로 앞 장부 행 = 커서 꼬리)다. 같은 이어 계산 회차의 최신 행 p 가 아니다 — 칸 계약이 「장부 바로 앞
// 행과의 관계」 이고(계약 r23 589행 · 렌더 설계 486행), PDT · 위치 · 길이만 p 의 함수다. q 의 칸은 장부 꼬리 칸(LoadCursor ·
// advance)에서 오므로 재기동 · 재스캔 · seq 충돌 재적재 뒤에도 같은 답이다. 대입은 buildRecord 한 자리에서만 한다.

import (
	"encoding/hex"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// discontinuity 는 끊김 판정의 결과와 신호 재료다.
type discontinuity struct {
	broken       bool  // is_discontinuity
	byMarks      bool  // 칸(표식 · 번호)으로 정했다 — 거짓이면 drift 폴백이다
	skipped      int64 // 같은 표식 안에서 건너뛴 번호 수(segment_number_skipped) · 0: 건너뜀 없음
	hookMismatch bool  // 칸은 같은 녹화기 이어짐인데 훅 경계를 소비했다(discontinuity_hook_mismatch)
}

// judgeDiscontinuity 는 꼬리 q 와 조각 칸 k 의 끊김 판정이다. 순수 함수다.
//
//	q · k 둘 다 표식 · 번호 있음: ¬SameInstance(q, k)   하나라도 없음: |driftMS| > toleranceMS
//	그 뒤 훅 경계(breakHit)를 OR 로 합친다 — 0.9초 재접속은 drift 가 톨러런스 안이라 훅이 세운다(POK-36).
//
// 칸이 있으면 drift 를 보지 않는다 — 같은 녹화기의 잇단 조각이면 파일명 시각이 어긋나도(−2초 · +5001ms) 이어진 것이고,
// 번호가 건너뛰면 drift 가 0 이어도 장부에 구멍이 있다.
func judgeDiscontinuity(q, k index.TimingFacts, driftMS, toleranceMS int64, breakHit bool) discontinuity {
	if !hasMarks(q) || !hasMarks(k) {
		return discontinuity{broken: abs64(driftMS) > toleranceMS || breakHit}
	}
	same := index.SameInstance(q, k)
	d := discontinuity{broken: !same || breakHit, byMarks: true, hookMismatch: same && breakHit}
	if *q.Recorder == *k.Recorder && *k.Number > *q.Number+1 {
		d.skipped = *k.Number - *q.Number - 1
	}
	return d
}

// hasMarks 는 칸 f 에 표식과 번호가 둘 다 있는가다. 장부 CHECK 가 둘을 함께 NULL 로 두지만, 한쪽만 있는 칸도 모름으로 본다.
func hasMarks(f index.TimingFacts) bool { return f.Recorder != nil && f.Number != nil }

// logDiscontinuity 는 판정 d 의 신호를 남긴다(설계 8절 — 표식 · 번호). seq 는 k 에 줄 seq 다. 칸으로 정하지 않았으면 남길
// 것이 없다.
func (ix *Indexer) logDiscontinuity(seg recording.Segment, seq int64, q, k index.TimingFacts, d discontinuity) {
	if !d.byMarks {
		return
	}
	attrs := []any{
		"stream_id", seg.StreamID, "path", seg.Path, "seq", seq,
		"recorder_id", hex.EncodeToString(k.Recorder[:]), "prev_number", *q.Number, "number", *k.Number,
	}
	if d.skipped > 0 {
		ix.log.Warn("segment_number_skipped", append(attrs, "skipped", d.skipped)...)
	}
	if d.hookMismatch {
		ix.log.Warn("discontinuity_hook_mismatch", attrs...)
	}
}
