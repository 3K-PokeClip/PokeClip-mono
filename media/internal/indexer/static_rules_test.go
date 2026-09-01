package indexer

// m2-ⓑ(POK-168 r15a 설계 6.5.3 ⑵) — "Settle 옵션을 만드는 자리가 소스에 정확히 1곳"의
// 정적 단언이다. 데이터 흐름 속성("모든 정착 판정 호출점이 fsop 주입 Settle 을 받는다")을
// 구문 속성("생성 자리가 newSettleOptions 하나")으로 내려 검사한다 — 호출점 열거는
// r15a 검증에서 두 번 깨졌으므로(1/3 · 4/14) 개수와 무관하게 안전한 형태를 단언한다.
//
// M0 시점에는 newSettleOptions 가 아직 없어 이 테스트가 실패한다 — 그것이 안전판이다
// (설계 11.3 ⒜ "규칙을 먼저 넣고 현재 코드에서 실패시킨다"). M1 이 단일 생성점을
// 만들면 통과로 전환된다. naked FS 금지(m2-ⓐ)는 .golangci.yml 이 강제한다.

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var (
	settleCtorDefRe  = regexp.MustCompile(`func newSettleOptions\(`)
	settleCtorCallRe = regexp.MustCompile(`newSettleOptions\(`)
	settleDefaultRe  = regexp.MustCompile(`recording\.DefaultSettleOptions\(`)
	settleLiteralRe  = regexp.MustCompile(`recording\.SettleOptions\{`)
)

func TestM2SettleOptionSingleSite(t *testing.T) {
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("소스 목록 수집 실패: %v", err)
	}

	var defs, calls, defaults, literals int
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			t.Fatalf("%s 읽기 실패: %v", p, err)
		}
		defs += len(settleCtorDefRe.FindAll(src, -1))
		calls += len(settleCtorCallRe.FindAll(src, -1))
		defaults += len(settleDefaultRe.FindAll(src, -1))
		literals += len(settleLiteralRe.FindAll(src, -1))
	}
	calls -= defs // 정의 행의 이름 등장은 호출이 아니다

	if defs != 1 {
		t.Errorf("newSettleOptions 정의가 %d곳이다 — 정확히 1곳이어야 한다(단일 생성점)", defs)
	}
	if calls != 1 {
		t.Errorf("newSettleOptions 호출이 %d곳이다 — New 안의 정확히 1곳이어야 한다", calls)
	}
	if defaults != 1 {
		t.Errorf("recording.DefaultSettleOptions 참조가 %d곳이다 — newSettleOptions 내부 1곳으로 수렴해야 한다", defaults)
	}
	if literals != 0 {
		t.Errorf("recording.SettleOptions 복합 리터럴이 %d곳이다 — 생성은 newSettleOptions 만 한다", literals)
	}
}

// m3c — Adopt 호출이 소스에 정확히 3곳이고 전부 수신자(ix.adopt) 경유다.
// 수신자 없는 패턴은 무관한 호출까지 세어 "정확히 3곳"이 흔들리므로(설계 0.5 ⅵ)
// 패턴은 `.adopt.Adopt(` 로 고정한다 — 널 오브젝트 필드를 지나야 계수된다.
func TestM3AdoptCallSitesExactlyThree(t *testing.T) {
	adoptCallRe := regexp.MustCompile(`\.adopt\.Adopt\(`)
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("소스 목록 수집 실패: %v", err)
	}
	calls := 0
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			t.Fatalf("%s 읽기 실패: %v", p, err)
		}
		calls += len(adoptCallRe.FindAll(src, -1))
	}
	if calls != 3 {
		t.Errorf("ix.adopt.Adopt 호출이 %d곳이다 — 정확히 3곳이어야 한다(H4·unsettled·Scan(d)). "+
			"자리를 늘렸다면 설계 6.5.3 의 호출점 목록과 f6e 를 함께 갱신하라", calls)
	}
}
