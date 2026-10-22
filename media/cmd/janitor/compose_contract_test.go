package main

// compose · 이미지 계약(D19 · D20 · D31 — 설계 5-D · 5-H · 6절). 실행 계정 · 마운트 · 끄기 스위치는
// 빌드도 기동도 통과한 뒤에야 틀린 것이 드러난다(모든 삭제 EACCES · 사이드카 :ro 붕괴 · 끄기가 안
// 먹음) — 그래서 파일을 구조로 읽어 대조한다. 판정 근거 값은 상수 사본이 아니라 정본 파일
// (Dockerfile.mtxhook 의 최종 USER)에서 읽는다. YAML 파서는 끌어오지 않는다(mtxhook 계약 시험의 관례).
// 음성 대조: janitor 서비스가 없는 기준 compose 에서는 첫 단언부터 FAIL 이다.

import (
	"os"
	"slices"
	"strings"
	"testing"
)

const (
	composePath          = "../../../docker-compose.yml"
	dockerfilePath       = "../../Dockerfile"
	mtxhookDockerfile    = "../../Dockerfile.mtxhook"
	janitorBinary        = "/usr/local/bin/janitor"
	sidecarRecordingsRO  = "recordings:/recordings:ro"
	janitorRecordingsRW  = "recordings:/recordings"
	janitorEnabledSwitch = "${JANITOR_ENABLED-true}"
)

func readFile(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("%s 를 읽지 못했다: %v", p, err)
	}
	return string(b)
}

// activeLines 는 주석 줄 · 줄 끝 주석을 걷은 줄들이다(빈 줄 제외).
func activeLines(text string) []string {
	var out []string
	for _, l := range strings.Split(text, "\n") {
		if i := strings.Index(l, " #"); i >= 0 {
			l = l[:i]
		}
		if t := strings.TrimSpace(l); t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		out = append(out, strings.TrimRight(l, " "))
	}
	return out
}

func indentOf(l string) int { return len(l) - len(strings.TrimLeft(l, " ")) }

// serviceBlock 은 services 아래 name 서비스의 줄들이다(머리 줄 제외). 없으면 nil.
func serviceBlock(compose, name string) []string {
	lines := activeLines(compose)
	for i, l := range lines {
		if l != "  "+name+":" {
			continue
		}
		var block []string
		for _, b := range lines[i+1:] {
			if indentOf(b) <= 2 {
				break
			}
			block = append(block, b)
		}
		return block
	}
	return nil
}

// child 는 서비스 직속 key 아래 줄들이다(found = key 가 서비스 직속으로 있다). key 줄에 값이 있으면
// 그 값을 inline 으로 돌려준다.
func child(block []string, key string) (inline string, lines []string, found bool) {
	for i, l := range block {
		if indentOf(l) != 4 || !strings.HasPrefix(strings.TrimSpace(l), key+":") {
			continue
		}
		inline = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(l), key+":"))
		for _, c := range block[i+1:] {
			if indentOf(c) <= 4 {
				break
			}
			lines = append(lines, strings.TrimSpace(c))
		}
		return inline, lines, true
	}
	return "", nil, false
}

// items 는 서비스 직속 key 의 순서열 항목이다(따옴표 걷음).
func items(block []string, key string) []string {
	_, lines, _ := child(block, key)
	var out []string
	for _, l := range lines {
		if v, ok := strings.CutPrefix(l, "- "); ok {
			out = append(out, strings.Trim(v, `"`))
		}
	}
	return out
}

func janitorBlock(t *testing.T) []string {
	t.Helper()
	block := serviceBlock(readFile(t, composePath), "janitor")
	if block == nil {
		t.Fatal("docker-compose.yml 에 janitor 서비스가 없다")
	}
	return block
}

// finalUser 는 Dockerfile 최종 스테이지의 USER 값이다(마지막 FROM 뒤의 마지막 USER).
func finalUser(t *testing.T, dockerfile string) string {
	t.Helper()
	user := ""
	for _, l := range activeLines(dockerfile) {
		f := strings.Fields(l)
		switch strings.ToUpper(f[0]) {
		case "FROM":
			user = ""
		case "USER":
			user = f[1]
		}
	}
	if user == "" {
		t.Fatalf("%s 최종 스테이지에 USER 가 없다", mtxhookDockerfile)
	}
	return user
}

// D19 — janitor 의 실행 계정은 녹화 디렉터리 소유자(MediaMTX 이미지의 최종 USER)와 같아야 지울 수 있다.
func TestComposeJanitorRunsAsRecordingOwner(t *testing.T) {
	want := finalUser(t, readFile(t, mtxhookDockerfile))
	user, _, found := child(janitorBlock(t), "user")
	if !found || strings.Trim(user, `"`) != want {
		t.Errorf("janitor user = %q, want %q(Dockerfile.mtxhook 최종 USER — 다르면 모든 삭제가 EACCES)", user, want)
	}
}

// D20 — recordings 쓰기 마운트는 janitor 하나다: janitor 는 rw(recordings 하나만 — hooks · dvr 미마운트),
// 사이드카는 :ro 그대로다.
func TestComposeOnlyJanitorMountsRecordingsWritable(t *testing.T) {
	if got := items(janitorBlock(t), "volumes"); !slices.Equal(got, []string{janitorRecordingsRW}) {
		t.Errorf("janitor volumes = %q, want [%q](rw · 다른 볼륨 없음)", got, janitorRecordingsRW)
	}
	sidecar := serviceBlock(readFile(t, composePath), "segment-indexer")
	if got := items(sidecar, "volumes"); !slices.Contains(got, sidecarRecordingsRO) || slices.Contains(got, janitorRecordingsRW) {
		t.Errorf("segment-indexer volumes = %q, want %q 유지(G4 — 사이드카는 지우지 않는다)", got, sidecarRecordingsRO)
	}
}

// 권한 · 재시작 · 기동 순서 — no-new-privileges · 능력 전부 버림 · 읽기 전용 루트 FS(t2 r1 Q6 — 공유
// 볼륨의 유일한 쓰기자라 recordings 밖으로는 아무것도 쓰지 못하게) · unless-stopped(꺼짐은 대기라 재시작
// 반복이 없다) · 표가 준비된 DB 와 사이드카 뒤에 뜬다.
func TestComposeJanitorHardeningAndOrdering(t *testing.T) {
	block := janitorBlock(t)
	if got := items(block, "security_opt"); !slices.Contains(got, "no-new-privileges:true") {
		t.Errorf("janitor security_opt = %q, want no-new-privileges:true", got)
	}
	if got := items(block, "cap_drop"); !slices.Equal(got, []string{"ALL"}) {
		t.Errorf("janitor cap_drop = %q, want [ALL]", got)
	}
	if ro, _, _ := child(block, "read_only"); ro != "true" {
		t.Errorf("janitor read_only = %q, want true", ro)
	}
	if restart, _, _ := child(block, "restart"); restart != "unless-stopped" {
		t.Errorf("janitor restart = %q, want unless-stopped", restart)
	}
	_, deps, _ := child(block, "depends_on")
	joined := strings.Join(deps, "\n")
	for _, want := range []string{"postgres:\ncondition: service_healthy", "segment-indexer:\ncondition: service_started"} {
		if !strings.Contains(joined, want) {
			t.Errorf("janitor depends_on 에 %q 가 없다: %q", want, deps)
		}
	}
}

// D31 · K6 — 끄기 스위치는 `${JANITOR_ENABLED-true}`(콜론 없음)다. `.env` 에 `JANITOR_ENABLED=` 를 적으면
// 빈 값이 그대로 가 꺼진다. `:-` 로 쓰면 빈 값이 true 로 바뀌어 끄기가 막힌다.
func TestComposeJanitorEnabledSwitchKeepsEmptyValue(t *testing.T) {
	_, env, _ := child(janitorBlock(t), "environment")
	var got string
	for _, l := range env {
		if v, ok := strings.CutPrefix(l, "JANITOR_ENABLED:"); ok {
			got = strings.Trim(strings.TrimSpace(v), `"`)
		}
	}
	if got != janitorEnabledSwitch {
		t.Errorf("janitor environment JANITOR_ENABLED = %q, want %q", got, janitorEnabledSwitch)
	}
	if strings.Contains(readFile(t, composePath), "${JANITOR_ENABLED:-") {
		t.Error("docker-compose.yml 에 `${JANITOR_ENABLED:-` 가 있다 — 빈 값 끄기가 막힌다")
	}
}

// 이미지 — 같은 이미지에 janitor 바이너리가 들어가고 compose 의 entrypoint 가 그 자리를 가리킨다.
func TestImageCarriesJanitorBinaryAtComposeEntrypoint(t *testing.T) {
	entry, _, _ := child(janitorBlock(t), "entrypoint")
	if entry != `["`+janitorBinary+`"]` {
		t.Errorf("janitor entrypoint = %q, want [%q]", entry, janitorBinary)
	}
	if build, _, _ := child(janitorBlock(t), "build"); build != "./media" {
		t.Errorf("janitor build = %q, want ./media(사이드카와 같은 이미지)", build)
	}
	df := activeLines(readFile(t, dockerfilePath))
	var built, copied bool
	for _, l := range df {
		built = built || (strings.Contains(l, "go build") && strings.Contains(l, "/out/janitor") && strings.HasSuffix(l, "./cmd/janitor"))
		copied = copied || l == "COPY --from=build /out/janitor "+janitorBinary
	}
	if !built || !copied {
		t.Errorf("media/Dockerfile: janitor 빌드 %v · COPY %v, want 둘 다", built, copied)
	}
}
