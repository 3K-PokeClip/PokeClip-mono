package index

// 로컬 정리 판정(POK-369 · T1)의 정적 단언 — PG 없이 돈다.
// 구조가 곧 단정인 자리(선잠금 형상 · 상한 결속 · 공유 조각)를 소스 수준에서 고정한다.

import (
	"context"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// 결속 시험 ② — 잠금 트랜잭션의 서버 측 상한은 인덱서 INSERT 의 두 시도 대기 창 합보다
// 작아야 한다(계획 4-3 W7′). store 층은 55P03 이면 즉시 1회 재시도하므로(store.go Insert)
// 두 시도가 각각 lock_timeout 까지 기다린다. janitor 세션이 그 합보다 먼저 끝나야
// ErrLockContended(단일 쓰기자 전제 붕괴 신호)가 헛켜지지 않는다.
func TestRowLockTxnTimeoutBelowTwoIndexerLockWaits(t *testing.T) {
	m := regexp.MustCompile(`set_config\('lock_timeout','([0-9a-z]+)',true\)`).FindStringSubmatch(setTxnLimitsSQL)
	if m == nil {
		t.Fatal("setTxnLimitsSQL 에서 lock_timeout 값을 찾지 못했다 — 인덱서 대기 창이 바뀌었으면 이 결속을 다시 판단하라")
	}
	indexerLockTimeout, err := time.ParseDuration(m[1])
	if err != nil {
		t.Fatalf("인덱서 lock_timeout %q 해석 실패: %v", m[1], err)
	}

	if limit := 2 * indexerLockTimeout; RowLockTxnTimeout >= limit {
		t.Fatalf("RowLockTxnTimeout = %v, want < 2 × 인덱서 lock_timeout(%v) = %v", RowLockTxnTimeout, indexerLockTimeout, limit)
	}
}

// D36 — 선잠금은 stream_segments 한 표 한 행만 잡는다. JOIN 이 붙으면 stream_sessions 까지
// 잠가 세션 CAS(MarkInitUploaded 의 FOR UPDATE)를 붙잡는다. NOWAIT 가 빠지면 잠금 경합에서
// 기다리게 되어 파일을 건드리기 전에 갈라지는 구조(K8)가 무너진다.
func TestRowLockSQLLocksOneTableWithNoWait(t *testing.T) {
	sql := strings.Join(strings.Fields(rowLockSQL), " ")

	if !strings.HasSuffix(sql, "FOR UPDATE NOWAIT") {
		t.Errorf("rowLockSQL = %q, want 끝이 FOR UPDATE NOWAIT", sql)
	}
	upper := strings.ToUpper(sql)
	if strings.Contains(upper, "JOIN") || strings.Count(upper, "FROM") != 1 {
		t.Errorf("rowLockSQL = %q, want 단일 표(JOIN 0 · FROM 1)", sql)
	}
	if !strings.Contains(sql, "FROM stream_segments WHERE stream_id = $1 AND seq = $2") {
		t.Errorf("rowLockSQL = %q, want PK 한 행(stream_id = $1 AND seq = $2)", sql)
	}
	if strings.Contains(sql, "stream_sessions") {
		t.Errorf("rowLockSQL = %q 가 stream_sessions 를 참조한다", sql)
	}
}

// E3′ · X1 A — 잠금 트랜잭션은 행을 잠그기 **전에** 전체 상한(transaction_timeout)과
// 비동기 커밋을 건다. 둘 다 그 트랜잭션에만(is_local = true) 건다 — 연결 · 역할 수준으로
// 새면 같은 풀의 조회(statement_timeout 15s)가 8s 에 끊기고 다른 커밋까지 비동기가 된다.
func TestRowLockTxnSetsLimitsBeforeLocking(t *testing.T) {
	if !strings.Contains(setRowLockTxnTimeoutSQL, "set_config('transaction_timeout', $1, true)") {
		t.Errorf("setRowLockTxnTimeoutSQL = %q, want transaction_timeout 을 트랜잭션 지역으로", setRowLockTxnTimeoutSQL)
	}
	if want := fmt.Sprintf("%dms", RowLockTxnTimeout.Milliseconds()); rowLockTxnTimeoutSetting != want {
		t.Errorf("rowLockTxnTimeoutSetting = %q, want %q — 상수와 SQL 인자가 갈라졌다", rowLockTxnTimeoutSetting, want)
	}
	if !strings.Contains(setRowLockAsyncCommitSQL, "set_config('synchronous_commit','off',true)") {
		t.Errorf("setRowLockAsyncCommitSQL = %q, want synchronous_commit off 를 트랜잭션 지역으로", setRowLockAsyncCommitSQL)
	}

	body := functionBody(t, "local_cleanup.go", "func lockRow(")
	timeoutAt := strings.Index(body, "setRowLockTxnTimeoutSQL")
	asyncAt := strings.Index(body, "setRowLockAsyncCommitSQL")
	lockAt := strings.Index(body, "rowLockSQL")
	if timeoutAt < 0 || asyncAt < 0 || lockAt < 0 || !(timeoutAt < asyncAt && asyncAt < lockAt) {
		t.Fatalf("lockRow 의 문장 순서 = (timeout %d, async %d, lock %d), want transaction_timeout → synchronous_commit → 선잠금", timeoutAt, asyncAt, lockAt)
	}
}

// E1 — 외부 삭제 나이는 조각 **시작** 시각으로 잰다. MediaMTX 정리기가 파일명의 시작 시각으로
// 판정하기 때문이다(_workspace/07 2 · 5항). 끝 시각으로 재면 조각 길이만큼 늦게 인정한다.
func TestDeletableSegmentsExternalDueIsMeasuredFromStart(t *testing.T) {
	const want = "t.start_wall_utc < pg_catalog.now() - pg_catalog.make_interval(secs => $6::double precision) AS external_delete_due"
	if !strings.Contains(oneLine(deletableSegmentsSQL), want) {
		t.Fatalf("deletableSegmentsSQL 에 %q 가 없다", want)
	}
}

// D09 — 무효 컷오프 신호(InvalidCutoffs)와 삭제 정지(DeletableSegments)는 같은 술어
// 조각에서 나온다. 원본이 둘이면 신호는 켜졌는데 삭제는 계속되는 어긋남이 생길 수 있다.
func TestCutoffRowPredicateIsSharedByStopAndSignal(t *testing.T) {
	if !strings.Contains(deletableSegmentsSQL, "OR "+cutoffRowExists) {
		t.Error("deletableSegmentsSQL 이 cutoffRowExists 조각으로 스트림을 정지하지 않는다")
	}
	if !strings.Contains(invalidCutoffsSQL, "NOT "+cutoffRowExists) {
		t.Error("invalidCutoffsSQL 이 cutoffRowExists 조각의 부정으로 신호를 내지 않는다")
	}
}

// D24 — 보호 ② 의 회차 종료 시각이 전부 NULL 이면 COALESCE 가 'infinity' 로 펴서 보호가
// 유지된다(NULL 비교는 거짓이라, 이 항이 빠지면 보호가 조용히 풀린다). 고정물로는 만들 수
// 없는 국면이라(조각이 있으면 마지막 끝이 있다) 정적 문자열로 단언한다 — 참고 · 미교정.
func TestProtectTwoEndTimeCoalescesToInfinity(t *testing.T) {
	re := regexp.MustCompile(`COALESCE\(GREATEST\(s\.ending_at, s\.ended_at, \(SELECT [^)]*\)\), 'infinity'::timestamptz\)`)
	if !re.MatchString(oneLine(deletableSegmentsSQL)) {
		t.Fatal("보호 ② 종료 시각에 COALESCE(…'infinity') 없음")
	}
}

// D10 — 보호 ① 의 「최신 조각」 선택자는 init 재수거 조회(pendingInitUploadsSQL)의 LATERAL 과
// 같은 조건이어야 한다. 조건이 하나라도 다르면 재수거가 읽을 조각과 보호하는 조각이 갈린다.
func TestProtectOneSelectorMatchesInitSourceSelector(t *testing.T) {
	const selector = "g.local_path IS NOT NULL AND g.bytes IS NOT NULL ORDER BY g.seq DESC LIMIT 1"
	if !strings.Contains(oneLine(pendingInitUploadsSQL), selector) {
		t.Fatalf("pendingInitUploadsSQL 의 선택자가 %q 가 아니다 — 보호 ① 을 다시 맞춰라", selector)
	}
	if !strings.Contains(oneLine(deletableSegmentsSQL), selector) {
		t.Fatalf("deletableSegmentsSQL 보호 ① 선택자가 %q 가 아니다", selector)
	}
}

// 입력 경계 — 페이지 크기 0 이하와 음수 기간은 조회 전에 거부한다(풀을 쓰지 않는다).
func TestDeletableSegmentsRejectsInvalidArguments(t *testing.T) {
	store := NewLocalCleanupStore(nil)
	ok := LocalCleanupPolicy{Grace: 8 * time.Minute, ReconnectWindow: 300 * time.Second, ExternalDeleteAge: 23 * time.Hour}
	cases := []struct {
		name   string
		policy LocalCleanupPolicy
		limit  int
	}{
		{"limit 0", ok, 0},
		{"limit 음수", ok, -1},
		{"grace 음수", LocalCleanupPolicy{Grace: -time.Second, ReconnectWindow: ok.ReconnectWindow, ExternalDeleteAge: ok.ExternalDeleteAge}, 10},
		{"window 음수", LocalCleanupPolicy{Grace: ok.Grace, ReconnectWindow: -time.Second, ExternalDeleteAge: ok.ExternalDeleteAge}, 10},
		{"외부 삭제 나이 음수", LocalCleanupPolicy{Grace: ok.Grace, ReconnectWindow: ok.ReconnectWindow, ExternalDeleteAge: -time.Second}, 10},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, _, err := store.DeletableSegments(context.Background(), c.policy, c.limit, LocalCursor{})
			if err == nil {
				t.Fatalf("DeletableSegments(%+v, %d) err = nil, want 입력 거부", c.policy, c.limit)
			}
		})
	}
}

// 판별 함수 넷 — janitor 가 DB 오류를 갈래로 나누는 유일한 통로다(설계 3-3).
// 25P04 는 transaction_timeout 의 세션 종료다(reason=txn_timeout — D34 시험이 실물로 받는다).
// 감싸인 오류(%w)도 알아봐야 한다: 호출자는 문맥을 붙여 올린다.
func TestSQLStatePredicates(t *testing.T) {
	wrap := func(code string) error {
		return fmt.Errorf("문맥: %w", &pgconn.PgError{Code: code})
	}
	cases := []struct {
		name string
		fn   func(error) bool
		hit  string
	}{
		{"IsLockTimeout", IsLockTimeout, "55P03"},
		{"IsStatementTimeout", IsStatementTimeout, "57014"},
		{"IsDeadlock", IsDeadlock, "40P01"},
		{"IsTxnTimeout", IsTxnTimeout, "25P04"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if !c.fn(wrap(c.hit)) {
				t.Errorf("%s(%s) = false, want true", c.name, c.hit)
			}
			for _, other := range []string{"55P03", "57014", "40P01", "25P04"} {
				if other != c.hit && c.fn(wrap(other)) {
					t.Errorf("%s(%s) = true, want false", c.name, other)
				}
			}
			if c.fn(nil) || c.fn(errors.New("평범한 오류")) {
				t.Errorf("%s 가 PgError 아닌 값에 true", c.name)
			}
		})
	}
}

// functionBody 는 file 에서 sig 로 시작하는 최상위 함수의 본문 텍스트다.
func functionBody(t *testing.T, file, sig string) string {
	t.Helper()
	src, err := os.ReadFile(file)
	if err != nil {
		t.Fatalf("%s 읽기 실패: %v", file, err)
	}
	s := string(src)
	start := strings.Index(s, sig)
	if start < 0 {
		t.Fatalf("%s 에 %q 가 없다", file, sig)
	}
	end := strings.Index(s[start:], "\n}\n")
	if end < 0 {
		t.Fatalf("%s 의 %q 끝을 찾지 못했다", file, sig)
	}
	return s[start : start+end]
}

// oneLine 은 SQL 의 공백을 한 칸으로 접는다 — 줄바꿈 · 들여쓰기 변경에 단언이 흔들리지 않게.
func oneLine(sql string) string { return strings.Join(strings.Fields(sql), " ") }
