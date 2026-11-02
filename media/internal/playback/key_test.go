package playback

// ③(재생 렌디션) 키 파생의 단위 검증(POK-195 M3 — 설계 5.2).
// 순수 함수라 DB·파일시스템이 없다 — 형상이 계약이므로 리터럴로 못 박는다.

import (
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
func TestSegKeyRejectsStreamIDThatIsNotURLSafe(t *testing.T) {
	for _, streamID := range []string{"", "a/b", "..", "a b", "a?b", "a#b", "한글"} {
		if got, err := SegKey(streamID, 0); err == nil {
			t.Errorf("stream_id=%q 가 통과했다: %q", streamID, got)
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
		gotStream, gotSeq, err := ParseSegKey(key)
		if err != nil || gotStream != tc.streamID || gotSeq != tc.seq {
			t.Errorf("ParseSegKey(%q) = (%q, %d, %v), want (%q, %d, nil)", key, gotStream, gotSeq, err, tc.streamID, tc.seq)
		}
	}
}

// ③ 키 문법 한 벌(계획 B #10 · 〔r37〕 ⑴) — 업로드 워커의 [0] 대상 검증과 목록 발행 전 검사 S5 가 이 함수를 함께 부른다.
// 수락 집합은 워커가 쓰던 정규식(dvr/{스트림}/seg/{십진 6자리 이상}.m4s)과 같다 — 앞자리 0 으로 늘린 seq 도 받는다. 스트림
// 성분만 SegKey 와 같은 검사로 본다(워커는 성분을 대상의 stream_id 와 견주고, 그 값은 이미 [A-Za-z0-9_-]{1,64} 다). seq 가
// int64 를 넘는 키는 풀 수 없어 거부한다 — SegKey 가 만들 수 없는 모양이다.
func TestParseSegKeyAcceptsOnlyTheSegmentKeyGrammar(t *testing.T) {
	for _, tc := range []struct {
		key      string
		streamID string
		seq      int64
	}{
		{"dvr/demo-stream/seg/000123.m4s", "demo-stream", 123},
		{"dvr/s/seg/000000.m4s", "s", 0},
		{"dvr/s/seg/1000000.m4s", "s", 1000000},
		{"dvr/s/seg/0000007.m4s", "s", 7},
		{"dvr/s/seg/9223372036854775807.m4s", "s", 9223372036854775807},
	} {
		gotStream, gotSeq, err := ParseSegKey(tc.key)
		if err != nil || gotStream != tc.streamID || gotSeq != tc.seq {
			t.Errorf("ParseSegKey(%q) = (%q, %d, %v), want (%q, %d, nil)", tc.key, gotStream, gotSeq, err, tc.streamID, tc.seq)
		}
	}
	for _, key := range []string{
		"",
		"dvr/s/seg/00007.m4s",               // 자릿수 다섯
		"dvr/s/seg/000007.mp4",              // 확장자
		"dvr/s/seg/000007.M4S",              // 확장자 대소문자
		"dvr/s/seg/000007.m4s\n",            // 끝 개행
		"dvr/s/seg/000007.m4s\r",            // 끝 CR
		"dvr/s/seg/000007.m4s?x=1",          // 질의
		"dvr/s/seg/+00007.m4s",              // 부호
		"dvr/s/seg/-000007.m4s",             // 음수
		"dvr/s/seg/０００００７.m4s",              // 전각 숫자
		"dvr/s/seg/9223372036854775808.m4s", // int64 를 넘는 seq
		"dvr/../seg/000007.m4s",             // 상위 경로 참조
		"dvr/./seg/000007.m4s",              // 현재 경로 참조
		"dvr//seg/000007.m4s",               // 빈 스트림
		"dvr/a/b/seg/000007.m4s",            // 경로 구분자가 든 스트림
		"dvr/s b/seg/000007.m4s",            // 공백
		"dvr/s\"/seg/000007.m4s",            // 따옴표
		"dvr/s/init/000007.m4s",             // 다른 축(init) 자리
		"streams/s/seg/000007.m4s",          // 다른 접두
		"/dvr/s/seg/000007.m4s",             // 앞 구분자
	} {
		if gotStream, gotSeq, err := ParseSegKey(key); err == nil {
			t.Errorf("ParseSegKey(%q) = (%q, %d, nil), want 오류", key, gotStream, gotSeq)
		}
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

// vodPathRow 는 공유 골든 testdata/vod_path.golden.txt 의 한 줄이다.
type vodPathRow struct {
	line  int    // 파일의 줄 번호(1부터) — 실패 문구가 가리킨다
	input string // ③ 조각 · init 키 후보
	want  string // ok 줄이면 기대 사본 키, err 줄이면 빈 값
	ok    bool
}

// readVODPathGolden 은 공유 골든을 읽는다. 꼴이 틀린 줄은 시험 대상이 아니라 골든의 고장이라 Fatal 이다 —
// clip Java 시험도 같은 파일을 같은 꼴로 읽으므로 꼴을 여기서 못 박는다(파일 머리 주석).
func readVODPathGolden(t *testing.T) []vodPathRow {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "vod_path.golden.txt"))
	if err != nil {
		t.Fatalf("Setup: %v", err)
	}
	var rows []vodPathRow
	var oks, errs int
	for i, line := range strings.Split(strings.TrimSuffix(string(b), "\n"), "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) != 3 {
			t.Fatalf("Setup: vod_path.golden.txt %d행 = %q, want 탭으로 가른 세 열", i+1, line)
		}
		switch {
		case cols[2] == "ok" && cols[1] != "":
			oks++
		case cols[2] == "err" && cols[1] == "":
			errs++
		default:
			t.Fatalf("Setup: vod_path.golden.txt %d행 = %q, want <입력> TAB <사본 키> TAB ok 또는 <입력> TAB TAB err", i+1, line)
		}
		rows = append(rows, vodPathRow{line: i + 1, input: cols[0], want: cols[1], ok: cols[2] == "ok"})
	}
	if oks == 0 || errs == 0 {
		t.Fatalf("Setup: vod_path.golden.txt 에 ok 줄 %d · err 줄 %d, want 둘 다 1 이상", oks, errs)
	}
	return rows
}

// E21(설계 r7 9절): 생산자 키 꼴 · 사본 키 판정 꼴 · 탈퇴 정리(clip Java)의 사본 키 파생이 갈라지지 않게 셋이 같은
// 골든을 읽는다. 이 시험은 Go 쪽 절반이다 — 앞자리 0 으로 늘린 seq · 2^53 · int64 끝은 받고, 그 밖은 오류다.
func TestVODPathGolden(t *testing.T) {
	for _, row := range readVODPathGolden(t) {
		got, err := VODPath(row.input)
		if row.ok && (err != nil || got != row.want) {
			t.Errorf("%d행: VODPath(%q) = (%q, %v), want (%q, nil)", row.line, row.input, got, err, row.want)
		}
		if !row.ok && err == nil {
			t.Errorf("%d행: VODPath(%q) = (%q, nil), want 오류", row.line, row.input, got)
		}
	}
}

// ParseVODPath 는 VODPath 의 역이다(⑤ 대사가 사본 키를 풀 때 쓴다). 골든의 ok 줄마다 기대 사본 키가 입력과 같은
// 스트림 · seq(또는 세션)로 풀린다. err 줄의 dvr/ 꼴 입력은 앞머리만 vod/ 로 바꿔도 여전히 오류다 — 역도 같은 문법
// 한 벌이다. vod/ 로 시작하지 않는 입력은 사본 키가 아니라 오류다.
func TestParseVODPathInvertsGolden(t *testing.T) {
	for _, row := range readVODPathGolden(t) {
		if row.ok {
			got, err := ParseVODPath(row.want)
			if err != nil {
				t.Errorf("%d행: ParseVODPath(%q) = %v, want nil", row.line, row.want, err)
			} else if want := dvrObjectOf(t, row.input); got != want {
				t.Errorf("%d행: ParseVODPath(%q) = %+v, want %+v", row.line, row.want, got, want)
			}
		}
		if rest, isDVR := strings.CutPrefix(row.input, "dvr/"); isDVR && !row.ok {
			if got, err := ParseVODPath("vod/" + rest); err == nil {
				t.Errorf("%d행: ParseVODPath(%q) = (%+v, nil), want 오류(VODPath 가 거절한 꼴)", row.line, "vod/"+rest, got)
			}
		}
		if !strings.HasPrefix(row.input, "vod/") {
			if got, err := ParseVODPath(row.input); err == nil {
				t.Errorf("%d행: ParseVODPath(%q) = (%+v, nil), want 오류(사본 키가 아님)", row.line, row.input, got)
			}
		}
	}
}

// dvrObjectOf 는 골든 ok 줄의 ③ 키가 가리키는 스트림 · seq(또는 세션)다. 조각은 공개된 역 ParseSegKey 로 풀고, init 은
// 성분을 잘라 InitKey 로 다시 만들어 같은지 본다(InitKey 가 만들 수 있는 키여야 init 이다).
func dvrObjectOf(t *testing.T, key string) VODObject {
	t.Helper()
	if streamID, seq, err := ParseSegKey(key); err == nil {
		return VODObject{StreamID: streamID, Seq: seq}
	}
	parts := strings.Split(key, "/")
	if len(parts) != 4 || parts[2] != "init" || !strings.HasSuffix(parts[3], ".mp4") {
		t.Fatalf("Setup: 골든 ok 줄 %q 는 조각 키도 init 키도 아니다", key)
	}
	obj := VODObject{StreamID: parts[1], SessionID: strings.TrimSuffix(parts[3], ".mp4")}
	if rebuilt, err := InitKey(obj.StreamID, obj.SessionID); err != nil || rebuilt != key {
		t.Fatalf("Setup: 골든 ok 줄 %q 를 InitKey 가 만들지 못한다: (%q, %v)", key, rebuilt, err)
	}
	return obj
}

// 왕복: SegKey · InitKey 가 만든 키를 VODPath 로 옮기고 ParseVODPath 로 풀면 만든 값 그대로다.
func TestParseVODPathRoundTripsVODPath(t *testing.T) {
	var keys []string
	var wants []VODObject
	for _, tc := range []struct {
		streamID string
		seq      int64
	}{
		{"demo-stream", 123},
		{"s", 0},
		{"s", 1000000},
		{"a.b", 9223372036854775807},
	} {
		key, err := SegKey(tc.streamID, tc.seq)
		if err != nil {
			t.Fatalf("SegKey(%q, %d) 가 거부됐다: %v", tc.streamID, tc.seq, err)
		}
		keys = append(keys, key)
		wants = append(wants, VODObject{StreamID: tc.streamID, Seq: tc.seq})
	}
	for _, tc := range []struct{ streamID, sessionID string }{
		{"demo-stream", "S-20260902-101530-demo-stream-42"},
		{"s", "a.b"},
	} {
		key, err := InitKey(tc.streamID, tc.sessionID)
		if err != nil {
			t.Fatalf("InitKey(%q, %q) 가 거부됐다: %v", tc.streamID, tc.sessionID, err)
		}
		keys = append(keys, key)
		wants = append(wants, VODObject{StreamID: tc.streamID, SessionID: tc.sessionID})
	}
	for i, key := range keys {
		vod, err := VODPath(key)
		if err != nil {
			t.Errorf("VODPath(%q) = %v, want nil", key, err)
			continue
		}
		got, err := ParseVODPath(vod)
		if err != nil || got != wants[i] {
			t.Errorf("ParseVODPath(VODPath(%q) = %q) = (%+v, %v), want (%+v, nil)", key, vod, got, err, wants[i])
		}
	}
}

// IsInit 은 init 사본과 조각 사본을 가른다 — 조각 seq 0 이 init 으로 읽히면 대사가 세션 축으로 장부를 본다.
func TestVODObjectIsInitOnlyForInitCopies(t *testing.T) {
	for _, tc := range []struct {
		key  string
		want bool
	}{
		{"vod/s/seg/000000.m4s", false},
		{"vod/s/seg/000007.m4s", false},
		{"vod/s/init/S-1.mp4", true},
	} {
		obj, err := ParseVODPath(tc.key)
		if err != nil {
			t.Fatalf("ParseVODPath(%q) = %v, want nil", tc.key, err)
		}
		if got := obj.IsInit(); got != tc.want {
			t.Errorf("ParseVODPath(%q).IsInit() = %v, want %v", tc.key, got, tc.want)
		}
	}
}

// 골든 파일에 실을 수 없는 꼴(개행 · CR · NUL)과 사본 키가 아닌 것(목록 문서 · 퍼센트 인코딩된 채인 보고서 키 · dvr/
// 키)을 양쪽 함수가 거절한다. 보고서 키는 ⑤ 가 URL 디코딩한 뒤에 넘긴다 — 디코딩 전 꼴이 통과하면 해석이 두 벌이 된다.
func TestVODPathAndParseVODPathRejectUnfitKeys(t *testing.T) {
	for _, key := range []string{
		"dvr/s/seg/000007.m4s\n",
		"dvr/s/seg/000007.m4s\r",
		"dvr/s/init/S-1.mp4\n",
		"dvr/s/init/S-1\x00.mp4",
		"dvr/s/S-1/index.m3u8",
	} {
		if got, err := VODPath(key); err == nil {
			t.Errorf("VODPath(%q) = (%q, nil), want 오류", key, got)
		}
	}
	for _, key := range []string{
		"",
		"vod/",
		"vod/s/seg/000007.m4s\n",
		"vod/s/init/S-1.mp4\n",
		"vod/s/S-1/index.m3u8",
		"vod/s/S-1/v2/index.m3u8",
		"vod%2Fs%2Fseg%2F000001.m4s",
		"vod/s%2Fx/seg/000001.m4s",
		"dvr/s/seg/000007.m4s",
		"dvr/s/init/S-1.mp4",
		"/vod/s/seg/000007.m4s",
		"vod/../seg/000007.m4s",
		"vod/s/init/..mp4",
	} {
		if got, err := ParseVODPath(key); err == nil {
			t.Errorf("ParseVODPath(%q) = (%+v, nil), want 오류", key, got)
		}
	}
}
