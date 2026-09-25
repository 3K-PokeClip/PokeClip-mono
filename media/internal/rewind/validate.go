package rewind

import (
	"bytes"
	"errors"
	"fmt"
	"math"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// headerLine 은 목록 본문의 첫 줄이다(RFC 8216bis-22 4.4.1.1).
const headerLine = "#EXTM3U\n"

// maxBodyBytes 는 목록 본문의 크기 상한이다 — 설계 4.5.5 S5 의 ≤512KB 를 1KB = 1,024B 로 읽었다.
// 1시간 창을 4초 조각으로 채운 본문이 약 104KiB(g6_f2 106,499B)라 다섯 배쯤 여유가 있다. 조각이
// 아주 짧아 줄이 많아지면 넘는다 — 0.79초 조각으로 1시간을 채우면 이 크기에 닿는다.
const maxBodyBytes = 512 << 10

// pdtSlack 은 PDT 엄격 단조 검사(S7)가 이웃한 두 조각의 겹침으로 봐주는 폭이다 — 설계 4.5.5 S7 의
// PDT_SLACK(1ms). S7 은 발행되는 PDT 줄(ms)로 재므로 이 폭은 발행 해상도의 한 칸이다.
//
// 우리 장부에서 이 여유가 흡수하는 차이는 없다: PDT 는 재귀식 max(PDT(k−1) + duration(k−1), 벽시계(k))
// 라 누적항이 하한이고(index 의 playbackPDT), ms 로 잘라 적어도 조각 길이가 ms 정수라 부등식이 그대로
// 선다(floor(t + n ms) = floor(t) + n ms — 앞 행 PDT 가 push 값과 DB 의 µs 절삭값으로 갈려도 ms 로
// 자르면 같다). 그래서 정상 목록은 여유 없이도 통과한다. 1ms 를 넘는 겹침은 재귀식을 거치지 않은 값이다.
const pdtSlack = time.Millisecond

// ErrHaltPublication 은 발행 중단 처치다 — S4 를 뺀 여섯 검사의 위반. 이번 발행을 내지 않는다.
var ErrHaltPublication = errors.New("rewind: 발행 중단")

// ErrRevokeInheritance 는 계승 취소 처치다 — S4(계승 접두 범위) 위반. 접두를 빼고 다시 렌더하면
// 소유 회차의 목록은 낼 수 있으므로 발행을 멈추지 않는다.
var ErrRevokeInheritance = errors.New("rewind: 계승 취소")

// Violation 은 발행 전 검사 하나에 걸린 것이다. 처치는 검사가 정하고(설계 4.5.5 표의 처치 열)
// 호출자는 errors.Is 로 가른다 — S4 는 ErrRevokeInheritance, 나머지 여섯은 ErrHaltPublication 이다.
type Violation struct {
	// Check 는 걸린 검사다 — 설계 4.5.5 표의 이름 "S1"–"S7".
	Check string
	// Reason 은 무엇이 어긋났는지다 — 사람이 읽는 진단이다.
	Reason string
}

// Error 는 처치 · 검사 · 까닭을 한 줄로 적는다(예: "rewind: 발행 중단: S7 — …").
func (v *Violation) Error() string {
	return fmt.Sprintf("%v: %s — %s", v.Unwrap(), v.Check, v.Reason)
}

// Unwrap 은 처치를 돌려준다 — errors.Is(err, ErrRevokeInheritance) 가 S4 위반에서만 참이 되게.
func (v *Violation) Unwrap() error {
	if v.Check == "S4" {
		return ErrRevokeInheritance
	}
	return ErrHaltPublication
}

// violation 은 검사 check 의 위반을 만든다.
func violation(check, format string, args ...any) error {
	return &Violation{Check: check, Reason: fmt.Sprintf(format, args...)}
}

// Published 는 한 목록 URL 에 이미 발행한 목록의 자기기술이다 — 설계 4.5.5 가 Prev 라 부르는 것.
//
// Gen · PublishedSeq · Terminal 은 발행 세대 규약이 본문 밖 메타데이터로 싣는 값(설계 4.4.2
// pc-gen · pc-pub-seq · pc-terminal)이고, MediaSequence · SegmentCount 는 줄 수 검사(S1)와
// MSN 검사(S2)가 읽는다.
type Published struct {
	// Gen 은 그 발행의 세대(manifest_gen)다.
	Gen int64
	// MediaSequence 는 그 목록의 EXT-X-MEDIA-SEQUENCE — 첫 조각의 seq 다.
	MediaSequence int64
	// PublishedSeq 는 그 목록 마지막 조각의 seq(published_seq)다.
	PublishedSeq int64
	// SegmentCount 는 그 목록의 조각 수다 — 설계의 LineCount(URI 줄 수이지 본문 전체 줄 수가 아니다).
	SegmentCount int
	// Terminal 은 그 목록이 EXT-X-ENDLIST 로 닫혔는가다.
	Terminal bool
}

// Validate 는 렌더한 목록을 발행해도 되는지 가른다 — 설계 4.5.5 발행 전 검사 S1–S7 이다. 목록을
// 객체 저장소에 올리기 직전(설계 4.4.3 P2)에 돈다. 발행된 줄은 고칠 수 없고 조각 URL 은 영구히
// 고정되므로(프로필 4절), 여기서 걸린 목록은 내지 않는다.
//
// p 와 body 는 한 쌍이다 — body 는 Render(p) 가 낸 바이트다. 빈 목록(S3)을 먼저 거른 뒤 본문이 행
// 값을 그대로 적었는지 확인하고(checkBodyMatchesRows), 그다음 값에 관한 검사는 p 를, 본문 형식
// 검사(S5)와 MAP·URI 검사는 body 를 읽는다. gen 은 이 발행이 세대 예약(설계 4.4.3 P0)에서 받은
// 세대이고, prev 는 같은 목록 URL 에 직전에 발행한 목록이다. 발행한 적이 없으면 prev 는 nil 이고,
// 직전과 견주는 검사(S1·S2·S6)는 견줄 것이 없어 통과한다.
//
// 통과면 nil 이다. 걸리면 *Violation 이고 처치는 errors.Is 로 가른다:
//
//	ErrRevokeInheritance  S4 — 계승 접두를 빼고 다시 렌더한다
//	ErrHaltPublication    그 밖의 여섯 — 이번 발행을 내지 않는다
//
// 처음 걸린 검사 하나만 돌려준다. 순서는 빈 목록(S3) → 본문 = 목록 값(S5 — 행과 본문 조각을 짝지어야
// 나머지를 잴 수 있다) → S4(까닭은 checkInheritedPrefix) → S1 → S2 → S3 → S5(형식·TD·MAP) → S6 → S7 이다.
// 본문이 목록 값과 다르면 그 목록이 계승 취소 대상이어도(예: 본문에서만 접두 URI 가 상대) 발행 중단이
// 먼저다 — 접두를 빼고 다시 렌더해도 고쳐지지 않는 렌더·호출자 결함이기 때문이다. 단 MAP 의 있고
// 없음·값은 이 순서 밖이다: 「본문 = 목록 값」 조항은 MAP 줄의 개수·위치만 보고, 있고 없음·값은 S4
// (접두 — 설계 S4 의 MAP 선행 조항)와 S5(소유 회차)가 본다. 그래서 본문에서만 접두 MAP 이 어긋나도
// 계승 취소다.
func Validate(p Playlist, body []byte, gen int64, prev *Published) error {
	if len(p.Rows) == 0 {
		return violation("S3", "실린 조각이 없다 — 범위가 없는 목록이다")
	}
	lines, segs := splitBody(body)
	if err := checkBodyMatchesRows(p, lines, segs); err != nil {
		return err
	}
	if err := checkInheritedPrefix(p, segs); err != nil {
		return err
	}
	if err := checkLineCount(p, prev); err != nil {
		return err
	}
	if err := checkMediaSequence(p, prev); err != nil {
		return err
	}
	if err := checkRange(p); err != nil {
		return err
	}
	if err := checkBody(p, body, lines, segs); err != nil {
		return err
	}
	if err := checkGeneration(p, body, gen, prev); err != nil {
		return err
	}
	return checkPDT(p)
}

// checkBodyMatchesRows 는 S5 의 「본문 = 목록 값」 조항이다 — 본문이 목록 행을 그대로 적었는가.
//
//	조각 수 = 행 수
//	머리 넷   VERSION · TD · MSN · DISC-SEQ 줄이 첫 조각 앞에 한 줄씩 — 값은 6 · 소유 회차 TD · 첫 행 seq ·
//	         소유 회차 base(headerMismatch)
//	조각마다  URI = BaseURL + "/" + playback_s3_key · PDT 줄 = playback_pdt(ms 로 자름) ·
//	         EXTINF 줄 = duration_ms · GAP 줄은 GAP 원장에 있는 행에만 · MAP 줄 개수·위치
//	         (segmentMismatch)
//
// 나머지 검사는 행 값으로 판정한다. 그 판정이 발행되는 바이트에도 참이려면 본문이 행 값을 그대로
// 적었어야 하고, 이 조항이 그것을 확인한다 — 없으면 행과 다른 EXTINF·PDT·URI 를 적은 본문이 검사를
// 통과한다. 검사가 읽는 값 가운데 본문에도 적히는 것은 이 여섯이 전부다: MSN(S1·S2) · URI(S3 가
// 올라갔다고 본 객체) · GAP 줄(S3 가 GAP 원장으로 settled 라고 본 행) · EXTINF(S3·S4 길이 합 · S5 TD) ·
// PDT(S7) · TD(S5 상한). 남는 차이는 해상도 하나다 — PDT 줄은 ms 로 잘려 적히므로 S7 은 ms 로 자른 값으로
// 잰다. 머리 줄과 MAP 줄은 개수·위치까지 본다 — 렌더가 쓰는 자리 한 곳만 읽으면 중복·이동한 줄이 지나간다.
// MAP 의 있고 없음·값과 URI 의 절대성은 S4·S5 가 본문에서 읽고, DISCONTINUITY 는 어느 검사도 읽지 않는다.
// 서식(EXTINF 소수 셋째 자리 · pdtLayout · URI 결합)은 Render 와 같다 — 둘이 갈리면 모든 목록이 여기서
// 멈추므로 곧바로 드러난다(g6_f2 검사).
func checkBodyMatchesRows(p Playlist, lines []string, segs []segmentLines) error {
	if len(segs) != len(p.Rows) {
		return violation("S5", "본문의 조각 %d개가 목록 행 %d개와 다르다 — 본문이 이 목록의 렌더 결과가 아니다",
			len(segs), len(p.Rows))
	}
	if problem := p.headerMismatch(lines, segs[0]); problem != "" {
		return violation("S5", "머리 줄이 목록 값과 다르다 — %s", problem)
	}
	for i, r := range p.Rows {
		runStart := i == 0 || r.SessionID != p.Rows[i-1].SessionID
		if problem := p.segmentMismatch(r, segs[i], runStart); problem != "" {
			return violation("S5", "seq %d 조각이 행과 다르다 — %s", r.Seq, problem)
		}
	}
	return nil
}

// headerMismatch 는 머리 태그 넷이 목록 값과 다른 곳이다("" = 같다). 태그마다 본문 전체에 정확히 한 줄이고
// 그 줄이 첫 조각 앞(first.tags)에 있어야 한다. 「한 줄」의 근거는 VERSION 이 RFC 8216bis-22 4.4.1.2(「MUST
// NOT contain more than one EXT-X-VERSION tag」), 나머지 셋이 4.4.3(「Media Playlist 태그는 종류마다
// 하나」)이다. 「첫 조각 앞」이 RFC 의 MUST 인 것은 MSN·DISC-SEQ 뿐이고(4.4.3.2·4.4.3.3), VERSION·TD 는
// 렌더가 쓰는 자리(목록 머리)를 기준으로 삼았다. 값은 VERSION 6(render.go 와 같은 값 — 계획 부기
// 32) · 소유 회차 TD · 첫 행 seq · 소유 회차 base 다. 소유 회차를 못 찾으면 영값이라 대조가 어긋나 멈춘다.
func (p Playlist) headerMismatch(lines []string, first segmentLines) string {
	owner, _ := p.session(p.Owner)
	for _, want := range []string{
		"#EXT-X-VERSION:6",
		"#EXT-X-TARGETDURATION:" + strconv.FormatInt(int64(owner.TargetDuration), 10),
		"#EXT-X-MEDIA-SEQUENCE:" + strconv.FormatInt(p.Rows[0].Seq, 10),
		"#EXT-X-DISCONTINUITY-SEQUENCE:" + strconv.FormatInt(owner.DiscontinuityBase, 10),
	} {
		tag, _, _ := strings.Cut(want, ":")
		if got := linesWithPrefix(lines, tag+":"); !slices.Equal(got, []string{want}) || !slices.Contains(first.tags, want) {
			return fmt.Sprintf("%s 줄 %q — 첫 조각 앞 한 줄 %q 여야 한다", tag, got, want)
		}
	}
	return ""
}

// segmentMismatch 는 본문 조각 seg 가 행 r 을 그대로 적지 않은 곳이다("" = 그대로 적었다). runStart 는 이
// 조각에서 회차가 바뀌는가다(목록 첫 조각 포함). PDT·EXTINF·GAP 줄은 순서를 보지 않고 모음으로 견준다 —
// 조각 앞 태그는 순서와 무관하게 그 조각에 붙는다(RFC 8216bis-22 4.4.4).
//
// MAP 줄은 개수·위치만 본다: 회차가 이어지는 조각 앞 0줄, 바뀌는 조각 앞 1줄 이하(MAP 은 다음 MAP 까지
// 이어 적용된다 — 4.4.4.5). 있는지·값이 맞는지는 S4(접두)·S5(MAP 도달 가능)가 본다 — 여기서 보면 접두
// MAP 문제가 계승 취소에서 발행 중단으로 뒤집힌다(이 조항이 S4 보다 먼저 돈다).
func (p Playlist) segmentMismatch(r boundary.Row, seg segmentLines, runStart bool) string {
	if want := p.BaseURL + "/" + r.PlaybackS3Key; seg.uri != want {
		return fmt.Sprintf("URI %q — 장부 키로는 %q", seg.uri, want)
	}
	if maps := linesWithPrefix(seg.tags, "#EXT-X-MAP:"); len(maps) > 1 || (!runStart && len(maps) > 0) {
		return fmt.Sprintf("MAP 줄 %d개 — 회차가 이어지는 조각 앞은 0줄, 바뀌는 조각 앞은 1줄까지다", len(maps))
	}
	const gap = "#EXT-X-GAP"
	want := []string{
		"#EXT-X-PROGRAM-DATE-TIME:" + r.PlaybackPDT.UTC().Format(pdtLayout),
		fmt.Sprintf("#EXTINF:%d.%03d,", r.DurationMS/1000, r.DurationMS%1000),
	}
	if r.IsGap {
		want = append(want, gap)
	}
	got := linesWithPrefix(seg.tags, "#EXT-X-PROGRAM-DATE-TIME:", "#EXTINF:", gap)
	slices.Sort(want)
	slices.Sort(got)
	if !slices.Equal(got, want) {
		return fmt.Sprintf("PDT·EXTINF·GAP 줄 %q — 행 값으로는 %q", got, want)
	}
	return ""
}

// linesWithPrefix 는 줄 가운데 prefixes 중 하나로 시작하는 것들이다(본문 순서 그대로).
func linesWithPrefix(lines []string, prefixes ...string) []string {
	var found []string
	for _, line := range lines {
		for _, prefix := range prefixes {
			if strings.HasPrefix(line, prefix) {
				found = append(found, line)
				break
			}
		}
	}
	return found
}

// checkInheritedPrefix 는 S4 계승 접두 범위다 — 목록 첫머리에 실린 다른 회차의 조각(계승 백필)을
// 실어도 되는가. 접두 = 소유 회차의 첫 행 앞에 있는 행들이다. 설계의 여섯 조항을 이렇게 읽는다:
//
//	직전 세션 소유    접두 행이 전부 소유 회차가 계승한 회차(inherits_session)의 것이다 — M4 의 계승
//	                 사슬은 1단계라 그 밖의 회차는 실을 수 없다(계획 2.3 ⑸ⓕ)
//	전부 settled      접두 행이 전부 boundary.Settled 다
//	MAP 선행 · init   접두 첫 조각 앞에 그 회차 init 을 가리키는 MAP 이 있고 그 init 이 올라가 있다
//	절대 URI          접두의 MAP·조각 URI 가 스킴과 호스트를 갖췄다 — 상대 URI 는 목록 URL(회차 축
//	                 /dvr/{stream}/{session}/)에 기대어 풀려 다른 곳을 가리킨다
//	1시간 상한        접두는 머리에서 1시간 창보다 먼 곳에서 시작하지 않는다 — 목록 첫 조각을 빼고 센
//	                 길이 합이 WindowMS 에 못 미친다(boundary 가 창 꼬리를 그렇게 자른다). 1단계 계승
//	                 에서는 창이 이미 지키는 조건이라, 걸리는 것은 창 밖 행을 실은 호출자다
//
// 「본문 = 목록 값」 조항 다음, 나머지 검사보다 먼저 본다. 접두 탓에 S3·S5 도 걸리는 목록(접두 행이
// settled 가 아니거나 접두 MAP 이 없다)이 발행 중단이 아니라 계승 취소로 가야 하기 때문이다 — 접두를
// 빼고 다시 렌더하면 소유 회차의 목록은 낼 수 있다. 소유 회차 행 뒤에 다른 회차 행이 오는 목록은 장부가 만들지 않는다(회차의 행은
// 이어져 있고 늦게 찾은 조각은 옛 회차가 아니라 NULL 로 간다).
func checkInheritedPrefix(p Playlist, segs []segmentLines) error {
	n := 0
	for n < len(p.Rows) && p.Rows[n].SessionID != p.Owner {
		n++
	}
	if n == 0 {
		return nil
	}
	// 소유 회차를 못 찾으면 영값 — 계승한 회차가 없는 것으로 읽혀 접두가 취소된다.
	owner, _ := p.session(p.Owner)
	for _, r := range p.Rows[:n] {
		switch {
		case owner.InheritsSession == "" || r.SessionID != owner.InheritsSession:
			return violation("S4", "접두 seq %d 는 회차 %q 의 것이다 — 소유 회차 %q 가 계승한 회차(%q)가 아니다",
				r.Seq, r.SessionID, p.Owner, owner.InheritsSession)
		case !boundary.Settled(r, p.Cutoff):
			return violation("S4", "접두 seq %d 가 settled 가 아니다(컷오프 %d)", r.Seq, p.Cutoff)
		}
	}
	if problem := p.mapProblem(segs[0], owner.InheritsSession); problem != "" {
		return violation("S4", "접두 seq %d: %s", p.Rows[0].Seq, problem)
	}
	mapped, _ := mapURI(segs[0].tags)
	uris := []string{mapped}
	for _, seg := range segs[:n] {
		uris = append(uris, seg.uri)
	}
	for _, u := range uris {
		if !isAbsoluteURL(u) {
			return violation("S4", "접두 URI %q 가 절대 URL 이 아니다 — 목록 URL 에 기대어 풀린다", u)
		}
	}
	if afterFirst := totalMS(p.Rows[1:]); afterFirst >= boundary.WindowMS {
		return violation("S4", "접두 seq %d 부터 실렸다 — 첫 조각을 빼고도 %dms 라 1시간 창보다 먼 곳에서 시작한다",
			p.Rows[0].Seq, afterFirst)
	}
	return nil
}

// isAbsoluteURL 은 s 가 스킴과 호스트를 갖춘 URL 인가다 — 목록 URL 에 기대어 풀리지 않는다. 스킴
// 종류(http·https)는 보지 않는다 — 베이스 URL 설정 검증(계획 2.3, PR ⓒ)의 몫이다.
func isAbsoluteURL(s string) bool {
	u, err := url.Parse(s)
	return err == nil && u.IsAbs() && u.Host != ""
}

// checkLineCount 는 S1 줄 수 단조다 — 목록은 앞에서 축출한 만큼만 줄이 줄 수 있다.
//
//	len(행) ≥ prev.SegmentCount − 축출 수,   축출 수 = max(0, MSN − prev.MediaSequence)
//
// 축출 수는 따로 받지 않고 MSN 차로 구한다(설계 r7–r13 4.5.5). MSN 은 조각을 하나 뺄 때마다 1씩
// 올라야 하고(RFC 8216bis-22 6.2.2) 목록 안 seq 는 1씩 이어지므로(Render), 첫 줄 seq 가 n 늘었다는
// 것이 곧 앞에서 n 줄이 빠졌다는 뜻이다 — 입력으로 받으면 같은 사실의 출처가 둘이 된다. 이 검사에
// 걸리는 것은 머리 쪽 줄이 사라진 목록이다(조각은 앞에서부터만 뺄 수 있다 — 6.2.2). MSN 이 뒤로 간
// 목록은 S2 가 거른다.
func checkLineCount(p Playlist, prev *Published) error {
	if prev == nil {
		return nil
	}
	evicted := max(0, p.Rows[0].Seq-prev.MediaSequence)
	if int64(len(p.Rows)) < int64(prev.SegmentCount)-evicted {
		return violation("S1", "조각 %d개 < 직전 %d개 − 축출 %d — 머리 쪽 줄이 사라졌다",
			len(p.Rows), prev.SegmentCount, evicted)
	}
	return nil
}

// checkMediaSequence 는 S2 first-MSN 단조다 — MSN(첫 줄 조각의 seq)은 뒤로 가지 않는다(RFC
// 8216bis-22 6.2.2 「MUST NOT decrease」). 뒤로 가면 플레이어가 이미 받은 MSN 에 다른 URI 를 보고
// 재생을 멈춘다(6.3.4 — 설계 RC-9-e).
func checkMediaSequence(p Playlist, prev *Published) error {
	if prev != nil && p.Rows[0].Seq < prev.MediaSequence {
		return violation("S2", "MSN %d 이 직전 발행의 %d 보다 뒤로 갔다", p.Rows[0].Seq, prev.MediaSequence)
	}
	return nil
}

// checkRange 는 S3 범위 일관성이다 — 목록이 settled 행만 싣고, 1시간을 채우거나 첫 행이 그 행
// 회차의 첫 조각인가.
//
//	∀ 행: boundary.Settled(행, cutoff)
//	Σ duration_ms ≥ WindowMS  ∨  첫 행 seq = max(첫 행 회차의 MinSeq, cutoff)
//
// settled 판정은 boundary.Settled 하나다 — 설계의 「모든 줄에 세 값(session_id · playback_pdt ·
// playback_s3_key)」도 그 술어의 항이다. 판정이 두 자리에 있으면 경계와 검사가 서로 다른 접두를 본다.
// 설계의 「또는 세션 전체」는 첫 행 기준으로 읽는다. 창이 1시간에 못 미치는 목록은 둘뿐이다 — 컷오프
// 뒤로 1시간이 아직 안 쌓였거나(창 꼬리 = 컷오프), 목록의 첫 회차(계승이면 접두 회차)가 1시간 안쪽에서
// 시작했다. 둘 다 목록이 그 회차의 첫 조각부터다. 회차 첫 조각을 컷오프로 자르는 것은 끊김 표시
// 술어(HasDiscontinuityTag)와 같다 — 컷오프 아래 행은 목록에 실릴 수 없다(설계 4.2 ⓐ).
func checkRange(p Playlist) error {
	for _, r := range p.Rows {
		if !boundary.Settled(r, p.Cutoff) {
			return violation("S3", "seq %d 가 settled 가 아니다(컷오프 %d)", r.Seq, p.Cutoff)
		}
	}
	if total := totalMS(p.Rows); total < boundary.WindowMS {
		// 첫 행 회차를 못 찾으면 영값(MinSeq 0)이라 첫 조각은 컷오프가 된다 — 렌더가 먼저 거르는 입력이다.
		first, _ := p.session(p.Rows[0].SessionID)
		if want := max(first.MinSeq, p.Cutoff); p.Rows[0].Seq != want {
			return violation("S3", "목록이 %dms 로 1시간이 안 되는데 회차 %q 의 첫 조각 seq %d 가 아니라 seq %d 부터다",
				total, first.ID, want, p.Rows[0].Seq)
		}
	}
	return nil
}

// totalMS 는 행들의 duration_ms 합이다.
func totalMS(rows []boundary.Row) int64 {
	var sum int64
	for _, r := range rows {
		sum += int64(r.DurationMS)
	}
	return sum
}

// checkBody 는 S5 본문 형식·상한이다 — 이 바이트를 그대로 올려도 되는가.
//
//	첫 줄 #EXTM3U(RFC 8216bis-22 4.4.1.1) · 크기 ≤ maxBodyBytes · #PC- 로 시작하는 줄 0개(우리 용도의
//	줄 — 세대 정보는 본문 밖 메타데이터로 간다, 설계 4.4.2)
//	TARGETDURATION ≥ 모든 조각의 반올림 길이(4.4.3.1) — TD 는 소유 회차 값, 조각 길이는 행의 duration_ms
//	다. 본문의 TD 줄이 그 값 한 줄뿐이고 EXTINF 줄이 그 길이라는 것은 checkBodyMatchesRows 가 확인했다
//	(그래서 플레이어가 보는 값과 같다). 반올림이 TD 분할 판정과 같은 식이라 입력도 같은 ms 정수로
//	둔다(roundedSeconds)
//	MAP 도달 가능 — 회차가 바뀌는 조각마다 그 회차 init 을 가리키는 MAP 이 있고 그 init 이 올라가 있다
//
// 「본문 = 목록 값」 조항(조각 수 · 머리 넷 · URI · PDT · EXTINF · GAP · MAP 개수·위치)은 checkBodyMatchesRows
// 가 빈 목록 검사 다음, S4 보다 앞서 본다.
func checkBody(p Playlist, body []byte, lines []string, segs []segmentLines) error {
	switch {
	case !bytes.HasPrefix(body, []byte(headerLine)):
		return violation("S5", "첫 줄이 #EXTM3U 가 아니다")
	case len(body) > maxBodyBytes:
		return violation("S5", "본문 %d바이트가 상한 %d바이트를 넘는다", len(body), maxBodyBytes)
	}
	for _, line := range lines {
		if strings.HasPrefix(line, "#PC-") {
			return violation("S5", "우리 용도의 줄 %q 가 본문에 있다", line)
		}
	}
	owner, _ := p.session(p.Owner)
	td := int64(owner.TargetDuration)
	for _, r := range p.Rows {
		if s := roundedSeconds(r.DurationMS); s > td {
			return violation("S5", "seq %d 의 길이 %dms 는 반올림 %d초로 TARGETDURATION %d 를 넘는다", r.Seq, r.DurationMS, s, td)
		}
	}
	for i, r := range p.Rows {
		if i > 0 && r.SessionID == p.Rows[i-1].SessionID {
			continue // 회차가 이어지는 줄 — 앞의 MAP 이 그대로 적용된다
		}
		if problem := p.mapProblem(segs[i], r.SessionID); problem != "" {
			return violation("S5", "seq %d: %s", r.Seq, problem)
		}
	}
	return nil
}

// roundedSeconds 는 ms 를 가장 가까운 초로 반올림한다 — 딱 가운데(x.5초)는 올린다(6,499ms → 6 ·
// 6,500ms → 7).
//
// TD 분할 판정(media/internal/session/registry.go 의 roundedSeconds — 새 조각의 반올림 길이가 회차
// TD 를 넘으면 새 회차를 연다)과 같은 규칙이어야 한다. 그 판정이 회차를 나누지 않은 조각은 여기서도
// TD 안이어야 하기 때문이다 — 규칙이 갈리면 분할되지 않은 6.5초 조각이 발행을 멈추거나 TD 를 넘은
// 조각이 발행된다. rewind 는 session(DB 층)을 임포트하지 않으므로 같은 식을 여기 다시 적는다. 한쪽을
// 고치면 다른 쪽도 고친다.
func roundedSeconds(durationMS int32) int64 {
	return int64(math.Round(float64(durationMS) / 1000))
}

// mapProblem 은 회차 sessionID 의 첫 조각 seg 에서 그 회차의 init 이 닿지 않는 까닭이다("" = 닿는다).
//
// 닿는다 = 조각 앞에 그 회차 init 을 가리키는 EXT-X-MAP 이 있고(MAP 은 다음 MAP 까지 이어 적용된다 —
// 4.4.4.5, 없으면 앞 회차의 init 으로 풀린다) 그 init 이 올라가 있다(init_uploaded_at). init 이 안
// 올라간 회차의 목록을 막는 것이 설계 G5(init 전 발행 금지)의 발행 직전 그물이다.
func (p Playlist) mapProblem(seg segmentLines, sessionID string) string {
	want, err := p.initURI(sessionID)
	if err != nil {
		return fmt.Sprintf("회차 %q 의 init 키를 만들 수 없다: %v", sessionID, err)
	}
	if got, ok := mapURI(seg.tags); !ok || got != want {
		return fmt.Sprintf("회차 %q 의 첫 조각 앞 MAP 이 %q 다 — 그 회차 init %q 여야 한다", sessionID, got, want)
	}
	if s, _ := p.session(sessionID); !s.InitUploaded {
		return fmt.Sprintf("회차 %q 의 init 이 올라가지 않았다(init_uploaded_at NULL)", sessionID)
	}
	return ""
}

// initURI 는 회차의 init 객체 URL 이다 — 렌더가 MAP 에 쓰는 것과 같은 BaseURL + "/" + init 키.
func (p Playlist) initURI(sessionID string) (string, error) {
	key, err := playback.InitKey(p.StreamID, sessionID)
	if err != nil {
		return "", err
	}
	return p.BaseURL + "/" + key, nil
}

// mapURI 는 태그 줄 가운데 EXT-X-MAP 의 URI 다(없으면 거짓).
func mapURI(tags []string) (string, bool) {
	for _, tag := range tags {
		if v, ok := strings.CutPrefix(tag, `#EXT-X-MAP:URI="`); ok {
			return strings.TrimSuffix(v, `"`), true
		}
	}
	return "", false
}

// checkGeneration 은 S6 세대 단조다 — 발행의 자기기술(설계 4.4.2 메타 pc-gen · pc-pub-seq ·
// pc-terminal)이 직전 발행보다 뒤로 가지 않는가.
//
//	gen > prev.Gen                  세대는 발행마다 새로 예약하므로(P0) 같거나 작으면 낡은 writer 다
//	마지막 행 seq ≥ prev.PublishedSeq 목록 머리는 뒤로 가지 않는다
//	닫힘은 거짓 → 참으로만          닫힌 목록(ENDLIST) 뒤에 열린 목록을 내면 끝난 목록이 다시 열린다
//
// 설계의 네 성분 가운데 MSN 은 S2 와 같은 비교라 S2 가 본다 — 여기서 또 재면 결코 먼저 걸리지 않는
// 갈래가 된다. 머리는 S1 과 겹쳐 보이지만 따로 산다: 직전 발행의 줄 수·MSN 과 published_seq 는 출처가
// 다를 수 있다(캐시의 마지막 발행 / DB). 이번 목록이 닫혔는지는 본문의 ENDLIST 줄로 읽는다.
func checkGeneration(p Playlist, body []byte, gen int64, prev *Published) error {
	if prev == nil {
		return nil
	}
	head := p.Rows[len(p.Rows)-1].Seq
	switch {
	case gen <= prev.Gen:
		return violation("S6", "세대 %d 이 직전 발행의 %d 보다 크지 않다 — 낡은 세대의 발행이다", gen, prev.Gen)
	case head < prev.PublishedSeq:
		return violation("S6", "마지막 조각 seq %d 가 직전 발행의 %d 보다 뒤로 갔다", head, prev.PublishedSeq)
	case prev.Terminal && !hasEndlist(body):
		return violation("S6", "직전 발행이 닫힌 목록(ENDLIST)인데 열린 목록을 낸다")
	}
	return nil
}

// checkPDT 는 S7 PDT 엄격 단조다 — 이웃한 두 조각의 PDT 가 뒤로 가거나 겹치지 않는가. 발행되는 PDT 줄
// (ms 로 자른 값)로 잰다: 원래 값이 1µs 라도 늘었어도 발행 줄 둘이 같으면 발행 목록은 엄격 증가가 아니다.
//
//	PDT[i+1] > PDT[i]  ∧  PDT[i+1] ≥ PDT[i] + duration[i] − pdtSlack      (PDT = ms 로 자른 playback_pdt)
//
// 첫째 항은 RFC 8216bis-22 6.2.1 의 「뒤 조각은 앞 조각과 일부만 겹쳐야 한다」이고, 둘째 항이 겹침
// 폭을 pdtSlack 로 묶는다(RFC 는 1초 미만 겹침까지 허용하지만 우리 PDT 는 재귀식이라 겹치지 않는다).
// 조각이 pdtSlack 보다 길면 둘째 항이 첫째 항을 함의하므로, 첫째 항이 따로 일하는 것은 길이 1ms
// 조각 뒤뿐이다. 앞으로 뛰는 PDT(순단·벽시계 전진)는 하한만 보므로 통과한다.
// 위반은 발행 중단이다(설계 4.5.5 처치 저울): 그 줄을 빼면 seq 가 끊겨 MSN 축이 무너지고, 값을
// 고치는 길은 장부 불변 트리거와 부딪힌다.
func checkPDT(p Playlist) error {
	for i := 1; i < len(p.Rows); i++ {
		prev, cur := p.Rows[i-1], p.Rows[i]
		// 발행되는 PDT 줄로 잰다 — 렌더는 PDT 를 ms 로 잘라 적는다(pdtLayout). 그 줄이 이 절삭값인지는
		// checkBodyMatchesRows 가 확인했다.
		prevPDT, curPDT := prev.PlaybackPDT.Truncate(time.Millisecond), cur.PlaybackPDT.Truncate(time.Millisecond)
		floor := prevPDT.Add(time.Duration(prev.DurationMS)*time.Millisecond - pdtSlack)
		if !curPDT.After(prevPDT) || curPDT.Before(floor) {
			return violation("S7", "seq %d 의 PDT 줄 %s 가 앞 조각 seq %d(PDT 줄 %s · %dms)보다 앞서거나 %s 넘게 겹친다",
				cur.Seq, curPDT.UTC().Format(pdtLayout), prev.Seq, prevPDT.UTC().Format(pdtLayout), prev.DurationMS, pdtSlack)
		}
	}
	return nil
}

// segmentLines 는 본문의 조각 하나다 — URI 줄과 그 앞(직전 URI 줄 다음부터)의 태그 줄들. 첫 조각의
// 태그에는 목록 머리 태그도 섞이지만, 검사는 태그 가운데 필요한 것만 찾으므로 상관없다.
type segmentLines struct {
	tags []string
	uri  string
}

// splitBody 는 본문을 줄로 자르고 URI 줄마다 조각으로 묶는다(RFC 8216bis-22 4.1 — '#' 로 시작하는
// 줄은 태그, 빈 줄은 무시, 나머지가 URI 다). 마지막 URI 줄 뒤의 태그(EXT-X-ENDLIST 등)는 어느
// 조각에도 붙지 않는다.
func splitBody(body []byte) (lines []string, segs []segmentLines) {
	lines = strings.Split(strings.TrimSuffix(string(body), "\n"), "\n")
	start := 0
	for i, line := range lines {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		segs = append(segs, segmentLines{tags: lines[start:i], uri: line})
		start = i + 1
	}
	return lines, segs
}
