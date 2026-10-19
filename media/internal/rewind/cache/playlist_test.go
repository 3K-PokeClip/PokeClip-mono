package cache_test

// 세션 필터(계획 「④ 캐시 착수 메모」) — 소유 회차의 목록 = 창 [TailSeq, HeadSeq] 안의 소유 회차 행 +
// 소유 회차가 계승 회차(inherits_session ≠ NULL)일 때만 그 직전 회차(1단계)의 접두 행. TD 분할 회차
// (inherits NULL · base 복사)와 비계승 인접 회차에는 접두를 싣지 않는다 — 실으면 MAP 만 바뀌고 끊김
// 표시가 빠진다(행 단독 표시 술어상 무표시 — ② 의 동치 뮤턴트 두 종과 계획 부기 27 의 전제).

import (
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// sessionP 는 부팅 재구성으로 읽어 온 회차다 — 컷오프 아래의 회차 O 를 계승해 열렸고(base 0 — O 는 컷오프
// 아래라 어느 목록에도 실린 적이 없어 옮겨 받을 증분이 없다), seq 60 이 컷오프를 주조했다. 오프라인으로
// ending 이 됐다.
var sessionP = index.RewindSession{SessionID: "P", State: "ending", EndReason: "offline", InitUploaded: true,
	FirstPDT: at(0), InheritsSession: "O", TargetDuration: 6}

// liveP 는 아직 송출 중인 P 다 — TD 분할은 live 회차에서만 일어난다.
func liveP() index.RewindSession {
	p := sessionP
	p.State, p.EndReason = "live", ""
	return p
}

// reloadP 는 컷오프 60 · 회차 p 의 60..62(③ 확정)를 적재한 캐시다.
func reloadP(t *testing.T, p index.RewindSession) *cache.Cache {
	t.Helper()
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(60, ledgerRun("P", 60, 62, at(0)), withMinSeq(p, 60)))
	return c
}

// openAfterP 는 seq 63 에서 회차 next 를 열고(첫 조각 길이 firstMS) 64·65 를 이어 넣은 뒤 ③ 을 확정한다.
func openAfterP(c *cache.Cache, next index.RewindSession, firstMS int32) {
	c.ApplyInsert(stream, 63, openingOf(next, 63, firstMS))
	pushRun(c, next.SessionID, 64, 65, next.FirstPDT.Add(time.Duration(firstMS)*time.Millisecond))
	uploadRun(c, 63, 65)
}

// playlistRows 는 목록 행을 「회차:seq」 로 적는다.
func playlistRows(pl rewind.Playlist) []string {
	var out []string
	for _, r := range pl.Rows {
		out = append(out, fmt.Sprintf("%s:%d", r.SessionID, r.Seq))
	}
	return out
}

// sessionIDs 는 목록이 싣는 회차 ID 다(순서대로).
func sessionIDs(pl rewind.Playlist) []string {
	var out []string
	for _, s := range pl.Sessions {
		out = append(out, s.ID)
	}
	return out
}

// cache_receives_inherits_on_open(계획 6.3 #36) — 재기동 없이 300초 안에 다시 열린 계승 회차 S 는 개시
// push 로 계승(inherits_session = P)을 세우고, 그 목록 첫머리에 P 의 접두가 실린다. 계승이 개시 때 실리지
// 않으면 DISC-SEQ(= S 의 base)만 승계되고 접두가 빠진 목록이 나간다(계획 2.3 ⑸ — cc 입력 B).
//
// 목록에 싣는 회차 값은 렌더·발행 전 검사가 읽는 여섯 칸을 그대로 옮긴 것이어야 한다. 그 가운데 TD 는 머리
// 한 줄로만, init 게이트는 발행 전 검사로만 드러난다(렌더는 init 게이트를 읽지 않는다). TD 가 0 이면 모든
// 목록 머리가 TARGETDURATION:0 이 되어 발행 전 검사 S5 가 모든 발행을 막고, init 게이트가 늘 참이면 G5
// 그물(validate.go mapProblem)이 init 을 올리지 않은 회차를 통과시킨다(ⓑ r4 cc #1).
//
// base 는 장부 쓰기 규칙으로 0 이 아닌 값에 닿는 이력으로 둔다 — base 는 컷오프 뒤 발행된 목록에서 끊김
// 표시가 빠질 때만 오르고, 조각이 목록에서 빠지려면 그 뒤로 한 시간이 쌓여야 한다. P 는 컷오프 아래 회차
// O 를 계승해 컷오프 행 60 에서 열렸고(base 0) 60..960(4초 901조각)을 쓰는 사이 창 꼬리가 61 로 넘어가
// 목록에서 첫 조각 60 의 표시가 빠졌다(base 1). S 는 P 가 끝나고 120초 뒤 P 를 계승해 그 1 을 옮겨
// 받았다(첫 조각 6.6초 → TD 7). 이 패키지의 다른 회차는 규칙상 base 0 이라 base 칸의 운반은 여기서 가른다.
func TestCacheReceivesInheritsOnOpen(t *testing.T) {
	hourP := withMinSeq(sessionP, 60)
	hourP.DiscontinuityBase = 1
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(60, ledgerRun("P", 60, 960, at(0)), hourP))
	if w := window(t, c); w.TailSeq != 61 || w.HeadSeq != 960 {
		t.Fatalf("픽스처 전제: P 의 창 = [%d, %d], want [61, 960] — 첫 조각 60 이 창 밖이어야 base 1 이 성립한다", w.TailSeq, w.HeadSeq)
	}
	sessionS := index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 1,
		FirstPDT: at(3724 * time.Second), InheritsSession: "P", TargetDuration: 7}
	c.ApplyInsert(stream, 961, openingOf(sessionS, 961, 6600))
	pushRun(c, "S", 962, 963, sessionS.FirstPDT.Add(6600*time.Millisecond))
	uploadRun(c, 961, 963)

	pl, ok := c.Playlist(stream, "S", window(t, c))

	if !ok {
		t.Fatal("Playlist(S) 가 목록을 만들지 못했다")
	}
	// 창은 [64, 963] 이다 — S 의 세 조각(14.6초)에 P 의 897조각을 더해야 한 시간이 찬다.
	if got := playlistRows(pl); len(got) != 900 || got[0] != "P:64" || !reflect.DeepEqual(got[896:], []string{"P:960", "S:961", "S:962", "S:963"}) {
		t.Errorf("행 %d개 = %v … %v, want 900개 = [P:64 …] … [P:960 S:961 S:962 S:963] — 계승 접두가 빠졌다",
			len(got), got[:min(2, len(got))], got[max(0, len(got)-4):])
	}
	wantSessions := []rewind.Session{
		{ID: "P", InheritsSession: "O", DiscontinuityBase: 1, TargetDuration: 6, MinSeq: 60, InitUploaded: true},
		{ID: "S", InheritsSession: "P", DiscontinuityBase: 1, TargetDuration: 7, MinSeq: 961}, // init 미확정(개시 push)
	}
	if !reflect.DeepEqual(pl.Sessions, wantSessions) {
		t.Errorf("회차 =\n%+v\nwant\n%+v", pl.Sessions, wantSessions)
	}
	if pl.StreamID != stream || pl.Owner != "S" || pl.Cutoff != 60 {
		t.Errorf("(StreamID, Owner, Cutoff) = (%q, %q, %d), want (%q, \"S\", 60)", pl.StreamID, pl.Owner, pl.Cutoff, stream)
	}
	body := render(t, c, "S", window(t, c))
	if !strings.Contains(body, "\n#EXT-X-TARGETDURATION:7\n") {
		t.Errorf("본문 머리 TD 가 소유 회차 S 의 7 이 아니다:\n%s", body)
	}
	if !strings.Contains(body, "#EXT-X-DISCONTINUITY-SEQUENCE:1\n") || !strings.Contains(body, "\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\""+baseURL+"/dvr/str/init/S.mp4\"\n") {
		t.Errorf("본문이 S 의 DISC-SEQ 1 과 S 첫 조각 앞 끊김 표시를 싣지 않았다:\n%s", body)
	}
}

// TD 분할 회차와 비계승 인접 회차의 목록에는 앞 회차의 행을 싣지 않는다(④ 착수 메모 「비계승 인접 회차를
// 한 목록에 싣는 형상 금지」). 창에는 P 의 행이 있어도 목록은 소유 회차의 행뿐이다.
//
//	TD 분할   송출 중인 P 에 seq 63 의 7.6초 조각이 들어와 P 의 TD 6 을 넘었다 — 새 회차 Q(base 0 복사 ·
//	         계승 없음 · TD 8)가 열리고 P 는 ending(td_exceeded)이 됐다
//	비계승    P 가 오프라인으로 끝나고 400초 뒤 새 방송 D 가 열렸다 — base 0 · 계승 없음 · 첫 조각 7.6초라 TD 8
func TestPlaylistOfNonInheritingSessionCarriesNoPrefix(t *testing.T) {
	for _, tt := range []struct {
		name string
		p    index.RewindSession
		next index.RewindSession
	}{
		{"TD_분할", liveP(), index.RewindSession{SessionID: "Q", State: "live", FirstPDT: at(12 * time.Second), TargetDuration: 8}},
		{"비계승_인접", sessionP, index.RewindSession{SessionID: "D", State: "live", FirstPDT: at(412 * time.Second), TargetDuration: 8}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := reloadP(t, tt.p)
			openAfterP(c, tt.next, 7600)

			pl, ok := c.Playlist(stream, tt.next.SessionID, window(t, c))

			id := tt.next.SessionID
			if want := []string{id + ":63", id + ":64", id + ":65"}; !ok || !reflect.DeepEqual(playlistRows(pl), want) {
				t.Errorf("Playlist(%s) = (%v, %v), want (%v, true)", id, playlistRows(pl), ok, want)
			}
			if got := sessionIDs(pl); !reflect.DeepEqual(got, []string{id}) {
				t.Errorf("회차 = %v, want [%s]", got, id)
			}
		})
	}
}

// 계승 사슬은 1단계다(계획 2.3 ⑸ⓕ) — O ← P ← S 가 모두 창 안이어도 S 의 목록은 P 의 접두까지만 싣고,
// P 의 목록은 O 의 접두와 P 의 행만 싣는다(뒤 회차 S 의 행은 싣지 않는다).
func TestPlaylistPrefixIsOneStepOfTheInheritanceChain(t *testing.T) {
	c := &cache.Cache{}
	sessions := []index.RewindSession{
		{SessionID: "O", State: "ended", EndReason: "offline", InitUploaded: true, FirstPDT: at(0), InheritsSession: "N", TargetDuration: 6},
		{SessionID: "P", State: "ended", EndReason: "offline", InitUploaded: true, FirstPDT: at(72 * time.Second), InheritsSession: "O", TargetDuration: 6},
		{SessionID: "S", State: "live", InitUploaded: true, FirstPDT: at(144 * time.Second), InheritsSession: "P", TargetDuration: 6},
	}
	rows := append(append(ledgerRun("O", 50, 52, at(0)), ledgerRun("P", 53, 55, at(72*time.Second))...), ledgerRun("S", 56, 58, at(144*time.Second))...)
	load(t, c, stream, ledgerOf(50, rows, sessions...))
	w := window(t, c)

	for _, tt := range []struct {
		owner    string
		rows     []string
		sessions []string
	}{
		{"S", []string{"P:53", "P:54", "P:55", "S:56", "S:57", "S:58"}, []string{"P", "S"}},
		{"P", []string{"O:50", "O:51", "O:52", "P:53", "P:54", "P:55"}, []string{"O", "P"}},
	} {
		pl, ok := c.Playlist(stream, tt.owner, w)
		if !ok || !reflect.DeepEqual(playlistRows(pl), tt.rows) || !reflect.DeepEqual(sessionIDs(pl), tt.sessions) {
			t.Errorf("Playlist(%s) = (%v · %v, %v), want (%v · %v, true)", tt.owner, playlistRows(pl), sessionIDs(pl), ok, tt.rows, tt.sessions)
		}
	}
}

// 소유 회차의 첫 조각이 아직 settled 전이면 목록은 접두만 싣는다 — 발행 전 검사가 그대로 내는 목록이다
// (③ 의 「접두만_실린_목록」). 소유 회차는 행이 없어도 회차 목록에 있다(머리의 TD·DISC-SEQ 가 그 값).
func TestPlaylistOfSessionWithoutSettledRowsCarriesOnlyThePrefix(t *testing.T) {
	c := reloadP(t, sessionP)
	c.ApplyInsert(stream, 63, openingOf(index.RewindSession{SessionID: "S", State: "live",
		FirstPDT: at(132 * time.Second), InheritsSession: "P", TargetDuration: 7}, 63, 6600))

	pl, ok := c.Playlist(stream, "S", window(t, c))

	if want := []string{"P:60", "P:61", "P:62"}; !ok || !reflect.DeepEqual(playlistRows(pl), want) {
		t.Errorf("Playlist(S) = (%v, %v), want (%v, true)", playlistRows(pl), ok, want)
	}
	if got := sessionIDs(pl); !reflect.DeepEqual(got, []string{"P", "S"}) {
		t.Errorf("회차 = %v, want [P S]", got)
	}
}

// 캐시가 회차 축을 모르면 목록을 만들지 않는다 — 머리의 TD·DISC-SEQ 와 MAP 을 정할 수 없다. 거짓은 그
// 스트림의 뷰가 장부를 다 담지 못했다는 뜻이라 되돌림은 적재다(요구 ② — 계획 4.5 A3 결정 6).
//
// 〔J47〕 옛 준비는 「부팅 때 컷오프가 없어 비운 스트림에 부팅 전 회차 P 의 행이 뒤늦게 주조한다」였다 — 이제 그
// 주조 push 는 적재를 요구하고(A3 결정 6 ⑤ · 뮤테이션 110) 적재가 P 의 축을 싣는다. 그래서 행이 참조하는 회차가
// 적재분에 빠진 뷰(장부 불변이 깨진 입력)로 같은 거짓을 단언한다 — 적재분에 P 가 없고 S 는 P 를 계승한다.
func TestPlaylistNeedsTheSessionAxisOfEveryRow(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(70, append(ledgerRun("P", 70, 72, at(0)), ledgerRun("S", 73, 73, at(132*time.Second))...),
		index.RewindSession{SessionID: "S", State: "live", FirstPDT: at(132 * time.Second), InheritsSession: "P",
			TargetDuration: 6, MinSeq: 73}))
	w := window(t, c)

	for _, tt := range []struct {
		stream, owner string
	}{
		{stream, "S"},      // 접두 회차 P 의 축을 모른다
		{stream, "P"},      // 소유 회차 P 의 축을 모른다
		{"nobody", "S"},    // 모르는 스트림
		{stream, "absent"}, // 없는 회차
	} {
		if pl, ok := c.Playlist(tt.stream, tt.owner, w); ok {
			t.Errorf("Playlist(%s, %s) = %v, want 거짓", tt.stream, tt.owner, playlistRows(pl))
		}
	}
}

// 창 밖의 행은 싣지 않는다 — 목록은 [TailSeq, HeadSeq] 안에서만 고른다. 창은 경계가 내는 모양이다: 앞쪽이
// 아직 settled 가 아닌 창(머리가 62 앞에서 멈춤) · 1시간이 차서 꼬리가 앞으로 간 창 · 빈 창(머리 = 컷오프
// − 1). 빈 창이면 행 0 인 목록이다(빈 창 판정은 발행 쪽이 필터 뒤 len(rows)==0 으로 한다 — ⓒ 착수 메모).
func TestPlaylistStaysInsideTheWindow(t *testing.T) {
	c := reloadP(t, sessionP)

	for _, tt := range []struct {
		w    boundary.Window
		want []string
	}{
		{boundary.Window{ScanFrom: 60, TailSeq: 60, HeadSeq: 61}, []string{"P:60", "P:61"}},
		{boundary.Window{ScanFrom: 60, TailSeq: 61, HeadSeq: 62}, []string{"P:61", "P:62"}},
		{boundary.Window{ScanFrom: 60, TailSeq: 60, HeadSeq: 59}, nil},
	} {
		pl, ok := c.Playlist(stream, "P", tt.w)
		if !ok || !reflect.DeepEqual(playlistRows(pl), tt.want) {
			t.Errorf("Playlist(P, [%d, %d]) = (%v, %v), want (%v, true)", tt.w.TailSeq, tt.w.HeadSeq, playlistRows(pl), ok, tt.want)
		}
	}
}

// 목록은 캐시와 저장소를 나누지 않는다 — 발행 워커로 넘겨도 루프의 다음 push 가 이미 넘긴 목록을 바꾸지
// 않는다(설계 3.3 — 캐시 소유 = 루프 단일, 워커는 렌더 입력 값만 받는다). 예: GAP 으로 발행된 행의 ③ 가
// 뒤늦게 확정됐다.
func TestPlaylistDoesNotShareRowsWithTheCache(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(60, append(ledgerRun("P", 60, 61, at(0)), index.RewindRow{Seq: 62, SessionID: "P",
		DurationMS: 4000, PlaybackPDT: at(8 * time.Second), PlaybackS3Key: key(62), IsGap: true}), withMinSeq(sessionP, 60)))
	pl, ok := c.Playlist(stream, "P", window(t, c))
	if !ok || len(pl.Rows) != 3 {
		t.Fatalf("Playlist(P) = (%v, %v), want 60..62(전제)", playlistRows(pl), ok)
	}

	c.ApplyPlaybackUploaded(stream, 62)

	if last := pl.Rows[len(pl.Rows)-1]; last.Seq != 62 || last.PlaybackUploaded {
		t.Errorf("넘긴 목록의 seq %d ③ = %v, want 62 · 거짓 — 캐시의 뒤 push 가 넘긴 목록을 바꿨다", last.Seq, last.PlaybackUploaded)
	}
}

// render_tag_same_before_and_after_reload(계획 6.3 #63) — 같은 장부 이력을 실행 중 push 로 쌓은 캐시와
// 재기동 뒤 적재한 캐시가 같은 목록을 낸다. 계승 회차 S 는 컷오프(12) 전인 seq 10 에서 열렸다 — 회차 최소
// seq 는 장부 값 10 이다(적재 하한 · 컷오프와 무관 — A3 결정 3). 끊김 표시의 「회차 첫 조각」은 컷오프로 잘라
// 12 이고, 표시는 목록 첫 조각 앞에 선다. 컷오프 아래 행으로 판정하면 목록에 표시가 서지 않다가 이미 나간 목록
// 머리에 표시가 새로 선다(DISC-SEQ 도 어긋난다). S 의 base 는 0 이다 — 계승한 P 는 컷오프 아래라 어느 목록에도
// 실린 적이 없어 옮겨 받을 증분이 없다.
//
// 〔J47〕 옛 「실행 중」 캐시는 빈 캐시에 개시(10) · 주조(12) push 로 뷰를 세웠다 — 이제 그 push 는 적재를 요구한다.
// 그래서 실행 중 캐시는 주조 직후의 적재분(컷오프 12 · 행 12)에서 시작해 13 · 14 를 push 로 받고, 재기동한 캐시는
// 12..14 를 한 번에 적재한다.
func TestRenderTagSameBeforeAndAfterReload(t *testing.T) {
	sessionS := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true,
		FirstPDT: at(0), InheritsSession: "P", TargetDuration: 7, MinSeq: 10}

	live := &cache.Cache{}
	load(t, live, stream, ledgerOf(12, pendingRun("S", 12, 12, at(10600*time.Millisecond)), sessionS))
	pushRun(live, "S", 13, 14, at(14600*time.Millisecond))
	uploadRun(live, 12, 14)

	reloaded := &cache.Cache{}
	load(t, reloaded, stream, ledgerOf(12, ledgerRun("S", 12, 14, at(10600*time.Millisecond)), sessionS))

	before := render(t, live, "S", window(t, live))
	after := render(t, reloaded, "S", window(t, reloaded))
	if before != after {
		t.Errorf("재기동 전후 목록이 다르다\n재기동 전:\n%s\n재기동 뒤:\n%s", before, after)
	}
	if want := "#EXT-X-DISCONTINUITY-SEQUENCE:0\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:"; !strings.Contains(before, want) {
		t.Errorf("재기동 전 목록 첫 조각(seq 12 = 계승 회차 S 의 첫 조각) 앞에 끊김 표시가 없다:\n%s", before)
	}
}
