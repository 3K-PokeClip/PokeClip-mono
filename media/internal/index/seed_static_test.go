package index

// 컷오프 주조의 정적 단언(POK-168 M2 — 설계 9.1 ⒝·⒟·n1d).
// "구조가 곧 단정"을 소스 수준에서 고정한다 — 코드가 바뀌어 구조가 무너지면 여기서 잡힌다.

import (
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"testing"
	"time"
)

func packageSources(t *testing.T) map[string][]byte {
	t.Helper()
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("소스 목록 수집 실패: %v", err)
	}
	out := map[string][]byte{}
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			t.Fatalf("%s 읽기 실패: %v", p, err)
		}
		out[p] = src
	}
	return out
}

// b_txn_boundary — stream_cutoffs INSERT 문자열이 소스 전체에서 정확히 1개이고,
// 그것이 WITH ins AS (INSERT INTO stream_segments …) 를 포함한 한 문장이다.
func TestB1CutoffInsertSingleStatement(t *testing.T) {
	insertRe := regexp.MustCompile(`INSERT INTO stream_cutoffs`)
	count := 0
	var holder string
	for p, src := range packageSources(t) {
		n := len(insertRe.FindAll(src, -1))
		count += n
		if n > 0 {
			holder = p
			s := string(src)
			if !strings.Contains(s, "WITH ins AS (") ||
				!strings.Contains(s, "INSERT INTO stream_segments") {
				t.Errorf("%s 의 stream_cutoffs INSERT 가 세그먼트 INSERT 와 한 문장(CTE)이 아니다", p)
			}
		}
	}
	if count != 1 {
		t.Fatalf("stream_cutoffs INSERT 가 %d곳이다(정확히 1곳 — store.go 의 CTE 뿐이어야 한다), 마지막 발견 = %s", count, holder)
	}
}

// d2_no_seq_argument — cutoff_seq 자리에 바인드 파라미터가 없다: 값은 ins 의 RETURNING(i.seq)
// 에서만 온다. 인자 자리 부재가 과대·과소 기록을 표현 불가능하게 만든다.
func TestD2CutoffSeqHasNoBindArgument(t *testing.T) {
	if !strings.Contains(insertSeedSQL, "SELECT i.stream_id, i.seq, $14, $15") {
		t.Fatal("주조 SELECT 목록이 설계 형상과 다르다 — cutoff_seq 는 i.seq 여야 하고 바인드가 아니어야 한다")
	}
	// seed CTE 절 안에 i.seq 이외의 seq 원천(바인드)이 없어야 한다.
	seedPart := insertSeedSQL[strings.Index(insertSeedSQL, "), seed AS ("):]
	if regexp.MustCompile(`cutoff_seq[^)]*\$`).MatchString(seedPart) {
		t.Fatal("cutoff_seq 가 바인드 파라미터를 받는다 — d2 위반")
	}
}

// d3_no_stray_mutation — stream_cutoffs 에 대한 UPDATE·DELETE 코드 경로가 0곳이다.
// 허용 변경 둘(6.6 만료 동반 삭제 · 장애 절차 수동 CAS) 중 전자는 M6 에서 1곳으로 늘고
// (그때 이 단언을 1로 갱신한다), 후자는 코드가 아니라 운영 절차다.
func TestD3NoStrayCutoffMutation(t *testing.T) {
	mutRe := regexp.MustCompile(`(UPDATE|DELETE FROM)\s+stream_cutoffs`)
	for p, src := range packageSources(t) {
		if m := mutRe.FindAll(src, -1); len(m) != 0 {
			t.Errorf("%s 에 stream_cutoffs 변이 %d곳 — M2 시점 허용 0곳(M6 동반 삭제에서 1곳으로 갱신)", p, len(m))
		}
	}
}

// n1d — Record 의 carrier 3필드가 전부 포인터 타입이다(값 타입 금지 — 5.4.2 구조 보장).
func TestN1DCarrierFieldsArePointers(t *testing.T) {
	rt := reflect.TypeOf(Record{})
	want := map[string]reflect.Type{
		"SessionID":     reflect.TypeOf((*string)(nil)),
		"PlaybackPDT":   reflect.TypeOf((*time.Time)(nil)),
		"PlaybackS3Key": reflect.TypeOf((*string)(nil)),
	}
	for name, wantType := range want {
		f, ok := rt.FieldByName(name)
		if !ok {
			t.Errorf("Record.%s 필드가 없다", name)
			continue
		}
		if f.Type != wantType {
			t.Errorf("Record.%s 가 %v 다 — %v(포인터 carrier)여야 한다", name, f.Type, wantType)
		}
	}
}
