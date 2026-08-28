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
	"strings"
	"testing"
)

// pinnedMediaMTXVersion 은 media/Dockerfile.mtxhook 의 FROM 태그와 같아야 하는 값이다.
// 이 값을 고치는 행위 = "아래 9개 전제를 새 버전에서 재확인했다"는 선언이다.
const pinnedMediaMTXVersion = "1.20.1"

// mediaMTXImage 는 버전을 고정하는 베이스 이미지 이름이다. 같은 Dockerfile 에 빌드
// 스테이지 FROM(golang:...)이 따로 있으므로 이 이름을 포함한 FROM 만 대상으로 삼는다.
const mediaMTXImage = "bluenviron/mediamtx"

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

// 버전 고정 자리(Dockerfile 의 FROM)와 이 파일의 상수가 어긋나면 빨간불이 된다.
// 상수는 "9개 전제를 재확인했다"는 서명이므로, 서명 없이 FROM 만 올라가는 것을 막는다.
func TestPinnedMediaMTXVersionMatchesDockerfile(t *testing.T) {
	tag := mediaMTXTagFromDockerfile(t, readDockerfile(t))

	if tag != pinnedMediaMTXVersion {
		t.Fatalf("media/Dockerfile.mtxhook 의 FROM 태그 = %q, pinnedMediaMTXVersion = %q — 어긋난다.\n%s",
			tag, pinnedMediaMTXVersion, versionUpgradeGuide)
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

	var refs []string
	for line := range strings.SplitSeq(dockerfile, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "#") {
			continue // 주석 안의 예시 FROM(digest pin 안내)을 잡지 않는다.
		}
		fields := strings.Fields(trimmed)
		if len(fields) < 2 || !strings.EqualFold(fields[0], "FROM") {
			continue
		}
		if strings.Contains(fields[1], mediaMTXImage) {
			refs = append(refs, fields[1])
		}
	}

	switch {
	case len(refs) == 0:
		t.Fatalf("%s 에 %q 를 쓰는 FROM 이 없다 — 버전 고정 자리가 사라졌거나 형식이 바뀌었다.\n%s",
			dockerfileRel, mediaMTXImage, versionUpgradeGuide)
	case len(refs) > 1:
		t.Fatalf("%s 에 %q FROM 이 %d 곳 있다(%q) — 어느 것이 버전 고정 자리인지 모호하다.",
			dockerfileRel, mediaMTXImage, len(refs), refs)
	}

	return tagOf(t, refs[0])
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
