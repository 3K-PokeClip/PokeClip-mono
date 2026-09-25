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

// sessionP 는 부팅 재구성으로 읽어 온 회차다 — 컷오프 아래의 회차 O 를 계승해 열렸고(base 5 는 앞선
// 계승·축출이 쌓인 값을 옮겨 받은 것), seq 60 이 컷오프를 주조했다. 오프라인으로 ending 이 됐다.
var sessionP = index.RewindSession{SessionID: "P", State: "ending", EndReason: "offline", InitUploaded: true,
	DiscontinuityBase: 5, FirstPDT: at(0), InheritsSession: "O", TargetDuration: 6}

// liveP 는 아직 송출 중인 P 다 — TD 분할은 live 회차에서만 일어난다.
func liveP() index.RewindSession {
	p := sessionP
	p.State, p.EndReason = "live", ""
	return p
}

// reloadP 는 컷오프 60 · 회차 p 의 60..62(③ 확정)를 재구성한 캐시다.
func reloadP(p index.RewindSession) *cache.Cache {
	c := &cache.Cache{}
	c.Reload(stream, index.RewindLedger{CutoffSeq: 60, HasCutoff: true,
		Rows: ledgerRun("P", 60, 62, at(0)), Sessions: []index.RewindSession{p}})
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
// 않으면 DISC-SEQ(= S 의 base 5)만 승계되고 접두가 빠진 목록이 나간다(계획 2.3 ⑸ — cc 입력 B).
//
// 목록에 싣는 회차 값은 렌더·발행 전 검사가 읽는 여섯 칸을 그대로 옮긴 것이어야 한다. 그 가운데 TD 는 머리
// 한 줄로만, init 게이트는 발행 전 검사로만 드러난다(렌더는 init 게이트를 읽지 않는다). TD 가 0 이면 모든
// 목록 머리가 TARGETDURATION:0 이 되어 발행 전 검사 S5 가 모든 발행을 막고, init 게이트가 늘 참이면 G5
// 그물(validate.go mapProblem)이 init 을 올리지 않은 회차를 통과시킨다(ⓑ r4 cc #1).
func TestCacheReceivesInheritsOnOpen(t *testing.T) {
	c := reloadP(sessionP)
	sessionS := index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 5,
		FirstPDT: at(132 * time.Second), InheritsSession: "P", TargetDuration: 7}
	openAfterP(c, sessionS, 6600)

	pl, ok := c.Playlist(stream, "S", window(t, c))

	if !ok {
		t.Fatal("Playlist(S) 가 목록을 만들지 못했다")
	}
	if got, want := playlistRows(pl), []string{"P:60", "P:61", "P:62", "S:63", "S:64", "S:65"}; !reflect.DeepEqual(got, want) {
		t.Errorf("행 = %v, want %v — 계승 접두가 빠졌다", got, want)
	}
	wantSessions := []rewind.Session{
		{ID: "P", InheritsSession: "O", DiscontinuityBase: 5, TargetDuration: 6, MinSeq: 60, InitUploaded: true},
		{ID: "S", InheritsSession: "P", DiscontinuityBase: 5, TargetDuration: 7, MinSeq: 63}, // init 미확정(개시 push)
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
	if !strings.Contains(body, "#EXT-X-DISCONTINUITY-SEQUENCE:5\n") || !strings.Contains(body, "\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\""+baseURL+"/dvr/str/init/S.mp4\"\n") {
		t.Errorf("본문이 S 의 DISC-SEQ 5 와 S 첫 조각 앞 끊김 표시를 싣지 않았다:\n%s", body)
	}
}

// TD 분할 회차와 비계승 인접 회차의 목록에는 앞 회차의 행을 싣지 않는다(④ 착수 메모 「비계승 인접 회차를
// 한 목록에 싣는 형상 금지」). 창에는 P 의 행이 있어도 목록은 소유 회차의 행뿐이다.
//
//	TD 분할   송출 중인 P 에 seq 63 의 7.6초 조각이 들어와 P 의 TD 6 을 넘었다 — 새 회차 Q(base 5 복사 ·
//	         계승 없음 · TD 8)가 열리고 P 는 ending(td_exceeded)이 됐다
//	비계승    P 가 오프라인으로 끝나고 400초 뒤 새 방송 D 가 열렸다 — base 0 · 계승 없음 · 첫 조각 7.6초라 TD 8
func TestPlaylistOfNonInheritingSessionCarriesNoPrefix(t *testing.T) {
	for _, tt := range []struct {
		name string
		p    index.RewindSession
		next index.RewindSession
	}{
		{"TD_분할", liveP(), index.RewindSession{SessionID: "Q", State: "live", DiscontinuityBase: 5, FirstPDT: at(12 * time.Second), TargetDuration: 8}},
		{"비계승_인접", sessionP, index.RewindSession{SessionID: "D", State: "live", FirstPDT: at(412 * time.Second), TargetDuration: 8}},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := reloadP(tt.p)
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
		{SessionID: "O", State: "ended", EndReason: "offline", InitUploaded: true, DiscontinuityBase: 5, FirstPDT: at(0), InheritsSession: "N", TargetDuration: 6},
		{SessionID: "P", State: "ended", EndReason: "offline", InitUploaded: true, DiscontinuityBase: 5, FirstPDT: at(72 * time.Second), InheritsSession: "O", TargetDuration: 6},
		{SessionID: "S", State: "live", InitUploaded: true, DiscontinuityBase: 5, FirstPDT: at(144 * time.Second), InheritsSession: "P", TargetDuration: 6},
	}
	rows := append(append(ledgerRun("O", 50, 52, at(0)), ledgerRun("P", 53, 55, at(72*time.Second))...), ledgerRun("S", 56, 58, at(144*time.Second))...)
	c.Reload(stream, index.RewindLedger{CutoffSeq: 50, HasCutoff: true, Rows: rows, Sessions: sessions})
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
	c := reloadP(sessionP)
	c.ApplyInsert(stream, 63, openingOf(index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 5,
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
// 스트림의 뷰가 장부를 다 담지 못했다는 뜻이라 되돌림은 Reload 다. 모르는 회차가 생기는 길: 부팅 때
// 컷오프가 없어 아무것도 싣지 않은 스트림에서, 부팅 전에 열린 회차 P 의 행이 뒤늦게 컷오프를 주조하면
// 그 행들은 들어오지만 P 의 개시는 옛 프로세스가 봤다. 그 P 를 계승한 S 의 목록도 P 의 축이 필요하다.
func TestPlaylistNeedsTheSessionAxisOfEveryRow(t *testing.T) {
	c := &cache.Cache{}
	c.Reload(stream, index.RewindLedger{})
	c.ApplyInsert(stream, 70, seeded(seedOf("P", 70, at(0), 4000)))
	pushRun(c, "P", 71, 72, at(4*time.Second))
	c.ApplyInsert(stream, 73, openingOf(index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 5,
		FirstPDT: at(132 * time.Second), InheritsSession: "P", TargetDuration: 6}, 73, 4000))
	uploadRun(c, 70, 73)
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
	c := reloadP(sessionP)

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
	c.Reload(stream, index.RewindLedger{CutoffSeq: 60, HasCutoff: true, Sessions: []index.RewindSession{sessionP},
		Rows: append(ledgerRun("P", 60, 61, at(0)), index.RewindRow{Seq: 62, SessionID: "P", DurationMS: 4000,
			PlaybackPDT: at(8 * time.Second), PlaybackS3Key: key(62), IsGap: true})})
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
// 재기동 뒤 재구성한 캐시가 같은 목록을 낸다. 계승 회차 S 는 컷오프(12) 전인 seq 10 에서 열렸다 —
// push 는 S 의 MinSeq 를 10 으로, 재구성은 적재 행의 최솟값 12 로 든다. 끊김 표시의 「회차 첫 조각」은
// 컷오프로 잘라 둘 다 12 이고, 표시는 목록 첫 조각 앞에 선다. 컷오프 아래 행으로 판정하면 재기동 전에는
// 표시가 없다가 재기동 뒤 이미 나간 목록 머리에 표시가 새로 선다(DISC-SEQ 도 어긋난다).
func TestRenderTagSameBeforeAndAfterReload(t *testing.T) {
	sessionS := index.RewindSession{SessionID: "S", State: "live", InitUploaded: true, DiscontinuityBase: 5,
		FirstPDT: at(0), InheritsSession: "P", TargetDuration: 7}

	live := &cache.Cache{}
	live.ApplyInsert(stream, 10, openingOf(sessionS, 10, 6600))
	live.ApplyInsert(stream, 11, seedOf("S", 11, at(6600*time.Millisecond), 4000))
	live.ApplyInsert(stream, 12, seeded(seedOf("S", 12, at(10600*time.Millisecond), 4000)))
	pushRun(live, "S", 13, 14, at(14600*time.Millisecond))
	uploadRun(live, 10, 14)

	reloaded := &cache.Cache{}
	reloaded.Reload(stream, index.RewindLedger{CutoffSeq: 12, HasCutoff: true,
		Rows: ledgerRun("S", 12, 14, at(10600*time.Millisecond)), Sessions: []index.RewindSession{sessionS}})

	before := render(t, live, "S", window(t, live))
	after := render(t, reloaded, "S", window(t, reloaded))
	if before != after {
		t.Errorf("재기동 전후 목록이 다르다\n재기동 전:\n%s\n재기동 뒤:\n%s", before, after)
	}
	if want := "#EXT-X-DISCONTINUITY-SEQUENCE:5\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:"; !strings.Contains(before, want) {
		t.Errorf("재기동 전 목록 첫 조각(seq 12 = 계승 회차 S 의 첫 조각) 앞에 끊김 표시가 없다:\n%s", before)
	}
}
