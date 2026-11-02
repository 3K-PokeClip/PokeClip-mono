package janitor

import (
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// 결속 시험 ① — 잠금 트랜잭션의 서버 측 상한은 사이드카 ③ CAS 의 기한(MarkTimeout)보다 작아야
// 한다(계획 4-3 W3~W5 · E3′). janitor 잠금을 기다린 CAS 가 기한 안에 끝나야 mark_error 가 헛켜지지
// 않는다(정상 조건 — 예외는 한계 L30 · L32 · L33). MarkTimeout 을 env 로 여는 경로가 생기면 이 결속을
// 다시 판단한다(한계 L21).
func TestRowLockTxnTimeoutBelowUploadMarkTimeout(t *testing.T) {
	mark := upload.DefaultOptions(nil, "").MarkTimeout
	if index.RowLockTxnTimeout >= mark {
		t.Fatalf("index.RowLockTxnTimeout = %v, want < upload MarkTimeout(%v)", index.RowLockTxnTimeout, mark)
	}
}

// E3′(설계 2-3 기한 표) — 호출별 클라이언트 기한은 서버 쪽 기한보다 길어야 정상 실패가 서버의 깨끗한
// 오류(57014 · 55P03 · 25P04)로 온다: 조회 20s(statement_timeout 15s 위) · 행 잠금 = 서버 상한
// RowLockTxnTimeout + 2s · 회수 5s(lock_timeout 2s 위 · 주기를 오래 붙잡지 않음). New 가 실제로 쥐는 값을 본다.
func TestCallLimitsMatchDeadlineTable(t *testing.T) {
	r := newRig(t, nil)

	want := callLimits{query: 20 * time.Second, rowLock: index.RowLockTxnTimeout + 2*time.Second, reclaim: 5 * time.Second}
	if r.j.limits != want {
		t.Errorf("호출별 기한 = %+v, want %+v(설계 2-3 기한 표)", r.j.limits, want)
	}
	if r.j.limits.rowLock <= index.RowLockTxnTimeout {
		t.Errorf("행 잠금 기한 %v ≤ 서버 상한 %v — 서버가 끝내기 전에 클라이언트가 먼저 끊는다", r.j.limits.rowLock, index.RowLockTxnTimeout)
	}
}
