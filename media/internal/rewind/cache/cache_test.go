package cache_test

// 되감기 캐시(설계 3.2 rewind/cache — 장부·GAP 원장·컷오프의 읽기 뷰)의 단위 검증 — POK-195 M4 PR ⓑ
// 커밋 ④. 채우는 길 둘(push 와 Reload)이 장부와 같은 값을 드는지, 경계 입력(boundary.Snapshot)으로
// 무엇을 내주는지를 잰다. 목록 고르기(세션 필터)는 playlist_test.go 가 잰다.
//
// 픽스처는 장부 쓰기 규칙과 맞춘다: 비계승 개시 = base 0 · 계승과 TD 분할 = 직전 회차 base 복사 ·
// TD = max(6, 첫 조각 반올림 초) · 회차 첫 행의 PDT = first_pdt · 컷오프 = 주조한 행의 seq. 기대값은
// 손으로 적은 리터럴이고, 기본값(TD 6 · base 0 · f2 base 3)과 겹치는 값에 판정을 걸지 않는다.
//
// 외부 테스트 패키지로 두는 이유: 소비자(인덱서 push · ⓒ 발행 루프)가 쓰는 공개 계약만으로 성립하는지가
// 곧 캐시의 계약 검증이다.

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

// stream 은 픽스처 스트림이다.
const stream = "str"

// t0 은 픽스처의 기준 벽시계다.
var t0 = time.Date(2026, 9, 24, 12, 0, 0, 0, time.UTC)

// at 은 t0 에서 d 뒤다.
func at(d time.Duration) time.Time { return t0.Add(d) }

// key 는 장부가 INSERT 때 예약하는 ③ 키의 모양이다(playback.SegKey — dvr/{stream}/seg/%06d.m4s).
func key(seq int64) string { return fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq) }

// seedOf 는 회차 sessionID 에 귀속된 seq 행의 커밋 결과(개시 아님)다. 인덱서가 INSERT 뒤에 넘기는 값이다.
func seedOf(sessionID string, seq int64, pdt time.Time, durationMS int32) index.SeedResult {
	return index.SeedResult{SessionID: sessionID, PlaybackPDT: pdt, PlaybackS3Key: key(seq), DurationMS: durationMS}
}

// openingOf 는 seq 행이 회차 s 를 연 커밋 결과다 — 개시가 새 회차 행에 쓴 값(base · 계승 · TD)을 싣고,
// 개시 행의 PDT 가 곧 그 회차의 first_pdt 다.
func openingOf(s index.RewindSession, seq int64, durationMS int32) index.SeedResult {
	res := seedOf(s.SessionID, seq, s.FirstPDT, durationMS)
	res.SessionOpened = true
	res.DiscontinuityBase, res.InheritsSession, res.TargetDuration = s.DiscontinuityBase, s.InheritsSession, s.TargetDuration
	return res
}

// seeded 는 res 의 INSERT 가 컷오프를 주조했다고 표시한다(주조 CTE 가 1행을 넣었다).
func seeded(res index.SeedResult) index.SeedResult {
	res.Seeded = true
	return res
}

// pushRun 은 회차 sessionID 의 seq from..to 를 4초 조각으로 차례로 넣는다. first 는 from 행의 PDT 다.
func pushRun(c *cache.Cache, sessionID string, from, to int64, first time.Time) {
	for seq := from; seq <= to; seq++ {
		c.ApplyInsert(stream, seq, seedOf(sessionID, seq, first.Add(time.Duration(seq-from)*4*time.Second), 4000))
	}
}

// uploadRun 은 seq from..to 의 ③ 확정을 차례로 반영한다(ⓒ 의 Dirty 처리가 할 일을 흉내 낸다).
func uploadRun(c *cache.Cache, from, to int64) {
	for seq := from; seq <= to; seq++ {
		c.ApplyPlaybackUploaded(stream, seq)
	}
}

// ledgerRun 은 재구성이 읽어 오는 회차 sessionID 의 seq from..to 다 — 4초 조각 · ③ 확정.
func ledgerRun(sessionID string, from, to int64, first time.Time) []index.RewindRow {
	var rows []index.RewindRow
	for seq := from; seq <= to; seq++ {
		rows = append(rows, index.RewindRow{
			Seq: seq, SessionID: sessionID, DurationMS: 4000,
			PlaybackPDT: first.Add(time.Duration(seq-from) * 4 * time.Second), PlaybackS3Key: key(seq), PlaybackUploaded: true,
		})
	}
	return rows
}

// seqsOf 는 행들의 seq 다.
func seqsOf(rows []boundary.Row) []int64 {
	var out []int64
	for _, r := range rows {
		out = append(out, r.Seq)
	}
	return out
}

// baseURL 은 픽스처 목록의 URI 앞머리다(발행 설정 — ⓒ 가 채운다).
const baseURL = "https://media.pokeclip.com"

// render 는 캐시가 고른 owner 의 목록(창 w)을 렌더한다.
func render(t *testing.T, c *cache.Cache, owner string, w boundary.Window) string {
	t.Helper()
	pl, ok := c.Playlist(stream, owner, w)
	if !ok {
		t.Fatalf("Playlist(%s) 가 목록을 만들지 못했다", owner)
	}
	pl.BaseURL = baseURL
	body, err := rewind.Render(pl)
	if err != nil {
		t.Fatalf("Render 실패: %v", err)
	}
	return string(body)
}

// extinfBefore 는 본문에서 조각 키 k 의 URI 줄 바로 앞 줄(EXTINF)이다. k 가 없으면 테스트를 멈춘다.
func extinfBefore(t *testing.T, body, k string) string {
	t.Helper()
	lines := strings.Split(body, "\n")
	for i, line := range lines {
		if line == baseURL+"/"+k && i > 0 {
			return lines[i-1]
		}
	}
	t.Fatalf("본문에 %s 가 없다", k)
	return ""
}

// window 는 캐시의 경계 입력으로 창을 센다(처음 계산 — 직전 꼬리 없음).
func window(t *testing.T, c *cache.Cache) boundary.Window {
	t.Helper()
	w, ok := boundary.Compute(c.Snapshot(stream), 0)
	if !ok {
		t.Fatal("컷오프가 없어 창을 셀 수 없다")
	}
	return w
}

// 회차 O(비계승 개시) — seq 40 이 7.6초 조각으로 열어 TD 8 이다. 40·41 은 주조가 미뤄졌고(방증
// 낡음) 42 가 컷오프를 주조했다.
var sessionO = index.RewindSession{SessionID: "O", State: "live", FirstPDT: at(0), TargetDuration: 8}

// cache_receives_every_insert(캐시 몫) — 장부에 커밋된 행은 push 로 캐시에 그대로 들어간다. 컷오프 전
// 행은 싣지 않는다(목록에 실릴 수 없다 — 설계 4.2 ⓐ). 컷오프는 주조한 행의 seq 이고, 그 행부터
// 커밋 값(회차 · 길이 · PDT · ③ 키)을 그대로 든다. 새 행은 ③ 전이고 GAP 원장에 없다.
func TestApplyInsertKeepsCommittedRowsFromTheCutoff(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, openingOf(sessionO, 40, 7600))
	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 42, seeded(seedOf("O", 42, at(11600*time.Millisecond), 4000)))
	c.ApplyInsert(stream, 43, seedOf("O", 43, at(15600*time.Millisecond), 3900))

	snap := c.Snapshot(stream)
	if cutoff, ok := snap.Cutoff(); !ok || cutoff != 42 {
		t.Errorf("Cutoff() = (%d, %v), want (42, true) — 주조한 행의 seq", cutoff, ok)
	}
	want := []boundary.Row{
		{Seq: 42, SessionID: "O", DurationMS: 4000, PlaybackPDT: at(11600 * time.Millisecond), PlaybackS3Key: key(42)},
		{Seq: 43, SessionID: "O", DurationMS: 3900, PlaybackPDT: at(15600 * time.Millisecond), PlaybackS3Key: key(43)},
	}
	if got := snap.RowsFrom(0); !reflect.DeepEqual(got, want) {
		t.Errorf("RowsFrom(0) =\n%+v\nwant\n%+v", got, want)
	}
}

// 개시 push 는 그 회차의 세션 축을 세운다 — 개시가 새 회차 행에 쓴 값(base · 계승 · TD · first_pdt)에
// state live · init 미확정 · 종료 사유 없음이고, MinSeq 는 그 회차의 첫 push 의 seq 다. 컷오프 전에
// 열린 회차도 적는다: 그 회차의 행이 컷오프에 닿으면 컷오프부터는 목록에 실린다. MinSeq 가 컷오프 아래여도
// 된다 — 끊김 표시 술어가 컷오프로 자른다(④ 착수 메모).
func TestApplyInsertRecordsOpenedSessionAxis(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, openingOf(sessionO, 40, 7600))
	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 42, seeded(seedOf("O", 42, at(11600*time.Millisecond), 4000)))

	got, ok := c.Session(stream, "O")
	want := cache.Session{RewindSession: sessionO, MinSeq: 40}
	if !ok || !reflect.DeepEqual(got, want) {
		t.Errorf("Session(O) = (%+v, %v), want (%+v, true)", got, ok, want)
	}
}

// 계승 개시와 TD 분할 개시가 싣는 base·계승·TD 도 그대로 든다. 재구성(부팅)으로 연 스트림에 이어지는
// push 가 운영의 모양이다 — 직전 회차 P 는 장부에서 읽었고(base 5 = 앞선 계승·축출이 쌓인 값), S 는
// 120초 순단 뒤 P 를 계승해 P 의 base 를 옮겨 받았다(첫 조각 6.6초 → TD 7).
func TestApplyInsertRecordsInheritedOpening(t *testing.T) {
	c := &cache.Cache{}
	sessionP := index.RewindSession{SessionID: "P", State: "ending", EndReason: "offline", InitUploaded: true,
		DiscontinuityBase: 5, FirstPDT: at(0), TargetDuration: 6}
	c.Reload(stream, index.RewindLedger{CutoffSeq: 60, HasCutoff: true,
		Rows: ledgerRun("P", 60, 62, at(0)), Sessions: []index.RewindSession{sessionP}})
	sessionS := index.RewindSession{SessionID: "S", State: "live", DiscontinuityBase: 5,
		FirstPDT: at(132 * time.Second), InheritsSession: "P", TargetDuration: 7}

	c.ApplyInsert(stream, 63, openingOf(sessionS, 63, 6600))

	got, ok := c.Session(stream, "S")
	want := cache.Session{RewindSession: sessionS, MinSeq: 63}
	if !ok || !reflect.DeepEqual(got, want) {
		t.Errorf("Session(S) = (%+v, %v), want (%+v, true)", got, ok, want)
	}
	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{60, 61, 62, 63}) {
		t.Errorf("행 = %v, want [60 61 62 63] — 재구성한 행에 push 가 이어 붙는다", got)
	}
}

// 캐시가 장부 행을 놓치면(seq 가 비는 push) 그 스트림의 뷰는 거기서 멈춘다 — 빈 seq 뒤를 이어 붙이면
// 경계가 그 빈자리를 모르고 넘어가 목록 안 seq 가 끊긴다. 놓치는 길: 다른 쓰기자가 seq 42 를 먼저 써서
// 인덱서가 seq 충돌로 커서를 다시 읽고 43 부터 이어 썼다(단일 쓰기자 전제 붕괴 — seq_conflict 재적재).
// 멈춘 뷰는 머리가 장부보다 뒤처지므로 정합성 감시(설계 4.1 (a) 캐시 드리프트)가 Reload 로 되돌린다.
func TestApplyInsertStopsAtAMissedSeq(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, seeded(openingOf(sessionO, 40, 7600)))
	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 43, seedOf("O", 43, at(15600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 44, seedOf("O", 44, at(19600*time.Millisecond), 4000))

	if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, []int64{40, 41}) {
		t.Errorf("행 = %v, want [40 41] — 빈 seq 뒤는 싣지 않는다", got)
	}
}

// RowsFrom 은 seq ≥ from 인 행을 오름차순으로 준다(boundary.Snapshot 계약). 모르는 스트림은 컷오프가
// 없고 행도 없다 — 경계가 목록을 만들지 않는다.
func TestSnapshotRowsFrom(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, seeded(openingOf(sessionO, 40, 7600)))
	pushRun(c, "O", 41, 43, at(7600*time.Millisecond))
	snap := c.Snapshot(stream)

	for _, tt := range []struct {
		from int64
		want []int64
	}{
		{0, []int64{40, 41, 42, 43}},
		{40, []int64{40, 41, 42, 43}},
		{42, []int64{42, 43}},
		{43, []int64{43}},
		{44, nil},
	} {
		if got := seqsOf(snap.RowsFrom(tt.from)); !reflect.DeepEqual(got, tt.want) {
			t.Errorf("RowsFrom(%d) = %v, want %v", tt.from, got, tt.want)
		}
	}

	unknown := c.Snapshot("nobody")
	if _, ok := unknown.Cutoff(); ok {
		t.Error("모르는 스트림에 컷오프가 있다")
	}
	if rows := unknown.RowsFrom(0); len(rows) != 0 {
		t.Errorf("모르는 스트림의 행 = %v, want 없음", seqsOf(rows))
	}
}

// ③ 확정과 GAP 원장 등록은 행의 settled 비트를 세운다 — 경계 머리가 그만큼 나아간다. ③ 가 안 올라간
// 행(홀)은 머리를 세우고, 그 행이 GAP 원장에 오르면 머리가 홀을 건넌다(설계 4.1 트리거 표 · 4.2).
func TestPlaybackUploadAndPublishedGapAdvanceTheHead(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, seeded(openingOf(sessionO, 40, 7600)))
	pushRun(c, "O", 41, 43, at(7600*time.Millisecond))

	if w := window(t, c); w.HeadSeq != 39 {
		t.Fatalf("③ 전 머리 = %d, want 39(빈 창 = 컷오프 − 1)", w.HeadSeq)
	}
	c.ApplyPlaybackUploaded(stream, 40)
	c.ApplyPlaybackUploaded(stream, 41)
	c.ApplyPlaybackUploaded(stream, 43)
	if w := window(t, c); w.HeadSeq != 41 {
		t.Errorf("42 가 홀인 머리 = %d, want 41", w.HeadSeq)
	}
	c.ApplyPublishedGap(stream, 42)
	if w := window(t, c); w.HeadSeq != 43 {
		t.Errorf("42 가 GAP 원장에 오른 뒤 머리 = %d, want 43", w.HeadSeq)
	}
	rows := c.Snapshot(stream).RowsFrom(42)
	if rows[0].PlaybackUploaded || !rows[0].IsGap || !rows[1].PlaybackUploaded || rows[1].IsGap {
		t.Errorf("42·43 = (③ %v · GAP %v)·(③ %v · GAP %v), want (거짓 · 참)·(참 · 거짓)",
			rows[0].PlaybackUploaded, rows[0].IsGap, rows[1].PlaybackUploaded, rows[1].IsGap)
	}
}

// 캐시 뷰에 없는 행의 사실은 아무것도 바꾸지 않는다. 컷오프 전의 유휴 꼬리 seq 40(2초 → 4초 교정)과 그
// ③ 확정은 뷰 밖이다 — 실시간 ③ 는 컷오프와 무관하게 돌아(계약 세그먼트인덱스 5-5 6항) 컷오프 아래 행의
// 확정도 온다. 캐시가 모르는 스트림(재구성도 push 도 없었다)의 사실도 마찬가지다.
func TestFactsOutsideTheViewAreIgnored(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, openingOf(index.RewindSession{SessionID: "O", State: "live", FirstPDT: at(0), TargetDuration: 6}, 40, 2000))
	c.ApplyTailCorrection(stream, 40, 4000)
	c.ApplyInsert(stream, 41, seeded(seedOf("O", 41, at(4*time.Second), 4000)))

	c.ApplyPlaybackUploaded(stream, 40)
	c.ApplyPlaybackUploaded("nobody", 41)
	c.ApplyPublishedGap("nobody", 41)
	c.ApplyTailCorrection("nobody", 41, 4500)

	want := []boundary.Row{{Seq: 41, SessionID: "O", DurationMS: 4000, PlaybackPDT: at(4 * time.Second), PlaybackS3Key: key(41)}}
	if got := c.Snapshot(stream).RowsFrom(0); !reflect.DeepEqual(got, want) {
		t.Errorf("행 = %+v, want %+v", got, want)
	}
	if _, ok := c.Snapshot("nobody").Cutoff(); ok {
		t.Error("모르는 스트림의 사실이 뷰를 만들었다")
	}
}

// 뷰 끝 너머 seq 의 사실도 뷰를 바꾸지 않는다. 놓친 seq 42 때문에 뷰가 41 에서 멈춘 뒤에도
// (TestApplyInsertStopsAtAMissedSeq) 이 인덱서는 커서를 다시 읽고 43·44 를 이어 써서 그 행의 꼬리
// 교정과 ③ 확정이 캐시로 온다. GAP 원장 등록도 같은 모양의 사실이라 함께 넣는다. 셋 다 장부에는
// 들어간 사실이지만 뷰에 없는 행의 것이다 — 뷰의 다른 행(마지막 행 41)을 고치면 안 된다. 41 은 ③
// 전이라 창 머리는 40 이다: 41 의 값이 바뀌면 행 대조가, 41 이 settled 가 되면 목록 본문 대조가
// 드러낸다. 44 는 유휴 꼬리 2.042초로 들어왔다가 4.012초로 교정됐다(playback 실물 조각 길이).
func TestFactsBeyondTheViewEndAreIgnored(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 40, seeded(openingOf(sessionO, 40, 7600)))
	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 43, seedOf("O", 43, at(15600*time.Millisecond), 4000))
	c.ApplyInsert(stream, 44, seedOf("O", 44, at(19600*time.Millisecond), 2042))
	c.ApplyPlaybackUploaded(stream, 40)
	before := render(t, c, "O", window(t, c))

	c.ApplyTailCorrection(stream, 44, 4012)
	c.ApplyPlaybackUploaded(stream, 44)
	c.ApplyPublishedGap(stream, 44)

	want := []boundary.Row{
		{Seq: 40, SessionID: "O", DurationMS: 7600, PlaybackPDT: at(0), PlaybackS3Key: key(40), PlaybackUploaded: true},
		{Seq: 41, SessionID: "O", DurationMS: 4000, PlaybackPDT: at(7600 * time.Millisecond), PlaybackS3Key: key(41)},
	}
	if got := c.Snapshot(stream).RowsFrom(0); !reflect.DeepEqual(got, want) {
		t.Errorf("행 =\n%+v\nwant\n%+v", got, want)
	}
	if after := render(t, c, "O", window(t, c)); after != before {
		t.Errorf("뷰 밖 사실 뒤에 목록이 바뀌었다\n전:\n%s\n후:\n%s", before, after)
	}
}

// 꼬리 교정(UpdateTail 성공)은 행 길이를 고친다 — 경계 꼬리와 목록 EXTINF 가 교정된 길이를 쓴다(계획
// 6.3 #27 의 캐시 몫). 900조각 × 4초 뒤의 유휴 꼬리 seq 900 이 2초로 들어왔다가 4초로 교정되면, 머리부터
// 1시간이 seq 1 에서 정확히 차 창 꼬리가 0 → 1 로 간다. 교정을 반영하지 않으면 꼬리가 0 에 머물고
// EXTINF 가 2.000 으로 나간다. 꼬리의 ③ 는 교정 뒤에 확정된다(유휴 꼬리의 ③ 는 보류 해제 때 요청 — 계획
// 4.2-R 규칙 ①).
func TestTailCorrectionMovesTheWindowAndTheExtinf(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 0, seeded(openingOf(index.RewindSession{SessionID: "O", State: "live", FirstPDT: at(0), TargetDuration: 6}, 0, 4000)))
	pushRun(c, "O", 1, 899, at(4*time.Second))
	uploadRun(c, 0, 899)
	c.ApplyInsert(stream, 900, seedOf("O", 900, at(3600*time.Second), 2000))

	c.ApplyTailCorrection(stream, 900, 4000)
	c.ApplyPlaybackUploaded(stream, 900)

	w := window(t, c)
	if w.TailSeq != 1 || w.HeadSeq != 900 {
		t.Fatalf("창 = [%d, %d], want [1, 900] — 교정된 4초로 센 1시간", w.TailSeq, w.HeadSeq)
	}
	if got := extinfBefore(t, render(t, c, "O", w), key(900)); got != "#EXTINF:4.000," {
		t.Errorf("seq 900 의 EXTINF 줄 = %q, want \"#EXTINF:4.000,\"", got)
	}
}

// 부팅 재구성은 그 스트림의 뷰를 장부에서 읽은 값으로 통째로 갈아 끼운다 — 유일한 되돌림이다(설계 3.2).
// 세션 축 여덟 열이 그대로 돌아오고, MinSeq 는 적재한 행 가운데 그 회차의 최솟값이다. 갈아 끼우기 전에
// 들고 있던 행·회차는 남지 않는다.
func TestReloadReplacesTheStreamView(t *testing.T) {
	c := &cache.Cache{}
	c.ApplyInsert(stream, 0, seeded(openingOf(index.RewindSession{SessionID: "OLD", State: "live", FirstPDT: at(0), TargetDuration: 6}, 0, 4000)))
	sessionB := index.RewindSession{SessionID: "B", State: "ended", EndReason: "td_exceeded", InitUploaded: true,
		DiscontinuityBase: 5, FirstPDT: at(128 * time.Second), InheritsSession: "A", TargetDuration: 6}
	sessionC := index.RewindSession{SessionID: "C", State: "ending", EndReason: "offline",
		DiscontinuityBase: 5, FirstPDT: at(140 * time.Second), TargetDuration: 8}
	rows := ledgerRun("B", 12, 14, at(128*time.Second))
	rows[2].PlaybackUploaded, rows[2].IsGap = false, true
	rows = append(rows,
		index.RewindRow{Seq: 15, SessionID: "C", DurationMS: 7600, PlaybackPDT: at(140 * time.Second), PlaybackS3Key: key(15)},
		index.RewindRow{Seq: 16, DurationMS: 4000})

	c.Reload(stream, index.RewindLedger{CutoffSeq: 12, HasCutoff: true, Rows: rows,
		Sessions: []index.RewindSession{sessionB, sessionC}})

	if cutoff, ok := c.Snapshot(stream).Cutoff(); !ok || cutoff != 12 {
		t.Errorf("Cutoff() = (%d, %v), want (12, true)", cutoff, ok)
	}
	wantRows := []boundary.Row{
		{Seq: 12, SessionID: "B", DurationMS: 4000, PlaybackPDT: at(128 * time.Second), PlaybackS3Key: key(12), PlaybackUploaded: true},
		{Seq: 13, SessionID: "B", DurationMS: 4000, PlaybackPDT: at(132 * time.Second), PlaybackS3Key: key(13), PlaybackUploaded: true},
		{Seq: 14, SessionID: "B", DurationMS: 4000, PlaybackPDT: at(136 * time.Second), PlaybackS3Key: key(14), IsGap: true},
		{Seq: 15, SessionID: "C", DurationMS: 7600, PlaybackPDT: at(140 * time.Second), PlaybackS3Key: key(15)},
		{Seq: 16, DurationMS: 4000},
	}
	if got := c.Snapshot(stream).RowsFrom(0); !reflect.DeepEqual(got, wantRows) {
		t.Errorf("행 =\n%+v\nwant\n%+v", got, wantRows)
	}
	for _, want := range []cache.Session{{RewindSession: sessionB, MinSeq: 12}, {RewindSession: sessionC, MinSeq: 15}} {
		if got, ok := c.Session(stream, want.SessionID); !ok || !reflect.DeepEqual(got, want) {
			t.Errorf("Session(%s) = (%+v, %v), want (%+v, true)", want.SessionID, got, ok, want)
		}
	}
	if _, ok := c.Session(stream, "OLD"); ok {
		t.Error("갈아 끼우기 전의 회차 OLD 가 남았다")
	}
}

// 재구성 입력이 컷오프부터 1씩 이어지지 않으면 이어진 데까지만 싣는다 — 장부는 seq 를 비우지 않으므로
// (커서가 다음 seq 를 1씩 내고 격리된 조각의 seq 는 다음 조각이 쓴다) 끊긴 입력은 장부 불변이 깨진 것이고,
// 끊긴 뒤를 실으면 경계가 빈자리를 모르고 넘어간다. 캐시의 자리 셈(seq → 위치)도 이어짐에 기댄다. 컷오프가
// 없으면 행을 싣지 않는다(되감기를 제공하지 않는 스트림).
func TestReloadKeepsOnlyTheRunFromTheCutoff(t *testing.T) {
	session := index.RewindSession{SessionID: "B", State: "live", TargetDuration: 6}
	for _, tt := range []struct {
		name   string
		ledger index.RewindLedger
		want   []int64
	}{
		{"이어짐", index.RewindLedger{CutoffSeq: 12, HasCutoff: true, Rows: ledgerRun("B", 12, 14, at(0))}, []int64{12, 13, 14}},
		{"중간이_빔", index.RewindLedger{CutoffSeq: 12, HasCutoff: true,
			Rows: append(ledgerRun("B", 12, 13, at(0)), ledgerRun("B", 15, 16, at(12*time.Second))...)}, []int64{12, 13}},
		{"컷오프_행이_없음", index.RewindLedger{CutoffSeq: 12, HasCutoff: true, Rows: ledgerRun("B", 13, 14, at(0))}, nil},
		{"컷오프_없음", index.RewindLedger{Rows: ledgerRun("B", 0, 1, at(0))}, nil},
	} {
		t.Run(tt.name, func(t *testing.T) {
			c := &cache.Cache{}
			tt.ledger.Sessions = []index.RewindSession{session}
			c.Reload(stream, tt.ledger)
			if got := seqsOf(c.Snapshot(stream).RowsFrom(0)); !reflect.DeepEqual(got, tt.want) {
				t.Errorf("행 = %v, want %v", got, tt.want)
			}
		})
	}
}

// 캐시를 조립하지 않은 프로세스(PR ⓑ 의 운영 형상 — 조립은 ⓒ)에서는 nil 이다. nil 이면 모든 메서드가
// 아무것도 하지 않는다 — 인덱서가 INSERT 마다 부르므로 여기서 멈추면 장부 기록이 멈춘다(upload.Dirty
// 와 같은 nil 규약).
func TestNilCacheDoesNothing(t *testing.T) {
	var c *cache.Cache

	c.Reload(stream, index.RewindLedger{CutoffSeq: 40, HasCutoff: true, Rows: []index.RewindRow{{Seq: 40, SessionID: "O",
		DurationMS: 7600, PlaybackPDT: at(0), PlaybackS3Key: key(40), PlaybackUploaded: true}},
		Sessions: []index.RewindSession{sessionO}})
	c.ApplyInsert(stream, 41, seedOf("O", 41, at(7600*time.Millisecond), 2000))
	c.ApplyTailCorrection(stream, 41, 4000)
	c.ApplyPlaybackUploaded(stream, 41)
	c.ApplyPublishedGap(stream, 41)

	if _, ok := c.Snapshot(stream).Cutoff(); ok {
		t.Error("nil 캐시에 컷오프가 있다")
	}
	if rows := c.Snapshot(stream).RowsFrom(0); len(rows) != 0 {
		t.Errorf("nil 캐시의 행 = %v", seqsOf(rows))
	}
	if _, ok := c.Session(stream, "O"); ok {
		t.Error("nil 캐시에 회차가 있다")
	}
	if _, ok := c.Playlist(stream, "O", boundary.Window{ScanFrom: 40, TailSeq: 40, HeadSeq: 41}); ok {
		t.Error("nil 캐시가 목록을 만들었다")
	}
}
