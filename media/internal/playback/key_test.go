package playback

// ③(재생 렌디션) 키 파생의 단위 검증(POK-195 M3 — 설계 5.2).
// 순수 함수라 DB·파일시스템이 없다 — 형상이 계약이므로 리터럴로 못 박는다.

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 설계 5.2 의 조각 키 형상 그대로다: dvr/{streamID}/seg/{NNNNNN}.m4s.
// 세션 축이 없는 것이 결정 자체다(계약 ⓑ "s3_key 에서 결정적 파생" 준수).
func TestSegKeyMatchesContractShape(t *testing.T) {
	got, err := SegKey("demo-stream", 123)
	if err != nil {
		t.Fatalf("정상 입력이 거부됐다: %v", err)
	}
	if want := "dvr/demo-stream/seg/000123.m4s"; got != want {
		t.Fatalf("SegKey = %q, want %q", got, want)
	}
}

// 자릿수는 index.S3Key 와 같은 %06d 다 — 사전식 정렬이 seq 순서와 같아진다.
// 100만을 넘으면 자릿수가 늘어 정렬은 깨지지만 유일성은 유지된다(S3Key 주석의 전례).
func TestSegKeyPadsToSixDigitsAndGrowsBeyond(t *testing.T) {
	for _, tc := range []struct {
		seq  int64
		want string
	}{
		{0, "dvr/s/seg/000000.m4s"},
		{999999, "dvr/s/seg/999999.m4s"},
		{1000000, "dvr/s/seg/1000000.m4s"},
	} {
		got, err := SegKey("s", tc.seq)
		if err != nil {
			t.Fatalf("seq=%d 가 거부됐다: %v", tc.seq, err)
		}
		if got != tc.want {
			t.Errorf("SegKey(seq=%d) = %q, want %q", tc.seq, got, tc.want)
		}
	}
}

// 키는 되감기 URL 경로에 그대로 실린다 — 경로 구분자·상위 참조·공백이 들어오면
// 다른 객체를 가리키거나 URL 이 깨진다. 파생 실패는 오류로 알리고 호출자가 NULL 로 남긴다.
// 성분 128자 상한(엣지 정규식과 같다 — POK-525 설계 3-1)은 모든 성분에 같이 걸린다.
func TestSegKeyRejectsStreamIDThatIsNotURLSafe(t *testing.T) {
	for _, streamID := range []string{"", "a/b", "..", "a b", "a?b", "a#b", "한글", strings.Repeat("s", 129)} {
		if got, err := SegKey(streamID, 0); err == nil {
			t.Errorf("stream_id=%.40q 가 통과했다: %q", streamID, got)
		}
	}
}

// 음수 seq 는 장부에 존재할 수 없다(seq 는 0 부터 단조). 자릿수 채움이 부호를 먹어
// 조용히 이상한 키가 되는 것을 막는다.
func TestSegKeyRejectsNegativeSeq(t *testing.T) {
	if got, err := SegKey("s", -1); err == nil {
		t.Fatalf("음수 seq 가 통과했다: %q", got)
	}
}

// ParseSegKey 는 SegKey 의 역이다(계획 B #10) — SegKey 가 만든 키는 만든 값 그대로 풀린다(왕복). 스트림 성분은 SegKey 와
// 같은 검사(checkComponent)를 받으므로 SegKey 가 받는 점 든 성분도 풀린다.
func TestParseSegKeyInvertsSegKey(t *testing.T) {
	for _, tc := range []struct {
		streamID string
		seq      int64
	}{
		{"demo-stream", 123},
		{"s", 0},
		{"s", 999999},
		{"s", 1000000},
		{"str_7a", 1297},
		{"a.b", 7},
	} {
		key, err := SegKey(tc.streamID, tc.seq)
		if err != nil {
			t.Fatalf("SegKey(%q, %d) 가 거부됐다: %v", tc.streamID, tc.seq, err)
		}
		want := SegRef{StreamID: tc.streamID, Seq: tc.seq}
		if got, err := ParseSegKey(key); err != nil || got != want {
			t.Errorf("ParseSegKey(%q) = (%+v, %v), want (%+v, nil)", key, got, err, want)
		}
	}
}

// 회차 꼴도 같다 — RoundSegKey 가 만든 키는 만든 세 값 그대로 풀린다(왕복). 회차 성분 128자는 상한 안이다.
func TestParseSegKeyInvertsRoundSegKey(t *testing.T) {
	for _, tc := range []SegRef{
		{StreamID: "demo-stream", RoundID: "S-20261103-101530-demo-stream-42", Seq: 123},
		{StreamID: "s", RoundID: "r", Seq: 0},
		{StreamID: "s", RoundID: "r", Seq: 1000000},
		{StreamID: "a.b", RoundID: "c.d", Seq: 7},
		{StreamID: "s", RoundID: "seg", Seq: 7},
		{StreamID: "s", RoundID: strings.Repeat("r", 128), Seq: 7},
	} {
		key, err := RoundSegKey(tc.StreamID, tc.RoundID, tc.Seq)
		if err != nil {
			t.Fatalf("RoundSegKey(%q, %q, %d) 가 거부됐다: %v", tc.StreamID, tc.RoundID, tc.Seq, err)
		}
		if got, err := ParseSegKey(key); err != nil || got != tc {
			t.Errorf("ParseSegKey(%q) = (%+v, %v), want (%+v, nil)", key, got, err, tc)
		}
	}
}

// ③ 키 문법 한 벌(계획 B #10 · 〔r37〕 ⑴) — 업로드 워커의 [0] 대상 검증과 목록 발행 전 검사 S5 가 이 함수를 함께 부른다.
// 수락 집합은 두 꼴이다(POK-525 설계 2-1) — 옛 꼴 dvr/{스트림}/seg/{십진 6자리 이상}.m4s(워커가 쓰던 정규식과 같다)와
// 회차 꼴 dvr/{스트림}/{회차}/seg/{십진 6자리 이상}.m4s. 앞자리 0 으로 늘린 seq 도 받는다. 성분은 SegKey 와 같은 검사로
// 본다(워커는 스트림 성분을 대상의 stream_id 와 견주고, 그 값은 이미 [A-Za-z0-9_-]{1,64} 다). seq 가 int64 를 넘는 키는
// 풀 수 없어 거부한다 — SegKey 가 만들 수 없는 모양이다. 줄 문법이 실을 수 없는 글자(개행 · CR · NUL · 빈 키)는 여기서
// 보고, 나머지 경계는 공유 골든(TestParseSegKeyMatchesSharedGolden)이 본다.
func TestParseSegKeyAcceptsOnlyTheSegmentKeyGrammar(t *testing.T) {
	for _, tc := range []struct {
		key  string
		want SegRef
	}{
		{"dvr/demo-stream/seg/000123.m4s", SegRef{StreamID: "demo-stream", Seq: 123}},
		{"dvr/s/seg/000000.m4s", SegRef{StreamID: "s", Seq: 0}},
		{"dvr/s/seg/1000000.m4s", SegRef{StreamID: "s", Seq: 1000000}},
		{"dvr/s/seg/0000007.m4s", SegRef{StreamID: "s", Seq: 7}},
		{"dvr/s/seg/9223372036854775807.m4s", SegRef{StreamID: "s", Seq: 9223372036854775807}},
		{"dvr/a/b/seg/000007.m4s", SegRef{StreamID: "a", RoundID: "b", Seq: 7}},
		{"dvr/s/r/seg/0000007.m4s", SegRef{StreamID: "s", RoundID: "r", Seq: 7}},
	} {
		if got, err := ParseSegKey(tc.key); err != nil || got != tc.want {
			t.Errorf("ParseSegKey(%q) = (%+v, %v), want (%+v, nil)", tc.key, got, err, tc.want)
		}
	}
	for _, key := range []string{
		"",
		"dvr/s/seg/00007.m4s",               // 자릿수 다섯
		"dvr/s/seg/000007.mp4",              // 확장자
		"dvr/s/seg/000007.M4S",              // 확장자 대소문자
		"dvr/s/seg/000007.m4s\n",            // 끝 개행
		"dvr/s/seg/000007.m4s\r",            // 끝 CR
		"dvr/s/r/seg/000007.m4s\n",          // 회차 꼴 끝 개행
		"dvr/s/r\x00/seg/000007.m4s",        // 회차 성분의 NUL
		"dvr/s/seg/000007.m4s?x=1",          // 질의
		"dvr/s/seg/+00007.m4s",              // 부호
		"dvr/s/seg/-000007.m4s",             // 음수
		"dvr/s/seg/０００００７.m4s",              // 전각 숫자
		"dvr/s/seg/9223372036854775808.m4s", // int64 를 넘는 seq
		"dvr/../seg/000007.m4s",             // 상위 경로 참조
		"dvr/./seg/000007.m4s",              // 현재 경로 참조
		"dvr//seg/000007.m4s",               // 빈 스트림
		"dvr/a/b/c/seg/000007.m4s",          // 성분 여섯
		"dvr/s b/seg/000007.m4s",            // 공백
		"dvr/s\"/seg/000007.m4s",            // 따옴표
		"dvr/s/init/000007.m4s",             // 다른 축(init) 자리
		"streams/s/seg/000007.m4s",          // 다른 접두
		"/dvr/s/seg/000007.m4s",             // 앞 구분자
	} {
		if got, err := ParseSegKey(key); err == nil {
			t.Errorf("ParseSegKey(%q) = (%+v, nil), want 오류", key, got)
		}
	}
}

// 공유 골든 testdata/seg_key.golden.txt — Go 의 ParseSegKey 와 엣지 함수(dvr-floor.js) 시험이 같은 파일을 읽는다(설계
// 3-1 · 4-2 · E6 · E22). 줄 문법: `#` 로 시작하는 줄과 빈 줄은 건너뛰고, 나머지 줄은 탭 셋으로 나뉜 세 열이다 — 입력 키 ·
// 기대(ok 면 `스트림,회차,seq` · 옛 꼴은 회차가 빈 값 · err 면 `-`) · 판정(ok | err). 잡는 결함: 회차 꼴 갈래 · 옛 꼴 갈래 ·
// 성분 128자 상한 가운데 하나가 빠지거나 넓어지면 그 행이 갈린다.
func TestParseSegKeyMatchesSharedGolden(t *testing.T) {
	rows := readSegKeyGolden(t)
	if len(rows) == 0 {
		t.Fatal("골든에 행이 없다")
	}
	for _, r := range rows {
		got, err := ParseSegKey(r.input)
		switch r.verdict {
		case "ok":
			if err != nil {
				t.Errorf("줄 %d: ParseSegKey(%.40q…) = 오류 %v, want ok %s", r.line, r.input, err, r.want)
				continue
			}
			if s := fmt.Sprintf("%s,%s,%d", got.StreamID, got.RoundID, got.Seq); s != r.want {
				t.Errorf("줄 %d: ParseSegKey(%.40q…) = %s, want %s", r.line, r.input, s, r.want)
			}
		case "err":
			if err == nil {
				t.Errorf("줄 %d: ParseSegKey(%.40q…) = %+v, want err", r.line, r.input, got)
			}
		}
	}
}

// segKeyGoldenRow 는 공유 골든의 한 행이다.
type segKeyGoldenRow struct {
	line                 int
	input, want, verdict string
}

// readSegKeyGolden 은 공유 골든을 읽는다. 줄 문법을 어긴 행(열 수 · 판정 값 · err 행의 기대 값)은 시험 실패다 — 엣지
// 함수 시험이 같은 파일을 같은 문법으로 읽으므로 여기서 문법을 못 박는다.
func readSegKeyGolden(t *testing.T) []segKeyGoldenRow {
	t.Helper()
	f, err := os.Open(filepath.Join("testdata", "seg_key.golden.txt"))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	defer f.Close()
	var rows []segKeyGoldenRow
	sc := bufio.NewScanner(f)
	for n := 1; sc.Scan(); n++ {
		line := sc.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) != 3 || cols[0] == "" {
			t.Fatalf("골든 줄 %d: 열 %d개 — 비지 않은 입력 · 기대 · 판정 세 열이어야 한다", n, len(cols))
		}
		r := segKeyGoldenRow{line: n, input: cols[0], want: cols[1], verdict: cols[2]}
		if (r.verdict != "ok" && r.verdict != "err") || (r.verdict == "err") != (r.want == "-") {
			t.Fatalf("골든 줄 %d: 기대 %q · 판정 %q — ok 는 기대 값과, err 는 기대 `-` 와 짝이어야 한다", n, r.want, r.verdict)
		}
		rows = append(rows, r)
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("Setup: %v", err)
	}
	return rows
}

// 설계 2-1 의 회차 꼴 형상 그대로다: dvr/{streamID}/{roundID}/seg/{NNNNNN}.m4s. 자릿수 규칙은 SegKey 와 같다.
func TestRoundSegKeyMatchesContractShape(t *testing.T) {
	got, err := RoundSegKey("demo-stream", "S-20261103-101530-demo-stream-42", 123)
	if err != nil {
		t.Fatalf("정상 입력이 거부됐다: %v", err)
	}
	if want := "dvr/demo-stream/S-20261103-101530-demo-stream-42/seg/000123.m4s"; got != want {
		t.Fatalf("RoundSegKey = %q, want %q", got, want)
	}
}

// 회차 성분도 URL 경로에 그대로 실린다 — SegKey 의 스트림 성분과 같은 검사를 받고, 엣지 정규식과 같은 128자 상한을
// 넘으면 거부한다(설계 3-1 · E22). 빈 회차도 거부한다 — 회차가 없으면 호출자가 옛 꼴(SegKey)을 고른다. 두 성분을 각각
// 본다: 한쪽만 검사하면 다른 쪽이 경로를 벗어난다.
func TestRoundSegKeyRejectsComponentThatIsNotURLSafe(t *testing.T) {
	for _, roundID := range []string{"", "a/b", ".", "..", "a b", "한글", strings.Repeat("r", 129)} {
		if got, err := RoundSegKey("s", roundID, 0); err == nil {
			t.Errorf("round_id=%.40q 가 통과했다: %q", roundID, got)
		}
	}
	if got, err := RoundSegKey("a/b", "r", 0); err == nil {
		t.Errorf("stream_id 가 경로를 벗어났는데 통과했다: %q", got)
	}
	if got, err := RoundSegKey("s", "r", -1); err == nil {
		t.Errorf("음수 seq 가 통과했다: %q", got)
	}
}

// 설계 5.2·5.3ⓐ: init 은 세션 축이다 — dvr/{streamID}/init/{sessionID}.mp4.
func TestInitKeyMatchesContractShape(t *testing.T) {
	got, err := InitKey("demo-stream", "S-20260902-101530-demo-stream-42")
	if err != nil {
		t.Fatalf("정상 입력이 거부됐다: %v", err)
	}
	if want := "dvr/demo-stream/init/S-20260902-101530-demo-stream-42.mp4"; got != want {
		t.Fatalf("InitKey = %q, want %q", got, want)
	}
}

// session_id 는 세션 결정자가 만든 값이지만 여기서 다시 검사한다 —
// 규칙이 바뀌어도(회부 ①) URL 에 실을 수 없는 값이 키가 되지 않게 하는 마지막 그물이다.
// 두 성분을 **각각** 본다: 한쪽만 검사하면 다른 쪽이 경로를 벗어난다.
func TestInitKeyRejectsComponentThatIsNotURLSafe(t *testing.T) {
	for _, sessionID := range []string{"", "a/b", "..", "a b"} {
		if got, err := InitKey("s", sessionID); err == nil {
			t.Errorf("session_id=%q 가 통과했다: %q", sessionID, got)
		}
	}
	if got, err := InitKey("a/b", "S-1"); err == nil {
		t.Errorf("stream_id 가 경로를 벗어났는데 통과했다: %q", got)
	}
}
