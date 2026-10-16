package ingestauth

import (
	"maps"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"sync"
	"testing"
	"time"
)

// 줄이 하나도 없는 디렉터리(시작 표시도 없음)는 LoadAllowed 가 에러다 — 상태 디렉터리가 지워졌다는
// 뜻이다(설계 3-4 3). 덜 쓰인 줄만 있어도 같다.
func TestLoadAllowedWithoutLines(t *testing.T) {
	tests := []struct {
		name  string
		files map[string]string
	}{
		{name: "빈_디렉터리"},
		{name: "빈_세대_파일", files: map[string]string{connLogName: "", connLogPrevName: ""}},
		{name: "덜_쓰인_줄뿐", files: map[string]string{connLogName: `{"at":"2026-10-12T09:00:00Z"}`}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			for name, content := range tc.files {
				writeFile(t, filepath.Join(dir, name), content)
			}
			if got, err := LoadAllowed(dir); err == nil {
				t.Errorf("LoadAllowed(%q) = %+v, nil error, want error", dir, got)
			}
		})
	}
}

// LoadAllowed 는 덜 쓰인 마지막 줄(줄바꿈 없음 — JSON 이 닫혔어도)과 이 패키지의 꼴이 아닌 줄(실패한
// 쓰기의 조각 · at 이 없는 줄)을 버린다. 그 줄의 연결은 마지막 확인이 읽기 뒤라 거부되거나, 쓰기에
// 실패해 401 이었다. 나머지 줄은 그대로 읽는다.
func TestLoadAllowedSkipsIncompleteAndBrokenLines(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, connLogName), `{"at":"2026-10-12T09:00:00Z"}`+"\n"+
		`{"at":"2026-10-12T09:00:01Z","path":"`+testTokenA+`","id":"conn-1"}`+"\n"+
		`{"at":"2026-10-12T09:00:02Z","pa`+"\n"+ // 실패한 쓰기의 조각
		`{"path":"`+testTokenA+`","id":"conn-no-at"}`+"\n"+ // at 이 없는 줄
		`{"at":"2026-10-12T09:00:03Z","path":"`+testTokenB+`","id":"conn-2"}`+"\n"+
		`{"at":"2026-10-12T09:00:04Z","path":"`+testTokenB+`","id":"conn-3"}`) // 덜 쓰인 마지막 줄 — 줄바꿈 앞에서 읽힘

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) returned error: %v", dir, err)
	}
	want := Allowed{
		Tokens: map[string]string{"conn-1": testTokenA, "conn-2": testTokenB},
		Since:  time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC),
	}
	if !maps.Equal(got.Tokens, want.Tokens) || !got.Since.Equal(want.Since) {
		t.Errorf("LoadAllowed(%q) = %+v, want %+v", dir, got, want)
	}
}

// 현재 세대가 회전 중이라 없으면 빈 파일로 본다 — 직전 세대만 읽는다(설계 2-4).
func TestLoadAllowedTreatsMissingCurrentAsEmpty(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, connLogPrevName), `{"at":"2026-10-12T09:00:00Z"}`+"\n"+
		`{"at":"2026-10-12T09:00:01Z","path":"`+testTokenA+`","id":"conn-1"}`+"\n")

	got, err := LoadAllowed(dir)
	if err != nil {
		t.Fatalf("LoadAllowed(%q) without the current generation returned error: %v", dir, err)
	}
	if want := map[string]string{"conn-1": testTokenA}; !maps.Equal(got.Tokens, want) {
		t.Errorf("LoadAllowed(%q).Tokens = %v, want %v", dir, got.Tokens, want)
	}
	if want := time.Date(2026, 10, 12, 9, 0, 0, 0, time.UTC); !got.Since.Equal(want) {
		t.Errorf("LoadAllowed(%q).Since = %v, want %v", dir, got.Since, want)
	}
}

// 착수 체크리스트 C2 회전 경합(앞부분 — 설계 3-4 「LoadAllowed 의 성질」): 두 읽기 사이에 회전이 0 · 1 · 2번
// 껴도, Since 뒤에 쓰였고 판독 시작 전에 쓰인 줄은 하나도 빠지지 않는다. 회전 횟수는 비공개 이음새
// loadAllowed 의 between(현재 세대를 읽은 뒤, 직전 세대를 읽기 전에 부른다)으로 정확히 만든다.
// maxBytes 가 1 이라 Append 하나가 회전 하나다. 「Since 앞에서 허용된 판독 중 연결」 부분은 Cut 의 기록 밖 갈래
// 시험(cut_test)이 맡는다.
func TestLoadAllowedAcrossRotations(t *testing.T) {
	tests := []struct {
		name      string
		rotations int
		sinceFrom string // Since 가 되어야 하는 줄 — 판독 전의 이 세대 파일의 첫 줄
	}{
		{name: "회전_0번", rotations: 0, sinceFrom: connLogPrevName}, // 두 파일은 이어진 두 세대다
		{name: "회전_1번", rotations: 1, sinceFrom: connLogName},     // .1 은 방금 읽은 파일과 같다
		{name: "회전_2번", rotations: 2, sinceFrom: connLogName},     // .1 은 판독 뒤에 생긴 파일이다
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			c, rec := openTestConnLog(t, dir, 1, newFakeClock())
			var written []recordedLine
			for i := range 3 {
				if err := c.Append(testTokenA, testConnID(i)); err != nil {
					t.Fatalf("Setup: Append returned error: %v", err)
				}
				l, err := lastLineOf(filepath.Join(dir, connLogName))
				if err != nil {
					t.Fatalf("Setup: %v", err)
				}
				written = append(written, l)
			}
			wantSince := readLines(t, filepath.Join(dir, tc.sinceFrom))[0].At
			rotatedBefore := len(rec.events(t, "ingestauth_connlog_rotated"))

			got, err := loadAllowed(dir, func() {
				for i := range tc.rotations {
					if err := c.Append(testTokenB, testConnID(100+i)); err != nil {
						t.Errorf("Append between the two reads returned error: %v", err)
					}
				}
			})
			if err != nil {
				t.Fatalf("loadAllowed(%q) returned error: %v", dir, err)
			}
			if n := len(rec.events(t, "ingestauth_connlog_rotated")) - rotatedBefore; n != tc.rotations {
				t.Fatalf("Setup: rotations between the two reads = %d, want %d", n, tc.rotations)
			}
			if !got.Since.Equal(wantSince) {
				t.Errorf("Since = %v, want %v (판독 전 %s 의 첫 줄)", got.Since, wantSince, tc.sinceFrom)
			}
			for _, l := range written {
				if !l.At.After(got.Since) {
					continue // Since 앞의 줄은 Cut 의 기록 밖 갈래가 맡는다
				}
				if token, ok := got.Tokens[l.ID]; !ok || token != l.Path {
					t.Errorf("Tokens[%q] = %q, %v, want %q — Since(%v) 뒤 · 판독 전에 쓰인 줄이 빠졌다", l.ID, token, ok, l.Path, got.Since)
				}
			}
		})
	}
}

// 회전 경합의 동시 반복: Append(작은 maxBytes 로 자주 돈다) · LoadAllowed(cut) · Check(healthz)가 판마다 함께
// 출발한다 — 시험 고루틴이 판마다 출발 장벽을 열고 셋이 함께 지난다. 판독과 Check 는 그 판의 쓰기가 끝날 때까지
// 되풀이하되 적어도 한 번은 돈다. 그래서 둘은 회전이 이어지는 동안 판마다 끼고, 겹침이 스케줄에 기대지 않는다(쓰기가
// 먼저 다 끝나 판독이 한 번도 돌지 않는 일이 없다). 되풀이마다 차례를 넘겨 쓰는 고루틴을 굶기지 않는다. 판독마다
// 「Since 뒤에 쓰였고 판독 시작 전에 쓰인 줄」이 모두 있고, Check 는 내내 nil 이다(-race -count=20 으로 돈다).
func TestLoadAllowedDuringRotation(t *testing.T) {
	dir := t.TempDir()
	c, rec := openTestConnLog(t, dir, 512, newFakeClock())
	current := filepath.Join(dir, connLogName)
	const rounds, appendsPerRound = 100, 3

	var mu sync.Mutex
	var written []recordedLine // 반환까지 끝난 Append 의 줄
	reads, checks := 0, 0      // 판마다 한 고루틴씩만 늘리고, 판은 wg.Wait 로 차례가 선다
	for round := range rounds {
		start, done := make(chan struct{}), make(chan struct{})
		var wg sync.WaitGroup
		wg.Go(func() {
			defer close(done)
			<-start
			for i := range appendsPerRound {
				if err := c.Append(testTokenA, testConnID(round*appendsPerRound+i)); err != nil {
					t.Errorf("Append returned error: %v", err)
					return
				}
				// 이 고루틴만 쓰므로 현재 세대의 마지막 줄이 방금 쓴 줄이다.
				l, err := lastLineOf(current)
				if err != nil {
					t.Errorf("lastLineOf(%q) returned error: %v", current, err)
					return
				}
				mu.Lock()
				written = append(written, l)
				mu.Unlock()
			}
		})
		wg.Go(func() {
			<-start
			for {
				mu.Lock()
				before := slices.Clone(written)
				mu.Unlock()
				got, err := LoadAllowed(dir)
				if err != nil {
					t.Errorf("LoadAllowed(%q) returned error: %v", dir, err)
					return
				}
				for _, l := range before {
					if _, ok := got.Tokens[l.ID]; l.At.After(got.Since) && !ok {
						t.Errorf("LoadAllowed missed %q written at %v before the read (Since %v)", l.ID, l.At, got.Since)
						return
					}
				}
				reads++
				select {
				case <-done:
					return
				default:
					runtime.Gosched()
				}
			}
		})
		wg.Go(func() {
			<-start
			for {
				if err := c.Check(); err != nil {
					t.Errorf("Check() during rotation = %v, want nil", err)
					return
				}
				checks++
				select {
				case <-done:
					return
				default:
					runtime.Gosched()
				}
			}
		})
		close(start)
		wg.Wait()
	}

	if rotations := len(rec.events(t, "ingestauth_connlog_rotated")); rotations < 10 || reads < rounds || checks < rounds {
		t.Errorf("rotations = %d, reads = %d, checks = %d, want at least 10 rotations and %d reads and checks — 경합이 서지 않았다", rotations, reads, checks, rounds)
	}
	if _, err := os.Stat(filepath.Join(dir, connLogPrevName)); err != nil {
		t.Errorf("previous generation after the run: %v", err)
	}
}
