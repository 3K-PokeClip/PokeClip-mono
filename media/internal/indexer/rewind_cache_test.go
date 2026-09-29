package indexer

// 되감기 캐시 push 배선(POK-195 M4 PR ⓑ 커밋 ④ · PR ⓒ 커밋 5 — 계획 4절 PR ⓑ 커밋 순서 ④ · 4.5 A3 결정 2) —
// 인덱서는 장부에 커밋된 행(INSERT push)과 꼬리 교정(교정 push)을 캐시에 넘긴다. 캐시가 평시 DB 를 묻지 않고
// 목록을 만들 수 있는 통로가 이 둘이다(프로필 4절 「합성은 평시 DB 조회 0」). 적재 중인 스트림의 push 는 캐시의
// 관문이 로그에 적었다가 적재 뒤 재생한다 — 인덱서의 push 호출은 바뀌지 않는다. 캐시 조립은 커밋 7 이라 운영
// 인덱서의 캐시는 nil 이고, 여기서는 테스트가 직접 끼운다. 적재 작업(장부 읽기)은 fake 저장소에 없어 테스트가
// 스냅숏(적재분)을 손으로 준다.

import (
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// withCache 는 픽스처 인덱서에 되감기 캐시를 끼운다(주조 허용).
func (f *fixture) withCache() *cache.Cache {
	f.t.Helper()
	f.opt.SeedEnabled = true
	f.reload()
	c := &cache.Cache{}
	f.ix.rewind = c
	return c
}

// beginDemanded 는 루프가 요구 목록을 비우며 적재를 여는 일(커밋 7)의 흉내다 — streamID 의 요구가 한 건 있어야 하고,
// 그 요구로 BeginLoad 한 토큰을 돌려준다. 적재 작업의 스냅숏은 이 뒤에 뜬다(계획 4.5 A3 증명 Q4).
func beginDemanded(t *testing.T, c *cache.Cache, streamID string) cache.LoadToken {
	t.Helper()
	d := c.DemandedLoads()
	if len(d) != 1 || d[0].StreamID != streamID || !c.Loading(streamID) {
		t.Fatalf("(요구, 적재 중) = (%+v, %v), want ([%s], 참)", d, c.Loading(streamID), streamID)
	}
	return c.BeginLoad(streamID, d[0].Hint)
}

// complete 는 적재 작업의 결과(스냅숏 l)를 루프가 반영하는 일의 흉내다. 적용되지 않으면 멈춘다.
func complete(t *testing.T, c *cache.Cache, streamID string, token cache.LoadToken, l index.RewindLedger) {
	t.Helper()
	if applied, _ := c.CompleteLoad(streamID, token, l); !applied {
		t.Fatalf("CompleteLoad(%s) 가 적재를 적용하지 않았다", streamID)
	}
}

// openedS1 은 seq 0 이 회차 S1 을 열고 컷오프 cutoff 를 주조한 뒤의 장부 스냅숏이다 — rows 는 그 행들이다.
func openedS1(cutoff int64, td int32, rows ...index.RewindRow) index.RewindLedger {
	return index.RewindLedger{CutoffSeq: cutoff, HasCutoff: true, FloorSeq: cutoff, Rows: rows,
		Sessions: []index.RewindSession{{SessionID: "S1", State: "live", FirstPDT: baseWall, TargetDuration: td, MinSeq: 0}}}
}

// cache_receives_every_insert — 장부에 커밋된 행은 빠짐없이 커밋 값 그대로 캐시에 닿고, 커밋되지 않은
// 조각(poison 으로 격리)은 닿지 않는다. 컷오프와 회차 축(TD·first_pdt·최소 seq)은 적재가 싣고, 적재 중에 커밋된
// 행은 로그에 쌓였다가 적재 뒤 재생된다.
//
// 이력: seq 0 이 7.6초 조각으로 회차 S1 을 열었다(TD 8) — 방증이 낡아 주조가 미뤄졌다. 그 개시 push 는 뷰가 없어
// 적재를 요구하고, 그때의 장부에는 컷오프가 없어 부정 표식이 남는다. seq 1 이 컷오프를 주조했다 — 주조 push 가
// 표식을 풀고 적재를 요구한다. 다음 조각은 22001 로 격리돼 장부에 없고, 그다음 조각이 seq 2 를 쓴다(기존 컷오프
// 승계) — 적재 중이라 로그에 쌓이고, 주조 직후의 스냅숏(컷오프 1 · 행 1)에 재생된다.
//
// 〔J47〕 옛 시험은 빈 캐시가 개시 · 주조 push 만으로 컷오프와 회차 축을 세운다고 단언했다 — 뮤테이션 119 의 반대라
// 적재를 끼워 고쳐 썼다(계획 4.5 A3 결정 6 ⑤). 기대값은 그대로다.
func TestCacheReceivesEveryInsert(t *testing.T) {
	f := newFixture(t, 7600, 4000, 4000, 4000)
	c := f.withCache()
	f.store.scriptSessions("S1", "S1", "S1")

	f.store.declineAs = index.DeclineStaleCorroboration
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook))
	complete(t, c, "s1", beginDemanded(t, c, "s1"), index.RewindLedger{}) // 그때의 장부 — 컷오프 없음
	f.store.seeds = true
	f.mustHandle(f.segment("s1", segName(baseWall, 7600*time.Millisecond), 1000, recording.ReasonHook))
	token := beginDemanded(t, c, "s1")
	f.store.seeds, f.store.declineAs = false, index.DeclineSkipped
	f.store.insertErrs = []error{&pgconn.PgError{Code: "22001"}}
	f.mustHandle(f.segment("s1", segName(baseWall, 11600*time.Millisecond), 1000, recording.ReasonHook))
	f.mustHandle(f.segment("s1", segName(baseWall, 15600*time.Millisecond), 1000, recording.ReasonHook))
	complete(t, c, "s1", token, openedS1(1, 8, index.RewindRow{Seq: 1, SessionID: "S1", DurationMS: 4000,
		PlaybackPDT: baseWall.Add(7600 * time.Millisecond), PlaybackS3Key: "dvr/s1/seg/000001.m4s"}))

	snap := c.Snapshot("s1")
	if cutoff, ok := snap.Cutoff(); !ok || cutoff != 1 {
		t.Errorf("Cutoff() = (%d, %v), want (1, true) — seq 1 이 주조했다", cutoff, ok)
	}
	want := []boundary.Row{
		{Seq: 1, SessionID: "S1", DurationMS: 4000, PlaybackPDT: baseWall.Add(7600 * time.Millisecond), PlaybackS3Key: "dvr/s1/seg/000001.m4s"},
		{Seq: 2, SessionID: "S1", DurationMS: 4000, PlaybackPDT: baseWall.Add(15600 * time.Millisecond), PlaybackS3Key: "dvr/s1/seg/000002.m4s"},
	}
	if got := snap.RowsFrom(0); !reflect.DeepEqual(got, want) {
		t.Errorf("캐시 행 =\n%+v\nwant\n%+v", got, want)
	}
	s, ok := c.Session("s1", "S1")
	if !ok || s.MinSeq != 0 || s.TargetDuration != 8 || !s.FirstPDT.Equal(baseWall) || s.State != "live" {
		t.Errorf("Session(S1) = (%+v, %v), want MinSeq 0 · TD 8 · first_pdt %v · live", s, ok, baseWall)
	}
}

// cache_reflects_tail_correction(계획 6.3 #27) — 유휴로 확정돼 2초로 들어간 꼬리가 다시 자라 4초로
// 교정되면(UpdateTail 성공) 캐시의 그 행도 4초가 되고, 목록 EXTINF 가 4.000 이다. 교정을 넘기지 않으면
// 캐시는 2초를 들어 창 길이와 EXTINF 가 장부와 어긋난다(발행된 줄은 고칠 수 없다). 장부가 교정을
// 거절하면(후속 행 · 확정된 행) 캐시도 그대로다 — 캐시가 장부보다 앞서면 안 된다.
//
// 〔J47〕 준비는 첫 조각(개시 · 주조)이 요구한 적재를 그 조각 직후의 스냅숏으로 끝낸 뷰다 — 교정은 그 뒤에 온다.
func TestCacheReflectsTailCorrection(t *testing.T) {
	for _, tt := range []struct {
		name     string
		accepted bool
		want     int32
		extinf   string
	}{
		{"장부가_교정을_받음", true, 4000, "#EXTINF:4.000,"},
		{"장부가_교정을_거절", false, 2000, "#EXTINF:2.000,"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			f := newFixture(t, 2000, 4000) // 첫 측정 2초 · 교정 재측정 4초
			c := f.withCache()
			f.store.seeds = true
			f.store.scriptSessions("S1")
			f.store.updateTailOK = &tt.accepted
			first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
			f.mustHandle(first)
			complete(t, c, "s1", beginDemanded(t, c, "s1"), openedS1(0, 6, index.RewindRow{Seq: 0, SessionID: "S1",
				DurationMS: 2000, PlaybackPDT: baseWall, PlaybackS3Key: "dvr/s1/seg/000000.m4s"}))

			f.makeFile("s1", segName(baseWall, 0), 2500) // 유휴 판정 뒤에도 파일이 자랐다
			first.Reason = recording.ReasonRegrown
			f.mustHandle(first)

			if got := c.Snapshot("s1").RowsFrom(0); len(got) != 1 || got[0].DurationMS != tt.want {
				t.Fatalf("캐시 행 = %+v, want seq 0 · %dms", got, tt.want)
			}
			c.ApplyPlaybackUploaded("s1", 0) // 보류 해제 뒤 ③ 확정(발행 루프가 넘길 사실)
			w, _ := boundary.Compute(c.Snapshot("s1"), 0)
			pl, ok := c.Playlist("s1", "S1", w)
			if !ok {
				t.Fatal("Playlist(S1) 가 목록을 만들지 못했다")
			}
			pl.BaseURL = "https://media.pokeclip.com"
			body, err := rewind.Render(pl)
			if err != nil {
				t.Fatalf("Render 실패: %v", err)
			}
			if !strings.Contains(string(body), "\n"+tt.extinf+"\n") {
				t.Errorf("목록에 %q 줄이 없다:\n%s", tt.extinf, body)
			}
		})
	}
}

// 캐시를 끼우지 않은 인덱서(PR ⓑ 의 운영 형상 — 조립은 ⓒ)도 INSERT 와 꼬리 교정을 그대로 한다 — 캐시
// push 가 nil 에서 멈추면 장부 기록이 멈춘다.
func TestIndexerWithoutCacheStillIndexes(t *testing.T) {
	f := newFixture(t, 2000, 4000)
	f.store.scriptSessions("S1")
	first := f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonIdle)
	f.mustHandle(first)
	f.makeFile("s1", segName(baseWall, 0), 2500)
	first.Reason = recording.ReasonRegrown
	f.mustHandle(first)

	if rows := f.store.records("s1"); len(rows) != 1 || rows[0].DurationMS != 4000 {
		t.Errorf("장부 = %+v, want seq 0 · 4000ms(삽입 뒤 교정)", rows)
	}
}

// indexer_push_during_load_is_logged_and_replayed(계획 6.3 #108 · A3 결정 2 · cx r40 #2 시나리오) — 적재가 열린 뒤
// 인덱서의 실제 advance 경로가 커밋한 행(ApplyInsert)은 적재 중 뷰에 바로 닿지 않고 캐시의 관문이 로그에 적는다. 그
// 행이 없는 스냅숏(BeginLoad 뒤 · 그 행의 커밋 앞)으로 CompleteLoad 하면 재생돼 뷰에 있다. 관문이 없으면 그 행이 옛
// 뷰에 적용되고 CompleteLoad 가 덮어 잃는다 — r40 증명이 틀린 자리다(버퍼가 루프에 있어 인덱서의 직접 호출이
// 건넜다). 인덱서의 push 호출은 그대로다.
func TestIndexerPushDuringLoadIsLoggedAndReplayed(t *testing.T) {
	f := newFixture(t, 4000, 4000)
	c := f.withCache()
	f.store.scriptSessions("S1", "S1")
	f.store.seeds = true
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook)) // seq 0 — 개시 · 주조
	snapshot := openedS1(0, 6, index.RewindRow{Seq: 0, SessionID: "S1", DurationMS: 4000, PlaybackPDT: baseWall,
		PlaybackS3Key: "dvr/s1/seg/000000.m4s"})
	complete(t, c, "s1", beginDemanded(t, c, "s1"), snapshot)
	token := c.BeginLoad("s1", nil) // 요구 적재(예: 드리프트 대조) — 워커의 스냅숏은 이 뒤에 뜬다
	f.store.seeds = false

	f.mustHandle(f.segment("s1", segName(baseWall, 4*time.Second), 1000, recording.ReasonHook)) // seq 1 — 스냅숏 뒤 커밋

	if rows := c.Snapshot("s1").RowsFrom(0); len(rows) != 0 {
		t.Errorf("적재 중 뷰 = %+v, want 읽히지 않음(적재 중 읽기는 「모른다」)", rows)
	}
	complete(t, c, "s1", token, snapshot) // 스냅숏에는 seq 1 이 없다
	want := []boundary.Row{
		{Seq: 0, SessionID: "S1", DurationMS: 4000, PlaybackPDT: baseWall, PlaybackS3Key: "dvr/s1/seg/000000.m4s"},
		{Seq: 1, SessionID: "S1", DurationMS: 4000, PlaybackPDT: baseWall.Add(4 * time.Second), PlaybackS3Key: "dvr/s1/seg/000001.m4s"},
	}
	if got := c.Snapshot("s1").RowsFrom(0); !reflect.DeepEqual(got, want) {
		t.Errorf("재생 뒤 행 =\n%+v\nwant\n%+v", got, want)
	}
}
