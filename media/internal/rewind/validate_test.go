package rewind_test

// 발행 전 검사(설계 4.5.5 S1–S7)의 단위 검증 — POK-195 M4 PR ⓑ 커밋 ③.
//
// 목록은 렌더한 본문과 한 쌍으로 검사한다(validate 도우미 — 본문은 Render 가 낸 그대로다). 본문 형식
// 검사(S5)와 계승 접두 검사(S4)의 몇 경우만 렌더 본문을 손으로 고쳐 넣는다. Render 는 그런 본문을
// 만들지 않지만, 발행 직전의 마지막 그물이 본문을 스스로 확인하는지가 계약이다.
// 기대값은 손으로 적은 리터럴이고 셈은 각 테스트 주석에 적는다. 픽스처는 장부 쓰기 규칙 전반과
// 맞춘다 — 개시(비계승 개시는 base 0, 계승·TD 분할 개시는 직전 회차의 base 를 복사, TD 는
// max(6, round(첫 조각))) · 귀속 하한(벽시계가 회차 개시보다 1초 넘게 이른 조각은 그 회차에 넣지
// 않는다) · TD 분할(반올림 길이가 회차 TD 를 넘는 새 조각은 새 회차를 연다)(session.openDecision ·
// decide · 계획 PR ⓑ discontinuity_base 정의). 실입력으로 성립하지 않는 조합은 쓰지 않는다. 재귀식을
// 거치지 않은 PDT(g6_f7 · S7 경계)는 검사 그물을 재려는 입력이라 그렇다고 주석에 적는다.
// G6 픽스처는 설계가 이름을 준 f6·f7 을 채운다(주석에 g6_ 접두 — 번호 정책은 render_test.go 머리).

import (
	"errors"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// clock 은 픽스처 날(2026-09-24 UTC)의 시각이다.
func clock(h, m, s, ms int) time.Time {
	return time.Date(2026, 9, 24, h, m, s, ms*int(time.Millisecond), time.UTC)
}

// validate 는 목록을 렌더한 본문 그대로 검사한다.
func validate(t *testing.T, p rewind.Playlist, gen int64, prev *rewind.Published) error {
	t.Helper()
	return rewind.Validate(p, []byte(mustRender(t, p)), gen, prev)
}

// wantVerdict 는 검사 결과를 본다. want 가 ""면 통과, "S4"면 계승 취소, 그 밖이면 그 검사의 발행
// 중단이다(설계 4.5.5 처치 열). 처치는 errors.Is 로만 가른다 — 호출자(ⓒ)가 그렇게 가르기 때문이다.
func wantVerdict(t *testing.T, err error, want string) {
	t.Helper()
	if want == "" {
		if err != nil {
			t.Errorf("Validate = %v, want nil(발행한다)", err)
		}
		return
	}
	var v *rewind.Violation
	if !errors.As(err, &v) {
		t.Fatalf("Validate = %v, want %s 위반", err, want)
	}
	if v.Check != want {
		t.Errorf("걸린 검사 = %s (%v), want %s", v.Check, err, want)
	}
	revoke := want == "S4"
	if got := errors.Is(err, rewind.ErrRevokeInheritance); got != revoke {
		t.Errorf("errors.Is(%v, ErrRevokeInheritance) = %v, want %v", err, got, revoke)
	}
	if got := errors.Is(err, rewind.ErrHaltPublication); got == revoke {
		t.Errorf("errors.Is(%v, ErrHaltPublication) = %v, want %v", err, got, !revoke)
	}
}

// prefixed 는 계승 회차 S 의 목록이다 — 직전 회차 P 의 접두 seq 40..42 + S 의 seq 43..45.
//
//	P  seq 40 에서 새로 연 회차(비계승 · base 0 · 첫 조각 4초라 TD 6)
//	S  P 가 끝난 뒤 48초 만에 다시 연 회차 — 300초 안이라 P 를 계승했고 P 의 base 0 을 복사했다
//
// 두 회차의 init 은 올라가 있다. 컷오프는 0 이다(P 앞에 다른 회차가 있다).
func prefixed() rewind.Playlist {
	p := rewind.Session{ID: "P", TargetDuration: 6, MinSeq: 40, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 6, MinSeq: 43, InitUploaded: true}
	return playlist("S", []rewind.Session{p, s}, run("P", 40, 42, day), run("S", 43, 45, day.Add(time.Minute)))
}

// g6_f2 — 설계 4.8.2 실물(골든 900조각 전문)은 S1–S7 을 전부 통과한다. 두 회차의 init 은 올라가
// 있다(착수 점검 숨은 가정 4). 직전 발행은 한 틱 앞의 목록 — seq 398..1296(899조각, seq 1297 의 GAP
// 등재 전)이고 세대 1186 이다. 이번 세대 1187 · 마지막 조각 1297 은 설계 r8 4.4.2 의 메타 예시
// (pc-gen 1187 · pc-pub-seq 1297)와 같다.
func TestValidateG6F2Passes(t *testing.T) {
	golden, err := os.ReadFile(filepath.Join("testdata", "g6_f2.m3u8"))
	if err != nil {
		t.Fatalf("골든 읽기 실패: %v", err)
	}
	p := f2Playlist()
	for i := range p.Sessions {
		p.Sessions[i].InitUploaded = true
	}
	tests := []struct {
		name string
		gen  int64
		prev *rewind.Published
	}{
		{"첫_발행", 1, nil},
		{"직전_틱_뒤", 1187, &rewind.Published{Gen: 1186, MediaSequence: 398, PublishedSeq: 1296, SegmentCount: 899}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(p, golden, tt.gen, tt.prev), "")
		})
	}
}

// g6_f6 — PDT 그물(재귀식): 벽시계가 뒤로 가도 PDT 는 증가한다(계획 6.2 · 설계 5.1.1).
//
// 회차 W 는 seq 70 에서 새로 연 회차(비계승 · base 0 · TD 6)이고 조각은 4초다. seq 72 의 녹화 시작
// 시각(start_wall_utc — 파일명)이 NTP 보정으로 7초 뒤로 갔다(12:00:08 이어야 할 자리에 12:00:01).
// 회차 개시 12:00:00 − 귀속 여유 1초 = 11:59:59 보다는 늦어 W 에 귀속된다(session.decide 귀속 하한).
// 인덱서는 그 행을 끊김으로 표시하고 그대로 넣는다(negative_drift). 장부 PDT 는 재귀식이라 누적항이
// 이긴다:
//
//	playback_pdt(k) = max(playback_pdt(k−1) + duration(k−1), start_wall_utc(k))
//
//	seq  start_wall_utc  재귀식 PDT
//	70   12:00:00.000    12:00:00.000   회차 첫 조각(first_pdt = 벽시계)
//	71   12:00:04.000    12:00:04.000   max(12:00:04, 12:00:04)
//	72   12:00:01.000    12:00:08.000   max(12:00:08, 12:00:01)
//	73   12:00:05.000    12:00:12.000   max(12:00:12, 12:00:05)
//	74   12:00:09.000    12:00:16.000   max(12:00:16, 12:00:09)
//
// 재귀식 PDT 는 S7 을 통과한다. 재귀식을 우회해 벽시계를 그대로 PDT 로 쓰면 seq 72 에서 PDT 가 뒤로
// 가고(12:00:04 → 12:00:01) S7 이 발행을 멈춘다 — 설계 r7–r14 9절이 f6 에 적은 「입력 playback_pdt
// 역행」이 이 경우다.
func TestValidateG6F6PDTRecursionSurvivesWallClockStepBack(t *testing.T) {
	w := rewind.Session{ID: "W", TargetDuration: 6, MinSeq: 70, InitUploaded: true}
	walls := []time.Time{clock(12, 0, 0, 0), clock(12, 0, 4, 0), clock(12, 0, 1, 0), clock(12, 0, 5, 0), clock(12, 0, 9, 0)}
	recursive := []time.Time{clock(12, 0, 0, 0), clock(12, 0, 4, 0), clock(12, 0, 8, 0), clock(12, 0, 12, 0), clock(12, 0, 16, 0)}
	tests := []struct {
		name string
		pdts []time.Time
		want string
	}{
		{"재귀식_PDT", recursive, ""},
		{"재귀식_우회_PDT_가_벽시계", walls, "S7"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var rows []boundary.Row
			for i, pdt := range tt.pdts {
				rows = append(rows, row("W", int64(70+i), pdt, 4000))
			}

			err := validate(t, playlist("W", []rewind.Session{w}, rows), 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// g6_f7 — PDT 그물(재귀식): 겹치는 PDT 는 발행하지 않는다. 회차 W(g6_f6 과 같다)의 seq 72 벽시계가
// 조각 하나(4초)만큼, 또는 2.5초 뒤로 갔다(12:00:04 · 12:00:05.500 — 둘 다 귀속 하한 11:59:59 안이다).
// 재귀식을 우회해 벽시계를 PDT 로 쓰면 seq 72 는 seq 71 과 같은 순간(중복 — 설계 r7–r14 9절이 f7 에
// 적은 「입력 playback_pdt 중복」)이나 seq 71 이 끝나기 2.5초 전(겹침)에 시작한다. 재귀식이면 두 경우
// 모두 max(12:00:08, 벽시계) = 12:00:08.000 이다.
func TestValidateG6F7OverlappingPDTHalts(t *testing.T) {
	w := rewind.Session{ID: "W", TargetDuration: 6, MinSeq: 70, InitUploaded: true}
	tests := []struct {
		name  string
		pdt72 time.Time
		want  string
	}{
		{"재귀식_PDT", clock(12, 0, 8, 0), ""},
		{"재귀식_우회_중복", clock(12, 0, 4, 0), "S7"},
		{"재귀식_우회_겹침_2500ms", clock(12, 0, 5, 500), "S7"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := []boundary.Row{
				row("W", 70, clock(12, 0, 0, 0), 4000),
				row("W", 71, clock(12, 0, 4, 0), 4000),
				row("W", 72, tt.pdt72, 4000),
			}

			err := validate(t, playlist("W", []rewind.Session{w}, rows), 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// S7 의 두 항과 여유 1ms 의 경계 — PDT[i+1] > PDT[i] ∧ PDT[i+1] ≥ PDT[i] + duration[i] − 1ms 를
// 발행되는 PDT 줄로 잰다(렌더는 PDT 를 ms 로 잘라 적는다). 회차 W 의 두 조각 seq 70(길이 d) · 71 의
// PDT 만 바꾼다(재귀식을 거치지 않은 검사 경계 입력). 비교가 ≥ 라 딱 1ms 겹침까지 통과하고, 앞으로
// 뛰는 PDT 는 하한만 보므로 통과한다. 첫째 항(엄격 증가)이 둘째 항 없이 일하는 것은 길이가 1ms 인
// 조각 뒤뿐이다 — 장부가 받는 가장 짧은 길이다(duration_ms ≥ 1, 인덱서 H7 폭 검증).
// 발행 줄로 재는 까닭이 둘째 무리다: 1ms 조각 뒤 1µs 만 늘면 원래 값은 증가지만 발행 줄 둘이 같은
// 12:00:00.000 이라 위반이고(r3 cx #2), 원래 값으로는 1ms 넘게 겹쳐도(0.5ms 시작 · 3,999.2ms 뒤)
// 발행 줄로는 00.000 → 03.999 = 딱 1ms 겹침이라 통과한다.
func TestValidatePDTToleratesOneMillisecondOverlap(t *testing.T) {
	w := rewind.Session{ID: "W", TargetDuration: 6, MinSeq: 70, InitUploaded: true}
	t0 := clock(12, 0, 0, 0)
	tests := []struct {
		name  string
		pdt70 time.Time
		d70   int32
		pdt71 time.Time
		want  string
	}{
		{"이어짐", t0, 4000, t0.Add(4 * time.Second), ""},
		{"벽시계가_8초_앞서_뜀", t0, 4000, t0.Add(12 * time.Second), ""},
		{"겹침_1ms", t0, 4000, t0.Add(3999 * time.Millisecond), ""},
		{"겹침_1ms_더하기_1µs", t0, 4000, t0.Add(3999*time.Millisecond - time.Microsecond), "S7"},
		{"1ms_조각_뒤_같은_PDT", t0, 1, t0, "S7"},
		{"1ms_조각_뒤_1µs_발행_줄이_같음", t0, 1, t0.Add(time.Microsecond), "S7"},
		{"1ms_조각_뒤_1ms", t0, 1, t0.Add(time.Millisecond), ""},
		{"원래_값은_1ms_넘게_겹치나_발행_줄로는_1ms", t0.Add(500 * time.Microsecond), 4000, t0.Add(3999200 * time.Microsecond), ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := []boundary.Row{row("W", 70, tt.pdt70, tt.d70), row("W", 71, tt.pdt71, 4000)}

			err := validate(t, playlist("W", []rewind.Session{w}, rows), 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// S3 — 목록에 실린 행은 전부 settled 다. 판정은 boundary.Settled 하나다(세 값 session_id ·
// playback_pdt · playback_s3_key 도 이 술어가 본다). 회차 N(seq 500 에서 새로 연 회차)의 900조각
// (3,600,000ms — 창 꼬리 500) 가운데 seq 502 한 행만 바꾼다. 목록을 1시간 이상으로 두는 까닭: 1시간이
// 안 되면 「회차 첫 조각」 항이 먼저 S3 를 내서, settled 판정에 넘기는 컷오프 인자가 따로 고정되지
// 않는다(r3 cc #3).
// GAP 원장에 있는 행은 ③ 없이도 settled 다. PDT 가 빈 행은 S7 보다 S3 에서 먼저 걸린다.
func TestValidateRangeRequiresSettledRows(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 500, InitUploaded: true}
	tests := []struct {
		name   string
		cutoff int64
		change func(r *boundary.Row)
		want   string
	}{
		{"모두_settled", 0, func(*boundary.Row) {}, ""},
		{"③_미업로드", 0, func(r *boundary.Row) { r.PlaybackUploaded = false }, "S3"},
		{"③_미업로드_GAP_원장", 0, func(r *boundary.Row) { r.PlaybackUploaded, r.IsGap = false, true }, ""},
		{"playback_pdt_NULL", 0, func(r *boundary.Row) { r.PlaybackPDT = time.Time{} }, "S3"},
		{"playback_s3_key_NULL", 0, func(r *boundary.Row) { r.PlaybackS3Key = "" }, "S3"},
		// 컷오프 501 아래의 seq 500 은 홀이 아니라 범위 밖이다(설계 4.2 ⓐ) — 목록에 실릴 수 없다.
		{"컷오프_아래_행", 501, func(*boundary.Row) {}, "S3"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := run("N", 500, 1399, day)
			tt.change(&rows[2])
			p := playlist("N", []rewind.Session{n}, rows)
			p.Cutoff = tt.cutoff

			err := validate(t, p, 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// S3 — 목록은 1시간(boundary.WindowMS)을 채우거나, 첫 행이 그 행 회차의 첫 조각이다(설계 「또는
// 세션 전체」). 회차 첫 조각 = max(회차 최소 seq, 컷오프) — 컷오프 아래 행은 목록에 실릴 수 없다.
// 창이 1시간이 안 되는 목록은 둘뿐이다: 컷오프 뒤로 1시간이 아직 안 쌓였거나, 목록 첫 회차가 1시간
// 안쪽에서 시작했다. 둘 다 목록이 그 회차의 첫 조각부터다.
//
// 회차 N 은 seq 1000 에서 새로 연 회차다(컷오프 0 — N 앞에 다른 회차가 있다). 조각은 4초다.
//
//	1001..1900                900조각 = 3,600,000ms — 꼬리가 회차 중간이어도 1시간을 채웠다
//	1001..1900, 1900 이 3,999ms  3,599,999ms — 1ms 모자라고 첫 행이 회차 첫 조각(1000)이 아니다
//	1000..1010                1시간이 안 되지만 회차 첫 조각부터다(컷오프 0 이 아니라 1000)
//	1001..1010                회차 첫 조각을 빠뜨렸다
//	컷오프 1003 · 1003..1010    회차 첫 조각은 컷오프 행이다
//	컷오프 1003 · 1004..1010    컷오프 행을 빠뜨렸다
//	접두 P 40..42 + S 43..45    첫 행 회차는 계승 접두 회차 P 다 — P 첫 조각 40 부터
//	접두 P 41..42 + S 43..45    P 첫 조각을 빠뜨렸다
func TestValidateRangeIsAnHourOrFromTheFirstSegment(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 1000, InitUploaded: true}
	short := run("N", 1001, 1900, day)
	short[len(short)-1].DurationMS = 3999
	fromP41 := prefixed()
	fromP41.Rows = fromP41.Rows[1:]
	tests := []struct {
		name string
		p    rewind.Playlist
		want string
	}{
		{"한_시간_꼬리가_회차_중간", playlist("N", []rewind.Session{n}, run("N", 1001, 1900, day)), ""},
		{"한_시간에서_1ms_모자람", playlist("N", []rewind.Session{n}, short), "S3"},
		{"한_시간_미만_회차_첫_조각부터", playlist("N", []rewind.Session{n}, run("N", 1000, 1010, day)), ""},
		{"한_시간_미만_회차_첫_조각_빠짐", playlist("N", []rewind.Session{n}, run("N", 1001, 1010, day)), "S3"},
		{"컷오프가_회차_중간_컷오프_행부터", withCutoff(playlist("N", []rewind.Session{n}, run("N", 1003, 1010, day)), 1003), ""},
		{"컷오프가_회차_중간_컷오프_행_빠짐", withCutoff(playlist("N", []rewind.Session{n}, run("N", 1004, 1010, day)), 1003), "S3"},
		{"계승_접두_회차_첫_조각부터", prefixed(), ""},
		{"계승_접두_회차_첫_조각_빠짐", fromP41, "S3"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, validate(t, tt.p, 1, nil), tt.want)
		})
	}
}

// withCutoff 는 p 의 컷오프를 바꾼 목록이다.
func withCutoff(p rewind.Playlist, cutoff int64) rewind.Playlist {
	p.Cutoff = cutoff
	return p
}

// 실을 조각이 없는 목록은 범위가 없다 — S3 로 발행 중단이다. Render 가 먼저 거르지만(빈 목록 = 오류)
// Validate 도 스스로 멈춘다.
func TestValidateEmptyPlaylistHalts(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 1000, InitUploaded: true}

	err := rewind.Validate(playlist("N", []rewind.Session{n}), nil, 1, nil)

	wantVerdict(t, err, "S3")
}

// S1·S2·S6 — 직전 발행과 견준다. 회차 N(seq 1000 에서 새로 연 회차, 조각 4초 — seq k 의 PDT 는
// 00:00 + (k − 1000) × 4초)의 1시간 창이 한 틱에 한 칸씩 밀린다. 직전 발행 = seq 1000..1899(900조각)
// · 세대 41 이고 이번 발행의 세대는 42 다.
//
//	첫 발행               견줄 것이 없다
//	한 칸 밀림             1001..1900 — 줄 수 900 ≥ 900 − 축출 1
//	6초 조각 둘에 셋 축출   1003..1901 — 1900·1901 이 6초(TD 6 회차에 들어가는 길이)라 1시간을 채우는
//	                      꼬리가 세 칸 밀렸다: 4초 897개 + 6초 2개 = 3,600,000ms. 줄 수가 900 → 899 로
//	                      줄었어도 축출 3 만큼은 줄 수 있다(899 ≥ 900 − 3) — 축출 수 = MSN 차
//	머리 후퇴             1000..1898 — 축출 없이 줄이 줄었다(899 < 900)
//	MSN 후퇴              직전 1001..1900 → 이번 1000..1899 — 줄 수는 같다(900 ≥ 900 − 0)
//	같은 목록 재발행        직전 = 이번 = 1001..1900 — 머리가 제자리인 발행(화해 뒤 재발행 · RC-19
//	                      재시도)은 되돌림이 아니다(published_seq 비엄격 — r3 cc #2)
//	세대 제자리            세대 41 — 발행마다 새로 예약하는 세대가 같다 = 낡은 writer 다
//	published_seq 후퇴     직전 발행 사실의 출처가 둘이고 DB 쪽이 앞선 국면 — 캐시의 마지막 발행은
//	                      1000..1898(899조각)인데 DB 의 published_seq 는 1900(그 사이 다른 writer 가
//	                      발행했다). 이번 1000..1899 는 줄 수·MSN 은 통과하고 머리가 1900 뒤로 간다
func TestValidateAgainstThePreviousPublication(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 1000, InitUploaded: true}
	window := func(from, to int64) []boundary.Row {
		return run("N", from, to, day.Add(time.Duration(from-1000)*4*time.Second))
	}
	longTail := slices.Concat(window(1003, 1899), []boundary.Row{
		row("N", 1900, day.Add(900*4*time.Second), 6000),
		row("N", 1901, day.Add(900*4*time.Second+6*time.Second), 6000),
	})
	last := &rewind.Published{Gen: 41, MediaSequence: 1000, PublishedSeq: 1899, SegmentCount: 900}
	tests := []struct {
		name string
		rows []boundary.Row
		gen  int64
		prev *rewind.Published
		want string
	}{
		{"첫_발행", window(1001, 1900), 1, nil, ""},
		{"한_칸_밀림", window(1001, 1900), 42, last, ""},
		{"6초_조각_둘에_셋_축출", longTail, 42, last, ""},
		{"같은_목록_재발행", window(1001, 1900), 42, &rewind.Published{Gen: 41, MediaSequence: 1001, PublishedSeq: 1900, SegmentCount: 900}, ""},
		{"머리_후퇴", window(1000, 1898), 42, last, "S1"},
		{"MSN_후퇴", window(1000, 1899), 42, &rewind.Published{Gen: 41, MediaSequence: 1001, PublishedSeq: 1900, SegmentCount: 900}, "S2"},
		{"세대_제자리", window(1001, 1900), 41, last, "S6"},
		{"published_seq_후퇴", window(1000, 1899), 42, &rewind.Published{Gen: 41, MediaSequence: 1000, PublishedSeq: 1900, SegmentCount: 899}, "S6"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validate(t, playlist("N", []rewind.Session{n}, tt.rows), tt.gen, tt.prev)

			wantVerdict(t, err, tt.want)
		})
	}
}

// S6 의 닫힘 성분 — 목록의 닫힘(ENDLIST)은 거짓→참으로만 간다. 닫힌 목록 뒤에 열린 목록을 내면 끝난
// 목록이 다시 열린다. 이번 목록이 닫혔는지는 본문에 EXT-X-ENDLIST 줄이 있는가로 읽는다(메타
// pc-terminal 과 같은 사실). 닫힌 목록 뒤에 닫힌 목록을 다시 내는 것은 되돌림이 아니다.
// 목록은 회차 N 의 seq 1001..1900 이고 직전 발행은 같은 목록(세대 41)이다 — 닫기는 같은 행에 ENDLIST
// 만 더한 목록이라 머리가 제자리다(published_seq 비엄격이 여기서도 고정된다 — r3 cc #2).
func TestValidateTerminalOnlyGoesFalseToTrue(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 1000, InitUploaded: true}
	p := playlist("N", []rewind.Session{n}, run("N", 1001, 1900, day.Add(4*time.Second)))
	open := []byte(mustRender(t, p))
	sealed, err := rewind.SealEndlist(open)
	if err != nil {
		t.Fatalf("SealEndlist 오류: %v", err)
	}
	openPrev := &rewind.Published{Gen: 41, MediaSequence: 1001, PublishedSeq: 1900, SegmentCount: 900}
	sealedPrev := &rewind.Published{Gen: 41, MediaSequence: 1001, PublishedSeq: 1900, SegmentCount: 900, Terminal: true}
	tests := []struct {
		name string
		body []byte
		prev *rewind.Published
		want string
	}{
		{"열린_목록_뒤_닫힌_목록", sealed, openPrev, ""},
		{"닫힌_목록_뒤_닫힌_목록", sealed, sealedPrev, ""},
		{"닫힌_목록_뒤_열린_목록", open, sealedPrev, "S6"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(p, tt.body, 42, tt.prev), tt.want)
		})
	}
}

// S5 의 TARGETDURATION 조항 — 반올림한 EXTINF 가 TD 를 넘지 않는다(RFC 8216bis-22 4.4.3.1). 반올림은
// TD 분할 판정(session.roundedSeconds)과 같이 딱 가운데를 올린다: 6,499ms → 6 · 6,500ms → 7.
//
//	회차 N(seq 700 에서 개시 · TD 6): 700..702 4초 + 703 이 6,499ms → 통과 / 6,500ms → 위반.
//	6,500ms 조각이 TD 6 회차에 남는 길은 꼬리 교정이다 — INSERT 때 4초였던 꼬리가 자라 UpdateTail 이
//	길이를 고치는데, 교정 경로는 TD 를 다시 판정하지 않는다(indexer correctTail).
//	회차 P8(TD 8): seq 300 의 7,600ms 조각이 TD 분할로 연 회차다(round 8 > 직전 TD 6 → 새 회차
//	TD max(6, 8) = 8, base 5 는 직전 회차에서 복사). 300..303 → round 8 ≤ 8 로 통과한다.
func TestValidateTargetDurationRoundsLikeTheTDSplit(t *testing.T) {
	n := rewind.Session{ID: "N", TargetDuration: 6, MinSeq: 700, InitUploaded: true}
	p8 := rewind.Session{ID: "P8", DiscontinuityBase: 5, TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	tailOf := func(d int32) []boundary.Row {
		return slices.Concat(run("N", 700, 702, day), []boundary.Row{row("N", 703, day.Add(12*time.Second), d)})
	}
	tests := []struct {
		name string
		p    rewind.Playlist
		want string
	}{
		{"TD_6_회차의_6499ms", playlist("N", []rewind.Session{n}, tailOf(6499)), ""},
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
// S 의 base 5 는 P8 에서 복사한 값이다. P8 은 seq 300..303(7.6초 + 4초 × 3 = 19.6초), S 는 P8 이
// 끝나고 40.4초 뒤 seq 304 부터다.
func TestValidateInheritedTDSplitPrefixHaltsOnS5(t *testing.T) {
	p8 := rewind.Session{ID: "P8", DiscontinuityBase: 5, TargetDuration: 8, MinSeq: 300, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P8", DiscontinuityBase: 5, TargetDuration: 6, MinSeq: 304, InitUploaded: true}
	p := playlist("S", []rewind.Session{p8, s},
		[]boundary.Row{row("P8", 300, day, 7600)},
		run("P8", 301, 303, day.Add(7600*time.Millisecond)),
		run("S", 304, 306, day.Add(time.Minute)),
	)

	err := validate(t, p, 1, nil)

	wantVerdict(t, err, "S5")
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

// headed 는 머리 값이 기본값(TD 6 · base 0 · f2 base 3)과 겹치지 않는 목록이다 — 회차 H 는 seq 300 의
// 7,600ms 조각이 TD 분할로 연 회차(TD max(6, 8) = 8 · base 5 는 직전 회차에서 복사 · 비계승)이고
// 300..303 이 실렸다. 머리 넷은 VERSION:6 · TARGETDURATION:8 · MEDIA-SEQUENCE:300 · DISCONTINUITY-SEQUENCE:5 다.
func headed() rewind.Playlist {
	h := rewind.Session{ID: "H", DiscontinuityBase: 5, TargetDuration: 8, MinSeq: 300, InitUploaded: true}
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
		{"DISC-SEQ_줄_값이_소유_회차_base_와_다름", headed(), strings.Replace(h, "#EXT-X-DISCONTINUITY-SEQUENCE:5\n", "#EXT-X-DISCONTINUITY-SEQUENCE:4\n", 1), "S5"},
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
// 소유 회차의 init 이 안 올라간 목록을 막는 것이 설계 G5(init 전 발행 금지)의 발행 직전 그물이다.
// 계승 접두 회차의 MAP·init 은 S4 가 먼저 본다(TestValidateInheritedPrefix).
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
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(tt.p, []byte(tt.body), 1, nil), tt.want)
		})
	}
}

// withInitUploaded 는 p 의 i 번째 회차 init 업로드 여부를 바꾼 목록이다(회차 목록은 새로 만든다).
func withInitUploaded(p rewind.Playlist, i int, uploaded bool) rewind.Playlist {
	p.Sessions = slices.Clone(p.Sessions)
	p.Sessions[i].InitUploaded = uploaded
	return p
}

// withStream 은 p 의 스트림 ID 를 바꾼 목록이다.
func withStream(p rewind.Playlist, streamID string) rewind.Playlist {
	p.StreamID = streamID
	return p
}

// S4 계승 접두 범위 — 목록 첫머리에 실린 직전 회차의 조각(계승 백필)을 실어도 되는가. 걸리면 처치는
// 계승 취소다(발행 중단이 아니다). 기준 목록은 prefixed(P 40..42 + S 43..45)이고 각 경우는 한 곳만
// 바꾼다. 접두 행이 settled 가 아니거나 접두 MAP 이 없으면 S3·S5 도 걸리지만, S4 를 먼저 봐서 계승
// 취소로 간다. 접두만 실린 목록(S 의 첫 조각이 아직 settled 전)은 그대로 낸다. 단 본문이 목록 값과
// 다르면 계승 취소 대상이어도 그보다 앞서 발행 중단이다(마지막 경우 — Validate 문서의 우선순위). MAP 의
// 있고 없음·값은 그 우선순위 밖이라 본문에서만 접두 MAP 이 어긋나도 계승 취소다(접두_MAP_없음 ·
// 접두_MAP_이_소유_회차_init — 설계 S4 의 MAP 선행 조항).
func TestValidateInheritedPrefix(t *testing.T) {
	pInit := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/P.mp4"`
	sInit := `#EXT-X-MAP:URI="https://media.pokeclip.com/dvr/str/init/S.mp4"`
	tests := []struct {
		name   string
		change func(p *rewind.Playlist)
		edit   func(body string) string
		want   string
	}{
		{"그대로", nil, nil, ""},
		{"접두만_실린_목록", func(p *rewind.Playlist) { p.Rows = p.Rows[:3] }, nil, ""},
		{"접두가_소유_회차가_계승한_회차의_것이_아님", func(p *rewind.Playlist) { p.Sessions[1].InheritsSession = "O" }, nil, "S4"},
		{"비계승_회차의_목록에_접두", func(p *rewind.Playlist) { p.Sessions[1].InheritsSession = "" }, nil, "S4"},
		// 접두의 첫 조각은 P 의 것이지만 뒤에 다른 회차 Y 의 조각이 섞였다 — 접두 행은 「전부」 P 의
		// 것이어야 한다(계승 사슬 1단계). Y 는 seq 42 에서 새로 연 회차로 init 도 올라가 있다.
		{"접두_중간에_다른_회차_행", func(p *rewind.Playlist) {
			p.Sessions = append(p.Sessions, rewind.Session{ID: "Y", TargetDuration: 6, MinSeq: 42, InitUploaded: true})
			p.Rows[2].SessionID = "Y"
		}, nil, "S4"},
		{"직전_회차_init_미업로드", func(p *rewind.Playlist) { p.Sessions[0].InitUploaded = false }, nil, "S4"},
		{"접두_행_미settled", func(p *rewind.Playlist) { p.Rows[1].PlaybackUploaded = false }, nil, "S4"},
		// 컷오프 41 아래의 seq 40 — 컷오프 아래 행은 목록에 실릴 수 없다(접두 settled 판정의 컷오프 인자).
		{"접두_행이_컷오프_아래", func(p *rewind.Playlist) { p.Cutoff = 41 }, nil, "S4"},
		{"접두_MAP_없음", nil, func(b string) string { return strings.Replace(b, pInit+"\n", "", 1) }, "S4"},
		{"접두_MAP_이_소유_회차_init", nil, func(b string) string { return strings.Replace(b, pInit, sInit, 1) }, "S4"},
		// 상대 URI 는 목록 URL(/dvr/{stream}/{session}/index.m3u8) 기준으로 풀려 다른 곳을 가리킨다.
		{"상대_URI", func(p *rewind.Playlist) { p.BaseURL = "/cdn" }, nil, "S4"},
		// 스킴 뒤 "//" 를 빠뜨린 베이스 URL — 스킴은 있으나 호스트가 없어 어느 서버인지 정해지지 않는다.
		{"호스트_없는_URI", func(p *rewind.Playlist) { p.BaseURL = "https:media.pokeclip.com" }, nil, "S4"},
		// 본문에서만 접두 조각 URI 가 상대다(행·베이스 URL 은 절대 — 렌더가 쓰지 않은 본문). 같은 바이트가 S4
		// 절대 URI 조항에도 걸리지만, 본문이 목록 값과 다른 것은 접두를 빼도 고쳐지지 않는 렌더·호출자
		// 결함이라 발행 중단이 먼저다(r3 확인 패스 cx MED).
		{"본문만_접두_URI_가_상대", nil, func(b string) string {
			return strings.Replace(b, "https://media.pokeclip.com/dvr/str/seg/000040.m4s", "dvr/str/seg/000040.m4s", 1)
		}, "S5"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := prefixed()
			if tt.change != nil {
				tt.change(&p)
			}
			body := mustRender(t, p)
			if tt.edit != nil {
				body = tt.edit(body)
			}

			err := rewind.Validate(p, []byte(body), 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// S4 의 1시간 상한 — 계승 접두는 머리에서 1시간 창보다 먼 곳에서 시작하지 않는다: 목록 첫 조각을
// 빼고 센 길이 합이 WindowMS 에 못 미친다(boundary 의 창 꼬리 = 그 합이 WindowMS 에 닿는 가장 늦은
// seq). M4 의 1단계 계승에서는 창이 이미 이 조건을 지키므로, 걸리는 것은 창 밖 행을 실은 호출자다.
//
//	P(seq 3 에서 개시, 4초 조각) 뒤 S(P 를 계승, seq 901..903 — P 가 끝나고 68초 뒤):
//	P 4..900 + S = 900조각 — 첫 조각을 뺀 합 899 × 4,000 = 3,596,000 < 3,600,000 → 통과
//	P 3..900 + S = 901조각 — 첫 조각을 뺀 합 900 × 4,000 = 3,600,000 → 계승 취소(꼬리가 한 칸 앞)
func TestValidatePrefixStaysWithinTheHourWindow(t *testing.T) {
	p := rewind.Session{ID: "P", TargetDuration: 6, MinSeq: 3, InitUploaded: true}
	s := rewind.Session{ID: "S", InheritsSession: "P", TargetDuration: 6, MinSeq: 901, InitUploaded: true}
	sRows := run("S", 901, 903, day.Add(time.Hour+time.Minute))
	tests := []struct {
		name string
		from int64
		want string
	}{
		{"창_꼬리부터", 4, ""},
		{"창_꼬리보다_한_칸_앞", 3, "S4"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			rows := slices.Concat(run("P", tt.from, 900, day.Add(time.Duration(tt.from-3)*4*time.Second)), sRows)

			err := validate(t, playlist("S", []rewind.Session{p, s}, rows), 1, nil)

			wantVerdict(t, err, tt.want)
		})
	}
}

// 계승 취소 뒤의 흐름(호출자 ⓒ) — 접두를 빼고 다시 렌더한 소유 회차만의 목록은 통과한다. 직전 회차의
// init 이 안 올라간 목록(S4)으로 본다. 소유 회차만의 목록은 회차 첫 조각(43)부터라 S3 도 통과한다.
func TestValidateRevokedPlaylistPassesWithoutPrefix(t *testing.T) {
	p := withInitUploaded(prefixed(), 0, false)
	wantVerdict(t, validate(t, p, 1, nil), "S4")

	p.Rows = p.Rows[3:]

	wantVerdict(t, validate(t, p, 1, nil), "")
}

// 위반 문장은 사람이 읽는 진단이다 — 처치와 검사 이름이 들어 있어 로그만 보고도 무엇에 걸렸는지 안다.
func TestViolationErrorNamesTreatmentAndCheck(t *testing.T) {
	tests := []struct {
		v    *rewind.Violation
		want []string
	}{
		{&rewind.Violation{Check: "S7", Reason: "seq 72 의 PDT 가 뒤로 갔다"}, []string{"발행 중단", "S7", "seq 72 의 PDT 가 뒤로 갔다"}},
		{&rewind.Violation{Check: "S4", Reason: "직전 회차 P 의 init 이 올라가지 않았다"}, []string{"계승 취소", "S4", "직전 회차 P"}},
	}
	for _, tt := range tests {
		got := tt.v.Error()
		for _, w := range tt.want {
			if !strings.Contains(got, w) {
				t.Errorf("%#v.Error() = %q, want %q 포함", tt.v, got, w)
			}
		}
	}
}
