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
//
// 판정은 **구조를 보고** 한다. 문자열만 훑으면 Dockerfile 의 스테이지 경계도 YAML 의 부모 키도
// 모르는데, 바로 그 두 가지가 이 장치를 실제로 뚫었다(둘 다 실측) — 최종 스테이지를 하나
// 덧붙이면 단언이 전부 초록불인데 이미지는 root 로 돌고, `command:` 밑에 같은 문자열을 적어
// 두면 플래그도 마운트도 없이 통과한다. 그래서 Dockerfile 은 **최종 스테이지만** 보고,
// compose 는 **부모 키 소속**을 확인한 뒤 판정한다. 그러면서도 YAML 파서는 끌어오지 않는다 —
// 한 줄 대조에 의존을 늘리지 않는다는 이 패키지의 관례를 지킨다.

import (
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
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

// volumeOwnerSrcRoot 는 그 스테이지가 빈 디렉토리를 만들어 두는 뿌리다(`mkdir -p /prep/...`).
// COPY 의 소스가 여기 밑이 아니면 볼륨 자리에 엉뚱한 것이 들어간다 — 예컨대 파일 하나를
// 복사하면 `/recordings` 가 디렉토리가 아니라 **파일**이 되어 마운트·기동이 깨진다.
const volumeOwnerSrcRoot = "/" + volumeOwnerStage

// hookBinaryPath 는 훅이 부르는 정적 바이너리가 최종 이미지에서 놓이는 자리다.
const hookBinaryPath = "/hooks-bin/mtxhookwrite"

// rootOwner 는 그 바이너리를 못박아 두는 소유자다. mediaRuntimeUser 와 **반드시 달라야** 한다 —
// 같아지는 순간 실행 계정이 자기가 실행할 바이너리를 덮어쓸 수 있다.
const rootOwner = "0:0"

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
지우고 USER 단언(2)과 훅 바이너리 소유 단언(6)은 남긴다 — 6은 오케스트레이터와 무관하게
"실행 계정이 자기 바이너리를 못 덮어쓴다"를 지키는 단언이다. 판정 체크리스트는 docs/decisions 의
"media 비특권 전환" ADR 의 "K8s 전환 시 폐기·대체 가이드" 절에 있다.
`

// 1. 이미지가 볼륨 소유권을 나른다 — 빈 디렉토리 3종이 10002 소유로 최종 이미지에 들어간다.
// 판정은 최종 스테이지 한정이다. 앞 스테이지의 COPY 는 최종 이미지에 남지 않는다.
func TestMediaImageCarriesVolumeOwnershipForEachVolumeDir(t *testing.T) {
	lines := finalStageLines(readDockerfile(t))

	for dir := range ownedVolumeDirs {
		if got := countVolumeOwnerCopies(t, lines, dir); got != 1 {
			t.Errorf("%s 의 최종 스테이지에 `COPY --chown=%s --from=%s %s %s` 가 %d 곳이다(기대 1곳).\n%s",
				dockerfileRel, mediaRuntimeUser, volumeOwnerStage, volumeOwnerSrcRoot+dir, dir, got,
				runtimeIdentityGuide)
		}
	}
}

// 2. 이미지가 비특권 계정으로 돈다 — 실행 계정의 정본은 최종 스테이지의 USER 한 줄이다.
func TestMediaImageRunsAsNonRootUser(t *testing.T) {
	users := usersIn(finalStageLines(readDockerfile(t)))

	if len(users) != 1 {
		t.Fatalf("%s 의 최종 스테이지에 USER 가 %d 곳이다(%q) — 실행 계정의 정본이 하나여야 한다. "+
			"앞 스테이지의 USER 는 최종 이미지가 물려받지 않으므로 여기서 0곳이면 root 로 돈다.\n%s",
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

	opts, found := serviceSequenceItems(block, "security_opt")
	if !found {
		t.Fatalf("docker-compose.yml 의 media 서비스에 security_opt 가 없다(서비스의 직속 키여야 한다) — "+
			"비특권 전환과 한 덩어리일 때만 의미가 있는 플래그다.\n%s", runtimeIdentityGuide)
	}
	if !slices.Contains(opts, "no-new-privileges:true") {
		t.Errorf("docker-compose.yml 의 media 서비스 security_opt 에 no-new-privileges:true 가 없다 "+
			"(security_opt 항목 = %q. 사이드카에는 있다 — 비대칭이 되돌아왔다).\n%s",
			opts, runtimeIdentityGuide)
	}
}

// 4. 복사 금지 옵션 봉인 — 붙는 순간 소유권 복사가 끊겨 이 해법이 **조용히** 무력화된다.
func TestComposeHasNoVolumeNocopyOption(t *testing.T) {
	for _, line := range nocopyLines(activeYAMLLines(readCompose(t))) {
		t.Errorf("docker-compose.yml 의 활성 YAML 에 %q 가 있다: %q — "+
			"붙는 순간 이미지에 심은 소유권 복사가 끊겨 비root 쓰기가 조용히 실패한다. "+
			"검사 대상은 이 파일 하나뿐이며, docker-compose.override.yml 은 gitignore 라 "+
			"검사할 수 없다(그쪽에도 넣지 마라).\n%s",
			nocopyOption, strings.TrimSpace(line), runtimeIdentityGuide)
	}
}

// 5. 볼륨 배선 — 소유권을 심어 둔 세 경로가 실제로 붙어 있어야 한다.
// (read_only: true 대신 오설정 자체를 직접 잡는 단언이다 — ADR-0003 결정 7번)
func TestComposeMediaServiceMountsOwnedVolumes(t *testing.T) {
	block := activeYAMLLines(mediaServiceBlock(t, readCompose(t)))
	mounts, _ := serviceSequenceItems(block, "volumes")

	for dir, name := range ownedVolumeDirs {
		mount := name + ":" + dir
		if !slices.Contains(mounts, mount) {
			t.Errorf("docker-compose.yml 의 media 서비스 volumes 에 %q 가 없다 — "+
				"소유권을 심어 둔 경로가 볼륨으로 붙지 않으면 컨테이너 레이어에 쓰고 사라진다.\n%s",
				"- "+mount, runtimeIdentityGuide)
		}
	}
}

// 6. 훅 바이너리는 root 소유다 — 실행 계정이 자기가 실행할 바이너리를 덮어쓸 수 없어야 한다.
//
// 단언 2가 최종 스테이지만 보게 되면서 build 스테이지의 USER 는 판정에서 빠졌다. 그런데
// COPY --from 은 **소스 스테이지의 uid 를 보존하므로**(실측), build 스테이지에 USER 를 넣는
// 선의의 변경 하나로 이 바이너리가 10002 소유가 된다. --chown 을 명시로 요구해 그 경로를 끊는다.
// 소스 스테이지의 USER 를 뒤지는 대신 --chown 을 보는 이유: --chown 이 있으면 소스 스테이지가
// 무엇을 하든 결과가 고정되므로 이쪽이 더 직접적이다.
//
// 소유자만 보면 부족하다([cx] r3 MEDIUM): `COPY --chmod=0777 --chown=0:0 ...` 는 root 소유인
// 동시에 **누구나 쓸 수 있는** 바이너리를 만든다 — 지키려던 성질이 깨진 채 이 단언은 통과한다.
// 그래서 copyOperands 로 플래그 화이트리스트(--chown·--from)를 함께 태운다.
func TestMediaImageKeepsHookBinaryRootOwned(t *testing.T) {
	copies := copiesTo(finalStageLines(readDockerfile(t)), hookBinaryPath)

	if len(copies) != 1 {
		t.Fatalf("%s 의 최종 스테이지에서 %s 를 심는 COPY 가 %d 곳이다(기대 1곳) — "+
			"경로 표기가 바뀌었다면 hookBinaryPath 를 함께 고쳐라.\n%s",
			dockerfileRel, hookBinaryPath, len(copies), runtimeIdentityGuide)
	}
	// 뜻을 모르는 플래그가 있으면 여기서 Fatal 이다(--chmod 는 모드를, --parents 는 배치를
	// 바꾼다). 그런 줄을 통과시키면 아래 --chown 검사가 혼자서는 아무것도 보장하지 못한다.
	if operands := copyOperands(t, copies[0]); len(operands) != 2 {
		t.Fatalf("%s 의 %s COPY 가 소스 1개 + 목적지 1개가 아니다(피연산자 %d개): %q — "+
			"소스가 여럿이면 목적지가 디렉토리가 되어 훅 바이너리 자리가 사라진다.\n%s",
			dockerfileRel, hookBinaryPath, len(operands), strings.Join(copies[0], " "),
			runtimeIdentityGuide)
	}
	if !hasField(copies[0], "--chown="+rootOwner) {
		t.Errorf("%s 의 %s COPY 에 `--chown=%s` 가 없다: %q — "+
			"COPY --from 은 소스 스테이지의 uid 를 보존한다(빌드 컨텍스트에서 복사할 때와 다르다). "+
			"그래서 build 스테이지에 `USER %s` 를 넣는 것만으로 이 바이너리가 실행 계정 소유가 되고, "+
			"**실행 계정이 자기 훅 바이너리를 덮어쓸 수 있게 된다.** 그 불가능성이 비특권 전환의 "+
			"가장 큰 실질 이득이자 ADR-0003 결정 7(read_only 를 넣지 않는 근거)의 전제다.\n%s",
			dockerfileRel, hookBinaryPath, rootOwner, strings.Join(copies[0], " "),
			mediaRuntimeUser, runtimeIdentityGuide)
	}
}

// ---------------------------------------------------------------------------
// 뚫렸던 입력 회귀
// ---------------------------------------------------------------------------

// 아래 4종은 이 장치가 실제로 **통과시켰던** 입력이다(POK-79 코드 검수 A~D 재현).
// 위 5단언은 실물 파일을 읽으므로 재현 입력을 담을 수 없다 — 그래서 헬퍼에 직접 먹여
// 판정만 대조한다.
//
// **한계**: 이것은 헬퍼를 고칠 때만 그물이 된다. 헬퍼를 그대로 두고 호출부만 옛것으로
// 되돌리면(단언이 finalStageLines 대신 activeDockerfileLines 를 부르는 식) 여기는 그대로
// 통과하고 구멍만 되살아난다(실측). 화이트박스 대조의 구조적 한계이므로, 호출부를 바꾸는
// 변경은 사람이 봐야 한다.
func TestDockerfileContractRejectsKnownBypasses(t *testing.T) {
	t.Run("최종 스테이지를 덧붙이면 앞 스테이지의 USER·소유권은 최종 이미지에 없다", func(t *testing.T) {
		const dockerfile = `FROM bluenviron/mediamtx:1.19.3
COPY --chown=10002:10002 --from=prep /prep/recordings /recordings
USER 10002:10002

FROM pokeclip-media-base
COPY --from=build /out/mtxhookwrite /hooks-bin/mtxhookwrite
`
		stage := finalStageLines(dockerfile)

		if got := countVolumeOwnerCopies(t, stage, "/recordings"); got != 0 {
			t.Errorf("최종 스테이지의 소유권 COPY = %d 곳(기대 0곳) — 스테이지 경계를 보지 않는다.", got)
		}
		if got := usersIn(stage); len(got) != 0 {
			t.Errorf("최종 스테이지의 USER = %q(기대 없음) — 스테이지 경계를 보지 않는다.", got)
		}
	})

	t.Run("소스가 디렉토리가 아닌 파일이면 소유권 COPY 로 세지 않는다", func(t *testing.T) {
		const dockerfile = `FROM bluenviron/mediamtx:1.19.3
COPY --chown=10002:10002 --from=prep /etc/alpine-release /recordings
`
		if got := countVolumeOwnerCopies(t, finalStageLines(dockerfile), "/recordings"); got != 0 {
			t.Errorf("소유권 COPY = %d 곳(기대 0곳) — 소스를 보지 않으면 /recordings 가 파일이 된다.", got)
		}
	})
}

// compose 쪽 재현 입력. 위 함수와 같은 취지이고 보는 파일만 다르다.
func TestComposeContractRejectsKnownBypasses(t *testing.T) {
	t.Run("엉뚱한 부모 키 밑의 항목은 그 키의 것이 아니다", func(t *testing.T) {
		const serviceBlock = `    security_opt:
      - seccomp=unconfined
    command:
      - no-new-privileges:true
      - recordings:/recordings
`
		block := activeYAMLLines(serviceBlock)

		opts, found := serviceSequenceItems(block, "security_opt")
		if !found {
			t.Fatal("security_opt 를 찾지 못했다 — 재현 입력이 아니라 헬퍼가 깨졌다.")
		}
		if slices.Contains(opts, "no-new-privileges:true") {
			t.Errorf("command: 밑의 항목을 security_opt 의 것으로 셌다(%q).", opts)
		}
		mounts, _ := serviceSequenceItems(block, "volumes")
		if slices.Contains(mounts, "recordings:/recordings") {
			t.Errorf("command: 밑의 항목을 volumes 의 것으로 셌다(%q).", mounts)
		}
	})

	t.Run("큰따옴표 이스케이프로 적은 복사 금지 옵션도 잡는다", func(t *testing.T) {
		const composeFragment = `        volume:
          "no\u0063opy": true
`
		if got := nocopyLines(activeYAMLLines(composeFragment)); len(got) != 1 {
			t.Errorf("적발 = %d 줄(기대 1줄) — compose 는 이 표기를 nocopy 로 읽는다.", len(got))
		}
	})
}

// 시퀀스를 부모 키와 같은 깊이로 적는 것도 유효한 YAML 이고 compose 가 받는다(실측).
// 그것을 블록의 끝으로 오해하면 항목을 0개로 읽어, 멀쩡한 설정을 "키는 있는데 비었다"고
// 오보하며 실패시킨다. 흔한 표기라 여기서 못 박는다.
func TestServiceSequenceItemsAcceptsSameIndentSequence(t *testing.T) {
	const serviceBlock = `    security_opt:
    - no-new-privileges:true
    environment:
      TZ: UTC
`
	items, found := serviceSequenceItems(activeYAMLLines(serviceBlock), "security_opt")

	if !found {
		t.Fatal("security_opt 를 찾지 못했다.")
	}
	if !slices.Contains(items, "no-new-privileges:true") {
		t.Errorf("같은 깊이의 시퀀스 항목을 읽지 못했다(항목 = %q).", items)
	}
	if slices.Contains(items, "TZ: UTC") {
		t.Errorf("다음 키(environment:)의 내용까지 삼켰다(항목 = %q).", items)
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

// finalStageLines 는 **최종 스테이지**의 명령 줄만 돌려준다 — FROM 을 만날 때마다 앞의 것을 버린다.
//
// 평평한 줄 목록으로 보면 스테이지 경계를 모른다. 현행 USER 뒤에 스테이지를 하나 덧붙이면
// 단언이 전부 초록불인데 최종 이미지는 root 로 돈다(실측) — 최종 이미지는 마지막 FROM 의 것이고
// USER·소유권 디렉토리는 거기로 상속되지 않기 때문이다. 스모크로도 안 잡힌다: root 는 10002
// 소유 볼륨에 그대로 쓰므로 녹화·훅·인덱싱이 전부 정상이고 보안 자세만 조용히 사라진다.
//
// 앞 스테이지를 FROM 하는 refactor(그때는 실제로 상속된다)에서는 이 판정이 오탐이 된다.
// 그래도 그렇게 둔다 — 조용한 통과보다 시끄러운 오탐이 낫다는 이 파일의 관례다.
func finalStageLines(dockerfile string) [][]string {
	var stage [][]string
	for _, fields := range activeDockerfileLines(dockerfile) {
		if strings.EqualFold(fields[0], "FROM") {
			stage = nil
			continue
		}
		stage = append(stage, fields)
	}
	return stage
}

// usersIn 은 주어진 스테이지에 적힌 USER 의 인자들이다.
func usersIn(lines [][]string) []string {
	var users []string
	for _, fields := range lines {
		if strings.EqualFold(fields[0], "USER") && len(fields) >= 2 {
			users = append(users, fields[1])
		}
	}
	return users
}

// countVolumeOwnerCopies 는 dir 로 소유권을 나르는 COPY 가 몇 줄인지 센다.
//
// --chown 과 --from 을 둘 다 요구한다 — --from 이 빠지면 빌드 컨텍스트에서 복사하게 되고,
// --chown 이 빠지면 root 소유로 들어가 볼륨이 root:root 가 된다.
// 소스 경로도 대조한다: 목적지만 보면 `--from=prep /etc/alpine-release /recordings` 도
// 통과하는데, 그러면 /recordings 가 디렉토리가 아닌 **파일**이 되어 마운트·기동이 깨진다.
func countVolumeOwnerCopies(t *testing.T, lines [][]string, dir string) int {
	t.Helper()

	chown := "--chown=" + mediaRuntimeUser
	from := "--from=" + volumeOwnerStage
	src := volumeOwnerSrcRoot + dir

	count := 0
	for _, fields := range copiesTo(lines, dir) {
		if !hasField(fields, chown) || !hasField(fields, from) {
			continue
		}
		// 소스 1개 + 목적지 1개만 인정한다. 소스가 여럿이면 목적지가 디렉토리여야 하는 등
		// 규칙이 달라져, 한 줄이 무엇을 심는지 이 대조로는 더 이상 단정할 수 없다.
		if operands := copyOperands(t, fields); len(operands) == 2 && operands[0] == src {
			count++
		}
	}
	return count
}

// copiesTo 는 목적지가 dst 인 COPY 줄만 고른다. 목적지는 플래그를 파싱하지 않아도 알 수 있다 —
// COPY 의 플래그는 언제나 피연산자 앞에 오므로 마지막 필드가 목적지다.
func copiesTo(lines [][]string, dst string) [][]string {
	var copies [][]string
	for _, fields := range lines {
		if strings.EqualFold(fields[0], "COPY") && fields[len(fields)-1] == dst {
			copies = append(copies, fields)
		}
	}
	return copies
}

// copyAllowedFlags 는 이 대조가 뜻을 아는 COPY 플래그다.
var copyAllowedFlags = []string{"--chown", "--from"}

// copyOperands 는 COPY 줄에서 명령어와 플래그를 뺀 소스·목적지만 남긴다.
//
// 모르는 플래그를 만나면 Fatal 이다. 플래그를 그냥 걷어내면 의미를 바꾸는 것까지 통과하기
// 때문이다 — 예컨대 --parents 는 부모 경로를 보존해서, "빈 디렉토리를 그 자리에 심는다"가
// 아니라 소스 경로째로 심는 다른 동작이 된다. 조용히 통과시키면 이 장치가 없는 것과 같다.
func copyOperands(t *testing.T, fields []string) []string {
	t.Helper()

	var operands []string
	for _, f := range fields[1:] {
		if !strings.HasPrefix(f, "--") {
			operands = append(operands, f)
			continue
		}
		if name, _, _ := strings.Cut(f, "="); !slices.Contains(copyAllowedFlags, name) {
			t.Fatalf("%s 의 COPY 에 이 대조가 뜻을 모르는 플래그 %q 가 있다: %q — "+
				"이 COPY 가 무엇을 심는지 단정할 수 없다(예: --parents 는 부모 경로를 보존해 "+
				"빈 디렉토리를 그 자리에 심는 것과 동작이 다르다). 의도한 변경이라면 "+
				"copyAllowedFlags 에 넣고 이 대조가 여전히 성립하는지 확인하라.\n%s",
				dockerfileRel, name, strings.Join(fields, " "), runtimeIdentityGuide)
		}
	}
	return operands
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

// serviceSequenceItems 는 서비스 블록의 **직속 키** 밑에 매달린 시퀀스 항목만 돌려준다
// (`- ` 를 뗀 값). 두 번째 반환값은 그 키가 있었는지다 — 키가 없는 것과 비어 있는 것은
// 다른 사건이라 호출부가 구분해서 안내할 수 있게 나눠 돌려준다.
//
// 평탄 검색이면 부모가 누구든 문자열만 맞으면 통과한다: `command:` 밑에
// `- no-new-privileges:true` 와 `- recordings:/recordings` 를 적어 두면, 실제로는 플래그도
// 마운트도 없는데 단언 3·5 가 초록불이 된다(실측). 그래서 들여쓰기로 부모를 판정한다.
// **직속 키로 좁히는 이유도 같다** — 더 깊은 블록 안의 같은 이름 키가 진짜 자리를 가로채는
// 것을 막는다. YAML 파서는 여기서도 끌어오지 않는다(mediaServiceBlock 과 같은 이유).
//
// 한계: 흐름 표기(`security_opt: [no-new-privileges:true]`)는 키를 못 찾은 것으로 읽어
// "없다"고 실패한다. 유효한 YAML 이므로 오탐이지만 **시끄러운 실패**라 조용한 통과보다 낫다.
func serviceSequenceItems(block []string, key string) ([]string, bool) {
	keyIndent := directChildIndent(block)

	var items []string
	found := false
	for _, line := range block {
		if !found {
			if indentOf(line) == keyIndent && strings.TrimSpace(line) == key+":" {
				found = true
			}
			continue
		}
		item, isItem := strings.CutPrefix(strings.TrimSpace(line), "- ")
		if isItem {
			// 시퀀스는 부모 키보다 깊게도, **같은 깊이로도** 쓸 수 있다(둘 다 유효한 YAML 이고
			// compose 가 받는다 — 실측). 같은 깊이를 블록의 끝으로 보면 항목을 0개로 읽어
			// "키는 있는데 비었다"는 오보를 낸다.
			items = append(items, strings.TrimSpace(item))
			continue
		}
		if indentOf(line) <= keyIndent {
			break // 같은 깊이의 다음 **키**를 만나면 이 키의 블록은 끝났다.
		}
	}
	return items, found
}

// directChildIndent 는 블록에서 가장 얕은 들여쓰기 = 서비스의 직속 자식 키가 놓이는 자리다.
// activeYAMLLines 를 거친 블록에는 빈 줄이 없으므로 이 최솟값이 곧 그 자리다.
func directChildIndent(lines []string) int {
	indent := -1
	for _, line := range lines {
		if n := indentOf(line); indent < 0 || n < indent {
			indent = n
		}
	}
	return indent
}

// nocopyLines 는 복사 금지 옵션이 보이는 줄을 원문 그대로 돌려준다(사람이 자기가 쓴 줄을
// 그대로 봐야 고칠 수 있으므로 정규화한 문자열이 아니라 원문을 남긴다).
func nocopyLines(lines []string) []string {
	var hits []string
	for _, line := range lines {
		if strings.Contains(decodedYAMLEscapes(line), nocopyOption) {
			hits = append(hits, line)
		}
	}
	return hits
}

// yamlCharEscape 는 큰따옴표 스칼라 안에서 한 글자를 코드로 적는 표기다(`\u0063` = c).
var yamlCharEscape = regexp.MustCompile(`\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}`)

// decodedYAMLEscapes 는 큰따옴표 스칼라의 `\uXXXX`·`\xXX` 를 실제 문자로 되돌린다.
// 원문 그대로 찾으면 `"no\u0063opy": true` 가 그물을 빠져나가는데 compose 는 그것을 nocopy 로 읽는다.
//
// 여기까지가 범위다. `\UXXXXXXXX`(8자리)나 앵커·병합 키는 **의도적 우회**라 목표 밖이다 —
// 목표는 우회를 막는 것이 아니라 **실수로 적어 넣은 표기**를 잡는 것이고, 그 이상은
// 의존을 늘리지 않는다는 관례에 어긋난다.
//
// 대소문자는 내리지 않는다: compose 의 nocopy 키는 대소문자를 구분해 `NoCopy` 를 아예
// 거부하므로(실측 — "additional properties 'NoCopy' not allowed"), 소문자화는 막아 주는 것
// 없이 `/data/NoCopy/out` 같은 정상 값만 오탐한다.
func decodedYAMLEscapes(line string) string {
	decoded := yamlCharEscape.ReplaceAllStringFunc(line, func(esc string) string {
		code, err := strconv.ParseUint(esc[2:], 16, 32)
		if err != nil {
			return esc
		}
		return string(rune(code))
	})
	return decoded
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
