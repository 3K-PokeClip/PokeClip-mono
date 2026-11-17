package cache_test

// 실시간 push 행의 판(POK-196 PDT 생산자 c7a — 계획 c7a 시험 3 · 5). 캐시 행은 두 입구로 들어온다 — 적재(장부 RewindRow)와
// INSERT push(index.InsertCommit). 둘 다 판을 옮겨야 같은 행이 같은 판 태그로 렌더된다. push 가 판을 버리면 라이브 발행본은
// 태그가 없는데 재기동 · 감사 재렌더(장부)는 태그가 있어, 같은 창의 본문 sha 가 갈리고 플레이어는 판 2 조각을 옛 식으로
// 환산한다.

import (
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// v2 는 판 2 다(InsertCommit.TimingVersion 은 nil = 판 NULL 인 포인터다).
var v2 = func() *int16 { v := int16(2); return &v }()

// pushV2Run 은 pushRun 의 판 2 판이다 — 회차 sessionID 의 seq from..to 를 판 2 커밋 결과로 넣는다.
func pushV2Run(c *cache.Cache, sessionID string, from, to int64, first time.Time) {
	for seq := from; seq <= to; seq++ {
		res := seedOf(sessionID, seq, first.Add(time.Duration(seq-from)*4*time.Second), 4000)
		res.TimingVersion = v2
		c.ApplyInsert(stream, seq, res)
	}
}

func TestRenderTimingTagSameFromPushAndReload(t *testing.T) {
	sessionV := index.RewindSession{SessionID: "V", State: "live", InitUploaded: true, FirstPDT: at(0), TargetDuration: 4, MinSeq: 10}
	head := ledgerRun("V", 10, 10, at(0))
	head[0].TimingVersion = 2

	live := &cache.Cache{}
	load(t, live, stream, ledgerOf(10, head, sessionV))
	pushV2Run(live, "V", 11, 12, at(4*time.Second))
	uploadRun(live, 11, 12)

	rows := ledgerRun("V", 10, 12, at(0))
	for i := range rows {
		rows[i].TimingVersion = 2
	}
	reloaded := &cache.Cache{}
	load(t, reloaded, stream, ledgerOf(10, rows, sessionV))

	before, after := render(t, live, "V", window(t, live)), render(t, reloaded, "V", window(t, reloaded))
	if before != after {
		t.Errorf("push 로 쌓은 목록과 장부로 적재한 목록이 다르다\npush:\n%s\n적재:\n%s", before, after)
	}
	if n := strings.Count(before, "#EXT-X-POKECLIP-TIMING:"); n != 1 || !strings.Contains(before, "init/V.mp4\"\n#EXT-X-POKECLIP-TIMING:2\n") {
		t.Errorf("판 2 목록의 판 태그 = %d줄, want 첫 조각 MAP 뒤 「:2」 한 줄:\n%s", n, before)
	}
}

// push 행이 판을 옮기는가를 바로 본다 — 적재 행 없이 회차 개시 push 로 판 2 회차를 연 뒤 이어 넣은 행의 판이 2 다.
func TestPushCarriesTimingVersion(t *testing.T) {
	c := &cache.Cache{}
	load(t, c, stream, ledgerOf(10, nil))
	opening := openingOf(index.RewindSession{SessionID: "V", FirstPDT: at(0), TargetDuration: 4}, 10, 4000)
	opening.TimingVersion = v2
	c.ApplyInsert(stream, 10, opening)
	c.ApplyInsert(stream, 11, seedOf("V", 11, at(4*time.Second), 4000))

	rows := c.Snapshot(stream).RowsFrom(10)
	if len(rows) != 2 || rows[0].TimingVersion != 2 || rows[1].TimingVersion != 0 {
		t.Errorf("캐시 행 판 = %+v, want seq 10 = 2 · seq 11 = 0(판 NULL push)", rows)
	}
}
