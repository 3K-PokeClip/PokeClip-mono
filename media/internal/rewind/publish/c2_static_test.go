package publish

// G9 leaf c2_no_flag_read(설계 9.1 ⒞ — 계획 게이트 매핑 M4 ⓒ) 의 정적 단언. 발행 경로는 컷오프 기능 플래그
// (REWIND_SEED_ENABLED — config 의 Indexer.SeedEnabled)를 읽지 않는다. 플래그는 장부 쪽 주조만 켜고 끄고, 발행은
// 장부(컷오프 유무)만 본다 — 플래그를 끈 롤백에서도 이미 주조된 스트림의 목록은 그대로 나간다(c1 · c3 와 같은 묶음).

import (
	"fmt"
	"go/scanner"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// flagIdent · flagEnv 는 컷오프 기능 플래그를 가리키는 이름이다 — 설정 칸과 환경변수 이름.
const (
	flagIdent = "SeedEnabled"
	flagEnv   = "REWIND_SEED_ENABLED"
)

// 훑개는 식별자와 문자열 글자만 센다 — 주석 안의 이름은 코드가 플래그를 읽는 것이 아니다. 문자열을 세는 까닭은
// 설정 칸을 거치지 않고 환경변수를 바로 읽는 길(os.Getenv)도 같은 위반이기 때문이다.
func TestFlagReadUsesCountsCodeOnly(t *testing.T) {
	src := []byte("package x\n" +
		"\n" +
		"// SeedEnabled 는 읽지 않는다 — REWIND_SEED_ENABLED 도\n" +
		"func f() {\n" +
		"\t_ = cfg.Indexer.SeedEnabled\n" +
		"\t_ = os.Getenv(\"REWIND_SEED_ENABLED\")\n" +
		"\t_ = seeded\n" +
		"}\n")
	want := []string{
		"x.go:5:18 SeedEnabled",
		`x.go:6:16 "REWIND_SEED_ENABLED"`,
	}

	got, err := flagReadUses("x.go", src)
	if err != nil {
		t.Fatalf("flagReadUses 실패: %v", err)
	}
	if !slices.Equal(got, want) {
		t.Errorf("flagReadUses = %q, want %q", got, want)
	}
}

// c2_no_flag_read — 발행 경로의 소스(internal/rewind 아래 전부와 조립점의 되감기 파일 cmd/segment-indexer/rewind_*.go ·
// 테스트 파일 제외)에 컷오프 기능 플래그를 읽는 코드가 0건이다.
func TestC2NoFlagRead(t *testing.T) {
	root := listScanRoot
	var files []string
	err := filepath.WalkDir(filepath.Join(root, "internal", "rewind"), func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".go") && !strings.HasSuffix(path, "_test.go") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("internal/rewind 를 훑지 못했다: %v", err)
	}
	cmd, err := filepath.Glob(filepath.Join(root, "cmd", "segment-indexer", "rewind_*.go"))
	if err != nil {
		t.Fatalf("조립점 되감기 파일 목록 실패: %v", err)
	}
	for _, path := range cmd {
		if !strings.HasSuffix(path, "_test.go") {
			files = append(files, path)
		}
	}

	scanned := map[string]bool{}
	for _, path := range files {
		rel, err := filepath.Rel(root, path)
		if err != nil {
			t.Fatalf("상대 경로 실패: %v", err)
		}
		rel = filepath.ToSlash(rel)
		src, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%s 읽기 실패: %v", rel, err)
		}
		uses, err := flagReadUses(rel, src)
		if err != nil {
			t.Fatalf("%s 훑기 실패: %v", rel, err)
		}
		for _, u := range uses {
			t.Errorf("발행 경로가 컷오프 기능 플래그를 읽는다: %s — c2_no_flag_read", u)
		}
		scanned[rel] = true
	}
	// 뿌리나 범위를 잘못 적으면 아무것도 훑지 않고 통과한다 — 갈래마다 대표 파일을 훑었는지 본다.
	for _, want := range []string{
		"internal/rewind/render.go",              // 목록 본문
		"internal/rewind/cache/cache.go",         // 목록 입력
		"internal/rewind/publish/tick.go",        // 발행
		"cmd/segment-indexer/rewind_assembly.go", // 발행 조립
		"cmd/segment-indexer/rewind_loop.go",     // 발행 루프
	} {
		if !scanned[want] {
			t.Errorf("%s 를 훑지 않았다(훑은 파일 %d개)", want, len(scanned))
		}
	}
}

// flagReadUses 는 src 의 식별자 가운데 flagIdent 를 품은 것과 문자열 글자 가운데 flagEnv 를 품은 것을
// 「파일:줄:열 글자」로 돌려준다. 주석은 훑개가 건너뛴다.
func flagReadUses(filename string, src []byte) ([]string, error) {
	fset := token.NewFileSet()
	file := fset.AddFile(filename, fset.Base(), len(src))
	var scanErr error
	var s scanner.Scanner
	s.Init(file, src, func(pos token.Position, msg string) {
		if scanErr == nil {
			scanErr = fmt.Errorf("%s: %s", pos, msg)
		}
	}, 0)

	var uses []string
	for {
		pos, tok, lit := s.Scan()
		if tok == token.EOF {
			return uses, scanErr
		}
		if (tok == token.IDENT && strings.Contains(lit, flagIdent)) || (tok == token.STRING && strings.Contains(lit, flagEnv)) {
			uses = append(uses, fmt.Sprintf("%s %s", fset.Position(pos), lit))
		}
	}
}
