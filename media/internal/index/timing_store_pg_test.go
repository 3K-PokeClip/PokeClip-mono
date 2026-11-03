package index

// store 의 판 정하기 · 회차 칸 · 원래 사실 칸 · 커밋 값의 PG 통합 시험(POK-196 PDT 생산자 c4a · 설계 r7 2.3 · 2.4 ·
// 9.3 · 계획 4절 c4a). 판을 고르는 자리를 본다 — 판 2 를 고른 행의 섞기 값은 timing_v2_store_pg_test.go(c4b)가 잰다.
// 위치(I23) · U 는 timing_placement_pg_test.go 가 잰다. PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ledgerTiming 은 장부 한 행의 PDT 판 칸 · 위치 · carrier 다. NULL 은 nil 이다.
type ledgerTiming struct {
	version              *int16
	wall                 *time.Time
	recorder             *[16]byte
	number, vfirst, vend *int64
	round, session       *string
	start                int64
	dur                  int32
	pdt                  *time.Time
}

func timingOf(t *testing.T, pool *pgxpool.Pool, stream string, seq int64) ledgerTiming {
	t.Helper()
	var r ledgerTiming
	err := pool.QueryRow(context.Background(), `
		SELECT timing_version, timing_wall_guess, timing_recorder_id, timing_segment_number, timing_video_first_ns,
		       timing_video_end_ns, timing_round_id, session_id, start_pts_ms, duration_ms, playback_pdt
		  FROM stream_segments WHERE stream_id = $1 AND seq = $2`, stream, seq).
		Scan(&r.version, &r.wall, &r.recorder, &r.number, &r.vfirst, &r.vend, &r.round, &r.session, &r.start, &r.dur, &r.pdt)
	if err != nil {
		t.Fatalf("PDT 칸 조회 실패 stream=%q seq=%d: %v", stream, seq, err)
	}
	return r
}

// sessionRound 는 세션 표의 round_id 다(NULL 이면 "").
func sessionRound(t *testing.T, pool *pgxpool.Pool, sessionID string) string {
	t.Helper()
	var round *string
	if err := pool.QueryRow(context.Background(), `SELECT round_id FROM stream_sessions WHERE session_id = $1`, sessionID).
		Scan(&round); err != nil {
		t.Fatalf("세션 회차 조회 실패 %s: %v", sessionID, err)
	}
	if round == nil {
		return ""
	}
	return *round
}

// setGateSwitch 는 게이트 스위치를 v 로 둔다(nil = NULL) — 소유자 손 SQL 의 흉내다. 2 면 트리거가 래치를 같이 세운다.
func setGateSwitch(t *testing.T, pool *pgxpool.Pool, v *int16) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_timing_gate SET new_round_version = $1, changed_at = now(), changed_by = 'test'`, v); err != nil {
		t.Fatalf("게이트 스위치 %v 설정 실패: %v", v, err)
	}
}

// timedSeg 는 원래 사실 칸 가운데 w 만 채운 조각이다 — 래치가 선 장부에서도 쓰기자 거부에 걸리지 않는 새 이미지 행.
func timedSeg(stream string, seq int64, wall time.Time, durationMS int32) Record {
	r := segAt(stream, seq, wall, durationMS)
	r.Timing.Facts.Wall = wall
	return r
}

func mustInsert(t *testing.T, store Store, r Record, src SessionSource) InsertCommit {
	t.Helper()
	out, res, err := store.Insert(context.Background(), r, Seed{}, src)
	if err != nil || out != InsertInserted {
		t.Fatalf("Insert seq %d = (%v, %v), want inserted", r.Seq, out, err)
	}
	return res
}

func strPtr(p *string) string {
	if p == nil {
		return "<NULL>"
	}
	return *p
}

var versionTwo = int16(2)

// 판 정하기 1항 · 새 회차(설계 r7 9.3) — 새 회차를 여는 행은 게이트 공유 잠금 아래 스위치의 판을 쓴다. 게이트 NULL 이면
// 판 NULL 이고 회차 칸은 새 세션의 round_id(= 그 session_id)다. 스위치 2 인 새 회차는 판 2 로 연다(회차 첫 행 — new_round).
// 잡는 결함: 새 회차가 게이트를 안 읽으면 스위치가 소용없다(m5 의 거울).
func TestNewRoundTakesGateSwitchVersion(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("ver-new-round")

	first := mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
	row := timingOf(t, pool, stream, 0)
	if row.version != nil || strPtr(row.round) != first.SessionID || sessionRound(t, pool, first.SessionID) != first.SessionID {
		t.Errorf("첫 회차 행 (판, 회차) = (%v, %s), want (NULL, 새 세션 %s)", row.version, strPtr(row.round), first.SessionID)
	}
	if first.TimingVersion != nil || first.TimingRoundID != first.SessionID || first.TimingGateRead == nil ||
		first.TimingGateRead.NewRoundVersion != nil {
		t.Errorf("첫 회차 커밋 (판, 회차, 게이트) = (%v, %q, %+v), want (NULL, %q, 스위치 NULL 을 읽음)",
			first.TimingVersion, first.TimingRoundID, first.TimingGateRead, first.SessionID)
	}

	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	setGateSwitch(t, pool, &versionTwo)
	// 재접속 창(300초) 밖 — 15.2 표 7행 새 회차.
	next := mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(10*time.Minute), 4000), liveIngress())
	row = timingOf(t, pool, stream, 1)
	if row.version == nil || *row.version != versionTwo || strPtr(row.round) != next.SessionID || next.TimingBranch != BranchRoundFirst ||
		next.TimingReset != resetNewRound || next.TimingGateRead == nil || next.TimingGateRead.NewRoundVersion == nil {
		t.Errorf("스위치 2 새 회차 (판, 회차, 갈래, 까닭, 게이트) = (%v, %s, %s, %s, %+v), want (2, %s, round_first, new_round, 스위치 2 를 읽음)",
			row.version, strPtr(row.round), next.TimingBranch, next.TimingReset, next.TimingGateRead, next.SessionID)
	}
}

// P4 판 NULL 몫(설계 10.3) — 판 NULL 회차가 열린 채 스위치를 2 로 올려도 그 회차를 잇는 행(계속 · TD 분할 · 재접속 계승)은
// 앞 세션 판(NULL)이다. 게이트는 읽지 않는다. 잡는 결함: 이어 가는 개시가 게이트 판을 쓰면 한 회차에 두 판이 섞인다.
func TestContinuingRoundKeepsLegacyVersionAfterSwitch(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("ver-p4")

	head := mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
	setGateSwitch(t, pool, &versionTwo)
	steps := []struct {
		name string
		seg  Record
		end  bool
	}{
		{"계속", timedSeg(stream, 1, sessionBase.Add(4*time.Second), 4000), false},
		{"TD 분할", timedSeg(stream, 2, sessionBase.Add(8*time.Second), 7000), true},
		{"재접속 계승", timedSeg(stream, 3, sessionBase.Add(20*time.Second), 4000), false},
	}
	for _, s := range steps {
		res := mustInsert(t, store, s.seg, liveIngress())
		row := timingOf(t, pool, stream, s.seg.Seq)
		if row.version != nil || strPtr(row.round) != head.SessionID || res.TimingGateRead != nil || res.TimingRoundID != head.SessionID {
			t.Errorf("%s 행 (판, 회차, 게이트 읽음) = (%v, %s, %t), want (NULL, %s, false)",
				s.name, row.version, strPtr(row.round), res.TimingGateRead != nil, head.SessionID)
		}
		if s.end {
			if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
				t.Fatalf("EndLive 실패: %v", err)
			}
		}
	}
	if n := len(sessionsOf(t, pool, stream)); n != 3 {
		t.Errorf("세션 수 = %d, want 3(첫 · TD 분할 · 계승)", n)
	}
}

// 판 정하기 1항 「v(s) = v(p)」 — 이어 가는 회차의 판은 게이트가 아니라 앞 세션의 마지막 귀속 행(기저 행)의 판이다. 앞
// 세션이 판 2 행을 쓴 회차를 게이트 NULL 에서 이어 쓰면 그 행은 판 2 다(게이트를 읽지 않는다). 잡는 결함: 이어 가는
// 행의 판을 게이트에서 읽으면 판 2 회차가 판 NULL 로 이어져 한 회차 두 판이 된다(P4 의 거울).
func TestContinuingRoundTakesBaseRowVersionNotGate(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("ver-base")
	prev := stream + "-prev"
	putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase, state: "ending", firstPDT: sessionBase})
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET round_id = $1, end_reason = 'offline', ending_at = $2
	                              WHERE session_id = $1`, prev, sessionBase.Add(5*time.Second)); err != nil {
		t.Fatalf("앞 세션 회차 픽스처 실패: %v", err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
		                             upload_state, bytes, session_id, playback_pdt, timing_version, timing_wall_guess,
		                             timing_round_id)
		VALUES ($1, 0, 0, $2, 4000, 'k0', '/recordings/v2/0.mp4', 'pending', 1000, $3, $2, 2, $2, $3)`,
		stream, sessionBase, prev); err != nil {
		t.Fatalf("판 2 앞 행 픽스처 실패: %v", err)
	}

	res := mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(10*time.Second), 4000), liveIngress())
	// 지금 행은 칸이 w 뿐이라 같은 녹화기 이어짐이 아니다 — 견주어 Δ = w − E = 10000 − 4000 > θ 라 새 장이다.
	if row := timingOf(t, pool, stream, 1); row.version == nil || *row.version != versionTwo || strPtr(row.round) != prev ||
		res.TimingGateRead != nil || res.TimingBranch != BranchNewChapterGap || res.DeltaMS != 6000 {
		t.Errorf("판 2 회차를 잇는 계승 행 (판, 회차, 게이트 읽음, 갈래, Δ) = (%v, %s, %t, %s, %d), want (2 — 게이트 NULL 이 아니라 앞 세션 판, %s, false, new_chapter_gap, 6000)",
			row.version, strPtr(row.round), res.TimingGateRead != nil, res.TimingBranch, res.DeltaMS, prev)
	}
}

// 판 정하기 3항 · 회차 규칙 2 · 3(설계 2.4) — 비귀속 행의 판 · 회차는 그 스트림의 마지막 귀속 행이 정한다. 회차가 열려
// 있으면 그 회차 · 그 판(게이트를 읽지 않는다), 닫혔거나 귀속 행이 없으면 회차 없음 · 게이트 판이다. 라이브 중 하한 거부
// 행은 판만 3항이고 회차는 없다. 잡는 결함(m13): 비귀속 행 회차를 늘 NULL 로 두면 열린 회차의 비귀속 행이 회차 밖이 된다.
func TestUnattributedRowFollowsLastAttributedRound(t *testing.T) {
	ctx := context.Background()

	t.Run("귀속 행 없음", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("ver-u-none")
		res := mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), currentOnly())
		row := timingOf(t, pool, stream, 0)
		if row.version != nil || row.round != nil || res.TimingGateRead == nil || res.TimingRoundID != "" {
			t.Errorf("(판, 회차, 게이트 읽음) = (%v, %s, %t), want (NULL, NULL, true)", row.version, strPtr(row.round), res.TimingGateRead != nil)
		}
		setGateSwitch(t, pool, &versionTwo)
		res = mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(4*time.Second), 4000), currentOnly())
		if row := timingOf(t, pool, stream, 1); row.version == nil || *row.version != versionTwo || row.round != nil || res.TimingGateRead == nil ||
			res.TimingBranch != BranchRoundFirst || res.TimingReset != resetNoRound {
			t.Errorf("스위치 2 · 회차 없는 비귀속 행 (판, 회차, 게이트 읽음, 갈래, 까닭) = (%v, %s, %t, %s, %s), want (2 — 게이트 판, NULL, true, round_first, no_round)",
				row.version, strPtr(row.round), res.TimingGateRead != nil, res.TimingBranch, res.TimingReset)
		}
	})

	t.Run("회차 열림 · 닫힘", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("ver-u-round")
		head := mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
		if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
			t.Fatalf("EndLive 실패: %v", err)
		}
		setGateSwitch(t, pool, &versionTwo)
		res := mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(4*time.Second), 4000), currentOnly())
		row := timingOf(t, pool, stream, 1)
		if row.session != nil || row.version != nil || strPtr(row.round) != head.SessionID || res.TimingGateRead != nil {
			t.Errorf("열린 회차의 비귀속 행 (세션, 판, 회차, 게이트 읽음) = (%s, %v, %s, %t), want (NULL, NULL, %s, false)",
				strPtr(row.session), row.version, strPtr(row.round), res.TimingGateRead != nil, head.SessionID)
		}
		if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET round_closed_at = now() WHERE session_id = $1`, head.SessionID); err != nil {
			t.Fatalf("회차 닫힘 픽스처 실패: %v", err)
		}
		res = mustInsert(t, store, timedSeg(stream, 2, sessionBase.Add(8*time.Second), 4000), currentOnly())
		if row := timingOf(t, pool, stream, 2); row.version == nil || *row.version != versionTwo || row.round != nil || res.TimingGateRead == nil ||
			res.TimingBranch != BranchRoundFirst || res.TimingReset != resetNoRound {
			t.Errorf("닫힌 회차 뒤 비귀속 행(스위치 2) (판, 회차, 게이트 읽음, 갈래, 까닭) = (%v, %s, %t, %s, %s), want (2 — 게이트 판, NULL, true, round_first, no_round)",
				row.version, strPtr(row.round), res.TimingGateRead != nil, res.TimingBranch, res.TimingReset)
		}
		setGateSwitch(t, pool, nil)
		res = mustInsert(t, store, timedSeg(stream, 3, sessionBase.Add(12*time.Second), 4000), currentOnly())
		if row := timingOf(t, pool, stream, 3); row.version != nil || row.round != nil || res.TimingGateRead == nil {
			t.Errorf("닫힌 회차 뒤 비귀속 행(스위치 NULL) (판, 회차, 게이트 읽음) = (%v, %s, %t), want (NULL, NULL, true)",
				row.version, strPtr(row.round), res.TimingGateRead != nil)
		}
	})

	t.Run("라이브 중 하한 거부", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("ver-u-floor")
		mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
		setGateSwitch(t, pool, &versionTwo)
		// 세션 시작 − 2초(하한 여유 1초 밖) — 인덱서 H3 가 라이브 중에는 막는 입력이지만 store 단위로는 만들 수 있다.
		res := mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(-2*time.Second), 4000), liveIngress())
		row := timingOf(t, pool, stream, 1)
		if row.session != nil || row.version != nil || row.round != nil || res.TimingGateRead != nil {
			t.Errorf("하한 거부 행 (세션, 판, 회차, 게이트 읽음) = (%s, %v, %s, %t), want (NULL, NULL — 라이브 회차 판, NULL, false)",
				strPtr(row.session), row.version, strPtr(row.round), res.TimingGateRead != nil)
		}
	})
}

// 회차 칸 = 세션 round_id(설계 2.4 「칸으로 남길지」 함정 행 — 교차 표 제약이 없어 시험이 지킨다). 개시 · 계속 · TD 분할
// · 재접속 계승의 귀속 행은 모두 그 세션의 round_id 를 칸에 쓴다.
func TestAttributedRoundColumnMatchesSessionRound(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("round-col")
	walls := []struct {
		at  time.Duration
		dur int32
		end bool
	}{{0, 4000, false}, {4 * time.Second, 4000, false}, {8 * time.Second, 7000, true}, {20 * time.Second, 4000, false}}
	for seq, w := range walls {
		res := mustInsert(t, store, timedSeg(stream, int64(seq), sessionBase.Add(w.at), w.dur), liveIngress())
		row := timingOf(t, pool, stream, int64(seq))
		if want := sessionRound(t, pool, res.SessionID); want == "" || strPtr(row.round) != want || res.TimingRoundID != want {
			t.Errorf("seq %d 회차 칸 %s · 커밋 %q, want 세션 %s 의 round_id %q", seq, strPtr(row.round), res.TimingRoundID, res.SessionID, want)
		}
		if w.end {
			if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
				t.Fatalf("EndLive 실패: %v", err)
			}
		}
	}
}

// 원래 사실 칸 저장 · 재적재(P18 PG 몫 · 설계 2.3) — Record.Timing.Facts 가 칸 다섯에 그대로 들어가고 LoadCursor 꼬리에
// 같은 값으로 돌아온다. w 는 ms 내림이다. 번호 넘침 꼴(표식 · 번호 · 영상 두 칸 NULL · w 있음)도 CHECK 넷을 지나 INSERT 가
// 성공한다. 잡는 결함: 칸 하나를 빠뜨리면 재기동 뒤 꼬리의 칸이 비어 이어짐 판정이 달라진다.
func TestTimingFactsStoredAndReloaded(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("facts")
	recorder := [16]byte{0xde, 0xad, 0xbe, 0xef, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
	number := int64(41)
	first, end := 123456789*time.Microsecond, 127456789*time.Microsecond
	facts := TimingFacts{Wall: sessionBase.Add(1234567 * time.Microsecond), Recorder: &recorder, Number: &number,
		VideoFirst: &first, VideoEnd: &end}
	r := segAt(stream, 0, sessionBase, 4000)
	r.Timing.Facts = facts
	mustInsert(t, store, r, liveIngress())

	row := timingOf(t, pool, stream, 0)
	wantWall := sessionBase.Add(1234 * time.Millisecond)
	if row.wall == nil || !row.wall.Equal(wantWall) || row.recorder == nil || *row.recorder != recorder ||
		row.number == nil || *row.number != number || row.vfirst == nil || *row.vfirst != int64(first) ||
		row.vend == nil || *row.vend != int64(end) {
		t.Errorf("칸 = (w %v, 표식 %v, 번호 %v, 첫 %v, 끝 %v), want (%v, %x, %d, %d, %d)",
			row.wall, row.recorder, row.number, row.vfirst, row.vend, wantWall, recorder, number, int64(first), int64(end))
	}
	cur, err := store.LoadCursor(ctx, stream)
	if err != nil || cur.Tail == nil {
		t.Fatalf("LoadCursor = (%+v, %v)", cur, err)
	}
	want := facts
	want.Wall = wantWall
	if got := cur.Tail.Timing; !got.Wall.Equal(want.Wall) || got.Recorder == nil || *got.Recorder != recorder ||
		got.Number == nil || *got.Number != number || got.VideoFirst == nil || *got.VideoFirst != first ||
		got.VideoEnd == nil || *got.VideoEnd != end {
		t.Errorf("꼬리 칸 = %+v, want %+v", got, want)
	}

	overflow := segAt(stream, 1, sessionBase.Add(4*time.Second), 4000)
	overflow.Timing.Facts = TimingFacts{Wall: sessionBase.Add(4 * time.Second)}
	mustInsert(t, store, overflow, liveIngress())
	row = timingOf(t, pool, stream, 1)
	if row.wall == nil || row.recorder != nil || row.number != nil || row.vfirst != nil || row.vend != nil {
		t.Errorf("번호 넘침 꼴 칸 = %+v, want w 만 있고 넷 NULL", row)
	}
	cur, err = store.LoadCursor(ctx, stream)
	if err != nil || cur.Tail.Timing.Recorder != nil || cur.Tail.Timing.VideoEnd != nil || !cur.Tail.Timing.Wall.Equal(sessionBase.Add(4*time.Second)) {
		t.Errorf("번호 넘침 꼴 꼬리 칸 = (%+v, %v), want w 만", cur.Tail, err)
	}
}

// 꼬리 교정의 영상 끝(P21 store 몫 · 설계 2.14) — 자란 꼬리는 칸 = 넘긴 영상 끝, 넘긴 값이 nil 이면 칸 그대로, uploaded
// 행은 갱신하지 않고(false), 줄이는 갱신은 불변 트리거가 거부하고(오류), 옛 값 NULL 꼬리는 길이만 고쳐진다. 잡는 결함
// (m19): 문장이 영상 끝을 안 고치면 다음 행 맞닿음을 옛 끝으로 판정한다.
func TestUpdateTailCorrectsVideoEnd(t *testing.T) {
	ctx := context.Background()
	recorder := [16]byte{1}
	number := int64(7)
	first, end := 10*time.Second, 14*time.Second
	insertTail := func(t *testing.T, stream string, withVideo bool) (*pgxpool.Pool, Store) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		r := timedSeg(stream, 0, sessionBase, 4000)
		if withVideo {
			r.Timing.Facts.Recorder, r.Timing.Facts.Number = &recorder, &number
			r.Timing.Facts.VideoFirst, r.Timing.Facts.VideoEnd = &first, &end
		}
		mustInsert(t, store, r, liveIngress())
		return pool, store
	}
	grown := int64(14500 * time.Millisecond)

	t.Run("자람", func(t *testing.T) {
		pool, store := insertTail(t, sessionStream("tail-grow"), true)
		if ok, err := store.UpdateTail(ctx, sessionStream("tail-grow"), 0, 4500, 2000, &grown); err != nil || !ok {
			t.Fatalf("UpdateTail = (%v, %v), want (true, nil)", ok, err)
		}
		if row := timingOf(t, pool, sessionStream("tail-grow"), 0); row.dur != 4500 || row.vend == nil || *row.vend != grown {
			t.Errorf("(길이, 영상 끝) = (%d, %v), want (4500, %d)", row.dur, row.vend, grown)
		}
	})
	t.Run("넘긴 값 없음", func(t *testing.T) {
		pool, store := insertTail(t, sessionStream("tail-nil"), true)
		if ok, err := store.UpdateTail(ctx, sessionStream("tail-nil"), 0, 4500, 2000, nil); err != nil || !ok {
			t.Fatalf("UpdateTail = (%v, %v), want (true, nil)", ok, err)
		}
		if row := timingOf(t, pool, sessionStream("tail-nil"), 0); row.dur != 4500 || row.vend == nil || *row.vend != int64(end) {
			t.Errorf("(길이, 영상 끝) = (%d, %v), want (4500, 그대로 %d)", row.dur, row.vend, int64(end))
		}
	})
	t.Run("uploaded", func(t *testing.T) {
		pool, store := insertTail(t, sessionStream("tail-up"), true)
		if _, err := pool.Exec(ctx, `UPDATE stream_segments SET upload_state = 'uploaded' WHERE stream_id = $1`, sessionStream("tail-up")); err != nil {
			t.Fatalf("uploaded 픽스처 실패: %v", err)
		}
		if ok, err := store.UpdateTail(ctx, sessionStream("tail-up"), 0, 4500, 2000, &grown); err != nil || ok {
			t.Errorf("UpdateTail = (%v, %v), want (false, nil)", ok, err)
		}
		if row := timingOf(t, pool, sessionStream("tail-up"), 0); row.vend == nil || *row.vend != int64(end) {
			t.Errorf("영상 끝 = %v, want 그대로 %d", row.vend, int64(end))
		}
	})
	t.Run("줄임", func(t *testing.T) {
		pool, store := insertTail(t, sessionStream("tail-shrink"), true)
		shrunk := int64(13 * time.Second)
		if ok, err := store.UpdateTail(ctx, sessionStream("tail-shrink"), 0, 4500, 2000, &shrunk); err == nil || ok {
			t.Errorf("UpdateTail = (%v, %v), want (false, 불변 트리거 오류)", ok, err)
		}
		if row := timingOf(t, pool, sessionStream("tail-shrink"), 0); row.dur != 4000 || row.vend == nil || *row.vend != int64(end) {
			t.Errorf("(길이, 영상 끝) = (%d, %v), want 그대로 (4000, %d)", row.dur, row.vend, int64(end))
		}
	})
	t.Run("옛 값 NULL", func(t *testing.T) {
		pool, store := insertTail(t, sessionStream("tail-null"), false)
		if ok, err := store.UpdateTail(ctx, sessionStream("tail-null"), 0, 4500, 2000, &grown); err != nil || !ok {
			t.Fatalf("UpdateTail = (%v, %v), want (true, nil)", ok, err)
		}
		if row := timingOf(t, pool, sessionStream("tail-null"), 0); row.dur != 4500 || row.vend != nil {
			t.Errorf("(길이, 영상 끝) = (%d, %v), want (4500, NULL 그대로)", row.dur, row.vend)
		}
	})
}

// 커밋 값 = 방금 쓴 장부 행(r6 259–262 승계 · 계획 0절 6) — InsertCommit.StartPTSMS · DurationMS 는 장부 행의 값이고, 실어
// 보낸 Record.StartPTSMS 는 쓰이지 않는다. 잡는 결함: 입력 위치를 그대로 쓰면 위치 결정 지점이 둘이 된다.
func TestInsertCommitCarriesLedgerPosition(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	stream := sessionStream("commit-pos")
	for seq := range int64(3) {
		r := timedSeg(stream, seq, sessionBase.Add(time.Duration(seq)*4100*time.Millisecond), 4000)
		r.StartPTSMS = 987654321 // 입력이 아니다 — 쓰이면 아래 대조가 깨진다.
		res := mustInsert(t, store, r, liveIngress())
		row := timingOf(t, pool, stream, seq)
		if res.StartPTSMS != row.start || res.DurationMS != row.dur || row.start == r.StartPTSMS {
			t.Errorf("seq %d 커밋 (start, dur) = (%d, %d), 장부 (%d, %d) — 같아야 하고 입력 %d 가 아니어야 한다",
				seq, res.StartPTSMS, res.DurationMS, row.start, row.dur, r.StartPTSMS)
		}
	}
}

// 게이트 열쇠를 다른 연결이 배타로 쥐면 새 회차 개시는 lock_timeout(5초)에서 끊기고, 즉시 한 번 다시 시도한 뒤
// ErrLockContended 로 올라간다 — 부르는 쪽 H9 이 재시도하는 갈래다(c1 보안 검수 M2 · 계획 R6). 잡는 결함: 대기 상한이
// 없으면 문장 상한까지 매달리고, 55P03 을 그대로 올리면 같은 분류지만 경합 신호가 사라진다.
func TestGateLockHeldElsewhereSurfacesLockContended(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("gate-lock")
	holder, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("잠금 쥘 트랜잭션 시작 실패: %v", err)
	}
	defer func() { _ = holder.Rollback(ctx) }()
	if _, err := holder.Exec(ctx, `SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('pc_timing_gate'::text)::bigint)`); err != nil {
		t.Fatalf("배타 잠금 실패: %v", err)
	}

	start := time.Now()
	_, _, err = store.Insert(ctx, timedSeg(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if !errors.Is(err, ErrLockContended) {
		t.Fatalf("Insert 오류 = %v, want ErrLockContended", err)
	}
	t.Logf("게이트 잠금 경합 Insert 소요 %v(5초 × 2회 기대)", time.Since(start))
	if err := holder.Rollback(ctx); err != nil {
		t.Fatalf("잠금 놓기 실패: %v", err)
	}
	mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
}

// 게이트 행이 없으면 판을 고르는 행은 fail-closed 다(c1 cc 설계 LOW 이연 — 0행을 「스위치 · 래치 NULL」 로 읽으면 래치가
// 선 장부에 판 NULL 로 새 회차를 연다). 이어 가는 행은 게이트를 읽지 않으므로 그대로 간다.
func TestMissingGateRowFailsClosed(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("gate-missing")
	mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `INSERT INTO stream_timing_gate (id) VALUES (true) ON CONFLICT (id) DO NOTHING`); err != nil {
			t.Errorf("게이트 씨앗 복구 실패: %v", err)
		}
	})
	if _, err := pool.Exec(ctx, `TRUNCATE stream_timing_gate`); err != nil {
		t.Fatalf("게이트 비우기 실패: %v", err)
	}
	if _, err := ReadTimingGate(ctx, pool); !errors.Is(err, errTimingGateMissing) {
		t.Errorf("ReadTimingGate 오류 = %v, want errTimingGateMissing", err)
	}

	mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(4*time.Second), 4000), liveIngress())
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	if _, _, err := store.Insert(ctx, timedSeg(stream, 2, sessionBase.Add(10*time.Minute), 4000), Seed{}, liveIngress()); !errors.Is(err, errTimingGateMissing) {
		t.Errorf("새 회차 Insert 오류 = %v, want errTimingGateMissing", err)
	}
}

// 기록 사례(계획 c4a 착수 체크리스트 4 · 설계 2.4 미확인 1 — 판정 아님) — 라이브 없음 동안 에폭 하한에서 거부된 옛
// 잔존물(스캔 유입)이 세션 사이에 끼면 그 행은 규칙 3 을 따른다. 그 행과 다음 회차 B 첫 행의 칸을 시험 출력으로 남긴다.
func TestRecordEpochFloorRejectedRowBetweenSessions(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("record-epoch")
	a := mustInsert(t, store, timedSeg(stream, 0, sessionBase, 4000), liveIngress())
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	obs := SessionObservation{Publishing: true, ObservedAt: sessionBase.Add(10 * time.Second), EpochKnown: true,
		EpochStartedAt: sessionBase.Add(9 * time.Second)}
	u := mustInsert(t, store, timedSeg(stream, 1, sessionBase.Add(2*time.Second), 4000), scanIngress(obs))
	b := mustInsert(t, store, timedSeg(stream, 2, sessionBase.Add(10*time.Second), 4000), liveIngress())
	for seq, res := range []InsertCommit{a, u, b} {
		row := timingOf(t, pool, stream, int64(seq))
		t.Logf("기록 seq %d: 세션 %s · 판 %v · 회차 %s · 게이트 읽음 %t · start %d · PDT %v",
			seq, strPtr(row.session), row.version, strPtr(row.round), res.TimingGateRead != nil, row.start, row.pdt)
	}
}
