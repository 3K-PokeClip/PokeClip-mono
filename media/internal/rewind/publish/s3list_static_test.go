package publish

// S3 LIST 금지(프로필 4절)의 정적 단언 — 계획 6.5 ⑴. media 모듈의 소스에 S3 목록 연산을 가리키는 식별자가
// 0건이다. 동적 그물은 가짜의 목록 함정이다(TestFakeStoreFailsTestOnListCalls).
//
// 훑는 길은 둘이다. 디렉터리 훑기(TestNoS3ListSymbolsInSource)는 모듈 디렉터리의 .go 를 GOOS 와
// 무관하게 본다(_test.go 와 testdata 아래는 뺀다). 빌드 그래프 훑기(TestNoS3ListSymbolsInBuildGraph)는 go list 가 고른 메인 모듈 패키지의
// 파일을 본다 — 디렉터리 훑기가 건너뛰는 testdata 아래 패키지와 심링크로 끌어온 모듈 밖 패키지도 import 되면
// 잡힌다. 빌드 그래프는 시험을 돌린 호스트의 GOOS · 빌드 태그 기준이어서, 다른 GOOS 전용 파일은 디렉터리
// 훑기가 덮는다.
//
// 범위 밖: 이 단언은 식별자 훑기다. 문자열로 만든 이름의 반사 호출(R1), 서명 앞 미들웨어의 경로
// 치환(R2), 손수 서명한 원 HTTP 요청(R3), 새 의존 라이브러리 내부 호출(R6), media 밖 모듈(R7)은 보지
// 못한다. 실행 시점에 이들을 모두 막는 것은 IAM 의 s3:ListBucket 거부뿐인데, 운영 사이드카에는 그 권한이
// 부여돼 있다(이전 명료화 문답 68항 · 2026-10 기준). 이 길들의 방어선은 코드 리뷰다.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go/scanner"
	"go/token"
	"io"
	"io/fs"
	"os"
	execpkg "os/exec"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

// listScanRoot 는 훑을 소스 뿌리 — media 모듈 전체다. 발행 경로와 이웃한 업로드 · 재포장 계층만이 아니라 S3
// 클라이언트를 조립하는 cmd/ 와 그 밖의 패키지도 목록 연산을 부를 수 있다. 테스트는 패키지
// 디렉터리(internal/rewind/publish)에서 돌아 상대 경로로 적는다.
var listScanRoot = filepath.Join("..", "..", "..")

// 훑개가 식별자만 센다 — 주석과 문자열 안의 이름은 코드가 그 연산을 부르거나 가리키는 것이 아니다. 목록
// 연산의 입력 형 · 페이지 넘기개처럼 이름에 연산 이름을 품은 식별자는 센다. 뿌리 다섯(listOps)이 저마다
// 잡히고, 뿌리를 품지 않은 List 이름(ListenAndServe)과 대소문자가 다른 이름(playlistParts)은 세지 않는다.
func TestListSymbolUsesCountsIdentifiersOnly(t *testing.T) {
	src := []byte("package x\n" +
		"\n" +
		"// ListObjects 는 부르지 않는다\n" +
		"var s = \"ListBuckets\"\n" +
		"func f() {\n" +
		"\tc.ListObjectsV2(ctx, in)\n" +
		"\t_ = s3.NewListObjectsV2Paginator(c, in)\n" +
		"\tc.ListBuckets(ctx, nil)\n" +
		"\tc.ListObjectVersions(ctx, in)\n" +
		"\tc.ListBucketMetricsConfigurations(ctx, in)\n" +
		"\tc.ListDirectoryBuckets(ctx, in)\n" +
		"\tc.ListMultipartUploads(ctx, in)\n" +
		"\t_ = s3.NewListPartsPaginator(c, in)\n" +
		"\tsrv.ListenAndServe()\n" +
		"\tplaylistParts := 0\n" +
		"}\n")
	want := []string{
		"x.go:6:4 ListObjectsV2",
		"x.go:7:9 NewListObjectsV2Paginator",
		"x.go:8:4 ListBuckets",
		"x.go:9:4 ListObjectVersions",
		"x.go:10:4 ListBucketMetricsConfigurations",
		"x.go:11:4 ListDirectoryBuckets",
		"x.go:12:4 ListMultipartUploads",
		"x.go:13:9 NewListPartsPaginator",
	}

	got, err := listSymbolUses("x.go", src)
	if err != nil {
		t.Fatalf("listSymbolUses 실패: %v", err)
	}
	if !slices.Equal(got, want) {
		t.Errorf("listSymbolUses = %q, want %q", got, want)
	}
}

// 이름 집합이 고정한 SDK 판(go.mod)의 목록 연산을 빠짐없이 덮는다 — 정적 단언과 가짜의 목록 함정
// (TestFakeStoreFailsTestOnListCalls)이 같은 집합을 본다. SDK 를 올려 목록 연산이 늘면 여기서 드러난다.
func TestListOpsCoverClientListMethods(t *testing.T) {
	methods := clientListMethods()
	if len(methods) == 0 {
		t.Fatal("*s3.Client 에서 List 로 시작하는 메서드를 찾지 못했다")
	}
	for _, name := range methods {
		if !isListOp(name) {
			t.Errorf("isListOp(%q) = false, want true — 정적 단언이 이 목록 연산을 놓친다", name)
		}
	}
}

// 계획 6.5 ⑴ — media 모듈의 소스(테스트 파일 · testdata 제외)에 S3 목록 연산 식별자가 없다. 객체가 있는지는
// 업로더가 PUT 200 시점에 인덱스에 적은 upload_state 로만 판단한다.
func TestNoS3ListSymbolsInSource(t *testing.T) {
	scanned := map[string]bool{}
	err := filepath.WalkDir(listScanRoot, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if d.Name() == "testdata" {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		rel, err := filepath.Rel(listScanRoot, path)
		if err != nil {
			return err
		}
		src, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		uses, err := listSymbolUses(filepath.ToSlash(rel), src)
		if err != nil {
			return err
		}
		for _, u := range uses {
			t.Errorf("S3 목록 연산 식별자 %s — 프로필 4절 「S3 LIST 금지」", u)
		}
		scanned[filepath.ToSlash(rel)] = true
		return nil
	})
	if err != nil {
		t.Fatalf("%s 를 훑지 못했다: %v", listScanRoot, err)
	}
	// 뿌리를 잘못 적으면 일부만 훑거나 아무것도 훑지 않고 통과한다 — 모듈 뿌리 기준 경로로 갈래마다 대표
	// 파일을 훑었는지 본다(뿌리가 모듈 뿌리보다 위이거나 아래이면 어긋난다).
	for _, want := range []string{
		"cmd/segment-indexer/main.go",        // S3 클라이언트 조립점
		"internal/index/store.go",            // 인덱스(PG)
		"internal/playback/key.go",           // 재포장 키
		"internal/rewind/publish/s3store.go", // 발행 저장소(S3)
		"internal/upload/s3.go",              // 세그먼트 업로더(S3)
	} {
		if !scanned[want] {
			t.Errorf("%s 를 훑지 않았다 — 뿌리가 모듈 뿌리가 아니다(훑은 파일 %d개)", want, len(scanned))
		}
	}
}

// 계획 6.5 ⑴ 보강 — media 모듈의 빌드 그래프(go list -deps 가 고른 메인 모듈 패키지의 파일)에 S3 목록 연산
// 식별자가 없다. 디렉터리 훑기가 건너뛰는 testdata 아래 패키지와 심링크로 끌어온 패키지도 import 되면 본다.
func TestNoS3ListSymbolsInBuildGraph(t *testing.T) {
	uses, scanned, err := buildGraphListUses(listScanRoot)
	if err != nil {
		t.Fatalf("%s 의 빌드 그래프를 훑지 못했다: %v", listScanRoot, err)
	}
	for _, u := range uses {
		t.Errorf("S3 목록 연산 식별자 %s — 프로필 4절 「S3 LIST 금지」", u)
	}
	// 빈 결과로 통과하지 않게 디렉터리 훑기와 같은 대표 파일을 훑었는지 본다.
	for _, want := range listScanRepresentativeFiles {
		if !scanned[want] {
			t.Errorf("%s 를 훑지 않았다 — 빌드 그래프가 모듈을 덮지 않는다(훑은 파일 %d개)", want, len(scanned))
		}
	}
}

// listScanRepresentativeFiles 는 media 모듈 갈래마다 하나씩 고른 대표 파일의 모듈 뿌리 기준 경로다 — 훑기가
// 모듈을 덮었는지 보는 표지다. TestNoS3ListSymbolsInSource 의 목록과 같다.
var listScanRepresentativeFiles = []string{
	"cmd/segment-indexer/main.go",        // S3 클라이언트 조립점
	"internal/index/store.go",            // 인덱스(PG)
	"internal/playback/key.go",           // 재포장 키
	"internal/rewind/publish/s3store.go", // 발행 저장소(S3)
	"internal/upload/s3.go",              // 세그먼트 업로더(S3)
}

// R4 — testdata 아래 패키지는 디렉터리 훑기가 건너뛰지만 import 되면 빌드에 들어간다. 빌드 그래프 훑기는 그
// 파일의 목록 연산 식별자를 잡는다.
func TestBuildGraphScanSeesTestdataImport(t *testing.T) {
	root := t.TempDir()
	writeScanModule(t, root, map[string]string{
		"main.go":               importingMain("testdata/evil"),
		"testdata/evil/evil.go": evilPackage,
	})

	uses, _, err := buildGraphListUses(root)
	if err != nil {
		t.Fatalf("buildGraphListUses(%s) 실패: %v", root, err)
	}
	want := []string{"testdata/evil/evil.go:3:6 ListObjectsV2"}
	if !slices.Equal(uses, want) {
		t.Errorf("buildGraphListUses = %q, want %q", uses, want)
	}
}

// R5 — 모듈 안 심링크가 가리키는 모듈 밖 패키지도 import 되면 빌드에 들어간다. 빌드 그래프 훑기는 그 파일의
// 목록 연산 식별자를 잡는다.
func TestBuildGraphScanSeesSymlinkedPackage(t *testing.T) {
	dir := t.TempDir()
	root := filepath.Join(dir, "mod")
	writeScanModule(t, root, map[string]string{"main.go": importingMain("linked")})
	writeScanFile(t, filepath.Join(dir, "outside", "evil", "evil.go"), evilPackage)
	if err := os.Symlink(filepath.Join("..", "outside", "evil"), filepath.Join(root, "linked")); err != nil {
		t.Fatalf("심링크를 만들지 못했다: %v", err)
	}

	uses, _, err := buildGraphListUses(root)
	if err != nil {
		t.Fatalf("buildGraphListUses(%s) 실패: %v", root, err)
	}
	want := []string{"linked/evil.go:3:6 ListObjectsV2"}
	if !slices.Equal(uses, want) {
		t.Errorf("buildGraphListUses = %q, want %q", uses, want)
	}
}

// 음성 기준 — 같은 testdata 패키지라도 import 하지 않으면 빌드에 들어가지 않아 잡지 않는다. 훑기가 무조건
// 잡는 것이 아님을 보인다.
func TestBuildGraphScanIgnoresUnimportedTestdata(t *testing.T) {
	root := t.TempDir()
	writeScanModule(t, root, map[string]string{
		"main.go":               "package main\n\nfunc main() {}\n",
		"testdata/evil/evil.go": evilPackage,
	})

	uses, scanned, err := buildGraphListUses(root)
	if err != nil {
		t.Fatalf("buildGraphListUses(%s) 실패: %v", root, err)
	}
	if len(uses) != 0 {
		t.Errorf("buildGraphListUses = %q, want 없음", uses)
	}
	if !scanned["main.go"] {
		t.Errorf("main.go 를 훑지 않았다(훑은 파일 %v)", scanned)
	}
}

// go 를 찾지 못하면 오류다 — 훑지 못한 것이 0건으로 통과하지 않는다.
func TestBuildGraphFilesFailsWithoutGo(t *testing.T) {
	t.Setenv("PATH", "")

	if _, files, err := buildGraphFiles(listScanRoot); err == nil {
		t.Errorf("buildGraphFiles(%s) = %d개 파일, nil 오류 — want go 부재 오류", listScanRoot, len(files))
	}
}

// import 를 찾지 못하면 오류다 — go list 가 0 아닌 코드로 끝난 그래프를 0건으로 통과시키지 않는다.
func TestBuildGraphFilesFailsOnBrokenImport(t *testing.T) {
	root := t.TempDir()
	writeScanModule(t, root, map[string]string{"main.go": importingMain("missing")})

	if _, files, err := buildGraphFiles(root); err == nil {
		t.Errorf("buildGraphFiles(%s) = %d개 파일, nil 오류 — want import 실패 오류", root, len(files))
	}
}

// evilPackage 는 목록 연산 이름의 함수를 둔 패키지다 — 3행 6열에 ListObjectsV2 가 있다.
const evilPackage = "package evil\n\nfunc ListObjectsV2() {}\n"

// importingMain 은 임시 모듈(scanModulePath)의 rel 패키지를 import 하는 main 패키지 소스다.
func importingMain(rel string) string {
	return fmt.Sprintf("package main\n\nimport _ %q\n\nfunc main() {}\n", scanModulePath+"/"+rel)
}

// scanModulePath 는 빌드 그래프 시험의 임시 모듈 경로다.
const scanModulePath = "example.com/scan"

// writeScanModule 은 root 에 외부 의존 없는 임시 모듈(go.mod 와 files)을 만든다. go 지시문은 로컬 툴체인
// 이하로 두어 툴체인을 내려받지 않는다.
func writeScanModule(t *testing.T, root string, files map[string]string) {
	t.Helper()
	writeScanFile(t, filepath.Join(root, "go.mod"), "module "+scanModulePath+"\n\ngo 1.22\n")
	for rel, src := range files {
		writeScanFile(t, filepath.Join(root, filepath.FromSlash(rel)), src)
	}
}

// writeScanFile 은 path 에 src 를 쓴다 — 없는 디렉터리는 만든다.
func writeScanFile(t *testing.T, path, src string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
}

// buildGraphListUses 는 moduleRoot 의 빌드 그래프 파일을 listSymbolUses 로 훑는다. uses 와 scanned 의
// 경로는 go list 가 돌려준 메인 모듈 디렉터리 기준이다 — macOS 의 t.TempDir() 는 /var/… 인데 go list 는
// 실경로 /private/var/… 를 낼 수 있어 moduleRoot 기준 경로는 어긋난다.
func buildGraphListUses(moduleRoot string) (uses []string, scanned map[string]bool, err error) {
	modDir, files, err := buildGraphFiles(moduleRoot)
	if err != nil {
		return nil, nil, err
	}
	scanned = map[string]bool{}
	for _, path := range files {
		rel, err := filepath.Rel(modDir, path)
		if err != nil {
			return nil, nil, err
		}
		rel = filepath.ToSlash(rel)
		src, err := os.ReadFile(path)
		if err != nil {
			return nil, nil, err
		}
		fileUses, err := listSymbolUses(rel, src)
		if err != nil {
			return nil, nil, err
		}
		uses = append(uses, fileUses...)
		scanned[rel] = true
	}
	return uses, scanned, nil
}

// buildGraphFiles 는 moduleRoot 의 메인 모듈 패키지와 그 의존 가운데 메인 모듈에 속한 패키지의 Go 소스
// 파일(GoFiles · CgoFiles) 절대 경로와 메인 모듈 디렉터리를 돌려준다. 외부 모듈과 표준 라이브러리는 뺀다.
//
// go list 는 사용자 환경을 물려받지 않게 GOWORK · GOTOOLCHAIN · GOENV · GOFLAGS 를 고정한다 — 작업 공간
// 파일이 다른 모듈을 메인으로 끌어들이지 않고, 툴체인을 내려받지(망 의존) 않는다. 환경 변수 GOFLAGS 를
// -mod=readonly 로 덮고 GOENV=off 로 사용자 go env 파일(GOFLAGS · GOPROXY · GOPRIVATE 등)을 통째로 끊어,
// 다른 플래그가 끼어들지 않고 go.mod 도 고치지 않는다. 빈 GOFLAGS 만으로는 go env 파일의 값이 그대로 산다.
// -e 를 쓰지 않아 import 를 찾지 못하면 오류다.
func buildGraphFiles(moduleRoot string) (modDir string, files []string, err error) {
	goBin, err := execpkg.LookPath("go")
	if err != nil {
		return "", nil, fmt.Errorf("go 를 찾지 못했다: %w", err)
	}
	cmd := execpkg.Command(goBin, "list", "-deps", "-json=Dir,Module,GoFiles,CgoFiles", "./...")
	cmd.Dir = moduleRoot
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOTOOLCHAIN=local", "GOENV=off", "GOFLAGS=-mod=readonly")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		return "", nil, fmt.Errorf("go list 실패: %w\n%s", err, strings.TrimSpace(stderr.String()))
	}

	dec := json.NewDecoder(bytes.NewReader(out))
	for {
		var pkg listedPackage
		err := dec.Decode(&pkg)
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", nil, fmt.Errorf("go list 출력을 해석하지 못했다: %w\n%s", err, strings.TrimSpace(stderr.String()))
		}
		if pkg.Module == nil || !pkg.Module.Main {
			continue
		}
		modDir = pkg.Module.Dir
		for _, name := range slices.Concat(pkg.GoFiles, pkg.CgoFiles) {
			files = append(files, filepath.Join(pkg.Dir, name))
		}
	}
	return modDir, files, nil
}

// listedPackage 는 go list -json 이 패키지마다 내는 객체 가운데 buildGraphFiles 가 읽는 필드다.
type listedPackage struct {
	Dir    string
	Module *struct {
		Main bool
		Dir  string
	}
	GoFiles  []string
	CgoFiles []string
}

// listSymbolUses 는 src 의 식별자 가운데 S3 목록 연산을 가리키는 것(isListOp)을 「파일:줄:열 이름」으로
// 돌려준다. 주석은 훑개가 건너뛰고 문자열은 식별자가 아니어서 세지 않는다.
func listSymbolUses(filename string, src []byte) ([]string, error) {
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
		if tok == token.IDENT && isListOp(lit) {
			uses = append(uses, fmt.Sprintf("%s %s", fset.Position(pos), lit))
		}
	}
}

// listOps 는 S3 목록 연산 식별자의 뿌리다. 다섯이 service/s3 의 목록 연산 열둘과 그 페이지 넘기개 ·
// 입력 · 출력 형 이름을 모두 덮는다 — ListObject 가 ListObjects · ListObjectsV2 · ListObjectVersions ·
// ListObjectAnnotations 를, ListBucket 이 ListBuckets 와 ListBucket…Configurations 넷을 덮고 나머지
// 셋은 제 이름이다.
var listOps = []string{"ListObject", "ListBucket", "ListDirectoryBuckets", "ListMultipartUploads", "ListParts"}

// isListOp 는 식별자 name 이 S3 목록 연산을 가리키는가다 — listOps 가운데 하나를 품는가.
func isListOp(name string) bool {
	return slices.ContainsFunc(listOps, func(op string) bool { return strings.Contains(name, op) })
}

// clientListMethods 는 *s3.Client 의 목록 연산 메서드 이름이다 — SDK 가 List 로 시작하는 이름을 붙인 메서드
// 전부.
func clientListMethods() []string {
	client := reflect.TypeFor[*s3.Client]()
	var names []string
	for i := range client.NumMethod() {
		if name := client.Method(i).Name; strings.HasPrefix(name, "List") {
			names = append(names, name)
		}
	}
	return names
}
