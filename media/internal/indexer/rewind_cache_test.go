package indexer

// 되감기 캐시 push 배선(POK-195 M4 PR ⓑ 커밋 ④ — 계획 4절 PR ⓑ 커밋 순서 ④) — 인덱서는 장부에 커밋된
// 행(INSERT push)과 꼬리 교정(교정 push)을 캐시에 넘긴다. 캐시가 평시 DB 를 묻지 않고 목록을 만들 수
// 있는 통로가 이 둘이다(프로필 4절 「합성은 평시 DB 조회 0」). 캐시 조립은 PR ⓒ 라 운영 인덱서의
// 캐시는 nil 이고, 여기서는 테스트가 직접 끼운다.

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

// cache_receives_every_insert — 장부에 커밋된 행은 빠짐없이 커밋 값 그대로 캐시에 들어가고, 커밋되지 않은
// 조각(poison 으로 격리)은 들어가지 않는다. 주조한 행이 컷오프가 되고 그 앞 행은 싣지 않으며, 개시 행이
// 회차 축(TD·first_pdt)을 세운다.
//
// 이력: seq 0 이 7.6초 조각으로 회차 S1 을 열었다(TD 8) — 방증이 낡아 주조가 미뤄졌다. seq 1 이 컷오프를
// 주조했다. 다음 조각은 22001 로 격리돼 장부에 없고, 그다음 조각이 seq 2 를 쓴다(기존 컷오프 승계).
func TestCacheReceivesEveryInsert(t *testing.T) {
	f := newFixture(t, 7600, 4000, 4000, 4000)
	c := f.withCache()
	f.store.scriptSessions("S1", "S1", "S1")

	f.store.declineAs = index.DeclineStaleCorroboration
	f.mustHandle(f.segment("s1", segName(baseWall, 0), 1000, recording.ReasonHook))
	f.store.seeds = true
	f.mustHandle(f.segment("s1", segName(baseWall, 7600*time.Millisecond), 1000, recording.ReasonHook))
	f.store.seeds, f.store.declineAs = false, index.DeclineSkipped
	f.store.insertErrs = []error{&pgconn.PgError{Code: "22001"}}
	f.mustHandle(f.segment("s1", segName(baseWall, 11600*time.Millisecond), 1000, recording.ReasonHook))
	f.mustHandle(f.segment("s1", segName(baseWall, 15600*time.Millisecond), 1000, recording.ReasonHook))

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
