package rewind_test

// 발행 전 검사 S5(설계 4.5.5 「본문 형식·상한」 — 본문 = 목록 값 · 머리 줄 · TD 상한 · MAP · 크기)의 단위
// 검증이다. validate_test.go 에서 검사 축으로 나눴다 — 픽스처 규칙은 그 파일 머리를 따르고, 두 파일이 함께
// 쓰는 도우미(validate · wantVerdict · prefixed · withInitUploaded)도 그 파일에 있다.

import (
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// S5 의 TARGETDURATION 조항 — 반올림한 EXTINF 가 TD 를 넘지 않는다(RFC 8216bis-22 4.4.3.1). 반올림은
// TD 분할 판정(session.roundedSeconds)과 같이 딱 가운데를 올린다: 6,499ms → 6 · 6,500ms → 7.
//
//	회차 N(seq 700 에서 개시 · TD 6): 700..702 4초 + 703 이 6,499ms → 통과 / 6,500ms → 위반.
//	6,500ms 조각이 TD 6 회차에 남는 길은 꼬리 교정이다 — INSERT 때 4초였던 꼬리가 자라 UpdateTail 이
//	길이를 고치는데, 교정 경로는 TD 를 다시 판정하지 않는다(indexer correctTail).
//	회차 P8(TD 8): seq 300 의 7,600ms 조각이 TD 분할로 연 회차다(round 8 > 직전 TD 6 → 새 회차
//	TD max(6, 8) = 8, base 는 직전 회차의 0 을 복사 — 컷오프 0 뒤 seq 300 앞까지는 한 시간이 안 돼
//	어느 목록에서도 끊김 표시가 빠진 적이 없다). 300..303 → round 8 ≤ 8 로 통과한다.
func TestValidateTargetDurationRoundsLikeTheTDSplit(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 700, InitUploaded: true}
	p8 := rewind.Session{ID: "P8", TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	tailOf := func(d int32) []boundary.Row {
		return slices.Concat(run("N", 700, 702, day), []boundary.Row{row("N", 703, day.Add(12*time.Second), d)})
	}
	tests := []struct {
		name string
		p    rewind.Playlist
		want string
	}{
		{"TD_6_회차의_6499ms", playlist("N", []rewind.Session{n}, tailOf(6499)), ""},
		// g6_f8 — 설계 r7–r14 의 「EXTINF 6.6초 → S5」. 여기서는 반올림 경계 6.5초로 잰다.
		{"TD_6_회차의_6500ms", playlist("N", []rewind.Session{n}, tailOf(6500)), "S5"},
		{"TD_8_회차의_7600ms", playlist("P8", []rewind.Session{p8},
			[]boundary.Row{row("P8", 300, day, 7600)}, run("P8", 301, 303, day.Add(7600*time.Millisecond))), ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, validate(t, tt.p, 1, nil), tt.want)
		})
	}
}

// 설계 공백(착수 점검 숨은 가정 11) — 현재 동작을 고정한다. TD 분할 회차 P8(TD 8)을 300초 안에 다시
// 연 회차 S 가 계승하면, S 의 TD 는 개시 규칙대로 max(6, round(첫 조각 4초)) = 6 인데 접두에는 P8 의
// 7,600ms 조각이 실린다. S4(계승 접두 범위)에는 TD 조항이 없어 계승은 취소되지 않고, S5 가 발행을
// 멈춘다 — P8 의 긴 조각이 창에서 빠질 때까지(최대 1시간) S 의 목록은 나가지 않는다. 설계에 없는
// 조항은 더하지 않았다. 처방 후보(ⓒ 착수 때 kty 회부): (A) 계승 개시 TD = max(직전 회차 TD,
// round(첫 조각)) · (B) S4 에 TD 호환 조항.
// S 의 base 0 은 P8 에서 복사한 값이다 — P8 의 0 은 TestValidateTargetDurationRoundsLikeTheTDSplit 의
// P8 과 같은 까닭이다. P8 은 seq 300..303(7.6초 + 4초 × 3 = 19.6초), S 는 P8 이 끝나고 40.4초 뒤
// seq 304 부터다.
func TestValidateInheritedTDSplitPrefixHaltsOnS5(t *testing.T) {
	p8 := rewind.Session{ID: "P8", TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P8", TargetDuration: 6, MinSeq: 304, InitUploaded: true}
	p := playlist("S", []rewind.Session{p8, s},
		[]boundary.Row{row("P8", 300, day, 7600)},
		run("P8", 301, 303, day.Add(7600*time.Millisecond)),
		run("S", 304, 306, day.Add(time.Minute)),
	)

	err := validate(t, p, 1, nil)

	wantVerdict(t, err, "S5")
}

// S5 머리 대조는 소유 회차의 TD·base 로 한다 — 목록 첫 줄이 계승 접두 회차의 것이어도 그렇다(렌더 머리와
// 같은 규칙 — TestRenderHeaderComesFromOwnerSession). 두 회차의 TD 가 다른 계승 목록으로 잰다: 바로 위
// TestValidateInheritedTDSplitPrefixHaltsOnS5 의 S 가 한 시간 남짓 송출해 P8 의 긴 첫 조각이 창에서
// 빠진 뒤의 목록이다.
//
//	P8  seq 300 의 7,600ms 조각이 TD 분할로 연 회차(TD 8 · base 0 · 비계승) — 300..303 을 쓰고 끝났다
//	S   P8 이 끝나고 40.4초 뒤 P8 을 계승해 seq 304 에서 열렸다(TD 6 · P8 의 base 0 복사) — 4초 조각
//	창  머리 1199 까지는 꼬리가 300 이하라 목록에 7,600ms 조각이 실려 S5 로 멈췄다. 머리 1200 에서
//	    꼬리가 301 로 넘어간다(301..1200 = 900 × 4초 = 한 시간) — S 의 목록이 처음 나간다(직전 발행 없음)
//
// 목록 = P8 접두 301..303 + S 304..1200 이고 모든 조각이 4초라 S 의 TD 6 안이다. 첫 줄 회차 P8 의 TD 8 로
// 대조하면(회차 목록 전체의 최댓값으로 대조해도) 렌더가 쓴 TARGETDURATION:6 과 어긋나 멈춘다. 두 회차의
// base 가 같은 것은 실입력 그대로다 — P8 은 계승 회차가 아니라 첫 조각 300 에 끊김 표시가 없어, 창에서
// 빠져도 base 가 오르지 않는다. 그래서 첫 줄 회차의 base 로 대조하는지는 이 목록으로 가를 수 없다.
func TestValidateHeaderComesFromOwnerSession(t *testing.T) {
	p8 := rewind.Session{ID: "P8", TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P8", TargetDuration: 6, MinSeq: 304, InitUploaded: true}
	p := playlist("S", []rewind.Session{p8, s},
		run("P8", 301, 303, day.Add(7600*time.Millisecond)),
		run("S", 304, 1200, day.Add(time.Minute)),
	)

	err := validate(t, p, 1, nil)

	wantVerdict(t, err, "")
}

// 접두만 실린 계승 목록의 머리도 소유 회차 값이다 — 목록의 줄이 모두 계승 접두 회차의 것이어도 그렇다(렌더
// 쪽 TestRenderHeaderComesFromOwnerSession 의 「소유_회차_행_없이_접두만」과 짝). 목록은 prefixed 에서 S 의
// 행을 뺀 것이다 — 소유 회차의 첫 조각이 아직 settled 전일 때 캐시가 내는 모양이다(cache 패키지의
// TestPlaylistOfSessionWithoutSettledRowsCarriesOnlyThePrefix).
//
//	P  seq 40 에서 새로 연 회차(비계승 · base 0 · 첫 조각 4초라 TD 6) — 40..42 가 settled 다
//	S  P 가 끝나고 48초 만에 P 를 계승해 seq 43 에서 열렸다(P 의 base 0 복사). 첫 조각이 6.6초라 TD 는
//	   max(6, round 6.6) = 7 이고, 43 의 ③ 가 아직 올라가지 않아 창 머리가 42 에 멈췄다
//
// 발행한다 — 설계 4.5.5 의 어느 검사도 소유 회차의 행을 요구하지 않는다. 접두는 S4 를 지나고(직전 회차
// 소유 · settled · MAP·init · 절대 URI · 1시간 안), 목록이 P 의 첫 조각부터라 S3 도 지나며, 조각이 모두
// 4초라 S5 의 TD 상한(S 의 7) 안이다. 머리 TD 는 S 의 행이 실린 뒤에도 7 그대로여야 한다(목록 수명 중 TD
// 불변 — RFC 8216bis-22 6.2.1 · Session.TargetDuration). 줄의 회차(첫 줄이든 마지막 줄이든 P)로 대조하면
// 6 이라 렌더가 쓴 TARGETDURATION:7 과 어긋나 멈춘다. 두 회차의 base 는 같아(0) base 쪽은 이 목록으로 가를
// 수 없다.
func TestValidatePrefixOnlyPlaylistHeaderComesFromOwnerSession(t *testing.T) {
	p := prefixed()
	p.Sessions[1].TargetDuration = 7
	p.Rows = p.Rows[:3]

	err := validate(t, p, 1, nil)

	wantVerdict(t, err, "")
}

// S5 본문 형식 — 렌더 본문을 한 곳만 고쳐 넣는다. 조항이 두 무리다.
//
//	본문 = 목록 값  본문이 목록 행을 그대로 적었다 — 조각 수 · 머리 넷 · 조각마다 URI · PDT · EXTINF ·
//	               GAP 줄. 다른 검사는 행 값으로 판정하므로 본문이 행과 다르면 발행되는 바이트는 판정을
//	               받지 않은 것이 된다(r3 cx #1 — EXTINF 만 7.000 인 본문이 TD 6 목록으로 나간다).
//	               TARGETDURATION 줄이 없거나 정수가 아닌 본문도 머리 넷 조항(소유 회차 TD 한 줄)이 잡는다
//	형식           첫 줄 #EXTM3U(RFC 8216bis-22 4.4.1.1) · 우리 용도의 #PC- 줄 0개(설계 4.4.2 — 세대 정보는
//	               메타데이터로). 빈 줄은 해석에서 건너뛴다(4.1)
//
// 목록은 회차 N(seq 700 에서 개시 · TD 6)의 700..703 이고 seq 702 는 GAP 원장에 있다(③ 미업로드).
func TestValidateBodyFormat(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 700, InitUploaded: true}
	rows := run("N", 700, 703, day)
	rows[2].PlaybackUploaded, rows[2].IsGap = false, true
	p := playlist("N", []rewind.Session{n}, rows)
	body := mustRender(t, p)
	shorter := mustRender(t, playlist("N", []rewind.Session{n}, rows[:3]))
	pdt701 := "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z"
	tests := []struct {
		name string
		body string
		want string
	}{
		{"렌더_그대로", body, ""},
		{"빈_줄은_건너뜀", strings.Replace(body, "#EXTM3U\n", "#EXTM3U\n\n", 1), ""},
		{"조각_수가_행_수와_다름", shorter, "S5"},
		{"MSN_줄이_첫_행과_다름", strings.Replace(body, "#EXT-X-MEDIA-SEQUENCE:700\n", "#EXT-X-MEDIA-SEQUENCE:701\n", 1), "S5"},
		{"EXTINF_만_7초", strings.Replace(body, "#EXTINF:4.000,", "#EXTINF:7.000,", 1), "S5"},
		{"PDT_가_행과_다름", strings.Replace(body, pdt701, "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:05.000Z", 1), "S5"},
		{"URI_가_장부_키와_다름", strings.Replace(body, "/seg/000701.m4s", "/seg/000799.m4s", 1), "S5"},
		{"GAP_줄_빠짐", strings.Replace(body, "#EXT-X-GAP\n", "", 1), "S5"},
		{"GAP_줄_덧붙음", strings.Replace(body, pdt701, "#EXT-X-GAP\n"+pdt701, 1), "S5"},
		{"#EXTM3U_없음", strings.TrimPrefix(body, "#EXTM3U\n"), "S5"},
		{"#PC-_줄", strings.Replace(body, "#EXTM3U\n", "#EXTM3U\n#PC-GEN:42\n", 1), "S5"},
		{"TARGETDURATION_줄_없음", strings.Replace(body, "#EXT-X-TARGETDURATION:6\n", "", 1), "S5"},
		{"TARGETDURATION_이_정수가_아님", strings.Replace(body, "#EXT-X-TARGETDURATION:6\n", "#EXT-X-TARGETDURATION:6.5\n", 1), "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(p, []byte(tt.body), 1, nil), tt.want)
		})
	}
}

// headed 는 머리의 TD 가 기본값 6 과 겹치지 않는 목록이다 — 회차 H 는 seq 300 의 7,600ms 조각이 TD 분할로
// 연 회차(TD max(6, 8) = 8 · base 는 직전 회차의 0 을 복사 · 비계승 — P8 과 같은 까닭)이고 300..303 이
// 실렸다. 머리 넷은 VERSION:6 · TARGETDURATION:8 · MEDIA-SEQUENCE:300 · DISCONTINUITY-SEQUENCE:0 이다. base 는
// 규칙상 기본값 0 과 겹치므로, DISC-SEQ 를 기본값이 아닌 값으로 대조하는지는 g6_f2(DISC-SEQ 3)가 가른다.
func headed() rewind.Playlist {
	h := rewind.Session{ID: "H", TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	return playlist("H", []rewind.Session{h},
		[]boundary.Row{row("H", 300, day, 7600)}, run("H", 301, 303, day.Add(7600*time.Millisecond)))
}

// S5 「본문 = 목록 값」의 머리 줄과 MAP 줄 — 개수·위치·값(r3 확인 패스 cc N1).
//
//	머리 태그 넷  VERSION · TARGETDURATION · MEDIA-SEQUENCE · DISCONTINUITY-SEQUENCE 가 본문에 정확히 한
//	             줄씩 첫 조각 앞에 있고 값은 VERSION 6 · 소유 회차 TD · 첫 행 seq · 소유 회차 base 다(RFC
//	             8216bis-22 4.4.1.2 VERSION 「하나만」 · 4.4.3 나머지 셋 「종류마다 하나」 · 4.4.3.2·4.4.3.3
//	             MSN·DISC-SEQ 「첫 조각 앞」 — VERSION·TD 의 「첫 조각 앞」은 렌더 자리 기준). TD 가 줄이 둘이면 뒤
//	             줄로 덮어쓰는 파서에게 TD 가 3 이 되고, 틀린 TD 가 나가면 목록 수명 중 TD 가 바뀐다
//	MAP 줄       개수·위치만 — 회차가 이어지는 조각 앞 0줄, 바뀌는 조각(첫 조각 포함) 앞 1줄 이하. MAP 이
//	             있는지·값이 맞는지는 이 조항이 아니라 S4·S5 가 본다(여기 넣으면 접두 MAP 문제가 계승 취소에서
//	             발행 중단으로 뒤집힌다 — TestValidateInheritedPrefix · TestValidateMapReachable)
//
// 목록은 headed(seq 302 의 PDT 00:00:11.600) 와 계승 목록 prefixed(seq 41 의 PDT 00:00:04.000)다.
func TestValidateBodyHeaderAndMapLines(t *testing.T) {
	h := mustRender(t, headed())
	pre := mustRender(t, prefixed())
	hMap := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/H.mp4"` + "\n"
	pdt302 := "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:11.600Z"
	pdt41 := "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:00:04.000Z"
	uri300 := "https://media.pokeclip.com/dvr/str/seg/000300.m4s\n"
	afterFirstURI := func(line string) string {
		return strings.Replace(strings.Replace(h, line, "", 1), uri300, uri300+line, 1)
	}
	tests := []struct {
		name string
		p    rewind.Playlist
		body string
		want string
	}{
		{"렌더_그대로", headed(), h, ""},
		{"VERSION_줄이_6_이_아님", headed(), strings.Replace(h, "#EXT-X-VERSION:6\n", "#EXT-X-VERSION:7\n", 1), "S5"},
		// TD 9 는 조각 반올림 8 보다 커서 TD 상한 조항은 통과한다 — 값 대조만 잡는다.
		{"TD_줄_값이_소유_회차와_다름", headed(), strings.Replace(h, "#EXT-X-TARGETDURATION:8\n", "#EXT-X-TARGETDURATION:9\n", 1), "S5"},
		{"DISC-SEQ_줄_값이_소유_회차_base_와_다름", headed(), strings.Replace(h, "#EXT-X-DISCONTINUITY-SEQUENCE:0\n", "#EXT-X-DISCONTINUITY-SEQUENCE:1\n", 1), "S5"},
		{"TD_줄_중복", headed(), strings.Replace(h, "#EXT-X-TARGETDURATION:8\n", "#EXT-X-TARGETDURATION:8\n#EXT-X-TARGETDURATION:3\n", 1), "S5"},
		{"MSN_줄이_첫_조각_뒤", headed(), afterFirstURI("#EXT-X-MEDIA-SEQUENCE:300\n"), "S5"},
		{"TD_줄이_첫_조각_뒤", headed(), afterFirstURI("#EXT-X-TARGETDURATION:8\n"), "S5"},
		{"MAP_중복", headed(), strings.Replace(h, hMap, hMap+hMap, 1), "S5"},
		{"회차가_이어지는_조각_앞_MAP", headed(), strings.Replace(h, pdt302, `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/X.mp4"`+"\n"+pdt302, 1), "S5"},
		{"접두_중간_상대_MAP", prefixed(), strings.Replace(pre, pdt41, `#EXT-X-MAP:URI="init/P.mp4"`+"\n"+pdt41, 1), "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(tt.p, []byte(tt.body), 1, nil), tt.want)
		})
	}
}

// S5 크기 상한 — 본문은 512KiB(524,288바이트) 이하다(설계의 ≤512KB 를 1KB = 1,024B 로 읽었다).
// 조각이 0.79초로 짧아 1시간 창에 4,557조각이 든 목록으로 경계에 선다. 스트림 str · 회차
// S-20260924-000000-str-100000(seq 100000 에서 개시 — session_id 규칙 그대로) · 머리 바로 앞 조각들이
// GAP 원장에 있다(upload_stall).
//
//	새로 연 회차(base 0 · TD 6), GAP 3줄: 머리 110 + MAP 90 + 4,557조각 × 115 + GAP 3 × 11
//	                                     = 524,288바이트 → 통과
//	TD 분할로 연 회차 — 첫 조각 6,500ms 가 TD max(6, 7) = 7 을 정했고 base 10 은 직전 회차에서 복사했다.
//	EXTINF:6.500 · TARGETDURATION:7 은 너비가 같고 DISCONTINUITY-SEQUENCE:10 만 한 자리 길다
//	                                     = 524,289바이트 → 위반(상한 + 1)
//	새로 연 회차, GAP 4줄                 = 524,299바이트 → 위반
//
// 머리 110 = #EXTM3U 8 + VERSION 17 + TARGETDURATION 24 + MEDIA-SEQUENCE:100000 29 +
// DISCONTINUITY-SEQUENCE:0 32 · 조각 115 = PDT 50 + EXTINF:0.790 15 + URI 50. 길이 합은 3,600,030ms ·
// 3,605,740ms 이고 둘 다 첫 조각을 빼면 3,599,240ms 라 창 꼬리 산식과 맞다.
func TestValidateBodySizeLimitIs512KiB(t *testing.T) {
	const id = "S-20260924-000000-str-100000"
	tests := []struct {
		name    string
		base    int64
		td      int32
		firstMS int32
		gaps    int
		wantLen int
		want    string
	}{
		{"정확히_512KiB", 0, 6, 790, 3, 524288, ""},
		{"512KiB_넘음_1바이트", 10, 7, 6500, 3, 524289, "S5"},
		{"512KiB_넘음_GAP_한_줄_더", 0, 6, 790, 4, 524299, "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			n := rewind.Session{ID: id, DiscontinuityBase: tt.base, TargetDuration: tt.td, MinSeq: 100000, InitUploaded: true}
			var rows []boundary.Row
			pdt := day
			for i := range 4557 {
				d := int32(790)
				if i == 0 {
					d = tt.firstMS
				}
				r := row(id, 100000+int64(i), pdt, d)
				if i >= 4556-tt.gaps && i < 4556 {
					r.PlaybackUploaded, r.IsGap = false, true
				}
				rows = append(rows, r)
				pdt = pdt.Add(time.Duration(d) * time.Millisecond)
			}
			p := playlist(id, []rewind.Session{n}, rows)
			body := mustRender(t, p)
			if len(body) != tt.wantLen {
				t.Fatalf("픽스처 본문 = %d바이트, want %d — 렌더 형식이 바뀌었으면 셈을 다시 한다", len(body), tt.wantLen)
			}

			wantVerdict(t, rewind.Validate(p, []byte(body), 1, nil), tt.want)
		})
	}
}

// S5 「MAP 도달 가능」 — 회차가 바뀌는 조각(목록 첫 조각 포함)마다 그 회차 init 을 가리키는 EXT-X-MAP
// 이 앞에 있고, 그 init 이 올라가 있다(init_uploaded_at). MAP 은 다음 MAP 까지 이어 적용되므로(RFC
// 8216bis-22 4.4.4.5) 회차 첫 조각에 제 MAP 이 없으면 그 회차 조각이 다른 회차 init 으로 풀린다.
// 소유 회차의 init 이 안 올라간 목록을 막는 것이 설계 G5(init 전 발행 금지)의 발행 직전 그물이다 —
// 계승 목록처럼 소유 회차의 첫 줄이 목록 가운데(접두 뒤)에 있어도 같다. 계승 접두 회차의 MAP·init 은
// S4 가 먼저 본다(TestValidateInheritedPrefix).
func TestValidateMapReachable(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 700, InitUploaded: true}
	fresh := playlist("N", []rewind.Session{n}, run("N", 700, 703, day))
	freshBody := mustRender(t, fresh)
	pInit := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/P.mp4"`
	sInit := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/S.mp4"`
	nInit := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/N.mp4"`
	inheritedBody := mustRender(t, prefixed())
	tests := []struct {
		name string
		p    rewind.Playlist
		body string
		want string
	}{
		{"소유_회차_init_미업로드", withInitUploaded(fresh, 0, false), freshBody, "S5"},
		{"소유_회차_MAP_없음", fresh, strings.Replace(freshBody, nInit+"\n", "", 1), "S5"},
		// init 키를 만들 수 없는 스트림 ID(URL 에 실을 수 없다) — 렌더는 이런 목록을 내지 않는다.
		{"MAP_키_파생_실패", withStream(fresh, "a/b"), freshBody, "S5"},
		{"계승_목록_소유_회차_MAP_없음", prefixed(), strings.Replace(inheritedBody, sInit+"\n", "", 1), "S5"},
		{"계승_목록_소유_회차_MAP_이_접두_회차_init", prefixed(), strings.Replace(inheritedBody, sInit, pInit, 1), "S5"},
		{"계승_목록_소유_회차_init_미업로드", withInitUploaded(prefixed(), 1, false), inheritedBody, "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(tt.p, []byte(tt.body), 1, nil), tt.want)
		})
	}
}

// withStream 은 p 의 스트림 ID 를 바꾼 목록이다.
func withStream(p rewind.Playlist, streamID string) rewind.Playlist {
	p.StreamID = streamID
	return p
}
