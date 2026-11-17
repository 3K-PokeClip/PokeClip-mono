package rewind_test

// VOD 목록의 끊김 표시(EXT-X-DISCONTINUITY) 검사 — POK-549 커밋 C2. 회차 단위 VOD 는 사슬이 여럿인
// 회차를 한 문서에 싣고 사슬 경계마다 끊김 표시를 단다. 그 표시를 발행 전 검사가 두 조항으로 본다
// (설계 S5 (a)(b) · K-d — 둘 다 KindVOD 한정이라 되감기 목록(/dvr)의 검사는 바뀌지 않는다).
//
//	(a) 본문 = 목록 값  조각마다 「#EXT-X-DISCONTINUITY」와 글자 그대로 같은 줄 수가 HasDiscontinuityTag 면
//	                   1, 아니면 0. 접두로 세면 첫 조각 앞 머리 줄 #EXT-X-DISCONTINUITY-SEQUENCE: 를 표시로
//	                   읽어 모든 VOD 가 멈춘다(R2)
//	(b) 세션 경계      i 번째 행의 끊김 표시 ⟺ i > 0 ∧ 세션이 바뀌는 줄. (a)는 본문이 목록 값을 그대로
//	                   적었는지만 보므로, 목록 값(계승 칸) 자체가 경계에 표시를 내지 않으면 못 잡는다(R1)
//
// 목록은 스트림 str 의 두 회차다 — 회차 A 는 seq 10 에서 새로 연 회차(비계승 · base 0 · TD 6)이고, 회차 B 는
// A 가 끝난 뒤 다시 연 회차로 seq 13 부터다. VOD 는 사슬 머리(A)가 소유하고 닫혀 있다(ENDLIST).

import (
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
)

// 위반 문구가 어느 조항의 것인지 가르는 표지다 — 처치(errors.Is)는 둘 다 발행 중단이라 같다.
const (
	clauseBodyDisc    = "DISCONTINUITY 줄" // (a) — 본문 = 목록 값
	clauseVODBoundary = "VOD 세션 경계"       // (b) — 세션 경계 ⟺ 끊김 표시
)

// twoRunVOD 는 회차 A(seq 10..12)와 회차 B(seq 13..15)를 실은 닫힌 VOD 목록이다. bInherits 는 B 의 계승
// 칸이다 — "A" 면 렌더가 seq 13 앞에 끊김 표시를 달고, "" 면 달지 않는다.
func twoRunVOD(bInherits string) rewind.Playlist {
	a := rewind.Session{ID: "A", TargetDuration: 6, MinSeq: 10, InitUploaded: true}
	b := rewind.Session{ID: "B", InheritsSession: bInherits, TargetDuration: 6, MinSeq: 13, InitUploaded: true}
	p := playlist("A", []rewind.Session{a, b}, run("A", 10, 12, day), run("B", 13, 15, day.Add(time.Minute)))
	p.Kind, p.Ended = rewind.KindVOD, true
	return p
}

// wantClause 는 S5 발행 중단이고 위반 문구가 clause 조항의 것인지 본다.
func wantClause(t *testing.T, err error, clause string) {
	t.Helper()
	wantVerdict(t, err, "S5")
	if err != nil && !strings.Contains(err.Error(), clause) {
		t.Errorf("Validate = %v — %q 조항의 위반이어야 한다", err, clause)
	}
}

// (a) 의 손 본문 표 — 렌더 본문에서 끊김 표시 줄만 빼거나 더한다. 목록 값은 그대로라 (b)는 통과하고, 본문이
// 목록 값과 다른 것을 (a)가 잡아야 한다. 마지막 두 경우는 통과다 — 첫 조각 앞의 머리 줄
// #EXT-X-DISCONTINUITY-SEQUENCE:0 은 끊김 표시가 아니다.
//
//	렌더 본문(twoRunVOD("A"))  seq 13 앞에 표시 한 줄 — B 가 A 를 계승한 첫 조각
//	경계_표시_빠짐             그 줄을 지움 → seq 13 은 1줄이어야 하는데 0줄
//	경계_아닌_곳_표시          seq 14 앞에 한 줄 → 0줄이어야 하는데 1줄
//	첫_행_표시                 seq 10 앞(PLAYLIST-TYPE 줄 다음)에 한 줄 → 0줄이어야 하는데 1줄
//	표시_두_줄                 seq 13 앞 줄을 두 번 → 1줄이어야 하는데 2줄
func TestValidateVODDiscontinuityLinesMatchRows(t *testing.T) {
	const disc = "#EXT-X-DISCONTINUITY\n"
	const vodType = "#EXT-X-PLAYLIST-TYPE:VOD\n"
	pdt14 := "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:01:04.000Z\n"
	vod := twoRunVOD("A")
	body := mustRender(t, vod)
	if strings.Count(body, disc) != 1 {
		t.Fatalf("픽스처 본문의 끊김 표시 %d줄, want 1(seq 13 앞):\n%s", strings.Count(body, disc), body)
	}
	single := twoRunVOD("A")
	single.Sessions, single.Rows = single.Sessions[:1], single.Rows[:3]
	tests := []struct {
		name   string
		p      rewind.Playlist
		body   string
		clause string // "" 면 통과
	}{
		{"경계_표시_빠짐", vod, strings.Replace(body, disc, "", 1), clauseBodyDisc},
		{"경계_아닌_곳_표시", vod, strings.Replace(body, pdt14, disc+pdt14, 1), clauseBodyDisc},
		{"첫_행_표시", vod, strings.Replace(body, vodType, vodType+disc, 1), clauseBodyDisc},
		{"표시_두_줄", vod, strings.Replace(body, disc, disc+disc, 1), clauseBodyDisc},
		{"두_회차_렌더_그대로", vod, body, ""},
		{"머리_DISC-SEQ_줄만_있는_한_회차", single, mustRender(t, single), ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.clause != "" && tt.body == body {
				t.Fatal("손 본문이 렌더 본문과 같다 — 고칠 줄을 못 찾았다")
			}
			err := rewind.Validate(tt.p, []byte(tt.body), 1, nil)
			if tt.clause == "" {
				wantVerdict(t, err, "")
				return
			}
			wantClause(t, err, tt.clause)
		})
	}
}

// (b) 독립 교정(K29) — 본문은 렌더 그대로라 (a)는 통과하고, 목록 값 자체가 세션 경계와 끊김 표시를 어긋나게
// 낸다. (b)만 위반하는 경우다.
//
//	계승_칸_빈_두_회차  B 의 계승 칸이 비어 렌더가 seq 13 앞에 표시를 달지 않는다 — 세션이 바뀌는 줄인데 표시
//	                   없음. 회차 경로가 둘째 사슬 머리의 계승 칸을 채우지 않은 결함(R1)이 이 꼴이다
//	첫_행이_계승한_회차 소유 회차 A 가 목록 밖 회차 O 를 계승해 렌더가 seq 10 앞에 표시를 단다 — 목록 첫 줄인데
//	                   표시 있음. 사슬 머리의 계승 칸을 지우지 않은 결함(R17 · M16)이 이 꼴이다
func TestValidateVODDiscontinuityMarksSessionBoundaries(t *testing.T) {
	emptyInherit := twoRunVOD("")
	headInherits := twoRunVOD("A")
	headInherits.Sessions[0].InheritsSession = "O"
	tests := []struct {
		name string
		p    rewind.Playlist
	}{
		{"계승_칸_빈_두_회차", emptyInherit},
		{"첫_행이_계승한_회차", headInherits},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantClause(t, validate(t, tt.p, 1, nil), clauseVODBoundary)
		})
	}
}

// 되감기 목록(KindSliding)은 끊김 표시를 읽지 않는다 — (a)(b)는 KindVOD 한정이라 4초 발행 경로가 바뀌지
// 않는다. 계승 목록 prefixed(접두 P seq 40..42 · 소유 S seq 43..45 — seq 43 앞에 표시 한 줄)의 본문에서 같은
// 손질을 해도 지금처럼 통과한다.
func TestValidateSlidingIgnoresDiscontinuityLines(t *testing.T) {
	const disc = "#EXT-X-DISCONTINUITY\n"
	pdt44 := "#EXT-X-PROGRAM-DATE-TIME:2026-09-24T00:01:04.000Z\n"
	p := prefixed()
	body := mustRender(t, p)
	if strings.Count(body, disc) != 1 || !strings.Contains(body, pdt44) {
		t.Fatalf("픽스처 본문이 가정(표시 한 줄 · seq 44 PDT)과 다르다:\n%s", body)
	}
	tests := []struct {
		name string
		body string
	}{
		{"경계_표시_빠짐", strings.Replace(body, disc, "", 1)},
		{"경계_아닌_곳_표시", strings.Replace(body, pdt44, disc+pdt44, 1)},
		{"표시_두_줄", strings.Replace(body, disc, disc+disc, 1)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			wantVerdict(t, rewind.Validate(p, []byte(tt.body), 1, nil), "")
		})
	}
}
