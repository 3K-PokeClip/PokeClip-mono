package publish

// 발행 워커의 조립 — 세대 규약을 도는 발행자(Publisher)와 작업을 나르는 차선(Lanes) · 두 쪽이 주고받는 값.
//
// 발행자는 상태를 들지 않는다. 한 회차 목록의 발행 상태(P · 본 세대 · fence · 화해 예약)는 루프가 스트림마다
// 들고 작업에 값으로 넘기며, 작업이 돌려준 새 값으로 갈아 끼운다(계획 4.5 A1 결정 3 · A3 결정 1 — 워커는
// 값만 받는다). 그래서 발행자는 여러 스트림의 작업이 동시에 불러도 된다.
//
// 이 커밋에는 호출자가 없다 — 루프 배선(rewindDone case · 입력 공급 · P 보관)은 커밋 7 이다.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// abortedLog 는 발행 경로의 중단 · 포기를 남기는 로그 키다 — 경로는 reason 속성으로 가른다(계획 4.5 A1
// 「중단 · 포기 로그」). 틱만 포기하는 갈래는 WARN, 발행이 서는 갈래는 ERROR 다.
const abortedLog = "rewind_publish_aborted"

// gapPublishedLog 는 GAP 원장에 GAP 이 새로 섰다는 신호다 — 설계 관측 목록의 rewind_gap_published_total{reason}(계획
// 5절 기정 신호)을 메트릭 기반이 없어 로그 키로 남긴다(판단 J28). GAP 트랜잭션의 커밋 뒤 한 줄이고(원장이 목록의
// 진실원이 된 시각 — PUT 이 실패해도 다음 틱이 싣는다) 조각 하나를 GAP 으로 내준 사건이라 WARN 이다.
const gapPublishedLog = "rewind_gap_published_total"

// 중단 · 포기 사유 — 계획 4.5 A1 로그 표의 reason 값 전부(r45 여덟 · r50 일곱)와 결과 모름 둘(p0_unknown ·
// p4_unknown — 커밋 3 r4 처분 R4-3), 쓰는 쪽 범위 검사 하나(meta_out_of_range — r5 처분 R5-2), GAP 트랜잭션 실패
// 하나(gap_tx_failed — 커밋 4 · 판단 J25)다.
const (
	reasonMetaInvalid        = "meta_invalid"         // ERROR — R2 메타 누락 · 해석 실패(발행 정지)
	reasonMetaOutOfRange     = "meta_out_of_range"    // ERROR — 올릴 판의 메타 정수나 끝 조각 DSN 이 2^53−1 을 넘음(발행 정지 — checkMetaRange)
	reasonETagSourceMismatch = "etag_source_mismatch" // WARN — P0 manifest_etag ≠ P.ETag(결정 8)
	reasonPublishDeadline    = "publish_deadline"     // WARN — P3 앞 마감 지남(결정 9)
	reasonUpdate412          = "update_412"           // WARN — 갱신 412(결정 10)
	reasonRevokeUnknown      = "revoke_unknown"       // WARN — P2′ 0행 · 결과 모름(A2 결정 2)
	reasonS4AfterPublish     = "s4_after_publish"     // ERROR — 첫 PUT 뒤 S4 위반(A2 결정 3)
	reasonValidate           = "validate"             // ERROR — S1–S7 위반(속성 check)
	reasonDiscSeqDecrease    = "disc_seq_decrease"    // ERROR — S2 DISC-SEQ 감소(A2 결정 4)
	reasonP0NoRow            = "p0_no_row"            // WARN — P0 0행(설계 4.4.3 — CAS 거부)
	reasonP0Unknown          = "p0_unknown"           // WARN — P0 결과 모름(DB 오류 — 서버 커밋 여부를 모른다)
	reasonCreate412          = "create_412"           // WARN — 최초 생성 412(설계 4.4.5)
	reasonUpdate404          = "update_404"           // WARN — 갱신 대상 없음(설계 4.4.5)
	reasonPutConflict        = "put_conflict"         // WARN — 409 재시도 뒤에도 409(결정 9)
	reasonPutUnknown         = "put_unknown"          // WARN — PUT 결과 모름
	reasonP4NoRow            = "p4_no_row"            // WARN — P4 0행(설계 4.4.3 — CAS 거부)
	reasonP4Unknown          = "p4_unknown"           // WARN — P4 결과 모름(DB 오류 — 서버 커밋 여부를 모른다)
	reasonHeadFailed         = "head_failed"          // ERROR — Head 실패(403 포함)
	reasonGapTxFailed        = "gap_tx_failed"        // WARN — GAP 트랜잭션의 P0 뒤 실패(속성 stage = insert · commit — 판단 J25)
)

// Options 는 발행자의 설정이다. 설계값 셋은 설계 4.4.3 · 6.2 가 값을 준 것이고(계획 5절 설계값 목록) 값의
// 집은 이 구조체 하나다 — Go 쪽 판정(결정 9 의 P3 앞 마감 · lazy 갱신 시각)과 SQL 인자가 같은 값을 본다(판단
// J12).
type Options struct {
	// Writer 는 이 프로세스의 fence 토큰(writer_fence)이다. 프로세스마다 달라야 하고 비면 안 된다 — 만드는 것은
	// 조립점 몫이다(커밋 7).
	Writer string
	// BaseURL 은 목록의 조각 · MAP URI 앞머리다(REWIND_PUBLIC_BASE_URL — 검증은 설정 몫, 커밋 7). 끝에 '/' 가
	// 없어야 한다(rewind.Render).
	BaseURL string
	// Log 가 nil 이면 slog.Default() 다.
	Log *slog.Logger

	// Lease 는 fence lease 다(설계 6.2 — 5초). P0 · 획득 · 갱신이 fence_expires_at 을 now() + Lease 로 둔다.
	Lease time.Duration
	// LazyRenewAfter 는 보조 갱신의 간격이다(설계 6.2 — 3.5초). 마지막 갱신 뒤 이만큼 지났을 때만 갱신한다.
	LazyRenewAfter time.Duration
	// PublishTimeout 은 설계의 T_pub — PUT 한 번의 예산이다(설계 4.4.3 P3 — 2.0초). R1 Head 의 마감도 같은
	// 값이다(판단 J8 — 저장소 요청 한 번의 예산).
	PublishTimeout time.Duration

	// 정체 사다리의 설계값 다섯이다(설계 4.6.3 · 4.6.5 · 판단 J21 — 사다리 EvaluateLadder 만 쓰고 check 가 양수 · 관계를
	// 본다).
	//
	// E2EBudget 은 설계의 E2E_BUDGET — 조각이 끝난 뒤 목록에 실리기까지의 예산이다(2.0초 · F-7 전 잠정). holdAge 의
	// 기준 dueAt 에 더한다.
	E2EBudget time.Duration
	// ExpediteAfter 는 L1 문턱이다(holdAge 0.5초).
	ExpediteAfter time.Duration
	// GapHold 는 설계의 GAP_HOLD — L2 문턱이다(holdAge 2.0초).
	GapHold time.Duration
	// RefreshObligation 은 목록 갱신 의무다(9.0초 = 1.5 × TD 6 — ADR-043). TD 분할 회차(TD 7 이상)의 실제 의무는 1.5 ×
	// TD 라 L4 가 이르게 뜬다(안전 쪽).
	RefreshObligation time.Duration
	// EdgeDelay 는 설계의 T_edge — 엣지가 새 목록을 보이기까지의 시간이다(1.5초 = TTL 1.0 + 전파 0.5). L4 는 stallAge
	// 가 RefreshObligation − EdgeDelay 에 닿으면 뜬다.
	EdgeDelay time.Duration

	// statementTimeout 은 DB 문장 하나(GAP 틱은 트랜잭션 하나)의 ctx 시한이다 — 0 이면 index.TxnDeadline 이다(stmtCtx).
	// 같은 패키지의 시험만 바꾼다: 루프 ctx 는 살아 있고 문장만 시한을 넘는 국면을 10초 기다리지 않고 만든다.
	statementTimeout time.Duration
}

// DefaultOptions 는 설계값을 채운 설정이다.
func DefaultOptions(writer, baseURL string) Options {
	return Options{
		Writer:         writer,
		BaseURL:        baseURL,
		Lease:          5 * time.Second,
		LazyRenewAfter: 3500 * time.Millisecond,
		PublishTimeout: 2 * time.Second,

		E2EBudget:         2 * time.Second,
		ExpediteAfter:     500 * time.Millisecond,
		GapHold:           2 * time.Second,
		RefreshObligation: 9 * time.Second,
		EdgeDelay:         1500 * time.Millisecond,
	}
}

// check 는 설정이 세대 규약을 돌릴 수 있는지 본다. 관계가 어긋나면 모든 틱이 멈추는데 그 까닭이 로그에
// 드러나지 않는다 — Lease ≤ T_pub 면 P3 앞 마감(m0 + Lease − T_pub)이 P0 앞이라 모든 틱이 PUT 없이 끝난다. 사다리
// 설계값도 본다 — 0 이하면 정상 경로(holdAge 음수)의 조각도 L2 에 닿아 GAP 줄로 나가고(GAP 줄은 되돌리지 않는다), L1
// 문턱이 GAP_HOLD 보다 길면 백오프를 당기기 전에 GAP 을 내며, T_edge 가 갱신 의무 이상이면 L4 가 정체 없이도 뜬다.
func (o Options) check() error {
	switch {
	case o.Writer == "":
		return errors.New("publish: writer 토큰이 비었다")
	case o.BaseURL == "" || o.BaseURL[len(o.BaseURL)-1] == '/':
		return fmt.Errorf("publish: base URL %q 를 쓸 수 없다 — 비었거나 '/' 로 끝난다", o.BaseURL)
	case o.PublishTimeout <= 0 || o.LazyRenewAfter <= 0:
		return fmt.Errorf("publish: 설계값이 0 이하다(T_pub %v · lazy %v)", o.PublishTimeout, o.LazyRenewAfter)
	case o.Lease <= o.PublishTimeout || o.Lease <= o.LazyRenewAfter:
		return fmt.Errorf("publish: lease %v 가 T_pub %v · lazy %v 보다 길어야 한다",
			o.Lease, o.PublishTimeout, o.LazyRenewAfter)
	case o.E2EBudget <= 0 || o.ExpediteAfter <= 0 || o.GapHold <= 0 || o.RefreshObligation <= 0 || o.EdgeDelay <= 0:
		return fmt.Errorf("publish: 사다리 설계값이 0 이하다(E2E %v · L1 %v · GAP_HOLD %v · 갱신 의무 %v · T_edge %v)",
			o.E2EBudget, o.ExpediteAfter, o.GapHold, o.RefreshObligation, o.EdgeDelay)
	case o.ExpediteAfter > o.GapHold:
		return fmt.Errorf("publish: L1 문턱 %v 가 GAP_HOLD %v 보다 길다", o.ExpediteAfter, o.GapHold)
	case o.EdgeDelay >= o.RefreshObligation:
		return fmt.Errorf("publish: T_edge %v 가 갱신 의무 %v 보다 짧아야 한다", o.EdgeDelay, o.RefreshObligation)
	}
	return nil
}

// Publisher 는 한 writer 의 세대 규약이다 — 설계 4.4.3 의 P0–P4 · R1–R4 와 설계 6.2 의 writer fence 를 돈다.
//
// DB 문장은 풀에서 한 문장씩 보낸다(판단 J7 — 평시 틱에 트랜잭션 왕복을 더하지 않는다). GAP 틱만 P0 과 원장 INSERT 를
// 트랜잭션 하나로 보낸다(판단 J20). ctx 시한은 문장마다(GAP 틱은 트랜잭션 하나에) index.TxnDeadline 이다(stmtCtx).
// 저장소는 조건부 쓰기만 한다(Store). 상태를 들지 않아 여러 고루틴에서 동시에 불러도
// 된다 — 한 회차의 작업은 차선이 한 번에 하나로 묶는다.
type Publisher struct {
	pool  *pgxpool.Pool
	store Store
	opt   Options
	log   *slog.Logger
	// now 는 단조 시계다 — 결정 9 의 m0 · P3 시각과 lazy 갱신 판정이 읽는다. 테스트가 바꾼다.
	now func() time.Time
}

// New 는 pool 과 store 로 도는 발행자를 만든다.
func New(pool *pgxpool.Pool, store Store, opt Options) (*Publisher, error) {
	if pool == nil || store == nil {
		return nil, errors.New("publish: pool · store 가 없다")
	}
	if err := opt.check(); err != nil {
		return nil, err
	}
	log := opt.Log
	if log == nil {
		log = slog.Default()
	}
	return &Publisher{pool: pool, store: store, opt: opt, log: log, now: time.Now}, nil
}

// stmtCtx 는 DB 문장 하나의 ctx 다(GAP 틱은 트랜잭션 하나 · 그 되돌림 하나에 하나씩) — 시한은 Options.statementTimeout
// 이고 0 이면 index.TxnDeadline 이다(판단 J7 · J20).
func (p *Publisher) stmtCtx(ctx context.Context) (context.Context, context.CancelFunc) {
	timeout := p.opt.statementTimeout
	if timeout == 0 {
		timeout = index.TxnDeadline
	}
	return context.WithTimeout(ctx, timeout)
}

// Manifest 는 목록 URL 에 저장된 판 하나다 — 계획 4.5 A1 결정 3 의 P(자기기술과 ETag 한 쌍). 두 값은
// 발행 성공(이 틱이 실은 메타 + P3 의 ETag)이나 R1 Head 한 번에서 함께 온다.
type Manifest struct {
	rewind.Published
	// ETag 는 그 판의 ETag 다 — 다음 갱신 P3 의 If-Match 다(불투명한 문자열 — 계획 4.5 A1 은 ETag 가 MD5 인지에
	// 기대지 않는다).
	ETag string
}

// State 는 한 회차 목록의 발행 상태다. 루프가 스트림마다 들고 작업에 값으로 넘기며, 작업이 돌려준
// Outcome.State 로 갈아 끼운다. 영값은 「아직 아무것도 모른다」다 — 첫 틱이 fence 를 얻고 화해부터 한다(설계
// 6.2 · 계획 4.5 A1 결정 5). 소유 회차가 바뀌면 영값에서 다시 시작한다.
type State struct {
	// Prev 는 그 목록 URL 의 마지막 발행본 P 다. nil 이면 발행본이 없다 — 다음 P3 은 IfAbsent 다(최초 생성
	// 경로 · 결정 6). 루프는 이 P 로 다음 DISC-SEQ 를 센다(NextDiscontinuitySequence).
	Prev *Manifest
	// Gen 은 이 writer 가 마지막으로 본 DB manifest_gen 이다 — P0 의 $genSeen(설계 4.4.3).
	Gen int64
	// FenceHeld 는 이 writer 가 그 회차의 fence 를 쥐었다고 보는가다. 거짓이면 다음 틱이 먼저 획득한다.
	FenceHeld bool
	// RenewedAt 은 fence lease 를 마지막으로 늘린 문장(P0 · 획득 · lazy 갱신)을 보낸 시각이다 — lazy 갱신의
	// 기준(설계 6.2). DB 는 그보다 늦게 now() 를 재므로 실제 lease 끝은 이 값 + Lease 보다 늦다(안전한 쪽).
	RenewedAt time.Time
	// ReconcileDue 는 P · Gen 을 저장소 · DB 와 다시 맞춰야 하는가다 — 참이면 다음 틱이 Reconcile 부터 한다
	// (결정 9 「Reconcile 예약」 · Head 실패 · 메타 해석 실패 · 획득 직후).
	ReconcileDue bool

	// lastAbort 는 이 목록에 마지막으로 남긴 중단 · 포기다 — 같은 것은 한 번만 남긴다(abortKey).
	lastAbort abortKey
}

// abortKey 는 중단 · 포기 로그 1회 가드의 단위다 — 사유와 stage 속성 값(없으면 빈 값)이다. 처치가 다른 갈래를 한 사유의
// stage 로 가르는 gap_tx_failed(insert · commit — 판단 J25)는 갈래마다 한 줄을 남긴다(c4-fix3 개정 4 · 보안 r3 R3-L1).
type abortKey struct{ reason, stage string }

// noteAbort 는 중단 · 포기 k 를 적고 로그를 남길 차례인지 돌려준다 — 같은 사유 · 같은 stage 가 이어지면 처음 한 번만
// 참이다(계획 4.5 A1 「중단 · 포기 로그」 — C3 1회성 가드와 같은 형).
func (s *State) noteAbort(k abortKey) bool {
	if s.lastAbort == k {
		return false
	}
	s.lastAbort = k
	return true
}

// clearAbort 는 틱이 끝까지 갔다고 적는다 — 그 뒤의 중단 · 포기는 같은 사유여도 다시 남긴다.
func (s *State) clearAbort() { s.lastAbort = abortKey{} }

// Outcome 은 발행 작업 하나(틱 · lazy 갱신)의 결과다.
type Outcome struct {
	// State 는 이 작업 뒤의 발행 상태다 — 루프가 그대로 갈아 끼운다.
	State State
	// Published 는 이 틱이 새 판을 발행했는가다(P3 · P4 성공). 새 판은 State.Prev 다.
	Published bool
	// Revoked 는 이 틱이 계승을 취소했는가다(P2′ 1행 — 계획 4.5 A2 결정 2). 루프가 캐시에 반영한다(커밋 5 · 7).
	Revoked bool
	// DemandLoad 는 그 스트림에 요구 적재를 내야 하는가다 — P2′ 가 0행이거나 결과를 모른다(A2 결정 2) · GAP
	// 트랜잭션의 COMMIT 결과를 모른다(판단 J25). A3 결정 6 넷째 부류이고 힌트는 없다.
	DemandLoad bool
	// GapSeq 는 이 GAP 틱에서 선 GAP 의 seq 다 — 원장 행을 새로 넣었든 이미 있었든 싣는다(판단 J18). PUT 이 실패해도
	// 싣는다(원장 행은 이미 커밋됐다). 루프가 cache.ApplyPublishedGap 으로 반영한다(커밋 7). nil 이면 선 GAP 이 없다.
	GapSeq *int64
	// UploadedSeq 는 GAP 틱이 DB 에서 GAP 후보를 이미 uploaded 로 봐 GAP 을 넣지 않았을 때 그 seq 다(J18 · J26 — PUT
	// 없이 끝났다). 루프가 cache.ApplyPlaybackUploaded 로 반영하고, 다음 평시 틱이 그 행을 일반 줄로 싣는다.
	UploadedSeq *int64
	// Err 는 중단 · 포기 표 밖의 실패다 — 입력 결함(키 · 렌더)과 표에 갈래가 없는 DB 문장(획득 · 갱신 · R3 · R4 ·
	// GAP 트랜잭션의 BEGIN)의 오류. 부른 쪽 ctx(루프 수명)가 끝나 멈춘 틱도 그 ctx 오류를 여기에 싣는다(로그 · 화해
	// 없이). 발행자는 이것을 로그하지 않는다(부른 쪽 몫). Err 가 있어도 State · Revoked · DemandLoad · GapSeq ·
	// UploadedSeq 는 유효하다 — 루프가 모두 반영한다(커밋 7 · 예: GAP 이 선 틱의 PUT 뒤 화해가 실패해도 GapSeq 는
	// 커밋된 원장 행이다).
	Err error
}

// keyComponent 는 목록 키 성분에 쓸 수 있는 문자다 — playback 의 조각 · init 키 성분 검사(`playback/key.go`
// checkComponent)와 같은 규칙이다.
var keyComponent = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

// manifestKey 는 회차 sessionID 의 되감기 목록 객체 키다 — 목록 URL /dvr/{stream}/{session}/index.m3u8 의
// 경로 그대로다(설계 4.5.1).
//
//	dvr/{streamID}/{sessionID}/index.m3u8
//
// 성분이 키에 그대로 실리므로 검사한다. Store 는 키를 검증하지 않고 SDK 는 점 구간(`..`)을 정규화하지 않고
// 보낸다(보안 확인 S-3) — 경로 구분자나 상위 참조가 들어오면 다른 객체를 가리킨다. `..` · `.` 는 문자
// 규칙을 통과하므로 따로 막는다.
func manifestKey(streamID, sessionID string) (string, error) {
	for _, c := range [...]struct{ name, value string }{{"stream_id", streamID}, {"session_id", sessionID}} {
		if !keyComponent.MatchString(c.value) || c.value == "." || c.value == ".." {
			return "", fmt.Errorf("publish: %s=%q 는 목록 키에 쓸 수 없다(URL 안전 문자 [A-Za-z0-9._-] 만 · 상위 · 현재 경로 참조 금지)", c.name, c.value)
		}
	}
	return "dvr/" + streamID + "/" + sessionID + "/index.m3u8", nil
}

// 메타 키 여덟 — 설계 4.4.2 넷에 계획 4.5 A1 결정 5 가 넷을 더했다. 키는 소문자다(4.4 [B-3]).
const (
	metaGen        = "pc-gen"
	metaPubSeq     = "pc-pub-seq"
	metaSession    = "pc-session"
	metaTerminal   = "pc-terminal"
	metaMSN        = "pc-msn"
	metaSegCount   = "pc-seg-count"
	metaDiscSeq    = "pc-disc-seq"
	metaBodySHA256 = "pc-body-sha256"
)

// encodeMeta 는 판 d 의 자기기술을 P3 에 싣는 메타 여덟 키로 쓴다. 값은 발행 층이 계산한 수와 회차 ID 뿐이다
// — 비밀 · 개인정보는 싣지 않는다(M5 에서 x-amz-meta-* 헤더가 CDN 시청자에게 보일 수 있다 — 보안 c2).
func encodeMeta(d rewind.Published, sessionID string) map[string]string {
	return map[string]string{
		metaGen:        strconv.FormatInt(d.Gen, 10),
		metaPubSeq:     strconv.FormatInt(d.PublishedSeq, 10),
		metaSession:    sessionID,
		metaTerminal:   strconv.FormatBool(d.Terminal),
		metaMSN:        strconv.FormatInt(d.MediaSequence, 10),
		metaSegCount:   strconv.Itoa(d.SegmentCount),
		metaDiscSeq:    strconv.FormatInt(d.DiscontinuitySequence, 10),
		metaBodySHA256: hex.EncodeToString(d.BodySHA256[:]),
	}
}

// parseMeta 는 Head 가 돌려준 메타를 판의 자기기술로 읽는다(R2). 메타는 믿을 수 없는 입력이라 엄격하게 읽는다
// — 여덟 키가 모두 있고 encodeMeta 가 쓰는 모양 그대로일 때만 받는다(부호 · 앞자리 0 · 대문자 16진처럼 뜻이
// 같아도 우리가 쓰지 않은 표기는 거부한다). pc-session 은 sessionID(목록 URL 의 회차)와 같아야 한다. 세대는
// 1 이상(첫 P0 이 1 을 만든다), 조각 수는 1 이상(빈 목록은 내지 않는다 — S3)이다. 정수는 모두 JavaScript 안전
// 정수 한계 안이고(metaInt), 조각 수는 pc-pub-seq − pc-msn + 1 이다 — 렌더가 seq 를 1씩 잇게 강제하므로
// (rewind.Render) 우리가 쓴 판은 늘 맞는다. 모르는 키는 보지 않는다. 오류 문자열에는 외부 값을 64자까지만
// 싣는다(보안 r4 M-1).
func parseMeta(meta map[string]string, sessionID string) (rewind.Published, error) {
	var d rewind.Published
	var err error
	if d.Gen, err = metaInt(meta, metaGen, 1); err != nil {
		return rewind.Published{}, err
	}
	if d.PublishedSeq, err = metaInt(meta, metaPubSeq, 0); err != nil {
		return rewind.Published{}, err
	}
	if d.MediaSequence, err = metaInt(meta, metaMSN, 0); err != nil {
		return rewind.Published{}, err
	}
	count, err := metaInt(meta, metaSegCount, 1)
	if err != nil {
		return rewind.Published{}, err
	}
	if span := d.PublishedSeq - d.MediaSequence + 1; count != span {
		return rewind.Published{}, fmt.Errorf("publish: 메타 %s=%d 가 %s − %s + 1 = %d 가 아니다", metaSegCount, count, metaPubSeq, metaMSN, span)
	}
	d.SegmentCount = int(count)
	if d.DiscontinuitySequence, err = metaInt(meta, metaDiscSeq, 0); err != nil {
		return rewind.Published{}, err
	}
	switch v, ok := meta[metaTerminal]; {
	case !ok:
		return rewind.Published{}, fmt.Errorf("publish: 메타 %s 가 없다", metaTerminal)
	case v != "true" && v != "false":
		return rewind.Published{}, fmt.Errorf("publish: 메타 %s=%.64q 는 true · false 가 아니다", metaTerminal, v)
	default:
		d.Terminal = v == "true"
	}
	if d.BodySHA256, err = metaSHA256(meta); err != nil {
		return rewind.Published{}, err
	}
	if v, ok := meta[metaSession]; !ok || v != sessionID {
		return rewind.Published{}, fmt.Errorf("publish: 메타 %s=%.64q 가 목록 URL 의 회차 %q 가 아니다(있음: %v)", metaSession, v, sessionID, ok)
	}
	return d, nil
}

// metaInt 는 메타 key 의 십진 정수다 — encodeMeta 가 쓰는 모양(strconv.FormatInt)과 같고 floor 이상 위끝
// (withinMetaLimit) 이하일 때만 받는다. 위끝이 없으면 조작된 Head 한 번이 R3 의 GREATEST 로 DB 에 int64 최대값을 박아
// 다음 P0 의 +1 이 int64 로 넘친다(보안 r4 M-1). 위끝이 막는 것은 그 넘침까지다 — 끝값을 받은 뒤 쓰는 쪽이 세대에 1 을 ·
// DISC-SEQ 에 축출 수를 더하면 위끝을 넘으므로, 올리기 전에 checkMetaRange 가 같은 위끝을 다시 본다(보안 r5 M1).
func metaInt(meta map[string]string, key string, floor int64) (int64, error) {
	v, ok := meta[key]
	if !ok {
		return 0, fmt.Errorf("publish: 메타 %s 가 없다", key)
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || strconv.FormatInt(n, 10) != v {
		return 0, fmt.Errorf("publish: 메타 %s=%.64q 는 십진 정수가 아니다", key, v)
	}
	if n < floor || !withinMetaLimit(n) {
		return 0, fmt.Errorf("publish: 메타 %s=%d 가 %d 이상 2^53-1 이하가 아니다", key, n, floor)
	}
	return n, nil
}

// checkMetaRange 는 올릴 판 d(목록 pl 의 자기기술)의 메타 정수 다섯(세대 · 마지막 seq · MSN · 조각 수 · DISC-SEQ)과 끝
// 조각의 DSN 이 읽는 쪽 위끝(withinMetaLimit) 안인가 본다 — 쓰는 쪽의 대칭 검사다(보안 r5 M1 · r6 M1). 플레이어는 조각마다
// DISC-SEQ 에 그 조각 앞 끊김 표시 수를 더한 DSN 을 쓰므로(RFC 8216bis-22 6.2.1) 끝 조각의 DSN 은 DISC-SEQ + 목록 안 표시
// 수다. 표시는 렌더와 같은 술어로 끝 행까지 센다(rewind.EvictedDiscontinuityTags). 읽는 쪽이 끝값(2^53−1)을 받아 R3 로 DB
// 와 P 에 싣고 나면, 그 위에서 P0 이 세대에 1 을 · 루프가 DISC-SEQ 에 축출 수를 더한 판은 위끝을 넘고, 축출이 없어도 목록에
// 남은 표시가 조각 DSN 을 위끝 밖으로 민다. 그런 판을 올리면 시청자에게 안전 정수 밖 값(MSN · DISC-SEQ · 조각 DSN)이 나가고,
// 머리 값이 넘은 판은 다음 화해가 거부한다. 표시를 셀 수 없는 목록도 위끝 안임을 보이지 못하므로 올리지 않는다.
//
// 받은 끝값이 DB 에 남는 기제는 키마다 다르다 — 세대 · 마지막 seq 는 R3 의 GREATEST 가 낮추지 않고, DISC-SEQ 는 R3 가
// 대입한 끝값을 뒤따르는 축출 없는 틱의 발행(목록에 표시가 없으면 이 검사를 지난다)과 P4 가 굳힌다. 이 검사는 그것을 풀지
// 않는다(DB 값에 상대적인 위끝은 계획 r51 몫).
func checkMetaRange(d rewind.Published, pl rewind.Playlist) error {
	for _, v := range [...]struct {
		key string
		n   int64
	}{
		{metaGen, d.Gen},
		{metaPubSeq, d.PublishedSeq},
		{metaMSN, d.MediaSequence},
		{metaSegCount, int64(d.SegmentCount)},
		{metaDiscSeq, d.DiscontinuitySequence},
	} {
		if !withinMetaLimit(v.n) {
			return fmt.Errorf("publish: 올릴 판의 메타 %s=%d 가 2^53-1 을 넘는다", v.key, v.n)
		}
	}
	tags, err := rewind.EvictedDiscontinuityTags(pl, d.PublishedSeq+1) // 끝 행까지 — 머리 값이 위끝 안이라 넘치지 않는다
	if err != nil {
		return fmt.Errorf("publish: 올릴 판의 끝 조각 DSN 을 잴 수 없다: %w", err)
	}
	if dsn := d.DiscontinuitySequence + tags; !withinMetaLimit(dsn) {
		return fmt.Errorf("publish: 올릴 판의 끝 조각 DSN(%s=%d + 끊김 표시 %d = %d)이 2^53-1 을 넘는다",
			metaDiscSeq, d.DiscontinuitySequence, tags, dsn)
	}
	return nil
}

// withinMetaLimit 는 n 이 메타 정수의 위끝 이하인가다. 위끝 2^53−1 은 JavaScript 의 안전 정수 한계
// (Number.MAX_SAFE_INTEGER — 규격이 정한 값)다: 이 값들은 목록 머리(MSN · DISC-SEQ)로 플레이어에 간다. 읽는
// 쪽(metaInt)과 쓰는 쪽(checkMetaRange)이 이 한 자리를 본다 — 두 쪽의 위끝이 어긋나면 자기가 올린 판을 다음 화해가
// 거부한다.
func withinMetaLimit(n int64) bool { return n <= 1<<53-1 }

// metaSHA256 은 메타 pc-body-sha256 이다 — 소문자 16진 64자일 때만 받는다.
func metaSHA256(meta map[string]string) ([sha256.Size]byte, error) {
	var sum [sha256.Size]byte
	v, ok := meta[metaBodySHA256]
	if !ok {
		return sum, fmt.Errorf("publish: 메타 %s 가 없다", metaBodySHA256)
	}
	b, err := hex.DecodeString(v)
	if err != nil || len(b) != sha256.Size || hex.EncodeToString(b) != v {
		return sum, fmt.Errorf("publish: 메타 %s=%.64q 는 소문자 16진 %d자가 아니다", metaBodySHA256, v, 2*sha256.Size)
	}
	copy(sum[:], b)
	return sum, nil
}

// checkETag 는 저장소가 돌려준 ETag 를 받을 수 있는지 본다 — 1–1024바이트의 인쇄 가능 ASCII(0x21–0x7E)만 받는다.
// S3 의 ETag(따옴표로 감싼 16진 · 약한 비교의 W/)는 이 안에 든다. 모양은 보지 않는다 — ETag 는 불투명한 문자열이다
// (Manifest.ETag). 다만 그대로 DB manifest_etag 와 다음 If-Match 헤더가 되므로, 비정상 엔드포인트가 준 큰 값 · 제어
// 문자를 싣지 않는다(보안 r4 M-1). Head 의 ETag 가 받을 수 없으면 해석 실패(meta_invalid), PUT 응답의 ETag 면 결과
// 모름(put_unknown)이다.
func checkETag(etag string) error {
	if etag == "" || len(etag) > 1024 {
		return fmt.Errorf("publish: ETag 가 비었거나 너무 길다(%d바이트)", len(etag))
	}
	for i := 0; i < len(etag); i++ {
		if c := etag[i]; c < '!' || c > '~' {
			return fmt.Errorf("publish: ETag %.64q 의 %d번째 바이트 %#x 가 인쇄 가능 ASCII 가 아니다", etag, i, c)
		}
	}
	return nil
}

// Kind 는 차선 작업의 종류다 — 루프의 완료 case 하나가 이것으로 결과를 가른다(계획 4.5 A3 결정 1). 값이 1
// 부터인 것은 채우지 않은 결과가 어느 종류로도 읽히지 않게 하려는 것이다.
type Kind int

const (
	// KindPublish 는 발행 작업(틱 · lazy 갱신)이다 — 스트림 차선.
	KindPublish Kind = iota + 1
	// KindLoad 는 캐시 적재 작업이다 — 스트림 차선(작업 몸체는 커밋 5).
	KindLoad
	// KindWatch 는 30초 감시 · 부팅 목록 작업이다 — 프로세스 차선(작업 몸체는 커밋 5).
	KindWatch
)

// Job 은 차선에서 도는 작업 하나다. ctx 가 끝나면 서둘러 돌아와야 한다 — 워커 고루틴은 작업이 돌아와야
// 끝난다.
type Job func(ctx context.Context) Result

// Result 는 차선 작업 하나의 결과다 — 완료 채널(Lanes.Done — 루프의 rewindDone case, 커밋 7)로 돌아온다.
type Result struct {
	Kind Kind
	// StreamID 는 스트림 차선 작업의 스트림이다. 프로세스 차선이면 비었다. 차선이 채운다.
	StreamID string
	// Publish 는 발행 작업(KindPublish)의 결과다.
	Publish Outcome

	// lane 은 이 작업이 돈 차선이다 — Finish 가 비울 자리다. 차선이 채운다.
	lane laneKey
}

// laneKey 는 차선 하나다 — 스트림마다 하나, 프로세스에 하나.
type laneKey struct {
	stream  string
	process bool
}

// Lanes 는 발행 워커의 차선이다(계획 4.5 A3 결정 1 · 체크리스트 A-6).
//
//	스트림 차선    스트림마다 한 번에 하나 — 발행 · 적재
//	프로세스 차선  하나 — 30초 감시 · 부팅 목록
//
// 작업은 저마다 고루틴에서 돌고 결과는 완료 채널 하나로 돌아온다. 차선이 도는 동안 온 요청은 버리지 않고
// 병합한다: 결과를 받은 루프가 Finish 를 부르면, 그 사이 요청이 있었는지 알려 준다(루프가 새 입력으로 한
// 번 더 낸다). 병합된 요청의 작업은 돌리지 않는다 — 그 작업이 든 입력은 요청 때의 스냅숏이라 낡았다(A-6 5).
//
// 워커는 입력 값만 받는다 — 캐시 · Dirty 를 만지지 않는다(A-6 4). Lanes 의 메서드는 루프 고루틴 하나에서만
// 부른다(차선 표는 잠그지 않는다). 워커 고루틴은 결과를 보내거나 작업에 준 ctx 가 끝나면 끝난다.
type Lanes struct {
	done    chan Result
	running map[laneKey]bool
	merged  map[laneKey]bool
}

// NewLanes 는 빈 차선을 만든다. 작업을 낼 때 넘기는 ctx 는 루프 수명 ctx 여야 한다(Start 의 계약).
func NewLanes() *Lanes {
	return &Lanes{done: make(chan Result), running: map[laneKey]bool{}, merged: map[laneKey]bool{}}
}

// Start 는 스트림 streamID 의 차선에 job 을 낸다. 차선이 비었으면 발사하고 참이다. 작업이 돌고 있으면 job 은
// 버리고 요청만 적어 두며 거짓이다 — 재실행은 Finish 가 알린다.
//
// ctx 는 루프 수명 ctx 다 — 그 취소가 차선 종료 신호다(결과를 보내지 못한 워커 고루틴은 ctx 가 끝나면 보내지 않고
// 끝난다). 작업별 시한은 작업 안에서 건다. 작업마다 시한 ctx 를 넘기면 시한에 걸린 작업의 결과가 버려질 수 있고,
// 그러면 Finish 가 불리지 않아 차선이 병합 상태로 멈춘다(그 스트림의 이후 요청이 모두 병합으로 삼켜진다).
func (l *Lanes) Start(ctx context.Context, streamID string, job Job) bool {
	return l.start(ctx, laneKey{stream: streamID}, job)
}

// StartProcess 는 프로세스 차선에 job 을 낸다. ctx 계약(루프 수명 ctx — 작업 시한은 작업 안에서)을 비롯해 나머지는
// Start 와 같다.
func (l *Lanes) StartProcess(ctx context.Context, job Job) bool {
	return l.start(ctx, laneKey{process: true}, job)
}

func (l *Lanes) start(ctx context.Context, k laneKey, job Job) bool {
	if l.running[k] {
		l.merged[k] = true
		return false
	}
	l.running[k] = true
	go func() {
		r := job(ctx)
		r.lane, r.StreamID = k, k.stream
		select {
		case l.done <- r:
		case <-ctx.Done(): // 루프가 더는 받지 않는다 — 보내지 않고 끝난다.
		}
	}()
	return true
}

// Done 은 완료 채널이다 — 루프의 select case 로만 받는다(커밋 7).
func (l *Lanes) Done() <-chan Result { return l.done }

// Finish 는 루프가 결과 r 을 받은 직후 부른다 — r 의 차선을 비우고, 그 작업이 도는 동안 병합된 요청이
// 있었으면 참을 돌려준다(루프가 새 입력으로 한 번 더 낸다 — 폐기 아님).
func (l *Lanes) Finish(r Result) (rerun bool) {
	rerun = l.merged[r.lane]
	delete(l.running, r.lane)
	delete(l.merged, r.lane)
	return rerun
}
