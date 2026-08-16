package mtxhook

// 이 파일은 media 컨테이너의 **실행 계정·볼륨 소유권 회귀 방지 장치**다 (POK-79).
//
// media 는 UID 10002 로 돈다. 그런데 named volume 의 소유권을 런타임에 고치는 주체는
// **아무도 없다** — Docker Engine 이 "볼륨이 비어 있을 때만 이미지의 같은 경로에서
// 소유권·모드를 복사한다"는 동작에 전적으로 기댄다. 그래서 media/Dockerfile.mtxhook 이
// 빈 디렉토리 3종을 최종 이미지에 심어 둔다. 내용은 없고 소유권만 나른다.
//
// 그 COPY 3줄이 지워지면 이미지에 그 경로가 없어져 복사가 조용히 건너뛰어지고, 새 볼륨은
// 0755 root:root 로 남는다 = **비root 가 못 써서 녹화가 멈춘다.** 빌드도 기동도 통과한 뒤
// 일어나는 무징후 실패라 사람의 기억 대신 기계가 잡게 한다.
//
// 상수 mediaRuntimeUser 하나가 진실원이다. 파일을 못 읽으면 skip 이 아니라 Fatal 이고
// 매치가 1건이 아니어도 Fatal 이다 — 조용히 통과하면 이 장치가 없는 것과 같다
// (version_contract_test.go 와 같은 관례. readDockerfile 도 그 파일 것을 그대로 쓴다).

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// mediaRuntimeUser 는 media 컨테이너의 실행 계정이다. 정본은 Dockerfile 의 USER 이고
// 이 상수는 그 값과 COPY --chown 을 한꺼번에 붙잡는 대조 기준이다.
// 사이드카(media/Dockerfile 의 10001:10001)와 **일부러 다르게** 둔다 — 파일 교환이
// 소유권이 아니라 모드(디렉토리 0755 / 파일 0644)로 성립한다는 계약을 런타임에서
// 관측 가능하게 만들기 위해서다. 같게 두면 스풀이 0600 으로 퇴화해도 스모크가 통과한다.
const mediaRuntimeUser = "10002:10002"

// volumeOwnerStage 는 빈 디렉토리를 만드는 빌드 스테이지 이름이다.
// 스테이지 이름을 바꾸면 여기도 같이 고친다 — 이름이 어긋나면 COPY 가 빌드 컨텍스트에서
// 복사하려 들어 의도와 다른 것이 들어간다.
const volumeOwnerStage = "prep"

// composeRel 은 저장소 루트 기준 상대 경로다. cmd/mtxhookwrite/contract_test.go 와 같은 관례.
const composeRel = "../../../docker-compose.yml"

// mediaServiceKey 는 compose 의 media 서비스 키다(들여쓰기 2칸).
const mediaServiceKey = "  media:"

// nocopyOption 은 붙는 순간 이 해법을 무력화하는 볼륨 옵션이다.
const nocopyOption = "nocopy"

// ownedVolumeDirs 는 이미지에 소유권을 심어야 하는 볼륨 마운트 지점 3종이다.
// 키 = 컨테이너 안 경로, 값 = compose 의 named volume 이름.
var ownedVolumeDirs = map[string]string{
	"/recordings": "recordings",
	"/dvr":        "dvr",
	"/hooks":      "hooks",
}

// runtimeIdentityGuide 는 실패했을 때 사람이 이것만 읽고 움직일 수 있어야 하는 안내다.
const runtimeIdentityGuide = `
왜 빈 디렉토리를 COPY 하는가: Docker Engine 은 named volume 이 **비어 있을 때만** 이미지의
같은 경로에서 소유권·모드를 복사한다. 이미지에 그 경로가 없으면 복사가 조용히 건너뛰어진다.
지우면 새 볼륨이 0755 root:root 로 생기고 **비root 인 media 가 못 써서 녹화가 멈춘다** —
빌드도 기동도 성공한 뒤에 일어나는 무징후 실패다.

K8s 로 이행하면 파드 레벨 fsGroup 이 같은 일을 하므로 소유권·복사금지·마운트 단언(1·4·5)은
지우고 USER 단언(2)은 남긴다. 판정 체크리스트는 docs/decisions 의
"media 비특권 전환" ADR 의 "K8s 전환 시 폐기·대체 가이드" 절에 있다.
`

// 1. 이미지가 볼륨 소유권을 나른다 — 빈 디렉토리 3종이 10002 소유로 최종 이미지에 들어간다.
func TestMediaImageCarriesVolumeOwnershipForEachVolumeDir(t *testing.T) {
	lines := activeDockerfileLines(readDockerfile(t))

	for dir := range ownedVolumeDirs {
		if got := countVolumeOwnerCopies(lines, dir); got != 1 {
			t.Errorf("%s 에 `COPY --chown=%s --from=%s ... %s` 가 %d 곳이다(기대 1곳).\n%s",
				dockerfileRel, mediaRuntimeUser, volumeOwnerStage, dir, got, runtimeIdentityGuide)
		}
	}
}

// 2. 이미지가 비특권 계정으로 돈다 — 실행 계정의 정본은 이 USER 한 줄이다.
func TestMediaImageRunsAsNonRootUser(t *testing.T) {
	var users []string
	for _, fields := range activeDockerfileLines(readDockerfile(t)) {
		if strings.EqualFold(fields[0], "USER") && len(fields) >= 2 {
			users = append(users, fields[1])
		}
	}

	if len(users) != 1 {
		t.Fatalf("%s 의 USER 가 %d 곳이다(%q) — 실행 계정의 정본이 하나여야 한다.\n%s",
			dockerfileRel, len(users), users, runtimeIdentityGuide)
	}
	if users[0] != mediaRuntimeUser {
		t.Errorf("%s 의 USER = %q, mediaRuntimeUser = %q — 어긋난다. "+
			"--chown 과 USER 가 갈리면 이미지가 자기 볼륨을 못 쓴다.\n%s",
			dockerfileRel, users[0], mediaRuntimeUser, runtimeIdentityGuide)
	}
}

// 3. 커널 플래그 — 실행 계정이 낮아졌으므로 이제 이 플래그가 막을 것이 생겼다(ADR-031).
func TestComposeMediaServiceHasNoNewPrivileges(t *testing.T) {
	block := activeYAMLLines(mediaServiceBlock(t, readCompose(t)))

	if !containsTrimmed(block, "security_opt:") {
		t.Fatalf("docker-compose.yml 의 media 서비스에 security_opt 가 없다 — "+
			"비특권 전환과 한 덩어리일 때만 의미가 있는 플래그다.\n%s", runtimeIdentityGuide)
	}
	if !containsTrimmed(block, "- no-new-privileges:true") {
		t.Errorf("docker-compose.yml 의 media 서비스 security_opt 에 no-new-privileges:true 가 없다 "+
			"(사이드카에는 있다 — 비대칭이 되돌아왔다).\n%s", runtimeIdentityGuide)
	}
}

// 4. 복사 금지 옵션 봉인 — 붙는 순간 소유권 복사가 끊겨 이 해법이 **조용히** 무력화된다.
func TestComposeHasNoVolumeNocopyOption(t *testing.T) {
	for _, line := range activeYAMLLines(readCompose(t)) {
		if strings.Contains(line, nocopyOption) {
			t.Errorf("docker-compose.yml 의 활성 YAML 에 %q 가 있다: %q — "+
				"붙는 순간 이미지에 심은 소유권 복사가 끊겨 비root 쓰기가 조용히 실패한다. "+
				"검사 대상은 이 파일 하나뿐이며, docker-compose.override.yml 은 gitignore 라 "+
				"검사할 수 없다(그쪽에도 넣지 마라).\n%s",
				nocopyOption, strings.TrimSpace(line), runtimeIdentityGuide)
		}
	}
}

// 5. 볼륨 배선 — 소유권을 심어 둔 세 경로가 실제로 붙어 있어야 한다.
// (read_only: true 대신 오설정 자체를 직접 잡는 단언이다 — ADR-0003 결정 7번)
func TestComposeMediaServiceMountsOwnedVolumes(t *testing.T) {
	block := activeYAMLLines(mediaServiceBlock(t, readCompose(t)))

	for dir, name := range ownedVolumeDirs {
		mount := "- " + name + ":" + dir
		if !containsTrimmed(block, mount) {
			t.Errorf("docker-compose.yml 의 media 서비스에 %q 마운트가 없다 — "+
				"소유권을 심어 둔 경로가 볼륨으로 붙지 않으면 컨테이너 레이어에 쓰고 사라진다.\n%s",
				mount, runtimeIdentityGuide)
		}
	}
}

// ---------------------------------------------------------------------------
// 읽기·정규화 헬퍼
// ---------------------------------------------------------------------------

// readCompose 는 오케스트레이션 층의 정본을 읽는다. 못 읽으면 Fatal 이다.
func readCompose(t *testing.T) string {
	t.Helper()

	raw, err := os.ReadFile(composeRel)
	if err != nil {
		abs, _ := filepath.Abs(composeRel)
		t.Fatalf("docker-compose.yml 을 읽지 못했다(%s): %v — 이 테스트가 조용히 넘어가면 안 된다.", abs, err)
	}
	return string(raw)
}

// activeDockerfileLines 는 주석을 뺀 Dockerfile 명령 줄을 필드로 쪼개 돌려준다.
// 주석 안의 예시(digest pin 안내·이 파일이 설명하는 COPY 형태)를 잡지 않기 위해서다 —
// version_contract_test.go 의 mediaMTXTagFromDockerfile 과 같은 관례.
func activeDockerfileLines(dockerfile string) [][]string {
	var lines [][]string
	for line := range strings.SplitSeq(dockerfile, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		if fields := strings.Fields(trimmed); len(fields) > 0 {
			lines = append(lines, fields)
		}
	}
	return lines
}

// countVolumeOwnerCopies 는 dir 로 소유권을 나르는 COPY 가 몇 줄인지 센다.
// --chown 과 --from 을 둘 다 요구한다 — --from 이 빠지면 빌드 컨텍스트에서 복사하게 되고,
// --chown 이 빠지면 root 소유로 들어가 볼륨이 root:root 가 된다.
func countVolumeOwnerCopies(lines [][]string, dir string) int {
	chown := "--chown=" + mediaRuntimeUser
	from := "--from=" + volumeOwnerStage

	count := 0
	for _, fields := range lines {
		if !strings.EqualFold(fields[0], "COPY") || fields[len(fields)-1] != dir {
			continue
		}
		if hasField(fields, chown) && hasField(fields, from) {
			count++
		}
	}
	return count
}

// activeYAMLLines 는 판정 대상이 되는 "활성 YAML" 줄만 남긴다.
//
//	(a) 첫 비공백 문자가 '#' 인 줄은 통째로 버린다.
//	(b) 남은 줄의 " #" 이후 꼬리 주석을 자른다(YAML 주석 규칙).
//
// **(b) 만** cmd/mtxhookwrite/contract_test.go 의 hookSpoolPathFromCompose 관례다.
// (a) 는 이 테스트가 새로 두는 규칙이다 — 원 함수는 `HOOK_SPOOL_PATH:` 키로 앵커링해서
// 주석 줄이 자연히 배제되는 **부수 효과**였고, 부분 문자열 검색인 nocopy 검사에는 그 효과가
// 물려지지 않는다. 헬퍼를 복제만 하면 (a) 가 빠지고, 그 순간 compose 의 nocopy 경고 주석이
// 스스로를 빨간불로 만든다.
func activeYAMLLines(yaml string) []string {
	var lines []string
	for line := range strings.SplitSeq(yaml, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			continue
		}
		code, _, _ := strings.Cut(line, " #")
		if strings.TrimSpace(code) == "" {
			continue
		}
		lines = append(lines, code)
	}
	return lines
}

// mediaServiceBlock 은 compose 에서 media 서비스 블록만 잘라 낸다.
//
// 주석을 떼기 **전에** 자른다 — 블록의 끝은 들여쓰기로 판정하는데, 다음 서비스 앞의
// 주석 줄이 먼저 사라지면 경계가 흐려진다. YAML 파서를 끌어오지 않는 이유는
// hookSpoolPathFromCompose 와 같다(한 줄 스칼라 대조에 의존을 늘릴 이득이 없다).
// 매치가 정확히 1건이 아니면 Fatal 이다.
func mediaServiceBlock(t *testing.T, compose string) string {
	t.Helper()

	all := strings.Split(compose, "\n")
	starts := []int{}
	for i, line := range all {
		if strings.TrimRight(line, " \t") == mediaServiceKey {
			starts = append(starts, i)
		}
	}
	if len(starts) != 1 {
		t.Fatalf("docker-compose.yml 에 %q 가 %d 곳이다 — 어느 것이 media 서비스인지 모호하다.\n%s",
			strings.TrimSpace(mediaServiceKey), len(starts), runtimeIdentityGuide)
	}

	end := len(all)
	for i := starts[0] + 1; i < len(all); i++ {
		if strings.TrimSpace(all[i]) == "" {
			continue
		}
		if indentOf(all[i]) <= indentOf(mediaServiceKey) {
			end = i
			break
		}
	}
	return strings.Join(all[starts[0]+1:end], "\n")
}

// indentOf 는 줄 앞 공백 수를 센다(compose 는 탭을 쓰지 않는다).
func indentOf(line string) int {
	return len(line) - len(strings.TrimLeft(line, " "))
}

// containsTrimmed 는 앞뒤 공백을 뗀 줄이 want 와 정확히 같은 것이 있는지 본다.
func containsTrimmed(lines []string, want string) bool {
	for _, line := range lines {
		if strings.TrimSpace(line) == want {
			return true
		}
	}
	return false
}

// hasField 는 명령 줄의 필드 중 want 와 정확히 같은 것이 있는지 본다.
func hasField(fields []string, want string) bool {
	for _, f := range fields {
		if f == want {
			return true
		}
	}
	return false
}
