package publish

// S3 LIST 금지(프로필 4절)의 정적 단언 — 계획 6.5 ⑴. media 모듈의 소스에 S3 목록 연산을 가리키는 식별자가
// 0건이다. 동적 그물은 가짜의 목록 함정이다(TestFakeStoreFailsTestOnListCalls).

import (
	"fmt"
	"go/scanner"
	"go/token"
	"io/fs"
	"os"
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
