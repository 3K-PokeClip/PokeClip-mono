package index

// 판 2 섞기(설계 2.5 · 2.6)의 입출력 타입이다. 타입과 이름 상수만 있다 — 식
// (MixV2)과 문턱 상수는 pdt_mix.go 에 둔다. 타입을 먼저 두는 까닭은 store ·
// indexer 배선이 겹침 처리 결정(K1) 전에도 컴파일되게 하려는 것이다(계획 0절).

// TimingInput 은 인덱서가 지금 조각 파일에서 읽어 넘기는 재료다.
type TimingInput struct {
	Facts            TimingFacts
	HeadVideo        bool  // 머리 트랙이 영상이다
	VideoOwnMS       int32 // 파일 안 영상 길이 B − A · 0: 영상 없음
	LegacyDurationMS int32 // mvhd — H7 이 1 이상 보장
}

// PrevRow 는 트랜잭션 안에서 읽은 p(seq < k 이고 timing_round_id = Round 인
// 최신 행)다.
type PrevRow struct {
	Seq, StartPTSMS int64
	DurationMS      int32
	PDTMS           *int64 // playback_pdt(ms) · nil: NULL
	Version         *int16 // timing_version · nil: 판 NULL
	Facts           TimingFacts
}

// LedgerView 는 k 를 쓰는 트랜잭션이 장부에서 읽은 것이다. Round 가 nil 이면
// 회차 없음(FloorRejected 면 라이브 중 귀속 하한 거부)이다. NewRound 면 k 가 새
// 회차를 연다. Last 는 seq < k 최신 행 q 의 시작과 길이다(nil: q 없음 — 회차
// 첫 행 · 하한 거부 행의 위치 기준).
type LedgerView struct {
	Prev *PrevRow
	Last *struct {
		StartPTSMS int64
		DurationMS int32
	}
	Round         *string
	NewRound      bool
	FloorRejected bool
}

// Branch 는 판 2 행을 쓴 갈래다. 값은 신호 속성 · InsertCommit 으로 나가는
// 이름이라 바꾸지 않는다.
type Branch string

const (
	// BranchRoundFirst 는 회차 첫 행이다 — PDT = w. 까닭은 TimingV2.ResetReason.
	BranchRoundFirst Branch = "round_first"
	// BranchContinue 는 견주지 않고 앞 끝에 잇는 갈래다 — PDT = E.
	BranchContinue Branch = "continue"
	// BranchJoin 은 견준 차가 문턱 안이라 앞 끝에 잇는 갈래다 — PDT = E.
	BranchJoin Branch = "join"
	// BranchNewChapterGap 은 짐작이 문턱 넘게 늦어 새 장을 여는 갈래다 — PDT = w.
	BranchNewChapterGap Branch = "new_chapter_gap"
	// BranchNewChapterOverlap 은 짐작이 문턱 넘게 일러 1초 미만 겹침으로 새 장을
	// 여는 갈래다(겹침 처리 (나)).
	BranchNewChapterOverlap Branch = "new_chapter_overlap"
)

// TimingV2 는 판 2 행의 세 칸 · 갈래와 신호 재료다.
type TimingV2 struct {
	PDTMS, StartPTSMS int64
	DurationMS        int32 // 늘 1 이상
	Branch            Branch
	ResetReason       string // stream_first · new_round · no_round · floor_rejected
	Broken            string // prev_missing · version_mismatch · ""
	SameInstance      bool
	Compared          bool
	DeltaMS           int64  // Compared 일 때만 뜻이 있다
	LimitedBy         string // overlap 일 때 이긴 항: w · overlap_cap · end_lead · start_lead
}
