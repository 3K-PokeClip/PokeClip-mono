package credrelay

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/secretfile"
)

// 토큰은 전체가 정확히 같을 때만 맞는다(P8). 접두 · 덧붙임 · 빈 값 · Bearer 꼴은 아무 호출자도 아니다. 다른
// 호출자의 토큰은 그 호출자로 맞는다(혼동하지 않는다).
func TestTokensMatchExactTokenOnly(t *testing.T) {
	tok := loadTestTokens(t)
	for _, c := range []struct {
		name, auth, wantCaller string
		wantOK                 bool
	}{
		{name: "indexer", auth: canaryIndexerToken, wantCaller: "indexer", wantOK: true},
		{name: "janitor", auth: canaryJanitorToken, wantCaller: "janitor", wantOK: true},
		{name: "prefix", auth: canaryIndexerToken[:8]},
		{name: "suffix_added", auth: canaryIndexerToken + "x"},
		{name: "empty", auth: ""},
		{name: "bearer", auth: "Bearer " + canaryIndexerToken},
		{name: "both_joined", auth: canaryIndexerToken + canaryJanitorToken},
	} {
		t.Run(c.name, func(t *testing.T) {
			caller, ok := tok.Match(c.auth)
			if caller != c.wantCaller || ok != c.wantOK {
				t.Errorf("Match(%s 사례) = (%q, %v), want (%q, %v)", c.name, caller, ok, c.wantCaller, c.wantOK)
			}
		})
	}
}

// 토큰 파일이 하나라도 받을 수 없으면 기동 거부다(이유 token_file). 루트 밖 링크 · 루트 안 다른 이름 링크는 받을
// 만한 파일을 가리키게 둔다 — 이어 붙인 경로로 열거나 이름 대조를 빼면 받아들인다(P23). 두 호출자의 토큰이
// 같아도 거부다(c2 보안 L) — 받아들이면 그 토큰의 요청을 어느 호출자로 볼지 갈리지 않아 caller 로그가 틀리고
// 한쪽만 끊을 수 없다.
func TestLoadTokensRejectsBadTokenFiles(t *testing.T) {
	for _, c := range []struct {
		name   string
		setup  func(t *testing.T, dir string)
		wantIs error // nil 이면 ErrTokenFile 만 본다
	}{
		{name: "missing", setup: func(t *testing.T, dir string) {}, wantIs: fs.ErrNotExist},
		{name: "empty", setup: func(t *testing.T, dir string) { writeToken(t, dir, "indexer", "\n") }},
		{name: "two_lines", setup: func(t *testing.T, dir string) { writeToken(t, dir, "indexer", "a\nb") }},
		{name: "other_bit", setup: func(t *testing.T, dir string) {
			chmod(t, writeToken(t, dir, "indexer", canaryIndexerToken), 0o404)
		}},
		{name: "directory", setup: func(t *testing.T, dir string) { mkdir(t, filepath.Join(dir, "indexer")) }, wantIs: secretfile.ErrNotRegularFile},
		{name: "link_inside", setup: func(t *testing.T, dir string) {
			symlink(t, "janitor", filepath.Join(dir, "indexer"))
		}, wantIs: secretfile.ErrLinkName},
		{name: "same_token", setup: func(t *testing.T, dir string) { writeToken(t, dir, "indexer", canaryJanitorToken) }},
		{name: "link_outside", setup: func(t *testing.T, dir string) {
			writeToken(t, filepath.Dir(dir), "outside", canaryIndexerToken)
			symlink(t, filepath.Join("..", "outside"), filepath.Join(dir, "indexer"))
		}},
	} {
		t.Run(c.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "cred-relay")
			mkdir(t, dir)
			writeToken(t, dir, "janitor", canaryJanitorToken)
			c.setup(t, dir)

			_, err := LoadTokens(dir, []string{"indexer", "janitor"})
			if err == nil {
				t.Fatalf("LoadTokens(%s) 가 받았다 — 루트 밖 링크를 열었거나 받을 수 없는 파일을 받았다", c.name)
			}
			if !errors.Is(err, ErrTokenFile) {
				t.Errorf("LoadTokens(%s) = %v, want errors.Is(ErrTokenFile)", c.name, err)
			}
			if c.wantIs != nil && !errors.Is(err, c.wantIs) {
				t.Errorf("LoadTokens(%s) = %v, want errors.Is(%v)", c.name, err, c.wantIs)
			}
			if strings.Contains(err.Error(), canaryIndexerToken) || strings.Contains(err.Error(), canaryJanitorToken) {
				t.Errorf("LoadTokens(%s): 에러에 토큰 카나리아 발견", c.name)
			}
		})
	}
}

// 호출자 이름은 경로 요소 하나(`^[a-z][a-z0-9]*$`)여야 하고 비거나 겹치면 안 된다(K3). 파일을 보기 전에
// 거부한다 — 없는 디렉터리를 줘도 이유가 ErrCallers 다.
func TestLoadTokensRejectsBadCallers(t *testing.T) {
	missingDir := filepath.Join(t.TempDir(), "none")
	for _, c := range []struct {
		name    string
		callers []string
	}{
		{name: "nil", callers: nil},
		{name: "empty_name", callers: []string{""}},
		{name: "upper", callers: []string{"Indexer"}},
		{name: "dotdot", callers: []string{"../indexer"}},
		{name: "slash", callers: []string{"a/b"}},
		{name: "dot", callers: []string{"."}},
		{name: "leading_digit", callers: []string{"1indexer"}},
		{name: "hyphen", callers: []string{"cred-indexer"}},
		{name: "duplicate", callers: []string{"indexer", "indexer"}},
	} {
		t.Run(c.name, func(t *testing.T) {
			_, err := LoadTokens(missingDir, c.callers)
			if !errors.Is(err, ErrCallers) {
				t.Errorf("LoadTokens(%q) = %v, want errors.Is(ErrCallers)", c.callers, err)
			}
		})
	}
}

// 디렉터리에 r 비트가 없으면(0311) os.Root 로 열지 못한다 — 중계 디렉터리를 0750 으로 둬야 하는 까닭(계획
// E1 · D1). 이유는 token_file 이다. root 는 권한 검사를 우회하므로 건너뛴다.
func TestLoadTokensUnreadableDir(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	dir := filepath.Join(t.TempDir(), "cred-relay")
	mkdir(t, dir)
	writeToken(t, dir, "indexer", canaryIndexerToken)
	chmod(t, dir, 0o311)
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })

	_, err := LoadTokens(dir, []string{"indexer"})
	if !errors.Is(err, ErrTokenFile) || !errors.Is(err, fs.ErrPermission) {
		t.Errorf("LoadTokens(0311 디렉터리) = %v, want errors.Is(ErrTokenFile) · errors.Is(fs.ErrPermission)", err)
	}
}

func mkdir(t *testing.T, dir string) {
	t.Helper()
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("시험 폴더 생성 실패: %v", err)
	}
}

func chmod(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	if err := os.Chmod(path, mode); err != nil {
		t.Fatalf("chmod 실패: %v", err)
	}
}

func symlink(t *testing.T, target, link string) {
	t.Helper()
	if err := os.Symlink(target, link); err != nil {
		t.Fatalf("링크 생성 실패: %v", err)
	}
}
