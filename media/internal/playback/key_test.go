package playback

// ③(재생 렌디션) 키 파생의 단위 검증(POK-195 M3 — 설계 5.2).
// 순수 함수라 DB·파일시스템이 없다 — 형상이 계약이므로 리터럴로 못 박는다.

import "testing"

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
