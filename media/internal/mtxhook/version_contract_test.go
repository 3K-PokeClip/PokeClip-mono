package mtxhook

// 이 파일은 MediaMTX **버전 드리프트 방지 장치**다.
//
// 버전을 고정하는 자리는 media/Dockerfile.mtxhook 의 FROM 한 곳뿐이며 그것이 설계 의도다.
// 문제는 "1.19.3 이라서 참인 사실"에 기대는 코드·설정·문서가 그 바깥에 흩어져 있다는 것이다.
// 그 전제가 새 버전에서 깨져도 **아무 오류가 나지 않는다** — 훅은 fire-and-forget 이라
// 실행되지 않아도 무징후이고, 길이 측정은 틀린 값을 조용히 기록한다.
//
// 그래서 사람의 기억 대신 기계가 잡게 한다. FROM 태그가 아래 상수와 어긋나는 순간
// 빨간불이 되고, 그 실패 메시지가 곧 재확인 안내서다.
//
// 이 패키지에 두는 이유: mtxhook 은 존재 이유 자체가 MediaMTX 훅 계약이고, 아래 9개 전제
// 중 하나(runOnReady 매핑)를 이미 event.go 에 담고 있다. 상수는 프로덕션 코드가 쓰지
// 않으므로 테스트 파일 안에 둔다 — 아무도 안 읽는 값을 프로덕션에 심는 것보다 정직하다.

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// pinnedMediaMTXTag 는 media/Dockerfile.mtxhook 의 FROM 태그와 같아야 하는 값이다.
// **이 값만 바뀌는 것(.1 → .2)은 우리 수정이 바뀐 것이지 버전업이 아니다** — 그래서 이
// 값이 어긋났을 때의 안내는 forkPinGuide(이미지 발행 절차)이지 9개 전제 재확인이 아니다.
const pinnedMediaMTXTag = "v1.20.1-pokeclip.1"

// upstreamBaseVersion 은 우리 포크 빌드가 올라타 있는 상류 MediaMTX 버전이다.
// **아래 9개 전제를 짊어지는 상수는 이것 하나다** — 이 값을 고치는 행위가 곧 "9개 전제를
// 새 버전에서 재확인했다"는 서명이고, 그때만 versionUpgradeGuide 가 안내로 나간다.
const upstreamBaseVersion = "1.20.1"

// pinnedMediaMTXDigest 는 FROM 이 가리키는 이미지의 불변 좌표다.
//
// 역할 분담을 흐리지 말 것: **같은 태그 재푸시로 내용이 바뀌는 것을 실제로 막는 것은
// Dockerfile 의 digest pin 자체**다(도커가 그 digest 를 받는다). 이 상수가 지키는 것은
// 그 좌표가 조용히 사라지거나 다른 값으로 바뀌는 것 — 즉 "핀이 풀린 상태"의 발견이다.
// 닻을 태그에서 digest 로 옮긴 근거는 ADR-050 선결 B. 이미지를 새로 발행했다면 워크플로
// 실행 요약이 찍어 주는 값을 여기에 옮겨 적는다.
const pinnedMediaMTXDigest = "sha256:8878310479bac1009bdc8b45f35a906b01b5a4fe2ec3fde52063071626903d17"

// mediaMTXImage 는 버전을 고정하는 베이스 이미지 이름이다. 같은 Dockerfile 에 빌드
// 스테이지 FROM(golang:...)이 따로 있으므로 이 이름을 포함한 FROM 만 대상으로 삼는다.
//
// **상류 공식 이미지가 아니라 우리 포크 빌드다.** 슬레이트(대기 화면) 무녹화 스위치가
// 상류에 아직 없어서, 머지될 때까지 우리 라인이 그 수정을 싣는다 — 자세한 사정은 Dockerfile
// 주석과 media/README.md 의 "이미지 출처에 묶인 전제" 절에 있다.
const mediaMTXImage = "xodbs1021/mediamtx"

// dockerfileRel 은 저장소 루트 기준 경로다. 상대 경로 방식은 cmd/mtxhookwrite 의
// contract_test.go(docker-compose.yml 대조)와 같은 관례를 따른다.
const dockerfileRel = "../../../media/Dockerfile.mtxhook"

// checklistSection 은 실패 메시지가 가리키는 media/README.md 의 절 제목이다.
const checklistSection = "MediaMTX 버전업 체크리스트"

// versionUpgradeGuide 는 실패했을 때 사람이 이것만 읽고 움직일 수 있어야 하는 안내다.
// 줄 번호는 일부러 적지 않는다 — 금방 낡는다. 대신 grep 으로 바로 찾히는 닻을 적는다.
const versionUpgradeGuide = `
버전을 올렸다면 이 상수도 같이 고치고, media/README.md 의 "` + checklistSection + `"를 따른다.
아래 9곳은 "지금 고정된 버전이라서 참인 사실"에 기대고 있다. 새 버전에서도 참인지 확인한
뒤에 상수를 고쳐라 — 전제가 깨져도 오류는 나지 않는다(훅 실패는 무징후다).

 1. infra/compose/mediamtx.yml  (닻: "pathDefaults 는 all_others 에도 상속된다")
    훅 3종을 pathDefaults 에만 적고 all_others 는 비워 두었다. 상속이 사라지면 훅이 하나도
    발화하지 않는다. → 새 이미지로 all_others 경로에 송출해 스풀에 줄이 쌓이는지 본다.
 2. infra/compose/mediamtx.yml  (닻: "이 세 줄에 ` + "`$`" + ` 를 넣지 않는다")
    명령 문자열을 shell 규칙으로 먼저 쪼갠 뒤 조각별로 변수를 치환한다는 순서에 기댄
    안전성 근거다. 순서가 뒤집히면 송출자가 인자 개수를 늘릴 수 있다.
    → 업스트림 internal/externalcmd/cmd_os.go 를 확인한다.
 3. media/README.md  (닻: "구명칭 runOnReady")
 4. media/internal/mtxhook/event.go  (닻: Kind 주석의 "runOnAvailable 로 매핑")
    3·4 는 같은 사실이다 — runOnReady 는 세션 축이 아니라 runOnAvailable 로 매핑된다.
    → 기동 로그의 deprecated/unknown 파라미터 WARN 으로 훅 3종 이름의 생존을 확인한다.
 5. media/internal/fmp4meta/probe.go  (닻: "트랙 중 최대 길이")
    mvhd 의 duration 이 트랙 중 최대 길이와 일치한다는 전제. 어긋나면 길이가 조용히 틀린다.
 6. media/internal/fmp4meta/probe_test.go  (닻: "채취: MediaMTX")
    testdata 3종은 이 버전이 recordPath 로 떨어뜨린 원본이다. 박스 배치가 바뀌면 픽스처를
    새 버전 산출물로 다시 채취해야 검증 대상이 실물과 같아진다.
 7. media/internal/recording/settle.go  (닻: "업스트림 기본값 recordPartDuration")
    기본값 1s 의 2배가 SEGMENT_SETTLE_WAIT 2s 의 근거다. 기본값이 커지면 절반짜리 파일을
    완성으로 오판한다. → 새 이미지의 기본 설정에서 recordPartDuration 을 확인한다.
 8. media/Dockerfile.mtxhook  (닻: "USER 10002:10002")
    MediaMTX 가 루트FS·CWD 에 쓰지 않는다는 전제. 비root(UID 10002)로 돌기 때문에 쓰려는
    순간 실패한다. moq: no 라 지금은 참이지만 moq/webrtc/rtsps + auto.key 류를 켜면 비root
    에서 기동 자체가 실패한다(POK-79 E7). → 새 버전 기본 설정의 CWD 쓰기 지점을 확인하고,
    기동 로그에 "failed to save"·"permission denied" 가 없는지 본다.
 9. media/README.md 전제 표 9행  (닻: "302 cookieCheck")
    HLS 첫 요청의 302 cookieCheck 는 1.19.3 에도 있던 동작이다(이번에 처음 체크리스트화).
    버전별 델타만 다르다 — 1.20.1: plain HTTP 쿠키 중단(Partitioned 통합)·세션 쿼리 폴백(만료 401).
    이 행은 서빙 경계 상시 리스크라 롤백해도 걷어내지 않는다. 델타 서술만 그 버전 값으로 갱신한다.
`

// forkPinGuide 는 "버전"이 아니라 **"어느 이미지냐"** 가 어긋났을 때의 안내다.
//
// 위 9곳이 MediaMTX 버전에 묶인 전제라면, 이것은 이미지 출처에 묶인 전제 하나다:
// 이 이미지에는 상류에 아직 없는 슬레이트 무녹화 수정이 들어 있다. 상류 공식 이미지로
// 되돌리면 **대기 화면이 다시 녹화되어 저장소로 올라간다 — 오류도 로그도 없이.**
const forkPinGuide = `
FROM 이 가리키는 이미지가 우리 포크 빌드가 아니다.

이 이미지에만 있는 것: 슬레이트(송출이 끊겼을 때 서버가 대신 내보내는 대기 화면) 구간을
녹화에서 빼는 스위치 alwaysAvailableRecorded. 상류 제안은 PR #5767 이고 아직 머지 전이다.
공식 이미지로 되돌리면 그 스위치가 사라져 대기 화면이 조용히 저장소로 올라간다.

새 이미지를 발행했다면:
  1. xodbs1021/mediamtx 의 pokeclip 라인에 커밋하고 *-pokeclip.* 태그를 민다.
  2. pokeclip-image 워크플로가 멀티아치 이미지를 올리고 실행 요약에 digest 를 찍는다.
  3. 그 tag·digest 를 media/Dockerfile.mtxhook 의 FROM 과 이 파일의 상수 2개에 함께 옮긴다.

상류에 PR #5767 이 머지됐다면(= 포크가 필요 없어졌다면) 아래를 전부 정리한다.
빠뜨리면 공식 태그에서 포크 전용 단언이 남아 빨간불이 된다:
  1. Dockerfile FROM 을 bluenviron/mediamtx:<그 버전> 으로 되돌린다.
  2. mediaMTXImage 를 "bluenviron/mediamtx" 로 되돌린다.
  3. pinnedMediaMTXTag 를 그 버전으로 고치거나, upstreamBaseVersion 하나로 합친다.
  4. pinnedMediaMTXDigest 상수와 TestPinnedMediaMTXDigestMatchesDockerfile 을 지운다
     (공식 이미지는 태그 pin 을 쓰던 기존 관례로 돌아간다).
  5. TestPinnedTagCarriesUpstreamBaseVersion 과 TestPinContractRejectsUnusedForkStageBypass,
     그리고 이 안내(forkPinGuide)를 지운다 — 전부 포크 전용이다.
  6. media/README.md 의 "이미지 출처에 묶인 전제" 절을 걷어낸다.
경로 설정의 alwaysAvailableRecorded 는 그대로 둔다 — 상류 파라미터 이름이 같다.
`

// 고정 자리(Dockerfile 의 FROM)와 이 파일의 상수가 어긋나면 빨간불이 된다.
// FROM 만 조용히 갈리는 것을 막는 장치다 — 9개 전제의 서명은 upstreamBaseVersion 이 진다.
func TestPinnedMediaMTXVersionMatchesDockerfile(t *testing.T) {
	tag := mediaMTXTagFromDockerfile(t, readDockerfile(t))

	if tag != pinnedMediaMTXTag {
		t.Fatalf("media/Dockerfile.mtxhook 의 FROM 태그 = %q, pinnedMediaMTXTag = %q — 어긋난다.\n%s",
			tag, pinnedMediaMTXTag, forkPinGuide)
	}
}

// digest 는 태그와 달리 갈아 끼울 수 없는 좌석이다. 같은 태그로 다른 이미지가 올라와도
// 이 단언이 걸린다 — 무징후 교체를 막는 것이 이 상수의 존재 이유다(ADR-050 선결 B).
func TestPinnedMediaMTXDigestMatchesDockerfile(t *testing.T) {
	digest := digestOf(t, mediaMTXRefFromDockerfile(t, readDockerfile(t)))

	if digest != pinnedMediaMTXDigest {
		t.Fatalf("media/Dockerfile.mtxhook 의 FROM digest = %q, pinnedMediaMTXDigest = %q — 어긋난다.\n%s",
			digest, pinnedMediaMTXDigest, forkPinGuide)
	}
}

// 포크 태그는 `v<상류버전>-pokeclip.<N>` 형식이고, 자기가 올라탄 상류 버전을 이름에 담는다.
// 그 대응이 깨지면 "전제 9곳이 묶인 버전"이 무엇인지 아무도 알 수 없게 된다.
//
// 접두 검사가 아니라 **형식 전체**를 본다 — 접두만 보면 `v1.20.1-pokeclip.1-hotfix` 같은
// 변형이 통과하고, 그러면 태그에서 상류 버전을 읽는 규칙 자체가 흐려진다.
func TestPinnedTagCarriesUpstreamBaseVersion(t *testing.T) {
	want := regexp.MustCompile(`^v` + regexp.QuoteMeta(upstreamBaseVersion) + `-pokeclip\.[1-9][0-9]*$`)

	if !want.MatchString(pinnedMediaMTXTag) {
		t.Fatalf("포크 태그 %q 가 형식 %s 에 맞지 않는다 — 전제 9곳이 어느 상류 버전에 묶인 "+
			"것인지 태그에서 읽을 수 없게 된다. 상류 버전을 올렸다면 upstreamBaseVersion 도 "+
			"함께 고치고 아래 9곳을 재확인하라.\n%s",
			pinnedMediaMTXTag, want, versionUpgradeGuide)
	}
}

// 뚫릴 수 있었던 입력의 회귀.
//
// "우리 이미지를 쓰는 FROM 이 하나 있는가"로 물으면 아래 입력이 통과한다 — 쓰지 않는
// 스테이지에 포크 이미지를 남겨 두고 **실제로 도는 최종 스테이지만 상류 공식 이미지로**
// 바꾼 모양이다. 그 컨테이너에는 우리 패치가 없고, 그 사실은 아무 오류도 내지 않는다.
func TestPinContractRejectsUnusedForkStageBypass(t *testing.T) {
	const dockerfile = `FROM ghcr.io/xodbs1021/mediamtx:v1.20.1-pokeclip.1@sha256:dead AS unused
FROM golang:1.26-alpine AS build
FROM bluenviron/mediamtx:1.20.1
COPY --from=build /out/mtxhookwrite /hooks-bin/mtxhookwrite
`

	if ref := lastFromRef(dockerfile); strings.Contains(ref, mediaMTXImage) {
		t.Errorf("최종 스테이지 FROM = %q 인데 %q 를 쓰는 것으로 판정했다 — 쓰지 않는 포크 "+
			"스테이지를 남긴 우회가 통과한다.", ref, mediaMTXImage)
	}
}

// 실패 메시지가 가리키는 절이 실제로 있어야 안내가 안내로 남는다.
// 절 제목이 바뀌면 메시지는 존재하지 않는 곳으로 사람을 보낸다.
func TestVersionUpgradeChecklistSectionExists(t *testing.T) {
	const readmeRel = "../../README.md"

	raw, err := os.ReadFile(readmeRel)
	if err != nil {
		abs, _ := filepath.Abs(readmeRel)
		t.Fatalf("media/README.md 를 읽지 못했다(%s): %v", abs, err)
	}
	if !strings.Contains(string(raw), checklistSection) {
		t.Errorf("media/README.md 에 %q 절이 없다 — 버전 드리프트 실패 메시지가 "+
			"존재하지 않는 곳을 가리키게 된다", checklistSection)
	}
}

// readDockerfile 은 버전 고정 자리의 본문을 읽는다.
// 못 읽으면 skip 이 아니라 fail 이다 — 조용히 통과하면 이 장치가 없는 것과 같다.
func readDockerfile(t *testing.T) string {
	t.Helper()

	raw, err := os.ReadFile(dockerfileRel)
	if err != nil {
		abs, _ := filepath.Abs(dockerfileRel)
		t.Fatalf("버전 고정 자리인 %s 를 읽지 못했다(%s): %v — 파일이 옮겨졌다면 "+
			"dockerfileRel 을 고쳐라. 이 테스트가 조용히 넘어가면 안 된다.",
			dockerfileRel, abs, err)
	}
	return string(raw)
}

// mediaMTXTagFromDockerfile 은 mediamtx 베이스 이미지의 태그를 뽑는다.
//
// 대상은 mediaMTXImage 를 포함한 FROM 뿐이다 — 같은 파일의 빌드 스테이지(FROM golang:...)를
// 잡지 않기 위해서다. 매치가 정확히 1건이 아니면 Fatal 이다. 나중에 스테이지가 늘어
// 첫 매치가 엉뚱한 것을 가리키게 되면, 그 순간 조용히 넘어가지 않고 여기서 멈춘다.
func mediaMTXTagFromDockerfile(t *testing.T, dockerfile string) string {
	t.Helper()

	return tagOf(t, mediaMTXRefFromDockerfile(t, dockerfile))
}

// mediaMTXRefFromDockerfile 은 **최종 스테이지**의 이미지 참조 전체(`이름:태그@digest`)를
// 돌려준다. 태그와 digest 를 각각 대조해야 해서 참조를 통째로 얻는 자리를 따로 둔다.
//
// **왜 "우리 이미지를 쓰는 FROM 이 하나 있는가"를 묻지 않는가**: 그 물음은 우회를 통과시킨다 —
// 쓰지 않는 스테이지에 포크 이미지를 남겨 두고 최종 스테이지만 상류 공식 이미지로 바꾸면
// 조건이 참이 되고, 실제로 도는 컨테이너에는 우리 패치가 없다(그 실패는 무징후다).
// 그래서 **마지막 FROM** 만 본다 — 최종 이미지의 베이스는 그것 하나뿐이다.
// 같은 패키지 runtime_identity_contract_test.go 의 finalStageLines 와 같은 취지다.
func mediaMTXRefFromDockerfile(t *testing.T, dockerfile string) string {
	t.Helper()

	last := lastFromRef(dockerfile)

	if last == "" {
		t.Fatalf("%s 에 FROM 이 하나도 없다 — 버전 고정 자리가 사라졌거나 형식이 바뀌었다.\n%s",
			dockerfileRel, forkPinGuide)
	}
	if !strings.Contains(last, mediaMTXImage) {
		t.Fatalf("%s 의 **최종 스테이지** FROM = %q 로 %q 가 아니다 — 실제로 도는 컨테이너의 "+
			"베이스가 우리 포크 빌드가 아니다.\n%s", dockerfileRel, last, mediaMTXImage, forkPinGuide)
	}
	return last
}

// lastFromRef 는 최종 스테이지의 베이스 이미지 참조다.
// 순수 함수로 떼어 둔 이유는 회귀 입력(아래 우회 재현)을 직접 먹이기 위해서다.
func lastFromRef(dockerfile string) string {
	var last string
	for _, fields := range activeDockerfileLines(dockerfile) {
		if strings.EqualFold(fields[0], "FROM") && len(fields) >= 2 {
			last = fields[1]
		}
	}
	return last
}

// digestOf 는 이미지 참조에서 digest 를 떼어 낸다. digest 가 없으면 Fatal 이다 —
// 태그만 남은 참조는 "같은 이름으로 다른 내용이 올 수 있는 상태"라 고정이 아니다.
func digestOf(t *testing.T, ref string) string {
	t.Helper()

	_, digest, ok := strings.Cut(ref, "@")
	if !ok || digest == "" {
		t.Fatalf("%s 의 FROM %q 에 digest 가 없다 — 태그만으로는 같은 이름에 다른 내용이 "+
			"올라오는 것을 막지 못한다(그 교체는 무징후다).\n%s", dockerfileRel, ref, forkPinGuide)
	}
	return digest
}

// tagOf 는 이미지 참조에서 태그만 떼어 낸다.
//
// digest pin(`이미지:태그@sha256:...`)도 받는다 — Dockerfile 주석이 그 형태로 바꾸는 길을
// 열어 두고 있으므로, 그때 이 장치가 형식 때문에 죽으면 안 된다.
// 레지스트리 호스트에 포트가 붙는 경우(`host:5000/...`)를 위해 마지막 `/` 뒤에서 자른다.
func tagOf(t *testing.T, ref string) string {
	t.Helper()

	name, _, _ := strings.Cut(ref, "@") // digest 부분을 떼어 낸다.
	if idx := strings.LastIndex(name, "/"); idx >= 0 {
		name = name[idx+1:]
	}

	_, tag, ok := strings.Cut(name, ":")
	if !ok || tag == "" {
		t.Fatalf("%s 의 FROM %q 에서 태그를 읽지 못했다 — `%s:<버전>` 형식이 아니다. "+
			"태그 없는 FROM 은 latest 추적이라 버전 고정 자체가 사라진 상태다.\n%s",
			dockerfileRel, ref, mediaMTXImage, versionUpgradeGuide)
	}
	return tag
}
