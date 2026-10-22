package pgconf

// RDS 통합 CA 번들 계약(계획 2-5 · V10). 번들이 틀리면 빌드도 기동도 통과한 뒤 첫 연결에서야
// x509 오류로 드러난다 — 그래서 저장소 파일 · Dockerfile 을 구조로 읽어 대조한다. sha256 정본은
// Dockerfile 한 곳이고, 이 시험은 그 값을 상수로 베끼지 않고 Dockerfile 에서 읽는다.
// 음성 대조: 번들 · COPY · sha256sum 줄이 없는 기준 트리에서는 첫 단언부터 FAIL 이다.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

const (
	rdsBundleRepoPath  = "../../certs/rds-global-bundle.pem"
	rdsBundleBuildPath = "/src/certs/rds-global-bundle.pem"
	rdsBundleImagePath = "/etc/pokeclip/rds-ca/global-bundle.pem"
	mediaDockerfile    = "../../Dockerfile"
)

// sha256CheckLine 은 빌드 스테이지의 `echo "<sha256>  <경로>" | sha256sum -c -` 줄이다.
var sha256CheckLine = regexp.MustCompile(`^RUN echo "([0-9a-f]{64})  ` + regexp.QuoteMeta(rdsBundleBuildPath) + `" \| sha256sum -c -$`)

// dockerStages 는 Dockerfile 의 활성 줄(주석 · 빈 줄 제외)을 FROM 마다 나눈 것이다.
func dockerStages(t *testing.T) [][]string {
	t.Helper()
	b, err := os.ReadFile(mediaDockerfile)
	if err != nil {
		t.Fatalf("%s 를 읽지 못했다: %v", mediaDockerfile, err)
	}
	var stages [][]string
	for _, l := range strings.Split(string(b), "\n") {
		l = strings.TrimSpace(l)
		if l == "" || strings.HasPrefix(l, "#") {
			continue
		}
		if strings.HasPrefix(strings.ToUpper(l), "FROM ") {
			stages = append(stages, nil)
		}
		if len(stages) > 0 {
			stages[len(stages)-1] = append(stages[len(stages)-1], l)
		}
	}
	if len(stages) < 2 {
		t.Fatalf("%s 에 빌드 · 최종 스테이지가 없다(스테이지 %d 개)", mediaDockerfile, len(stages))
	}
	return stages
}

// V10 — 빌드 스테이지가 저장소 번들의 sha256 을 검사하고, 최종 스테이지가 그 파일을 이미지 경로로 싣는다.
func TestDockerfilePinsRDSCABundle(t *testing.T) {
	stages := dockerStages(t)
	build, final := stages[0], stages[len(stages)-1]

	pinned := ""
	for _, l := range build {
		if m := sha256CheckLine.FindStringSubmatch(l); m != nil {
			pinned = m[1]
		}
	}
	if pinned == "" {
		t.Fatalf("빌드 스테이지에 %s 의 sha256sum -c 줄이 없다", rdsBundleBuildPath)
	}
	data, err := os.ReadFile(rdsBundleRepoPath)
	if err != nil {
		t.Fatalf("저장소 번들을 읽지 못했다: %v", err)
	}
	if sum := sha256.Sum256(data); hex.EncodeToString(sum[:]) != pinned {
		t.Errorf("저장소 번들 sha256 = %x, Dockerfile 고정값 = %s(번들 교체는 두 곳을 한 커밋에)", sum, pinned)
	}

	// --chmod=0644 — COPY 는 빌드 입력의 권한을 그대로 옮기므로, 작업 트리의 번들이 0600 이면 실행 계정 10001 이
	// 못 읽어 iam 기동이 실패한다(C3 검수 M). 이미지 안 권한을 빌드 입력과 떼어 놓는다.
	wantCopy := "COPY --from=build --chmod=0644 " + rdsBundleBuildPath + " " + rdsBundleImagePath
	// --chmod 는 COPY 가 새로 만드는 부모 디렉터리에도 같은 모드(실행 비트 없음)를 준다(2026-10-22 실측 — buildx
	// v0.37.2). 그러면 10001 이 디렉터리를 지나가지 못하므로 부모를 COPY 앞에서 먼저 만든다(755).
	wantDir := "RUN mkdir -p " + filepath.Dir(rdsBundleImagePath)
	found, dirFirst := false, false
	for _, l := range final {
		dirFirst = dirFirst || (l == wantDir && !found)
		found = found || l == wantCopy
		if strings.Contains(l, "POSTGRES_SSLROOTCERT") {
			t.Errorf("최종 스테이지가 POSTGRES_SSLROOTCERT 를 정한다(%q) — 값은 배포 정의가 준다", l)
		}
	}
	if !found {
		t.Errorf("최종 스테이지에 %q 줄이 없다", wantCopy)
	}
	if !dirFirst {
		t.Errorf("최종 스테이지에서 %q 줄이 번들 COPY 앞에 없다 — 부모 디렉터리가 --chmod=0644 로 만들어져 10001 이 못 지나간다", wantDir)
	}
}

// E1 — 실제 통합 번들이 기동 검증(checkRootCert)을 통과한다. 해석 못 하는 인증서가 하나라도 있으면
// 기동 에러이므로, AWS 번들이 바뀌어 그런 인증서가 들어오면 여기서 먼저 드러난다.
func TestRDSCABundlePassesRootCertCheck(t *testing.T) {
	abs, err := filepath.Abs(rdsBundleRepoPath)
	if err != nil {
		t.Fatal(err)
	}
	s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_SSLROOTCERT", abs)))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken("t"))); err != nil {
		t.Errorf("PoolConfig(통합 번들) 에러 %v, want nil", err)
	}
}
