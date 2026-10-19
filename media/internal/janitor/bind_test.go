package janitor

import (
	"testing"

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
