package index

// 판 NULL 위치(I23) · U 의 PG 통합 시험(POK-196 PDT 생산자 c4a · r6 c4a 시험 목록 973–996 승계 · 계획 4절 c4a 시험
// 7 · 9). 식은 placement.go 머리에 있다. PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"fmt"
	"math/rand/v2"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// placedRow 는 위치 불변식 대조에 쓰는 장부 한 행이다.
type placedRow struct {
	seq     int64
	start   int64
	dur     int32
	pdt     *time.Time
	session *string
	round   *string // 세션의 round_id(비귀속이면 nil)
	legacy  bool    // 출시 이미지 흉내 행(귀속 · PDT NULL — 손 SQL 로 넣음)
}

// placedRows 는 스트림의 전 행을 seq 순으로 읽는다(세션 회차를 함께).
func placedRows(t *testing.T, pool *pgxpool.Pool, stream string) []placedRow {
	t.Helper()
	rows, err := pool.Query(context.Background(), `
		SELECT g.seq, g.start_pts_ms, g.duration_ms, g.playback_pdt, g.session_id, s.round_id,
		       g.local_path LIKE '%/legacy-%'
		  FROM stream_segments g LEFT JOIN stream_sessions s ON s.session_id = g.session_id
		 WHERE g.stream_id = $1 ORDER BY g.seq`, stream)
	if err != nil {
		t.Fatalf("행 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []placedRow
	for rows.Next() {
		var r placedRow
		if err := rows.Scan(&r.seq, &r.start, &r.dur, &r.pdt, &r.session, &r.round, &r.legacy); err != nil {
			t.Fatalf("행 스캔 실패: %v", err)
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("행 조회 중 오류: %v", err)
	}
	return out
}

// putLegacyRow 는 출시 이미지가 쓴 귀속 행(PDT NULL — 배포 경계 · M3 전 행)을 바로 앞 행 끝 위치에 넣는다.
func putLegacyRow(t *testing.T, pool *pgxpool.Pool, stream string, seq int64, wall time.Time, durMS int32, sessionID string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
		                             upload_state, bytes, session_id)
		SELECT $1, $2, COALESCE((SELECT start_pts_ms + duration_ms FROM stream_segments
		                          WHERE stream_id = $1 AND seq < $2 ORDER BY seq DESC LIMIT 1), 0),
		       $3, $4, $5, $6, 'pending', 1000, $7`,
		stream, seq, wall, durMS, S3Key(stream, seq, wall), fmt.Sprintf("/recordings/%s/legacy-%d.mp4", stream, seq),
		sessionID); err != nil {
		t.Fatalf("출시 이미지 행 픽스처 실패 seq=%d: %v", seq, err)
	}
}

// placementHistory 는 무작위 회차 · 비귀속 열을 실물 store 로 쓰는 흉내다.
type placementHistory struct {
	t       *testing.T
	pool    *pgxpool.Pool
	store   Store
	stream  string
	rng     *rand.Rand
	seq     int64
	wall    time.Time
	live    string // 현 live 세션(없으면 "")
	opened  time.Time
	legacys int
	floors  int
}

// jitter 는 µs 를 섞은 벽시계 흔들림이다 — ⌊⌋ 이 항마다 한 번인지(내림 · 반올림)를 가르는 재료다.
func (h *placementHistory) jitter(maxMS int) time.Duration {
	return time.Duration(h.rng.IntN(2*maxMS*1000+1)-maxMS*1000) * time.Microsecond
}

func (h *placementHistory) insert(wall time.Time, durMS int32, src SessionSource) InsertCommit {
	res := mustInsert(h.t, h.store, segAt(h.stream, h.seq, wall, durMS), src)
	h.seq++
	return res
}

// step 은 한 걸음이다. live 면 대개 이어 붙이고 가끔 끊기 · TD 분할 · 출시 이미지 행 · 하한 거부 행을, live 가 없으면
// 비귀속 행이나 개시(재접속 창 안 · 밖 · 벽시계 역행)를 고른다.
func (h *placementHistory) step() {
	dur := int32(3500 + h.rng.IntN(1001))
	switch p := h.rng.Float64(); {
	case h.live != "" && p < 0.04:
		if _, err := EndLive(context.Background(), h.pool, h.stream, testClose, noopEnqueue); err != nil {
			h.t.Fatalf("EndLive 실패: %v", err)
		}
		h.live = ""
	case h.live != "" && p < 0.08:
		putLegacyRow(h.t, h.pool, h.stream, h.seq, h.wall, dur, h.live)
		h.seq++
		h.wall = h.wall.Add(time.Duration(dur)*time.Millisecond + h.jitter(80))
		h.legacys++
	case h.live != "" && p < 0.11:
		h.insert(h.opened.Add(-2*time.Second-h.jitter(500)), dur, liveIngress()) // 귀속 하한 거부(벽시계 유지)
		h.floors++
	case h.live != "":
		if h.rng.IntN(25) == 0 {
			dur = 7000 // TD 분할 — 회차는 이어 간다
		}
		gap := h.jitter(80)
		if h.rng.IntN(15) == 0 {
			gap = time.Duration(2+h.rng.IntN(8))*time.Second + h.jitter(500) // 순단 — 벽시계가 이긴다
		}
		h.wall = h.wall.Add(gap)
		res := h.insert(h.wall, dur, liveIngress())
		if res.SessionOpened {
			h.live, h.opened = res.SessionID, h.wall
		}
		h.wall = h.wall.Add(time.Duration(dur) * time.Millisecond)
	case p < 0.5:
		h.insert(h.wall, dur, currentOnly())
		h.wall = h.wall.Add(time.Duration(dur)*time.Millisecond + h.jitter(80))
	default:
		switch h.rng.IntN(3) {
		case 0:
			h.wall = h.wall.Add(h.jitter(80)) // 재접속 창 안 — 계승
		case 1:
			h.wall = h.wall.Add(6*time.Minute + h.jitter(500)) // 창 밖 — 새 회차
		default:
			// 벽시계 역행 — 바로 앞 조각 끝보다 이르되 그 조각 시작보다는 늦다. 인덱서 H3 가 「꼬리 시각보다 늦은 조각」 만 넘기므로
			// 그보다 이른 입력은 실제로 오지 않고, 만들면 직전 회차(started_at 최대)가 seq 순과 갈려 흉내가 깨진다.
			h.wall = h.wall.Add(-time.Duration(1+h.rng.IntN(3000)) * time.Millisecond)
		}
		res := h.insert(h.wall, dur, liveIngress())
		h.live, h.opened = res.SessionID, h.wall
		h.wall = h.wall.Add(time.Duration(dur) * time.Millisecond)
	}
}

// checkPlacement 는 I10 · I23 · 회차 안 PDT 단조를 대조하고 대조한 회차 수를 돌려준다.
//   - I10: seq 순 start_pts 비감소(모든 행).
//   - I23: 회차 안 PDT 행의 ⌊PDT⌋ − start 폭 0.
//   - 회차 안 PDT: 잇단 PDT 행이 PDT(k) ≥ PDT(j) + dur(j).
//
// 뒤 둘은 그 회차의 PDT 행 범위에 출시 이미지 행이 낀 회차를 뺀다 — 그 뒤 행은 기저 PDT 가 NULL 이라 PDT = 벽시계이고
// (store.go 의 기저 행 NULL 방어 · 지금 식 그대로) 뒤 항이 이겨 폭이 바뀔 수 있다. 그 회차의 I10 은 본다.
func checkPlacement(t *testing.T, rows []placedRow) (rounds int) {
	t.Helper()
	byRound := map[string][]placedRow{}
	for i, r := range rows {
		if i > 0 && r.start < rows[i-1].start {
			t.Errorf("I10 깨짐: seq %d start %d < seq %d start %d", r.seq, r.start, rows[i-1].seq, rows[i-1].start)
		}
		if r.round != nil && r.pdt != nil {
			byRound[*r.round] = append(byRound[*r.round], r)
		}
	}
	tainted := func(first, last int64) bool {
		for _, r := range rows {
			if r.legacy && r.seq > first && r.seq <= last {
				return true
			}
		}
		return false
	}
	for round, rs := range byRound {
		if tainted(rs[0].seq, rs[len(rs)-1].seq) {
			continue
		}
		rounds++
		width := rs[0].pdt.UnixMilli() - rs[0].start
		for i, r := range rs[1:] {
			if w := r.pdt.UnixMilli() - r.start; w != width {
				t.Errorf("I23 깨짐: 회차 %s seq %d ⌊PDT⌋ − start = %d, 회차 첫 PDT 행 폭 %d", round, r.seq, w, width)
			}
			if prev := rs[i]; r.pdt.Before(prev.pdt.Add(time.Duration(prev.dur) * time.Millisecond)) {
				t.Errorf("회차 %s PDT 역행: seq %d %v < seq %d %v + %dms", round, r.seq, r.pdt, prev.seq, prev.pdt, prev.dur)
			}
		}
	}
	return rounds
}

// I23 · I10 · 회차 안 PDT 단조(판 NULL · r6 984–985 승계) — 무작위 회차 · 비귀속 열 10⁴ 행(씨앗 넷 × 2500)에서 회차 안
// ⌊PDT⌋ − start_pts 폭이 0 이고 start_pts 가 seq 순으로 단조다. 열에는 세션 개시 때 벽시계 역행 · 출시 이미지 행(기저
// PDT NULL) 뒤 벽시계 PDT 처럼 위치 식 max 의 뒤 항이 이기는 갈래가 들어 있다. 잡는 결함: 뒤 항을 빼면 start 가 뒤로
// 간다 · U 를 빼면 비귀속 행을 건넌 회차의 폭이 바뀐다 · ⌊⌋ 를 반올림으로 바꾸면 µs 벽시계에서 폭이 흔들린다.
func TestLegacyPlacementInvariantsHoldOverRandomHistory(t *testing.T) {
	for _, seed := range []uint64{1, 2, 3, 4} {
		t.Run(fmt.Sprintf("씨앗_%d", seed), func(t *testing.T) {
			pool := newTestPool(t)
			h := &placementHistory{t: t, pool: pool, store: newSessionStore(pool), stream: sessionStream(fmt.Sprintf("i23-%d", seed)),
				rng: rand.New(rand.NewPCG(seed, 23)), wall: sessionBase.Add(321 * time.Microsecond)}
			start := time.Now()
			for h.seq < 2500 {
				h.step()
			}
			rows := placedRows(t, pool, h.stream)
			rounds := checkPlacement(t, rows)
			t.Logf("행 %d · 대조 회차 %d · 출시 이미지 행 %d · 하한 거부 %d · 소요 %v", len(rows), rounds, h.legacys, h.floors, time.Since(start))
			if rounds < 5 || h.legacys == 0 || h.floors == 0 {
				t.Errorf("열이 갈래를 덮지 못했다: 회차 %d · 출시 이미지 행 %d · 하한 거부 %d", rounds, h.legacys, h.floors)
			}
		})
	}
}

// 위치 식의 뒤 항(q.start + q.dur)이 이기는 갈래를 하나로 고정한다 — 기저 행 PDT 가 NULL(출시 이미지 행)이라 PDT = 벽시계
// 인데, 앵커(그 앞 PDT 행)보다 벽시계가 늦게 가지 않아 앞 항이 바로 앞 행 끝보다 작다.
func TestLegacyPlacementBackTermWinsAfterLegacyRow(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	stream := sessionStream("i23-back")
	head := mustInsert(t, store, segAt(stream, 0, sessionBase, 4000), liveIngress())
	putLegacyRow(t, pool, stream, 1, sessionBase.Add(4*time.Second), 4000, head.SessionID)
	res := mustInsert(t, store, segAt(stream, 2, sessionBase.Add(7900*time.Millisecond), 4000), liveIngress())
	if res.StartPTSMS != 8000 || !res.PlaybackPDT.Equal(sessionBase.Add(7900*time.Millisecond)) {
		t.Errorf("(start, PDT) = (%d, %v), want (8000 — 뒤 항, 벽시계 %v)", res.StartPTSMS, res.PlaybackPDT, sessionBase.Add(7900*time.Millisecond))
	}
}

// 앵커(r6 R23 · 992–993 승계) — 앵커 없음이면 q 끝, µs 앵커는 ⌊⌋ 이 항마다 한 번이라 되풀이해도 폭이 쌓이지 않고, NULL
// 꼬리 10⁴ 행 너머의 앵커도 찾는다. 회차 없는 비귀속 행의 판 조회(마지막 귀속 행)도 같은 꼬리를 건넌다.
func TestLegacyPlacementAnchors(t *testing.T) {
	ctx := context.Background()

	t.Run("앵커 없음", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("anchor-none")
		mustInsert(t, store, segAt(stream, 0, sessionBase, 4000), currentOnly())
		mustInsert(t, store, segAt(stream, 1, sessionBase.Add(4*time.Second), 4500), currentOnly())
		res := mustInsert(t, store, segAt(stream, 2, sessionBase.Add(30*time.Second), 4000), liveIngress())
		if res.StartPTSMS != 8500 || res.SessionID == "" {
			t.Errorf("앵커 없는 첫 귀속 행 (start, 세션) = (%d, %q), want (8500 — q 끝, 귀속)", res.StartPTSMS, res.SessionID)
		}
	})

	t.Run("µs 앵커", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("anchor-us")
		wall := sessionBase.Add(600 * time.Microsecond)
		mustInsert(t, store, segAt(stream, 0, wall, 4000), liveIngress())
		// 벽시계가 이기는 순단마다 µs 자리가 .3 · .9 · .1 로 바뀐다 — ⌊⌋ 하나면 폭이 그대로다.
		for i, us := range []int{300, 900, 100, 700, 400} {
			wall = wall.Add(10 * time.Second).Truncate(time.Millisecond).Add(time.Duration(us) * time.Microsecond)
			mustInsert(t, store, segAt(stream, int64(i+1), wall, 4000), liveIngress())
		}
		rows := placedRows(t, pool, stream)
		for _, r := range rows[1:] {
			if w, w0 := r.pdt.UnixMilli()-r.start, rows[0].pdt.UnixMilli()-rows[0].start; w != w0 {
				t.Errorf("seq %d ⌊PDT⌋ − start = %d, want %d(첫 행 폭)", r.seq, w, w0)
			}
		}
	})

	t.Run("NULL 꼬리 10⁴", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("anchor-r23")
		head := mustInsert(t, store, segAt(stream, 0, sessionBase, 4200), liveIngress())
		if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
			t.Fatalf("EndLive 실패: %v", err)
		}
		const n = 10000
		if _, err := pool.Exec(ctx, `
			INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
			                             upload_state, bytes)
			SELECT $1, i, 4200 + (i - 1) * 10, $2::timestamptz + i * interval '10 milliseconds', 10, 'k' || i,
			       '/recordings/r23/' || i || '.mp4', 'pending', 10
			  FROM pg_catalog.generate_series(1, $3::bigint) AS i`, stream, sessionBase.Add(4200*time.Millisecond), n); err != nil {
			t.Fatalf("NULL 꼬리 픽스처 실패: %v", err)
		}
		start := time.Now()
		u := mustInsert(t, store, segAt(stream, n+1, sessionBase.Add(104300*time.Millisecond), 4000), currentOnly())
		t.Logf("NULL %d행 너머 비귀속 Insert 소요 %v", n, time.Since(start))
		if row := timingOf(t, pool, stream, n+1); strPtr(row.round) != head.SessionID || u.TimingGateRead != nil || row.start != 4200+n*10 {
			t.Errorf("꼬리 너머 비귀속 행 (회차, 게이트 읽음, start) = (%s, %t, %d), want (%s — 마지막 귀속 행의 열린 회차, false, %d)",
				strPtr(row.round), u.TimingGateRead != nil, row.start, head.SessionID, 4200+n*10)
		}
		t.Logf("앵커 조회 실행 계획(기록용):\n%s", explain(t, pool, false, "(ANALYZE, BUFFERS) "+anchorSQL, stream, int64(n)+2))
		t.Logf("마지막 귀속 행 조회 실행 계획(기록용):\n%s", explain(t, pool, false, "(ANALYZE, BUFFERS) "+lastAttributedSQL, stream, int64(n)+2))

		start = time.Now()
		// 계승 — 기저 = head(PDT base, 4200ms) · U = 10⁴ × 10ms + 4000 → 누적항 base + 108.2초 · 벽시계 108.3초가 이긴다.
		res := mustInsert(t, store, segAt(stream, n+2, sessionBase.Add(108300*time.Millisecond), 4000), liveIngress())
		t.Logf("NULL %d행 너머 귀속 Insert 소요 %v", n, time.Since(start))
		want := int64(0) + 108300 // 앵커 = head(start 0 · PDT base): start = 0 + 108300 − 0, q 끝 = 4200 + 10⁴·10 + 4000
		if res.StartPTSMS != want {
			t.Errorf("꼬리 너머 귀속 행 start = %d, want %d(앵커 = NULL %d행 너머 head)", res.StartPTSMS, want, n)
		}
	})
}

// U(r6 989–991 승계) — 비귀속 행 뒤 첫 귀속 행의 PDT 는 그 사이 비귀속 행 길이 합만큼 더 간다(계약 6-6-3). 벽시계가
// 정체해 누적항이 이기는 입력으로 값을 못 박는다. 잡는 결함: U 를 빼면 계승 첫 행의 PDT 가 기저 끝에 붙어 그 사이
// 비귀속 구간과 겹친다(VOD GAP 줄 · S7 — rewind/publish 의 VOD 동결 시험).
func TestLegacyPDTCarriesUnattributedLength(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("legacy-u")
	mustInsert(t, store, segAt(stream, 0, sessionBase, 4000), liveIngress())
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	mustInsert(t, store, segAt(stream, 1, sessionBase.Add(4*time.Second), 3000), currentOnly())
	mustInsert(t, store, segAt(stream, 2, sessionBase.Add(7*time.Second), 2500), currentOnly())
	res := mustInsert(t, store, segAt(stream, 3, sessionBase.Add(5*time.Second), 4000), liveIngress())
	want := sessionBase.Add((4000 + 3000 + 2500) * time.Millisecond)
	if !res.PlaybackPDT.Equal(want) || res.StartPTSMS != 9500 {
		t.Errorf("계승 첫 행 (PDT, start) = (%v, %d), want (%v — 기저 + 길이 + U, 9500)", res.PlaybackPDT, res.StartPTSMS, want)
	}
	// U 는 커밋 결과로도 돌아온다 — 신호 legacy_unattributed_sum 의 재료다(설계 r7 8절 · c11). 비귀속 행 없이 잇는 다음
	// 귀속 행은 0 이다.
	next := mustInsert(t, store, segAt(stream, 4, sessionBase.Add(9*time.Second), 4000), liveIngress())
	if res.LegacyUnattributedMS != 5500 || next.LegacyUnattributedMS != 0 {
		t.Errorf("커밋 U = (%d, %d), want (5500, 0)", res.LegacyUnattributedMS, next.LegacyUnattributedMS)
	}
}
