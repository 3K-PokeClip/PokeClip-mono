package rewind_test

// discontinuity_base 정의(계획 PR ⓑ · 설계 4.2 「축출 시 DISC-SEQ 증가 의무」 · 4.3 계수)의 정의 테스트 —
// POK-195 M4 PR ⓑ 커밋 ⑤. 목록 앞에서 조각이 빠질 때 소유 회차 base 에 더하는 값(축출 증분)을 잰다.
// 렌더는 base 를 그대로 DISC-SEQ 로 적으므로(커밋 ②) 축출 증분이 그 목록에서 빠진 끊김 표시 수와 같아야
// 남은 조각의 Discontinuity Sequence Number 가 그대로다(RFC 8216bis-22 6.2.2).

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// chainCutoff 는 연쇄 계승 픽스처 스트림(str)의 활성화 컷오프다 — 회차 O 의 셋째 조각이 주조했다.
const chainCutoff = 10

// 연쇄 계승 픽스처의 회차들 — 개시 때 장부에 쓰인 값이다. 장부 쓰기 규칙과 맞춘다: TD = max(6, 첫 조각
// 반올림 초) · 계승 개시(300초 안 재접속)는 직전 회차의 그때 base 를 복사 · TD 분할도 base 를 복사하되
// 계승하지 않는다. MinSeq 는 그 회차에 귀속된 행의 가장 작은 seq 다.
//
//	O  seq  8..13  N 을 계승(N 은 컷오프 아래라 어느 목록에도 없다) · base 5(N 에서 복사)
//	               첫 조각 seq 8 = 7.6초 → TD 8 · seq 8·9 는 컷오프 아래라 목록 첫 조각은 컷오프 행 10
//	P  seq 14..19  O 가 끝나고 120초 뒤 재접속 — O 를 계승 · base 5 · 첫 조각 6.6초 → TD 7
//	S  seq 20..27  P 가 끝나고 120초 뒤 재접속 — P 를 계승 · base 5 · 첫 조각 6.6초 → TD 7
//	               seq 24 앞에서 벽시계가 120초 뛰었다(장부라면 is_discontinuity 가 서는 자리)
//	Q  seq 28..30  seq 28 이 7.6초라 S 의 TD 7 을 넘어 분할 — 계승 없음 · TD 8 · base 7(S 의 그때 값)
//
// P·S 의 base 5 는 O·P 목록이 아직 표시를 내보내기 전에 복사한 값이다 — 이 파일의 O·P 목록은 다음 회차가
// 열린 뒤에야(창이 다음 회차 쪽으로 자란 뒤에야) 표시를 내보낸다. Q 가 열릴 때 S 는 자기 목록에서 표시 둘
// (P·S 의 첫 조각 14·20)을 내보내 base 가 7 이었다.
var (
	chainO = rewind.Session{ID: "O", InheritsSession: "N", DiscontinuityBase: 5, TargetDuration: 8, MinSeq: 8, InitUploaded: true}
	chainP = rewind.Session{ID: "P", InheritsSession: "O", DiscontinuityBase: 5, TargetDuration: 7, MinSeq: 14, InitUploaded: true}
	chainS = rewind.Session{ID: "S", InheritsSession: "P", DiscontinuityBase: 5, TargetDuration: 7, MinSeq: 20, InitUploaded: true}
	chainQ = rewind.Session{ID: "Q", DiscontinuityBase: 7, TargetDuration: 8, MinSeq: 28, InitUploaded: true}
)

// chainRows 는 연쇄 계승 픽스처의 장부 행 가운데 목록에 실릴 수 있는 것(컷오프 이상 seq 10..30, 전부
// settled)이다. 회차 첫 조각만 위 표의 길이이고 나머지는 4초다. PDT 는 앞 조각 끝에 이어지고 재접속(14·20)과
// 벽시계 점프(24) 앞에서만 120초 뛴다.
func chainRows() []boundary.Row {
	sessionOf := func(seq int64) string {
		switch {
		case seq < 14:
			return "O"
		case seq < 20:
			return "P"
		case seq < 28:
			return "S"
		default:
			return "Q"
		}
	}
	firstMS := map[int64]int32{8: 7600, 14: 6600, 20: 6600, 28: 7600}
	var rows []boundary.Row
	pdt := day
	for seq := int64(8); seq <= 30; seq++ {
		if seq == 14 || seq == 20 || seq == 24 {
			pdt = pdt.Add(120 * time.Second)
		}
		d := int32(4000)
		if ms, ok := firstMS[seq]; ok {
			d = ms
		}
		if seq >= chainCutoff {
			rows = append(rows, row(sessionOf(seq), seq, pdt, d))
		}
		pdt = pdt.Add(time.Duration(d) * time.Millisecond)
	}
	return rows
}

// chainList 는 회차 owner 가 소유한 목록 — 연쇄 계승 픽스처의 seq w[0]..w[1] 이다. 회차 목록은 행이 실린
// 직전 회차(계승 접두)와 owner 다(rewind/cache 의 Playlist 와 같은 모양). 세션 필터(소유 회차 + 계승한
// 직전 회차 한 단계) 밖의 행이 끼면 픽스처 결함이라 멈춘다.
func chainList(t *testing.T, owner rewind.Session, w [2]int64) rewind.Playlist {
	t.Helper()
	p := rewind.Playlist{StreamID: "str", BaseURL: baseURL, Cutoff: chainCutoff, Owner: owner.ID}
	hasPrefix := false
	for _, r := range chainRows() {
		switch {
		case r.Seq < w[0] || r.Seq > w[1]:
			continue
		case r.SessionID == owner.InheritsSession:
			hasPrefix = true
		case r.SessionID != owner.ID:
			t.Fatalf("픽스처 결함: %s 목록 [%d, %d] 에 회차 %s 의 seq %d 가 끼었다", owner.ID, w[0], w[1], r.SessionID, r.Seq)
		}
		p.Rows = append(p.Rows, r)
	}
	if hasPrefix {
		for _, s := range []rewind.Session{chainO, chainP, chainS} {
			if s.ID == owner.InheritsSession {
				p.Sessions = append(p.Sessions, s)
			}
		}
	}
	p.Sessions = append(p.Sessions, owner)
	return p
}

// evictedTags 는 축출 증분을 센다. 오류는 이 경우의 전제가 깨진 것이다.
func evictedTags(t *testing.T, prev rewind.Playlist, nextMSN int64) int64 {
	t.Helper()
	n, err := rewind.EvictedDiscontinuityTags(prev, nextMSN)
	if err != nil {
		t.Fatalf("EvictedDiscontinuityTags(%s 목록 seq %d..%d, 새 MSN %d) 오류: %v",
			prev.Owner, prev.Rows[0].Seq, prev.Rows[len(prev.Rows)-1].Seq, nextMSN, err)
	}
	return n
}

// evict_counts_only_tagged_segments(계획 4.2-R RF · 뮤테이션 42) — 축출 증분은 빠진 행 가운데 끊김 표시가
// 선 행(HasDiscontinuityTag)만 센다. 기대값은 위 픽스처 표로 손으로 센 값이다. base 는 개시 값 그대로 둔다 —
// 증분은 base 를 읽지 않는다.
//
// 장부라면 is_discontinuity 가 섰을 행(회차 안 벽시계 점프 seq 24 — 목록 입력에는 그 열이 없다)은 표시가
// 없으므로 빠져도 0 이다(PDT 점프로 표시를 되살려 세면 1). 계승 회차의 첫 조각은 표시가 있어 1 이고, 계승하지
// 않은 회차(TD 분할 Q)의 첫 조각은 0 이다. 컷오프가 회차 중간이면 회차 첫 조각은 컷오프 행이다(O 의 최소
// seq 8 은 컷오프 10 아래).
func TestEvictCountsOnlyTaggedSegments(t *testing.T) {
	tests := []struct {
		name    string
		prev    rewind.Playlist
		nextMSN int64
		want    int64
	}{
		{"회차_안_PDT_점프_행", chainList(t, chainS, [2]int64{22, 27}), 25, 0},
		{"계승_회차_첫_조각", chainList(t, chainS, [2]int64{18, 21}), 21, 1},
		// 새 목록의 첫 조각은 빠지지 않는다 — 표시도 새 목록에 남는다.
		{"계승_회차_첫_조각이_새_목록_첫_조각", chainList(t, chainS, [2]int64{18, 21}), 20, 0},
		// 연쇄 계승이라 P 의 첫 조각 14 와 S 의 첫 조각 20 둘 다 표시가 있다.
		{"두_계승_경계가_한꺼번에", chainList(t, chainS, [2]int64{14, 21}), 21, 2},
		{"비계승_회차_첫_조각", chainList(t, chainQ, [2]int64{28, 30}), 29, 0},
		{"컷오프에_걸린_회차의_첫_조각은_컷오프_행", chainList(t, chainO, [2]int64{10, 13}), 11, 1},
		// 발행이 오래 밀려 새 목록이 직전 목록 끝 뒤에서 시작하면 직전 목록의 표시는 모두 빠진다.
		{"직전_목록이_통째로_빠짐", chainList(t, chainS, [2]int64{18, 21}), 23, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := evictedTags(t, tt.prev, tt.nextMSN)

			if got != tt.want {
				t.Errorf("EvictedDiscontinuityTags(%s 목록 seq %d..%d, 새 MSN %d) = %d, want %d", tt.prev.Owner,
					tt.prev.Rows[0].Seq, tt.prev.Rows[len(tt.prev.Rows)-1].Seq, tt.nextMSN, got, tt.want)
			}
		})
	}
}

// 빠지는 행의 회차가 회차 목록에 없으면 오류다 — 표시 여부를 모르는 행을 건너뛰고 세면 표시를 덜 세어 남은
// 조각의 Discontinuity Sequence Number 가 어긋난다(Render 가 회차 목록에 없는 회차를 거부하는 것과 같은
// 부류). S 목록의 접두 18·19 는 회차 P 의 행인데 회차 목록에서 P 를 뺐다.
func TestEvictRejectsEvictedRowOfUnknownSession(t *testing.T) {
	prev := chainList(t, chainS, [2]int64{18, 21})
	prev.Sessions = []rewind.Session{chainS}

	got, err := rewind.EvictedDiscontinuityTags(prev, 21)

	if err == nil {
		t.Errorf("EvictedDiscontinuityTags(회차 P 를 모르는 S 목록, 새 MSN 21) = %d, nil; want 오류", got)
	}
}

// render_tag_is_row_local 의 증분 절반(계획 4.2-R RF · 뮤테이션 55) — 표시 절반은 짝인
// TestRenderTagIsRowLocal 이 잰다(같은 행은 어느 목록에서도 같은 표시). 여기서는 같은 행이 어느 목록에서
// 빠져도 같은 증분인지 잰다. 행은 연쇄 계승 O←P←S 에서 P 의 첫 조각 seq 14 다 — P 목록(O 접두 + P)에서는
// O 의 조각 뒤에, S 목록(P 접두 + S)에서는 목록 첫 조각에 있다. S 는 P 의 base 를 옮겨 받았으므로 두
// 목록에서 더하는 값이 같아야 한다. 「목록 안 회차 경계」로 세면 S 목록에서 0 이 된다.
func TestEvictedTagIsRowLocal(t *testing.T) {
	fromP := evictedTags(t, chainList(t, chainP, [2]int64{12, 19}), 15)
	fromS := evictedTags(t, chainList(t, chainS, [2]int64{14, 21}), 15)

	if fromP != 1 || fromS != 1 {
		t.Errorf("seq 14 가 빠진 증분 — P 목록 %d · S 목록 %d, want 둘 다 1(같은 행 = 같은 증분)", fromP, fromS)
	}
}

// discontinuitySequenceNumbers 는 렌더 본문을 해석해 조각(seq)마다 Discontinuity Sequence Number 를 센다 —
// DISC-SEQ 값 + 그 조각 URI 줄 앞에 있는 EXT-X-DISCONTINUITY 줄 수(RFC 8216bis-22 6.2.1). 렌더 코드를 쓰지
// 않는 독립 해석(parse)이다.
func discontinuitySequenceNumbers(t *testing.T, body string) map[int64]int64 {
	t.Helper()
	head, segs := mustParse(t, body)
	v, ok := strings.CutPrefix(headLine(head, "#EXT-X-DISCONTINUITY-SEQUENCE:"), "#EXT-X-DISCONTINUITY-SEQUENCE:")
	n, err := strconv.ParseInt(v, 10, 64)
	if !ok || err != nil {
		t.Fatalf("머리에 DISC-SEQ 값이 없다(%q): %v", head, err)
	}
	dsn := map[int64]int64{}
	for _, s := range segs {
		for _, tg := range s.tags {
			if tg == "#EXT-X-DISCONTINUITY" {
				n++
			}
		}
		dsn[s.seq(t)] = n
	}
	return dsn
}

// discontinuity_base 의 정의 — 목록 앞에서 조각이 빠질 때 새 base = 직전 base + EvictedDiscontinuityTags 로
// 렌더하면 두 본문에 함께 있는 조각의 Discontinuity Sequence Number 가 그대로다(RFC 8216bis-22 6.2.2 MUST —
// 표시를 목록에서 없애면 DISC-SEQ 를 올려 남은 조각의 번호가 바뀌지 않게 한다). 번호는 본문을 해석해 센다.
// 그래서 렌더(표시를 다는 자리 · DISC-SEQ = base 그대로)와 축출 증분이 같은 술어를 쓴다는 계약이 발행
// 바이트로 묶인다 — 어느 한쪽이 다른 근거로 세면(렌더가 DISC-SEQ 에 목록 안 표시 수를 더함 · 증분이 목록
// 문맥이나 PDT 점프로 셈) 남은 조각의 번호가 움직인다.
//
// 목록은 연속 발행의 모양이다 — 앞에서 빠지고(MSN 이 는다) 뒤에 붙는다(끝 seq 가 는다). 창 길이는 이
// 불변식의 입력이 아니어서 1시간 창의 900조각 대신 몇 조각짜리 창으로 같은 관계를 만든다. wantTotal 은
// 미끄러지는 동안 빠진 표시 수를 픽스처 표로 손으로 센 값이다 — 불변식이 표시가 빠지는 자리를 실제로
// 지났는지(공허하게 통과하지 않는지) 가드한다.
func TestEvictionKeepsDiscontinuitySequenceNumbers(t *testing.T) {
	tests := []struct {
		name      string
		owner     rewind.Session
		windows   [][2]int64 // 연속 발행한 목록의 [첫 seq, 끝 seq]
		wantTotal int64
	}{
		// S 목록: 접두만(S 첫 조각 미settled) → S 첫 조각이 붙음 → P 첫 조각 14 가 빠짐 → … → S 첫 조각 20 이
		// 빠짐 → 회차 안 점프 24 가 빠짐.
		{"계승_경계를_한_조각씩_넘음", chainS,
			[][2]int64{{14, 19}, {14, 21}, {15, 22}, {19, 23}, {20, 24}, {21, 25}, {23, 27}, {25, 27}}, 2},
		// 발행이 밀려 두 표시(14·20)가 한 번에 빠진다.
		{"두_경계가_한꺼번에_빠짐", chainS, [][2]int64{{14, 21}, {21, 23}, {22, 27}}, 2},
		// P 목록: O 접두의 첫 조각은 컷오프 행 10(O 의 최소 seq 8 은 컷오프 아래) → 그다음 P 첫 조각 14.
		{"컷오프_행과_계승_경계", chainP, [][2]int64{{10, 13}, {10, 15}, {10, 19}, {11, 19}, {14, 19}, {15, 19}, {18, 19}}, 2},
		// TD 분할 회차는 계승하지 않아 첫 조각 28 에 표시가 없다 — 빠져도 base 는 그대로다.
		{"TD_분할_회차", chainQ, [][2]int64{{28, 29}, {28, 30}, {29, 30}}, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			owner := tt.owner
			prev := chainList(t, owner, tt.windows[0])
			prevDSN := discontinuitySequenceNumbers(t, mustRender(t, prev))
			var total int64
			for _, w := range tt.windows[1:] {
				inc := evictedTags(t, prev, w[0])
				owner.DiscontinuityBase += inc
				total += inc
				next := chainList(t, owner, w)
				nextDSN := discontinuitySequenceNumbers(t, mustRender(t, next))

				common := 0
				for seq := w[0]; seq <= w[1]; seq++ {
					before, ok := prevDSN[seq]
					if !ok {
						continue
					}
					common++
					if after := nextDSN[seq]; after != before {
						t.Errorf("[%d, %d] → [%d, %d]: seq %d 의 Discontinuity Sequence Number %d → %d (증분 %d)",
							prev.Rows[0].Seq, prev.Rows[len(prev.Rows)-1].Seq, w[0], w[1], seq, before, after, inc)
					}
				}
				if common == 0 {
					t.Fatalf("픽스처 결함: [%d, %d] → [%d, %d] 에 함께 있는 조각이 없다",
						prev.Rows[0].Seq, prev.Rows[len(prev.Rows)-1].Seq, w[0], w[1])
				}
				prev, prevDSN = next, nextDSN
			}
			if total != tt.wantTotal {
				t.Errorf("빠진 표시 합 = %d, want %d", total, tt.wantTotal)
			}
		})
	}
}
