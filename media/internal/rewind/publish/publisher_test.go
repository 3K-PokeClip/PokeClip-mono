package publish

// 발행자의 순수 부품 — 목록 키 생성기 · 메타 8키 · 설정 · 로그 1회 가드 · 차선. PG 없이 돈다(계획 4.5 B-1
// 「테스트 층」 — 순수 함수는 PG 없이).

import (
	"context"
	"crypto/sha256"
	"fmt"
	"maps"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// 목록 객체 키(커밋 2 리뷰 인계 · 보안 확인 S-3) — 성분을 검증하는 생성기다. Store 는 키를 검증하지 않고 SDK 는
// 점 구간(`..`)을 정규화하지 않고 보내므로, 성분에 경로 구분자나 상위 참조가 들어오면 다른 객체를 가리킨다.
// 규칙은 playback 의 조각 · init 키 성분 검사(`playback/key.go:25-33`)와 같다.
func TestManifestKeyValidatesComponents(t *testing.T) {
	if got, err := manifestKey("str", "S-20260831-0152"); err != nil || got != "dvr/str/S-20260831-0152/index.m3u8" {
		t.Errorf(`manifestKey("str", "S-20260831-0152") = (%q, %v), want ("dvr/str/S-20260831-0152/index.m3u8", nil)`, got, err)
	}
	bad := []string{"", ".", "..", "a/b", "../x", "a b", "a?b", "a#b", "a%2Fb", "스트림"}
	for _, v := range bad {
		if got, err := manifestKey(v, "S"); err == nil {
			t.Errorf("manifestKey(%q, \"S\") = %q, want 오류(stream 성분 거부)", v, got)
		}
		if got, err := manifestKey("str", v); err == nil {
			t.Errorf("manifestKey(\"str\", %q) = %q, want 오류(session 성분 거부)", v, got)
		}
	}
}

// sampleDesc 는 메타 표의 기준 자기기술이다 — 1시간 목록 한 판(900조각)의 값을 손으로 적었다.
func sampleDesc() rewind.Published {
	return rewind.Published{
		Gen:                   1187,
		MediaSequence:         398,
		PublishedSeq:          1297,
		SegmentCount:          900,
		DiscontinuitySequence: 3,
		BodySHA256:            sha256.Sum256([]byte("body")),
	}
}

// 메타 8키(계획 4.5 A1 결정 5) — P3 가 싣는 모양 그대로다. 값은 십진 정수 · 소문자 true/false · 소문자 16진
// 64자다. 넣는 값은 발행 층이 계산한 값뿐이다(비밀 · 개인정보 없음 — 보안 c2: M5 에서 CDN 시청자에게 보일 수
// 있다).
func TestEncodeMetaWritesEightKeys(t *testing.T) {
	got := encodeMeta(sampleDesc(), "S-1")
	want := map[string]string{
		"pc-gen":         "1187",
		"pc-pub-seq":     "1297",
		"pc-session":     "S-1",
		"pc-terminal":    "false",
		"pc-msn":         "398",
		"pc-seg-count":   "900",
		"pc-disc-seq":    "3",
		"pc-body-sha256": "230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5",
	}
	if !maps.Equal(got, want) {
		t.Errorf("encodeMeta = %v, want %v", got, want)
	}
}

// Head 응답(메타 8키 · ETag)은 믿을 수 없는 입력이다(커밋 2 리뷰 인계 · A1 결정 5 · 보안 r4 M-1). 여덟 키가 모두
// 있고 각각 정해진 모양일 때만 자기기술로 읽는다 — 하나라도 없거나 해석에 실패하면 오류다(발행 정지 ·
// meta_invalid). 모양은 P3 가 쓴 것 하나뿐이다: 부호 · 앞자리 0 · 대문자 16진처럼 뜻은 같아도 우리가 쓰지 않은
// 표기는 받지 않는다. pc-session 은 목록 URL 의 회차와 같아야 한다 — 다른 회차의 목록 판을 자기 것으로 읽지 않는다.
// 정수는 JavaScript 안전 정수 한계(2^53−1 = 9007199254740991) 안이어야 하고, 조각 수는 마지막 seq − MSN + 1 이어야
// 한다(렌더는 seq 를 1씩 잇는다 — 우리가 쓴 판은 늘 맞는다). ETag 는 1–1024바이트의 인쇄 가능 ASCII(0x21–0x7E)다.
func TestParseMetaIsStrict(t *testing.T) {
	good := encodeMeta(sampleDesc(), "S-1")
	if got, err := parseMeta(good, "S-1"); err != nil || got != sampleDesc() {
		t.Fatalf("parseMeta(encodeMeta(…)) = (%+v, %v), want (%+v, nil)", got, err, sampleDesc())
	}
	with := func(kv ...string) map[string]string {
		m := maps.Clone(good)
		for i := 0; i < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return m
	}
	without := func(key string) map[string]string {
		m := maps.Clone(good)
		delete(m, key)
		return m
	}
	cases := []struct {
		name string
		meta map[string]string
	}{
		{"pc-gen_없음", without("pc-gen")},
		{"pc-pub-seq_없음", without("pc-pub-seq")},
		{"pc-session_없음", without("pc-session")},
		{"pc-terminal_없음", without("pc-terminal")},
		{"pc-msn_없음", without("pc-msn")},
		{"pc-seg-count_없음", without("pc-seg-count")},
		{"pc-disc-seq_없음", without("pc-disc-seq")},
		{"pc-body-sha256_없음", without("pc-body-sha256")},
		{"pc-gen_숫자_아님", with("pc-gen", "x")},
		{"pc-gen_부호", with("pc-gen", "+1187")},
		{"pc-gen_앞자리_0", with("pc-gen", "01187")},
		{"pc-gen_0", with("pc-gen", "0")},
		{"pc-gen_범위_밖", with("pc-gen", "99999999999999999999")},
		{"pc-pub-seq_음수", with("pc-pub-seq", "-1")},
		{"pc-pub-seq_공백", with("pc-pub-seq", " 1297")},
		{"pc-msn_음수", with("pc-msn", "-1")},
		{"pc-seg-count_0", with("pc-seg-count", "0")},
		{"pc-disc-seq_음수", with("pc-disc-seq", "-3")},
		{"pc-terminal_대문자", with("pc-terminal", "FALSE")},
		{"pc-terminal_숫자", with("pc-terminal", "0")},
		{"pc-body-sha256_짧음", with("pc-body-sha256", strings.Repeat("a", 63))},
		{"pc-body-sha256_대문자", with("pc-body-sha256", strings.ToUpper(good["pc-body-sha256"]))},
		{"pc-body-sha256_16진_아님", with("pc-body-sha256", strings.Repeat("g", 64))},
		{"pc-session_다른_회차", with("pc-session", "S-2")},
		{"pc-gen_int64_최대", with("pc-gen", "9223372036854775807")},
		{"pc-gen_2^53", with("pc-gen", "9007199254740992")},
		{"pc-disc-seq_2^53", with("pc-disc-seq", "9007199254740992")},
		{"pc-pub-seq_2^53_조각_수는_맞음", with("pc-msn", "9007199254740093", "pc-pub-seq", "9007199254740992")},
		{"pc-seg-count_2^53_조각_수는_맞음", with("pc-msn", "0", "pc-pub-seq", "9007199254740991", "pc-seg-count", "9007199254740992")},
		{"pc-seg-count_불일치", with("pc-seg-count", "899")},
		{"pc-seg-count_범위보다_큼", with("pc-seg-count", "901")},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got, err := parseMeta(tc.meta, "S-1"); err == nil {
				t.Errorf("parseMeta(%v) = %+v, want 오류", tc.meta, got)
			}
		})
	}

	// 경계 안쪽 끝값은 받는다(2^53−1).
	edge := func(edit func(d *rewind.Published)) rewind.Published {
		d := sampleDesc()
		edit(&d)
		return d
	}
	accepted := []struct {
		name string
		meta map[string]string
		want rewind.Published
	}{
		{"pc-gen_2^53−1", with("pc-gen", "9007199254740991"), edge(func(d *rewind.Published) { d.Gen = 9007199254740991 })},
		{"pc-disc-seq_2^53−1", with("pc-disc-seq", "9007199254740991"),
			edge(func(d *rewind.Published) { d.DiscontinuitySequence = 9007199254740991 })},
		{"pc-pub-seq_2^53−1", with("pc-msn", "9007199254740092", "pc-pub-seq", "9007199254740991"),
			edge(func(d *rewind.Published) { d.MediaSequence, d.PublishedSeq = 9007199254740092, 9007199254740991 })},
	}
	for _, tc := range accepted {
		t.Run(tc.name, func(t *testing.T) {
			if got, err := parseMeta(tc.meta, "S-1"); err != nil || got != tc.want {
				t.Errorf("parseMeta(%v) = (%+v, %v), want (%+v, nil)", tc.meta, got, err, tc.want)
			}
		})
	}

	etags := []struct {
		etag string
		ok   bool
	}{
		{bodyETag([]byte("body")), true}, // S3 단일 PUT 모양
		{`W/"weak"`, true},
		{`"` + strings.Repeat("a", 1022) + `"`, true}, // 1024바이트
		{`"` + strings.Repeat("a", 1023) + `"`, false},
		{"", false},
		{"\"ab\ncd\"", false},
		{"\"ab\x00cd\"", false},
		{`"ab cd"`, false},
		{"\"ab\x7fcd\"", false},
		{`"é"`, false},
	}
	for _, tc := range etags {
		if err := checkETag(tc.etag); (err == nil) != tc.ok {
			t.Errorf("checkETag(%.40q …%d바이트) = %v, want 받음 %v", tc.etag, len(tc.etag), err, tc.ok)
		}
	}
}

// 쓰는 쪽 대칭 검사(보안 r5 M1) — 올릴 판의 메타 정수 다섯(세대 · 마지막 seq · MSN · 조각 수 · DISC-SEQ)이 읽는 쪽
// 위끝(2^53−1 — parseMeta)을 넘으면 거부하고, 넘은 키와 값을 오류에 적는다. 끝값은 받는다 — 읽는 쪽이 받는 값이다.
func TestCheckMetaRangeMatchesReaderBound(t *testing.T) {
	cases := []struct {
		name    string
		edit    func(d *rewind.Published)
		wantErr string // 오류에 든 말 — 빈 값이면 받는다
	}{
		{"기준값", func(*rewind.Published) {}, ""},
		{"세대_2^53−1", func(d *rewind.Published) { d.Gen = 9007199254740991 }, ""},
		{"세대_2^53", func(d *rewind.Published) { d.Gen = 9007199254740992 }, "pc-gen=9007199254740992"},
		{"마지막_seq_2^53−1", func(d *rewind.Published) { d.PublishedSeq = 9007199254740991 }, ""},
		{"마지막_seq_2^53", func(d *rewind.Published) { d.PublishedSeq = 9007199254740992 }, "pc-pub-seq=9007199254740992"},
		{"MSN_2^53−1", func(d *rewind.Published) { d.MediaSequence = 9007199254740991 }, ""},
		{"MSN_2^53", func(d *rewind.Published) { d.MediaSequence = 9007199254740992 }, "pc-msn=9007199254740992"},
		{"조각_수_2^53−1", func(d *rewind.Published) { d.SegmentCount = 9007199254740991 }, ""},
		{"조각_수_2^53", func(d *rewind.Published) { d.SegmentCount = 9007199254740992 }, "pc-seg-count=9007199254740992"},
		{"DISC-SEQ_2^53−1", func(d *rewind.Published) { d.DiscontinuitySequence = 9007199254740991 }, ""},
		{"DISC-SEQ_2^53", func(d *rewind.Published) { d.DiscontinuitySequence = 9007199254740992 }, "pc-disc-seq=9007199254740992"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := sampleDesc()
			tc.edit(&d)
			err := checkMetaRange(d, soloFixture(1297).window(398, 1297)) // sampleDesc 의 목록 — 계승이 없어 끊김 표시가 없다
			if (err == nil) != (tc.wantErr == "") || (err != nil && !strings.Contains(err.Error(), tc.wantErr)) {
				t.Errorf("checkMetaRange(%+v) = %v, want %q(빈 값이면 nil)", d, err, tc.wantErr)
			}
		})
	}
}

// 끝 조각의 DSN(DISC-SEQ + 목록 안 끊김 표시 수 — RFC 8216bis-22 6.2.1)도 읽는 쪽 위끝 안이어야 한다(보안 r6 M1) — 머리 값이
// 모두 위끝 안이어도 목록 안 표시가 끝 조각 DSN 을 위끝 밖으로 밀면 거부한다. 표시는 끝 행까지 센다. 표시를 셀 수 없는
// 목록(회차 목록에 없는 회차의 행)은 위끝 안임을 보이지 못하므로 거부한다. DISC-SEQ 자체가 위끝 밖이면 표시를 더하기 전에
// 머리 값 검사가 막는다 — int64 끝에 표시를 더하면 넘쳐 음수가 되어 위끝 안으로 읽힌다.
func TestCheckMetaRangeCoversLastSegmentDSN(t *testing.T) {
	chain := chainFixture(700) // 끊김 표시는 seq 100(P 의 첫 조각) · 600(S 의 첫 조각)
	unknownSession := chain.window(100, 610)
	unknownSession.Sessions = unknownSession.Sessions[1:] // P 가 빠져 seq 100–599 의 회차를 모른다
	cases := []struct {
		name    string
		pl      rewind.Playlist
		disc    int64  // 올릴 판의 DISC-SEQ
		wantErr string // 오류에 든 말 — 빈 값이면 받는다
	}{
		{"표시_없음_DSN_2^53−1", soloFixture(10).window(0, 5), 9007199254740991, ""},
		{"표시_둘_DSN_2^53−1", chain.window(100, 610), 9007199254740989, ""},
		{"표시_둘_DSN_2^53", chain.window(100, 610), 9007199254740990, "끊김 표시 2 = 9007199254740992"},
		{"끝_행의_표시_DSN_2^53", chain.window(100, 600), 9007199254740990, "끊김 표시 2 = 9007199254740992"},
		{"표시를_셀_수_없음", unknownSession, 5, "끊김 표시를 셀 수 없다"},
		{"DISC-SEQ_int64_끝_표시_하나", chain.window(101, 610), math.MaxInt64, "pc-disc-seq=9223372036854775807"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rows := tc.pl.Rows
			d := rewind.Published{Gen: 1, MediaSequence: rows[0].Seq, PublishedSeq: rows[len(rows)-1].Seq,
				SegmentCount: len(rows), DiscontinuitySequence: tc.disc}
			err := checkMetaRange(d, tc.pl)
			if (err == nil) != (tc.wantErr == "") || (err != nil && !strings.Contains(err.Error(), tc.wantErr)) {
				t.Errorf("checkMetaRange(DISC-SEQ %d · 목록 [%d, %d]) = %v, want %q(빈 값이면 nil)",
					tc.disc, d.MediaSequence, d.PublishedSeq, err, tc.wantErr)
			}
		})
	}
}

// 오류 문자열에 싣는 외부 값(Head 메타 · ETag)은 잘라서 싣는다(보안 r4 M-1) — 비정상 엔드포인트가 수 MB 값을 줘도
// 중단 · 포기 로그 한 줄(err 속성)이 그만큼 커지지 않는다.
func TestErrorsTruncateExternalValues(t *testing.T) {
	huge := strings.Repeat("9", 1<<20)
	good := encodeMeta(sampleDesc(), "S-1")
	metaErr := func(key string) error {
		m := maps.Clone(good)
		m[key] = huge
		_, err := parseMeta(m, "S-1")
		return err
	}
	cases := []struct {
		name string
		err  error
	}{
		{"pc-gen", metaErr(metaGen)},
		{"pc-terminal", metaErr(metaTerminal)},
		{"pc-body-sha256", metaErr(metaBodySHA256)},
		{"pc-session", metaErr(metaSession)},
		{"ETag", checkETag(`"` + strings.Repeat("a", 1000) + "\n\"")},
	}
	for _, tc := range cases {
		if tc.err == nil || len(tc.err.Error()) > 512 {
			t.Errorf("%s: 오류 %d바이트(%v), want 오류 · 512바이트 이하", tc.name, len(fmt.Sprint(tc.err)), tc.err != nil)
		}
	}
}

// 중단 · 포기 로그의 err 속성은 앞 1024바이트만 싣고 잘렸다고 적는다(보안 r5 L1) — 저장소 오류 문자열에는 외부 값
// (x-amz-request-id · x-amz-id-2 헤더 · 오류 본문 <Message>)이 길이 제한 없이 실린다. 비정상 엔드포인트가 헤더를
// 256KiB 로 줘도 head_failed 한 줄이 그만큼 커지지 않고, 오류의 앞머리(무엇이 실패했나)와 원래 길이는 남는다. 화해가
// 예약된 상태의 틱이라 R1 Head 가 DB 문장보다 먼저다 — PG 없이 돈다.
func TestAbortLogClipsLongStoreError(t *testing.T) {
	store, _ := newStubbedStore(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("x-amz-request-id", strings.Repeat("R", 256<<10))
		w.WriteHeader(http.StatusForbidden)
	})
	logs := &logRecorder{}
	pub := newPublisher(t, unreachablePool(t), store, "w-me", logs)

	pub.Tick(t.Context(), State{FenceHeld: true, ReconcileDue: true}, TickInput{Playlist: soloFixture(10).window(0, 5)})

	recs := logs.abortRecs()
	if len(recs) != 1 || recs[0].attrs["reason"] != reasonHeadFailed {
		t.Fatalf("중단 · 포기 로그 %v, want [%s]", logs.aborts(), reasonHeadFailed)
	}
	got := recs[0].attrs["err"]
	head, tail, clipped := strings.Cut(got, "…(잘림 — 원래 ")
	n, err := strconv.Atoi(strings.TrimSuffix(tail, "바이트)"))
	if !clipped || len(head) > 1024 || !strings.HasPrefix(head, `publish: HEAD key="dvr/str/S/index.m3u8": `) || err != nil || n <= 256<<10 {
		t.Errorf("err 속성 %d바이트(앞 %.60q · 뒤 %q), want 오류 앞머리 1024바이트 이하 + 잘림 표시(원래 길이)",
			len(got), got, got[max(0, len(got)-48):])
	}
}

// clipErr 는 1024바이트를 넘는 값만 자른다 — 앞 1024바이트(UTF-8 글자 가운데서는 자르지 않는다)에 잘림 표시(원래
// 길이)를 붙인다. 글자 시작 바이트가 하나도 없는 값(헤더의 obs-text 0x80–0xFF 로 채운 외부 값)도 패닉 없이 자른다.
func TestClipErr(t *testing.T) {
	a1023, a1024 := strings.Repeat("a", 1023), strings.Repeat("a", 1024)
	cases := []struct {
		name, in, want string
	}{
		{"빈_값", "", ""},
		{"1024바이트", a1024, a1024},
		{"1025바이트", a1024 + "b", a1024 + "…(잘림 — 원래 1025바이트)"},
		{"1024째_바이트가_글자_가운데", a1023 + "가나", a1023 + "…(잘림 — 원래 1029바이트)"},
		{"글자_시작_바이트_없음", strings.Repeat("\x80", 1100), "…(잘림 — 원래 1100바이트)"},
	}
	for _, tc := range cases {
		if got := clipErr(tc.in); got != tc.want {
			t.Errorf("%s: clipErr(%d바이트) = %d바이트 %.40q…, want %d바이트", tc.name, len(tc.in), len(got), got, len(tc.want))
		}
	}
}

// err 속성은 몇 번째에 있든 자르고 다른 속성은 그대로 싣는다 — 모든 사유가 note 한 자리를 지난다(검사 위반은 check
// 속성 뒤에 err 를 싣는다).
func TestAbortLogClipsErrAttrInAnyPosition(t *testing.T) {
	logs := &logRecorder{}
	tk := &tick{p: newPublisher(t, unreachablePool(t), newFakeStore(t), "w-me", logs), stream: fxStream, session: "S"}

	tk.halt(t.Context(), reasonS4AfterPublish, "check", "S4", "err", strings.Repeat("x", 2000))

	recs := logs.abortRecs()
	if len(recs) != 1 {
		t.Fatalf("중단 · 포기 로그 %d줄, want 1", len(recs))
	}
	want := strings.Repeat("x", 1024) + "…(잘림 — 원래 2000바이트)"
	if got := recs[0].attrs; got["check"] != "S4" || got["err"] != want {
		t.Errorf("check %q · err %d바이트 %.40q…, want S4 · 잘린 %d바이트", got["check"], len(got["err"]), got["err"], len(want))
	}
}

// 닫힌 목록의 메타(pc-terminal=true)도 읽는다 — M4 는 닫힌 목록을 내지 않지만, 그런 판을 읽으면 P2 의 S6 이
// 열린 목록 발행을 막는다(체크리스트 A-3 3).
func TestParseMetaReadsTerminal(t *testing.T) {
	d := sampleDesc()
	d.Terminal = true
	if got, err := parseMeta(encodeMeta(d, "S-1"), "S-1"); err != nil || !got.Terminal {
		t.Errorf("parseMeta(닫힌 목록 메타) = (%+v, %v), want Terminal 참", got, err)
	}
}

// 로그 1회 가드(계획 4.5 A1 「중단 · 포기 로그」 · c4-fix3 개정 4) — 같은 스트림 · 같은 사유(stage 속성이 있으면 사유 ·
// stage 쌍)는 한 번만 남기고, 상태가 바뀌면(다른 사유 · 다른 stage · 끝까지 간 틱) 다시 남긴다.
func TestAbortLogOncePerReason(t *testing.T) {
	var st State
	steps := []struct {
		reason, stage string // reason 이 "" 면 끝까지 간 틱
		want          bool
	}{
		{"p0_no_row", "", true},
		{"p0_no_row", "", false},
		{"p0_no_row", "", false},
		{"update_412", "", true},
		{"p0_no_row", "", true},
		{"", "", false},
		{"p0_no_row", "", true},
		{"p0_no_row", "", false},
		{"gap_tx_failed", "insert", true},
		{"gap_tx_failed", "insert", false},
		{"gap_tx_failed", "commit", true},
		{"gap_tx_failed", "commit", false},
		{"gap_tx_failed", "insert", true},
	}
	for i, s := range steps {
		if s.reason == "" {
			st.clearAbort()
			continue
		}
		if got := st.noteAbort(abortKey{reason: s.reason, stage: s.stage}); got != s.want {
			t.Errorf("걸음 %d: noteAbort(%q · stage %q) = %v, want %v", i, s.reason, s.stage, got, s.want)
		}
	}
}

// 설정 검사 — 설계값 셋의 관계가 어긋나면 결정 9 의 P3 앞 판정이 모든 틱을 포기한다(Lease ≤ T_pub). writer
// 토큰과 베이스 URL 이 비면 fence 와 목록 URI 를 만들 수 없다. 사다리 설계값 다섯은 모두 양수여야 하고(영값이면 정상
// 경로의 조각이 곧바로 GAP 으로 나간다) L1 문턱 ≤ GAP_HOLD · T_edge < 갱신 의무다(c4-fix1 개정 3). 감시 설계값
// 둘(SEED_ALARM_AFTER · ERROR 승격 감시 수)도 양수여야 한다 — 0 이면 (c) 가 서지 않거나 첫 감시에 ERROR 다(체크리스트
// 419 A-8 · 〔r53b — Q-6〕).
// 관계 행은 값 하나만 Lease 로 올려 그 관계만 어긋나게 둔다 — 나머지 값은 기본값(Lease 보다 짧다)이라, 관계 절
// 하나를 지운 회귀도 가른다. 사다리 관계 행은 경계값(L1 문턱 = GAP_HOLD + 1ms · T_edge = 갱신 의무)이다.
func TestNewValidatesOptions(t *testing.T) {
	pool, err := pgxpool.New(context.Background(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	defer pool.Close()
	store := newFakeStore(t)
	base := DefaultOptions("w-1", "https://media.pokeclip.com")
	if _, err := New(pool, store, base); err != nil {
		t.Fatalf("New(기본값) = %v, want nil", err)
	}
	cases := []struct {
		name string
		edit func(*Options)
	}{
		{"writer_없음", func(o *Options) { o.Writer = "" }},
		{"base_URL_없음", func(o *Options) { o.BaseURL = "" }},
		{"base_URL_끝_슬래시", func(o *Options) { o.BaseURL += "/" }},
		{"lease_가_T_pub_이하", func(o *Options) { o.PublishTimeout = o.Lease }},
		{"lazy_갱신_간격이_lease_이상", func(o *Options) { o.LazyRenewAfter = o.Lease }},
		{"T_pub_0", func(o *Options) { o.PublishTimeout = 0 }},
		{"lazy_갱신_간격_0", func(o *Options) { o.LazyRenewAfter = 0 }},
		{"E2E_BUDGET_0", func(o *Options) { o.E2EBudget = 0 }},
		{"L1_문턱_0", func(o *Options) { o.ExpediteAfter = 0 }},
		{"GAP_HOLD_0", func(o *Options) { o.GapHold = 0 }},
		{"갱신_의무_0", func(o *Options) { o.RefreshObligation = 0 }},
		{"T_edge_0", func(o *Options) { o.EdgeDelay = 0 }},
		{"L1_문턱이_GAP_HOLD_보다_김", func(o *Options) { o.ExpediteAfter = o.GapHold + time.Millisecond }},
		{"T_edge_가_갱신_의무_이상", func(o *Options) { o.EdgeDelay = o.RefreshObligation }},
		{"SEED_ALARM_AFTER_0", func(o *Options) { o.SeedAlarmAfter = 0 }},
		{"SEED_ALARM_AFTER_음수", func(o *Options) { o.SeedAlarmAfter = -time.Minute }},
		{"ERROR_승격_감시_수_0", func(o *Options) { o.SeedAlarmErrorTicks = 0 }},
		{"ERROR_승격_감시_수_음수", func(o *Options) { o.SeedAlarmErrorTicks = -1 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			opt := base
			tc.edit(&opt)
			if _, err := New(pool, store, opt); err == nil {
				t.Errorf("New(%+v) = nil 오류, want 거부", opt)
			}
		})
	}
	same := base
	same.ExpediteAfter = same.GapHold // L1 과 L2 가 같은 문턱이어도 된다(≤)
	if _, err := New(pool, store, same); err != nil {
		t.Errorf("New(L1 문턱 = GAP_HOLD) = %v, want nil", err)
	}
	if _, err := New(nil, store, base); err == nil {
		t.Error("New(pool nil) = nil 오류, want 거부")
	}
	if _, err := New(pool, nil, base); err == nil {
		t.Error("New(store nil) = nil 오류, want 거부")
	}
}

// unreachablePool 은 접속할 수 없는 풀이다 — 문장마다 연결 거부(0행이 아닌 DB 실패)로 끝난다. 만들 때는 접속하지
// 않는다.
func unreachablePool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	pool, err := pgxpool.New(context.Background(), "postgres://user@127.0.0.1:1/none")
	if err != nil {
		t.Fatalf("풀 생성 실패(접속은 하지 않는다): %v", err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// 표에 갈래가 없는 DB 문장(획득 · R3 · R4 · lazy 갱신 · 반납)의 오류는 삼키지 않고 Outcome.Err(반납은 돌려주는
// 오류)로 올린다 — 로그는 부른 쪽 몫이라 발행자는 남기지 않는다(r4 cc 지적 2 의 같은 부류). 화해가 서지 않았으면
// 화해 예약이 남아 다음 틱이 다시 한다.
func TestUnlistedDBFailuresReturnAsErr(t *testing.T) {
	pl := soloFixture(10).window(0, 5)
	published := func(t *testing.T) *fakeStore {
		store := newFakeStore(t)
		rendered := pl
		rendered.BaseURL = fxBaseURL
		body := mustRender(t, rendered)
		plant(store, putCall{key: soloKey, body: body, meta: encodeMeta(describe(rendered, 1, sha256Of(body)), "S")})
		return store
	}
	empty := func(t *testing.T) *fakeStore { return newFakeStore(t) }
	cases := []struct {
		name  string
		store func(t *testing.T) *fakeStore
		st    State
		heads int
	}{
		{"획득", empty, State{}, 0},
		{"R4_객체_없음", empty, State{FenceHeld: true, ReconcileDue: true}, 1},
		{"R3_객체_있음", published, State{FenceHeld: true, ReconcileDue: true}, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store, logs := tc.store(t), &logRecorder{}
			pub := newPublisher(t, unreachablePool(t), store, "w-me", logs)

			out := pub.Tick(t.Context(), tc.st, TickInput{Playlist: pl})

			if out.Err == nil || out.Published || len(logs.aborts()) != 0 || len(store.putCalls()) != 0 {
				t.Errorf("틱 = %+v · 로그 %v · PUT %d번, want Err · 발행 없음 · 로그 없음 · PUT 0", out, logs.aborts(), len(store.putCalls()))
			}
			if n := len(store.headKeys()); n != tc.heads || out.State.ReconcileDue != tc.st.ReconcileDue {
				t.Errorf("Head %d번 · 화해 예약 %v, want %d번 · %v", n, out.State.ReconcileDue, tc.heads, tc.st.ReconcileDue)
			}
		})
	}
	t.Run("lazy_갱신", func(t *testing.T) {
		pub := newPublisher(t, unreachablePool(t), newFakeStore(t), "w-me", &logRecorder{})
		st := State{FenceHeld: true, RenewedAt: time.Now().Add(-time.Minute)}
		if out := pub.RenewFence(t.Context(), st, "S"); out.Err == nil || out.State != st {
			t.Errorf("RenewFence = %+v, want Err · 상태 그대로", out)
		}
	})
	t.Run("반납", func(t *testing.T) {
		pub := newPublisher(t, unreachablePool(t), newFakeStore(t), "w-me", &logRecorder{})
		if err := pub.Release(t.Context()); err == nil {
			t.Error("Release = nil, want 오류")
		}
	})
}

// 스트림 차선(계획 4.5 A3 결정 1 · 체크리스트 A-6) — 스트림마다 한 번에 하나다. 돌고 있는 동안 온 요청은
// 버리지 않고 병합해 두었다가, 결과를 받은 루프에게 한 번 더 내라고 알린다. 병합된 요청의 작업(낡은 스냅숏)은
// 돌지 않는다 — 다시 낼 때 루프가 새 입력을 만든다(A-6 5).
func TestStreamLaneMergesRequestsWhileRunning(t *testing.T) {
	ctx := t.Context()
	lanes := NewLanes()
	release := make(chan struct{})
	started := lanes.Start(ctx, "str", func(ctx context.Context) Result {
		<-release
		return Result{Kind: KindPublish}
	})
	if !started {
		t.Fatal("빈 차선의 Start = 거짓, want 참(발사)")
	}
	var staleRan atomic.Bool
	for range 3 {
		if lanes.Start(ctx, "str", func(context.Context) Result { staleRan.Store(true); return Result{} }) {
			t.Fatal("도는 차선의 Start = 참, want 거짓(병합)")
		}
	}
	close(release)
	r := <-lanes.Done()
	if r.Kind != KindPublish || r.StreamID != "str" {
		t.Errorf("결과 = %+v, want Kind 발행 · 스트림 str", r)
	}
	if rerun := lanes.Finish(r); !rerun {
		t.Error("병합된 요청이 있었는데 Finish = 거짓, want 참(한 번 더)")
	}
	if staleRan.Load() {
		t.Error("병합된 요청의 낡은 작업이 돌았다 — 재실행은 새 입력으로만 돈다")
	}
	// 한 번 더 낸 작업이 끝나면 병합 표식은 남지 않는다.
	if !lanes.Start(ctx, "str", func(context.Context) Result { return Result{Kind: KindPublish} }) {
		t.Fatal("비운 차선의 Start = 거짓, want 참")
	}
	if rerun := lanes.Finish(<-lanes.Done()); rerun {
		t.Error("병합 없이 끝난 작업의 Finish = 참, want 거짓")
	}
}

// 스트림 차선끼리는 서로를 기다리지 않고, 프로세스 차선(30초 감시 · 부팅 목록 — 작업 몸체는 커밋 5)은 스트림
// 차선과 따로 하나다. 결과는 완료 채널 하나로 돌아오고 종류(Kind)로 가른다.
func TestLanesAreIndependent(t *testing.T) {
	ctx := t.Context()
	lanes := NewLanes()
	started := make(chan struct{}, 3)
	release := make(chan struct{})
	block := func(kind Kind) Job {
		return func(context.Context) Result {
			started <- struct{}{}
			<-release
			return Result{Kind: kind}
		}
	}
	for _, s := range []string{"a", "b"} {
		if !lanes.Start(ctx, s, block(KindPublish)) {
			t.Fatalf("스트림 %s 차선 Start = 거짓", s)
		}
	}
	if !lanes.StartProcess(ctx, block(KindWatch)) {
		t.Fatal("프로세스 차선 Start = 거짓")
	}
	if lanes.StartProcess(ctx, block(KindWatch)) {
		t.Fatal("도는 프로세스 차선 Start = 참, want 거짓(병합)")
	}
	// 셋이 한꺼번에 돌아야 셋 다 시작 신호를 낸다 — 한 차선이 다른 차선을 기다리면 여기서 시한을 넘긴다.
	for i := range 3 {
		select {
		case <-started:
		case <-time.After(5 * time.Second):
			t.Fatalf("작업 %d개만 시작했다 — 차선 하나가 다른 차선을 기다린다", i)
		}
	}
	close(release)
	got := map[string]Kind{}
	for range 3 {
		r := <-lanes.Done()
		got[r.StreamID] = r.Kind
		rerun := lanes.Finish(r)
		if wantRerun := r.Kind == KindWatch; rerun != wantRerun {
			t.Errorf("Finish(%+v) = %v, want %v", r, rerun, wantRerun)
		}
	}
	want := map[string]Kind{"a": KindPublish, "b": KindPublish, "": KindWatch}
	if !maps.Equal(got, want) {
		t.Errorf("결과 = %v, want %v", got, want)
	}
}
