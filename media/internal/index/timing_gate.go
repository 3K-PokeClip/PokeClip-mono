package index

// PDT 판 게이트(설계 r7 9.3 · 3절) — stream_timing_gate 한 행을 읽고 판을 대조한다.
//
// 게이트 행은 새 회차를 여는 판(스위치)과 판 2 쓰기를 허용한 적이 있다는 래치다. 사이드카는 SELECT 만 갖고 행을
// 잠그지 않는다 — 회차 개시와 래치 낮추기의 직렬화는 advisory 열쇠 pc_timing_gate 가 한다(ddl.go 게이트 트리거).

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TimingGate 는 stream_timing_gate 한 행이다. NewRoundVersion 은 새 회차를 열 때의 판(스위치), Highest 는 판 2 쓰기를
// 허용한 적이 있다는 래치다. nil 은 NULL 이다. ChangedAt 은 마지막 변경 기록이고 영값이면 NULL 이다(신호
// timing_gate_read 의 재료).
type TimingGate struct {
	NewRoundVersion, Highest *int16
	ChangedAt                time.Time
}

// readableVersions 는 이 이미지가 읽고 쓸 수 있는 판이다(nil = 판 NULL). 이미지 라벨 com.pokeclip.timing.readable 이
// 이 목록과 같다는 것은 라벨을 두는 커밋(c11)의 시험이 단언한다.
var readableVersions = []*int16{nil, new(int16(2))}

// ErrTimingVersionUnsupported 는 쓰려는 행의 판이 이 이미지가 쓸 수 없는 판이라는 뜻이다(쓰기 시점 판 검사 — 신호
// timing_version_unsupported). 이 이미지가 쓰는 판은 readableVersions(판 NULL · 2)다 — 판 정하기가 그 밖의 판(앞 세션이
// 앞날 이미지로 쓴 판 3 회차를 잇는 행 등)을 고르면 이 오류다.
//
// 부르는 쪽(indexer H9)은 PgError 가 아닌 오류라 재시도로 접고(classifyInsertError), 상한을 다 쓰면 프로세스를 끝낸다 —
// 잘못된 판으로 장부에 쓰는 것보다 색인이 멈추는 쪽이 안전하다.
var ErrTimingVersionUnsupported = errors.New("index: 이 이미지가 쓰지 못하는 판이다")

// errTimingGateMissing 은 게이트 행이 없다는 뜻이다(c1 검수 이연 — 읽는 쪽은 0행을 fail-closed 로 다룬다). 행은 DDL
// 씨앗이 넣고 DELETE 는 트리거가 막으므로 소유자의 TRUNCATE 같은 손 작업에서만 생긴다. 0행을 「스위치 · 래치 NULL」 로
// 읽으면 래치가 선 장부에 판 NULL 로 새 회차를 열 수 있어 오류로 올린다.
var errTimingGateMissing = errors.New("index: 판 게이트 행(stream_timing_gate)이 없다")

// timingGateSQL 은 게이트 한 행을 잠금 없이 읽는다. 회차 개시는 이 문장 앞의 별도 문장으로 공유 잠금을 잡는다
// (lockTimingGateSharedSQL) — READ COMMITTED 의 스냅숏은 문장마다 새로 잡히므로, 잠금을 기다린 뒤의 이 문장이 그 사이
// 커밋된 게이트 변경을 본다. 한 문장으로 합치면 스냅숏이 잠금 대기 앞에 잡혀 낡은 스위치를 읽을 수 있다.
const timingGateSQL = `SELECT new_round_version, highest_version, changed_at FROM stream_timing_gate`

// lockTimingGateSharedSQL 은 게이트 열쇠의 공유 잠금이다(설계 9.3 · P19) — 게이트 트리거의 배타 잠금(래치 낮추기 ·
// 스위치 변경)과만 부딪히고 회차 개시끼리는 부딪히지 않는다. 트랜잭션 끝에 풀린다. 대기는 트랜잭션 지역
// lock_timeout(setTxnLimitsSQL 5초)을 따라 55P03 으로 끊기고, Insert 가 즉시 한 번 다시 시도한 뒤 ErrLockContended 로
// 올린다 — 부르는 쪽은 H9 백오프로 재시도하고, 상한(기본 5회 · 약 30초)을 다 쓰면 프로세스를 끝내 재기동에 맡긴다(D8 ·
// indexer/insert.go insertWithRetry). 전역 열쇠를 남이 오래 쥐면 상자의 사이드카가 종료 · 재기동을 되풀이한다(c1 보안 검수 M2
// · K7 (가)로 받아들인 위협).
// hashtext 는 integer 를 내고 pg_catalog 의 잠금 함수는 bigint 판뿐이라 ::bigint 로 맞춘다(lockCutoffStreamSQL 과 같은 꼴).
const lockTimingGateSharedSQL = `
SELECT pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtext('pc_timing_gate'::text)::bigint)`

// ReadTimingGate 는 게이트 행을 잠금 없이 읽는다(기동 검사 · 기동 전 검사). 행이 없으면 오류다(fail-closed).
func ReadTimingGate(ctx context.Context, pool *pgxpool.Pool) (TimingGate, error) {
	return scanTimingGate(pool.QueryRow(ctx, timingGateSQL))
}

// readTimingGateLocked 는 트랜잭션 tx 에서 게이트 열쇠의 공유 잠금을 잡은 뒤 게이트 행을 읽는다 — 새 회차 개시 · 회차
// 없는 비귀속 행이 판을 고르는 자리다(설계 9.3 「읽는 때 · 잠금」).
func readTimingGateLocked(ctx context.Context, tx pgx.Tx) (TimingGate, error) {
	if _, err := tx.Exec(ctx, lockTimingGateSharedSQL); err != nil {
		return TimingGate{}, fmt.Errorf("판 게이트 공유 잠금 실패: %w", err)
	}
	return scanTimingGate(tx.QueryRow(ctx, timingGateSQL))
}

// scanTimingGate 는 timingGateSQL 한 행을 읽는다. 0행은 errTimingGateMissing 이다.
func scanTimingGate(row pgx.Row) (TimingGate, error) {
	var (
		g       TimingGate
		changed *time.Time
	)
	err := row.Scan(&g.NewRoundVersion, &g.Highest, &changed)
	if errors.Is(err, pgx.ErrNoRows) {
		return TimingGate{}, errTimingGateMissing
	}
	if err != nil {
		return TimingGate{}, fmt.Errorf("판 게이트 읽기 실패: %w", err)
	}
	if changed != nil {
		g.ChangedAt = changed.UTC()
	}
	return g, nil
}

// ErrTimingDowngrade 는 게이트(래치 · 스위치)가 이미지가 읽지 못하는 판을 요구한다는 뜻이다(기동 검사 · 기동 전 검사 —
// 신호 timing_downgrade_refused). 실제 값은 *TimingDowngradeError 가 싣는다.
var ErrTimingDowngrade = errors.New("index: 이 이미지가 읽지 못하는 판이 장부에 있다")

// TimingDowngradeError 는 기동 검사가 거부한 게이트 값과 이미지 판 목록(FormatTimingVersions 꼴)이다.
type TimingDowngradeError struct {
	Gate     TimingGate
	Readable string
}

func (e *TimingDowngradeError) Error() string {
	return fmt.Sprintf("%v: highest_version=%s new_round_version=%s readable=%s",
		ErrTimingDowngrade, versionText(e.Gate.Highest), versionText(e.Gate.NewRoundVersion), e.Readable)
}

func (e *TimingDowngradeError) Unwrap() error { return ErrTimingDowngrade }

// CheckTimingGate 는 기동 검사다(설계 r7 9.3) — MissingLedgerSchema 다음에 부른다. 게이트를 읽어 래치 · 스위치가 이 이미지의
// 판 목록(readableVersions) 밖이면 *TimingDowngradeError 다. 읽기 실패(권한 42501 · 행 없음 포함)도 오류다 — 부르는 쪽은
// 둘 다 기동을 멈춘다. 이 이미지는 판 NULL · 2 를 다 읽으므로 거부는 게이트가 모르는 판(3 등)을 요구할 때만 난다.
func CheckTimingGate(ctx context.Context, pool *pgxpool.Pool) error {
	return checkTimingGate(ctx, pool, readableVersions)
}

// checkTimingGate 는 판 목록 readable 을 지닌 이미지의 기동 검사다(시험이 판 목록을 바꿔 부른다).
func checkTimingGate(ctx context.Context, pool *pgxpool.Pool, readable []*int16) error {
	g, err := ReadTimingGate(ctx, pool)
	if err != nil {
		return err
	}
	return RefuseUnreadable(g, readable)
}

// RefuseUnreadable 은 게이트 g 가 판 목록 readable 의 이미지를 띄워도 되면 nil, 아니면 *TimingDowngradeError 다.
func RefuseUnreadable(g TimingGate, readable []*int16) error {
	if AdmitsImage(g, readable) {
		return nil
	}
	return &TimingDowngradeError{Gate: g, Readable: FormatTimingVersions(readable)}
}

// ParseTimingVersions 는 판 목록 글자를 읽는다 — 쉼표로 이은 판이고 null 은 판 NULL 이다(이미지 라벨
// com.pokeclip.timing.readable · 기동 전 검사의 SIDECAR_TIMING_READABLE 꼴). 판은 1 이상 smallint 다. 빈 글자 · 빈 칸 ·
// 공백 · 모르는 글자는 오류다 — 잘못 읽은 목록으로 검사를 통과시키지 않는다.
func ParseTimingVersions(s string) ([]*int16, error) {
	if s == "" {
		return nil, errors.New("index: 판 목록이 비었다")
	}
	var out []*int16
	for field := range strings.SplitSeq(s, ",") {
		if field == "null" {
			out = append(out, nil)
			continue
		}
		v, err := strconv.ParseInt(field, 10, 16)
		if err != nil || v < 1 {
			return nil, fmt.Errorf("index: 판 목록 %q 의 %q 는 null 도 1 이상 판도 아니다", s, field)
		}
		out = append(out, new(int16(v)))
	}
	return out, nil
}

// FormatTimingVersions 는 판 목록을 ParseTimingVersions 꼴 글자로 쓴다(nil = null).
func FormatTimingVersions(list []*int16) string {
	parts := make([]string, len(list))
	for i, v := range list {
		parts[i] = versionText(v)
	}
	return strings.Join(parts, ",")
}

// versionText 는 판 하나를 판 목록 글자 꼴로 쓴다(nil = null).
func versionText(v *int16) string {
	if v == nil {
		return "null"
	}
	return strconv.Itoa(int(*v))
}

// AdmitsImage 는 판 목록 readable 을 지닌 이미지를 지금 게이트가 띄워도 되는가다 — 래치 · 스위치가 모두 readable
// 안이면 참이다. 판 목록의 nil 은 판 NULL 이다.
func AdmitsImage(g TimingGate, readable []*int16) bool {
	return hasVersion(readable, g.Highest) && hasVersion(readable, g.NewRoundVersion)
}

// hasVersion 은 판 목록 list 에 판 v 가 있는가다(nil 끼리는 같다 — 판 NULL).
func hasVersion(list []*int16, v *int16) bool {
	return slices.ContainsFunc(list, func(w *int16) bool {
		if w == nil || v == nil {
			return w == v
		}
		return *w == *v
	})
}

// checkWritableVersion 은 쓰기 시점 판 검사다 — 이 이미지가 쓰는 판(readableVersions — 판 NULL 식 · 판 2 섞기)이 아니면
// ErrTimingVersionUnsupported 다.
func checkWritableVersion(v *int16) error {
	if hasVersion(readableVersions, v) {
		return nil
	}
	return fmt.Errorf("%w: timing_version=%d", ErrTimingVersionUnsupported, *v)
}
