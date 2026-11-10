package index

// 판 2 섞기 경로의 PG 통합 시험(POK-196 PDT 생산자 c4b · 설계 r7 2.4 · 2.5 · 2.6 · 10.3 P16 · P17 · P22 · 계획 4절 c4b). 스위치
// 2 인 게이트 아래에서 store 가 p(같은 회차 최신 행) · q(바로 앞 행)를 읽어 MixV2 로 PDT · 위치 · 길이를 쓰는지, 그 값이 시험
// 오라클 explainV2(설계 2.15)로 전 행 설명되는지 본다. 두 연결 엇갈림(P19 · 격리)은 timing_v2_race_pg_test.go 가 잰다.
// PG_DSN 미설정이면 skip 이다.

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// v2At 은 픽스처 기준 벽시계(sessionBase)에서 ms 만큼 뒤 시각이다.
func v2At(ms int64) time.Time { return sessionBase.Add(time.Duration(ms) * time.Millisecond) }

// v2Facts 는 녹화기 recorder 의 번호 n 조각 칸이다 — 영상 [n×4초, (n+1)×4초)라 잇단 번호끼리 맞닿는다(영상 길이 4000ms).
func v2Facts(recorder [16]byte, n int64, w time.Time) TimingFacts {
	first := time.Duration(n) * 4 * time.Second
	f := withVideo(marked(recorder, n), first, first+4*time.Second)
	f.Wall = w
	return f
}

// v2Seg 는 판 2 섞기 재료를 실은 조각이다. 파일명 시각 file 은 세션 판정(하한 · 재접속 창)의 입력이고, 짐작 w 는 칸의
// 값이다. mvhd 길이를 4010 으로 두어 영상 길이(4000)와 갈라 놓는다 — 길이의 출처가 값으로 드러난다.
func v2Seg(stream string, seq int64, file time.Time, facts TimingFacts, headVideo bool) Record {
	r := segAt(stream, seq, file, 4010)
	r.Timing = TimingInput{Facts: facts, HeadVideo: headVideo, VideoOwnMS: 4000, LegacyDurationMS: 4010}
	return r
}

// ledgerExplainRows 는 스트림의 장부 행을 seq 순으로 오라클 입력으로 읽는다(mvhd 칸이 장부에 없어 LegacyMS 는 0 — 영상
// 길이를 모르는 행의 길이는 보지 않는다).
func ledgerExplainRows(t *testing.T, pool *pgxpool.Pool, stream string) []explainRow {
	t.Helper()
	rows, err := pool.Query(context.Background(), `
		SELECT seq, start_pts_ms, duration_ms, playback_pdt, timing_version, timing_wall_guess, timing_recorder_id,
		       timing_segment_number, timing_video_first_ns, timing_video_end_ns, timing_round_id
		  FROM stream_segments WHERE stream_id = $1 ORDER BY seq`, stream)
	if err != nil {
		t.Fatalf("장부 행 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []explainRow
	for rows.Next() {
		var (
			r    explainRow
			pdt  *time.Time
			cols factColumns
		)
		if err := rows.Scan(&r.Seq, &r.StartPTSMS, &r.DurationMS, &pdt, &r.Version, &cols.wall, &cols.recorder,
			&cols.number, &cols.videoFirst, &cols.videoEnd, &r.Round); err != nil {
			t.Fatalf("장부 행 스캔 실패: %v", err)
		}
		if pdt != nil {
			ms := pdt.UnixMilli()
			r.PDTMS = &ms
		}
		r.Facts = cols.toFacts()
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("장부 행 조회 중 오류: %v", err)
	}
	return out
}

// requireExplained 는 스트림의 판 2 행이 모두 원래 사실로 설명되는지 본다(설계 2.15 · 계획 c4b 시험 6 「explainV2 전 행」).
func requireExplained(t *testing.T, pool *pgxpool.Pool, stream string) {
	t.Helper()
	if bad := explainV2(ledgerExplainRows(t, pool, stream), MixThreshold); len(bad) != 0 {
		t.Errorf("explainV2 가 설명하지 못한 행: %q", bad)
	}
}

// roundBaselines 는 회차 round 의 판 2 행마다 ⌊PDT⌋ − start 값을 모은다(seq 순 · 같은 값은 하나로). 회차 안 기준점이 하나면
// 길이 1 이다(설계 2.11 · B9).
func roundBaselines(rows []explainRow, round string) []int64 {
	var out []int64
	for _, r := range rows {
		if r.Round == nil || *r.Round != round || r.PDTMS == nil {
			continue
		}
		if b := *r.PDTMS - r.StartPTSMS; !slices.Contains(out, b) {
			out = append(out, b)
		}
	}
	return out
}

func mustV2(t *testing.T, row ledgerTiming, what string) {
	t.Helper()
	if row.version == nil || *row.version != versionV2 {
		t.Fatalf("%s 판 = %v, want 2", what, row.version)
	}
}

// 회차 첫 행 넷(설계 2.5 위 네 줄 · 계획 c4b 시험 1) — 같은 회차 판 2 앞 행이 없으면 PDT = w 이고 위치는 바로 앞 행 끝(하한
// 거부만 q.start)이다. 길이는 자기 영상 길이(4000)이지 mvhd(4010)가 아니다. 음성 대조: 직전 커밋(store.go 는 c4a 상태)은 판
// 2 요구를 timing_version_unsupported 로 거부한다.
func TestV2RoundFirstRows(t *testing.T) {
	ctx := context.Background()

	t.Run("stream_first · new_round", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("v2-first")
		setGateSwitch(t, pool, &versionTwo)

		first := mustInsert(t, store, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(20)), true), liveIngress())
		row := timingOf(t, pool, stream, 0)
		mustV2(t, row, "첫 행")
		if first.TimingBranch != BranchRoundFirst || first.TimingReset != resetStreamFirst || row.pdt == nil ||
			!row.pdt.Equal(v2At(20)) || row.start != 0 || row.dur != 4000 || strPtr(row.round) != first.SessionID {
			t.Errorf("첫 행 (갈래, 까닭, PDT, start, dur, 회차) = (%s, %s, %v, %d, %d, %s), want (round_first, stream_first, w, 0, 4000, %s)",
				first.TimingBranch, first.TimingReset, row.pdt, row.start, row.dur, strPtr(row.round), first.SessionID)
		}
		if first.TimingResetBackMS != nil {
			t.Errorf("q 없는 첫 행의 q 끝 − w = %d, want nil", *first.TimingResetBackMS)
		}
		if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
			t.Fatalf("EndLive 실패: %v", err)
		}

		// 재접속 창(300초) 밖 — 새 회차. w 는 앞 회차 끝(PDT 20 + 4000)보다 1초 이르다(회차를 넘는 역행 · 설계 2.5 끝 항).
		next := mustInsert(t, store, v2Seg(stream, 1, v2At(600_000), v2Facts(recorderY, 0, v2At(3020)), true), liveIngress())
		row = timingOf(t, pool, stream, 1)
		mustV2(t, row, "새 회차 첫 행")
		if next.TimingBranch != BranchRoundFirst || next.TimingReset != resetNewRound || !next.SessionOpened ||
			row.pdt == nil || !row.pdt.Equal(v2At(3020)) || row.start != 4000 || strPtr(row.round) != next.SessionID {
			t.Errorf("새 회차 첫 행 (갈래, 까닭, PDT, start, 회차) = (%s, %s, %v, %d, %s), want (round_first, new_round, w, q 끝 4000, %s)",
				next.TimingBranch, next.TimingReset, row.pdt, row.start, strPtr(row.round), next.SessionID)
		}
		if next.TimingResetBackMS == nil || *next.TimingResetBackMS != 1000 {
			t.Errorf("q 끝 − w = %v, want 1000(q PDT 끝 4020 − w 3020)", next.TimingResetBackMS)
		}
		if s := sessionsOf(t, pool, stream); len(s) != 2 || s[1].FirstPDT == nil || !s[1].FirstPDT.Equal(v2At(3020)) {
			t.Errorf("새 회차 first_pdt = %+v, want w(섞기가 정한 PDT 가 세션 개시 값)", s)
		}
		requireExplained(t, pool, stream)
	})

	t.Run("no_round", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("v2-noround")
		setGateSwitch(t, pool, &versionTwo)
		for seq := range int64(2) {
			w := v2At(seq*4000 + 7)
			res := mustInsert(t, store, v2Seg(stream, seq, v2At(seq*4000), v2Facts(recorderX, 10+seq, w), true), currentOnly())
			row := timingOf(t, pool, stream, seq)
			mustV2(t, row, "회차 없는 비귀속 행")
			if res.TimingReset != resetNoRound || row.round != nil || row.session != nil || row.pdt == nil ||
				!row.pdt.Equal(w) || row.start != seq*4000 {
				t.Errorf("seq %d (까닭, 회차, 세션, PDT, start) = (%s, %s, %s, %v, %d), want (no_round, NULL, NULL, w, %d)",
					seq, res.TimingReset, strPtr(row.round), strPtr(row.session), row.pdt, row.start, seq*4000)
			}
		}
		requireExplained(t, pool, stream)
	})

	t.Run("floor_rejected", func(t *testing.T) {
		pool := newTestPool(t)
		store := newSessionStore(pool)
		stream := sessionStream("v2-floor")
		setGateSwitch(t, pool, &versionTwo)
		head := mustInsert(t, store, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), liveIngress())
		// 세션 시작 − 2초(하한 여유 1초 밖) — 인덱서 H3 가 라이브 중에는 막는 입력이지만 store 단위로는 만들 수 있다.
		res := mustInsert(t, store, v2Seg(stream, 1, v2At(-2000), v2Facts(recorderY, 0, v2At(-3000)), true), liveIngress())
		row := timingOf(t, pool, stream, 1)
		mustV2(t, row, "하한 거부 행(라이브 회차의 판)")
		if res.TimingReset != resetFloorRejected || row.round != nil || row.session != nil || row.pdt == nil ||
			!row.pdt.Equal(v2At(-3000)) || row.start != 0 {
			t.Errorf("하한 거부 행 (까닭, 회차, 세션, PDT, start) = (%s, %s, %s, %v, %d), want (floor_rejected, NULL, NULL, w, q.start 0)",
				res.TimingReset, strPtr(row.round), strPtr(row.session), row.pdt, row.start)
		}
		if head.TimingRoundID == "" {
			t.Fatal("첫 행이 회차를 열지 않았다")
		}
		requireExplained(t, pool, stream)
	})
}

// timing_round_broken 두 까닭(설계 2.5 「R 있음 ∧ ¬새 회차 ∧ (p 없음 ∨ p.판 ≠ 2)」) — 판 고정 위반이거나 회차 행이 빠진 배열은
// 정상 경로에서 생기지 않으므로 장부를 SQL 로 심는다. 앞 세션의 마지막 귀속 행(기저 행)이 판 2 라 계승 행의 판은 2 인데,
// 그 회차의 p 가 없거나(prev_missing) 판 NULL 이면(version_mismatch) 회차 첫 행(PDT = w)으로 쓰고 까닭을 싣는다.
func TestV2RoundBrokenFallsBackToRoundFirst(t *testing.T) {
	ctx := context.Background()
	for _, tc := range []struct {
		name       string
		baseRound  bool // 기저 행에 회차 칸을 채우는가
		legacyNext bool // 기저 행 뒤에 같은 회차의 판 NULL 비귀속 행을 심는가
		want       string
	}{
		{"prev_missing", false, false, brokenPrevMissing},
		{"version_mismatch", true, true, brokenVersionMismatch},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			store := newSessionStore(pool)
			stream := sessionStream("v2-broken-" + tc.name)
			prev := stream + "-prev"
			setGateSwitch(t, pool, &versionTwo)
			putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase, state: "ending", firstPDT: sessionBase,
				roundID: prev})
			if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET end_reason = 'offline', ending_at = $2
			                              WHERE session_id = $1`, prev, v2At(5000)); err != nil {
				t.Fatalf("앞 세션 회차 픽스처 실패: %v", err)
			}
			var round *string
			if tc.baseRound {
				round = &prev
			}
			if _, err := pool.Exec(ctx, `
				INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
				                             upload_state, bytes, session_id, playback_pdt, timing_version, timing_wall_guess,
				                             timing_round_id)
				VALUES ($1, 0, 0, $2, 4000, 'k0', '/recordings/v2b/0.mp4', 'pending', 1000, $3, $2, 2, $2, $4)`,
				stream, sessionBase, prev, round); err != nil {
				t.Fatalf("판 2 기저 행 픽스처 실패: %v", err)
			}
			seq := int64(1)
			if tc.legacyNext {
				if _, err := pool.Exec(ctx, `
					INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
					                             upload_state, bytes, timing_wall_guess, timing_round_id)
					VALUES ($1, 1, 4000, $2, 4000, 'k1', '/recordings/v2b/1.mp4', 'pending', 1000, $2, $3)`,
					stream, v2At(4000), prev); err != nil {
					t.Fatalf("판 NULL 회차 행 픽스처 실패: %v", err)
				}
				seq = 2
			}

			w := v2At(10_050)
			res := mustInsert(t, store, v2Seg(stream, seq, v2At(10_000), v2Facts(recorderX, 10, w), true), liveIngress())
			row := timingOf(t, pool, stream, seq)
			mustV2(t, row, "계승 행(기저 행 판)")
			if res.TimingBranch != BranchRoundFirst || res.TimingBroken != tc.want || res.TimingReset != "" ||
				strPtr(row.round) != prev || row.pdt == nil || !row.pdt.Equal(w) || row.start != seq*4000 {
				t.Errorf("(갈래, 위반, 까닭, 회차, PDT, start) = (%s, %s, %q, %s, %v, %d), want (round_first, %s, \"\", %s, w, q 끝 %d)",
					res.TimingBranch, res.TimingBroken, res.TimingReset, strPtr(row.round), row.pdt, row.start, tc.want, prev, seq*4000)
			}
		})
	}
}

// P16(설계 10.3 · 계획 c4b 시험 1 · 5) — 판 2 회차 R 에서 세션 A 가 offline → 지금 회차만(CurrentOnly) 비귀속 u1 · u2 → 재접속
// 계승 B. u 행은 회차 R · 판 2 · PDT 를 갖고(세션 · ③ 키는 NULL — not_settleable · ③ 업로드 조회에 안 집힘), B 첫 행은 p = u2
// 로 이어 continue/join 이며 회차 안 기준점(PDT − start)은 값 하나다. 잡는 결함: m13(비귀속 R 을 늘 NULL — u 가 회차 밖이라
// B 가 A 끝에서 이어 위치가 u 와 겹친다) · m14(판 2 비귀속 PDT 비움 — CHECK 넷째로 INSERT 23514).
func TestV2UnattributedRowsStayInRoundAcrossReconnect(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("v2-p16")
	setGateSwitch(t, pool, &versionTwo)

	at := func(i int64) time.Time { return v2At(i * 4000) }
	// 머리 행이 컷오프를 주조해야 ③ 업로드 조회가 이 스트림 행을 후보로 본다(아래 대조가 공허하지 않게).
	_, head, err := store.Insert(ctx, v2Seg(stream, 0, at(0), v2Facts(recorderX, 10, at(0)), true), eligibleSeed(time.Now().UTC()), liveIngress())
	if err != nil || !head.Seeded {
		t.Fatalf("머리 행 Insert = (%+v, %v), want 회차 개시 · 주조", head, err)
	}
	mustInsert(t, store, v2Seg(stream, 1, at(1), v2Facts(recorderX, 11, at(1)), true), liveIngress())
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	round := head.TimingRoundID
	for seq := int64(2); seq <= 3; seq++ {
		_, res, err := store.Insert(ctx, v2Seg(stream, seq, at(seq), v2Facts(recorderX, 10+seq, at(seq)), true),
			eligibleSeed(time.Now().UTC()), currentOnly())
		if err != nil {
			t.Fatalf("비귀속 u seq %d Insert 실패: %v", seq, err)
		}
		row := timingOf(t, pool, stream, seq)
		mustV2(t, row, "판 2 회차의 비귀속 행")
		if row.session != nil || strPtr(row.round) != round || row.pdt == nil || !row.pdt.Equal(at(seq)) ||
			res.Decline != DeclineNotSettleable || res.TimingBranch != BranchJoin {
			t.Errorf("u seq %d (세션, 회차, PDT, 귀속, 갈래) = (%s, %s, %v, %s, %s), want (NULL, %s, %v, not_settleable, join)",
				seq, strPtr(row.session), strPtr(row.round), row.pdt, res.Decline, res.TimingBranch, round, at(seq))
		}
		if c := carrierOf(t, pool, stream, seq); c.Key != nil {
			t.Errorf("u seq %d playback_s3_key = %v, want NULL", seq, *c.Key)
		}
	}

	b := mustInsert(t, store, v2Seg(stream, 4, at(4), v2Facts(recorderX, 14, at(4)), true), liveIngress())
	if !b.SessionOpened || b.InheritsSession == "" || b.TimingRoundID != round {
		t.Fatalf("B 첫 행 = %+v, want 재접속 계승 개시(회차 %s)", b, round)
	}
	if b.TimingBranch != BranchJoin && b.TimingBranch != BranchContinue || b.TimingReset != "" || b.StartPTSMS != 16000 {
		t.Errorf("B 첫 행 (갈래, 까닭, start) = (%s, %q, %d), want (continue|join, \"\", 16000)", b.TimingBranch, b.TimingReset, b.StartPTSMS)
	}
	rows := ledgerExplainRows(t, pool, stream)
	if got := roundBaselines(rows, round); len(got) != 1 {
		t.Errorf("회차 안 기준점(PDT − start) = %v, want 값 하나", got)
	}
	requireExplained(t, pool, stream)

	targets, _, err := NewUploadStore(pool).PendingUploads(ctx, AxisPlayback, 0, 100, SweepCursor{})
	if err != nil {
		t.Fatalf("③ 업로드 조회 실패: %v", err)
	}
	var picked []int64
	for _, tg := range targets {
		if tg.StreamID == stream {
			picked = append(picked, tg.Seq)
		}
	}
	if !slices.Equal(picked, []int64{0, 1, 4}) {
		t.Errorf("③ 업로드 조회가 집은 seq = %v, want [0 1 4](귀속 행만 — 비귀속 판 2 행 2 · 3 은 세션 결합에서 빠진다)", picked)
	}
}

// P22(설계 10.3 · 계획 c4b 시험 1) — 라이브 B 에서 k1(X · 10) → 하한 거부 u(Y · 0 · w = PDT(k1) − 3000) → k2(X · 11). u 는
// 회차 칸 NULL · 위치 q.start(나아가지 않음)이고, k2 의 p 는 u 가 아니라 k1 이다. k2 의 is_discontinuity 는 입력 Record 값
// 그대로다(store 가 바꾸지 않는다 — 끊김 표지는 q 기준 · buildRecord 의 몫). 잡는 결함: m24(하한 거부 행에 R — k2 가 u 에서
// 이어 2초 앞뛰기) · m24′(하한 거부 행이 위치를 나아감 — 겹침 k2 가 u 보다 앞 위치) · m8(p 를 q 로 — k2 위치가 u 에서 이어짐).
func TestV2FloorRejectedRowStaysOutOfRound(t *testing.T) {
	for _, tc := range []struct {
		name      string
		headVideo bool
		w         int64 // k2 의 짐작(ms)
		branch    Branch
		pdt       int64
	}{
		{"소리 머리 · continue", false, 4000, BranchContinue, 4000},
		{"영상 머리 · 겹침", true, 3500, BranchNewChapterOverlap, 3500},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			store := newSessionStore(pool)
			stream := sessionStream("v2-p22")
			setGateSwitch(t, pool, &versionTwo)

			k1 := mustInsert(t, store, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), liveIngress())
			u := mustInsert(t, store, v2Seg(stream, 1, v2At(-2000), v2Facts(recorderY, 0, v2At(-3000)), true), liveIngress())
			k2rec := v2Seg(stream, 2, v2At(4000), v2Facts(recorderX, 11, v2At(tc.w)), tc.headVideo)
			k2rec.IsDiscontinuity = true
			k2 := mustInsert(t, store, k2rec, liveIngress())

			if u.TimingReset != resetFloorRejected || u.TimingRoundID != "" || u.StartPTSMS != k1.StartPTSMS {
				t.Errorf("u (까닭, 회차, start) = (%s, %q, %d), want (floor_rejected, \"\", k1.start %d)",
					u.TimingReset, u.TimingRoundID, u.StartPTSMS, k1.StartPTSMS)
			}
			row := timingOf(t, pool, stream, 2)
			if k2.TimingBranch != tc.branch || row.pdt == nil || !row.pdt.Equal(v2At(tc.pdt)) || row.start != tc.pdt ||
				strPtr(row.round) != k1.TimingRoundID || k2.TimingPrevFacts == nil || *k2.TimingPrevFacts.Number != 10 {
				t.Errorf("k2 (갈래, PDT, start, 회차, p 번호) = (%s, %v, %d, %s, %v), want (%s, %v, %d, %s, 10)",
					k2.TimingBranch, row.pdt, row.start, strPtr(row.round), k2.TimingPrevFacts, tc.branch, v2At(tc.pdt), tc.pdt, k1.TimingRoundID)
			}
			var discont bool
			if err := pool.QueryRow(context.Background(), `SELECT is_discontinuity FROM stream_segments WHERE stream_id = $1 AND seq = 2`,
				stream).Scan(&discont); err != nil || !discont {
				t.Errorf("k2 is_discontinuity = (%v, %v), want 입력 그대로 참", discont, err)
			}
			rows := ledgerExplainRows(t, pool, stream)
			for i := 1; i < len(rows); i++ {
				if rows[i].StartPTSMS < rows[i-1].StartPTSMS {
					t.Errorf("seq 순 start 단조(I10) 깨짐: seq %d = %d < seq %d = %d", rows[i].Seq, rows[i].StartPTSMS,
						rows[i-1].Seq, rows[i-1].StartPTSMS)
				}
			}
			if got := roundBaselines(rows, k1.TimingRoundID); len(got) != 1 {
				t.Errorf("회차 안 기준점 = %v, want 값 하나", got)
			}
			requireExplained(t, pool, stream)
		})
	}
}

// P17(설계 10.3 · 계획 c4b 시험 2) — 회차 닫힘과 비귀속 행의 경합. 닫힘 문장이 커밋되기 전(다른 연결이 쥔 채)에 쓴 u1 은
// 회차 R 에 들고(닫힌 회차는 다시 열리지 않으므로 결과는 같다), 커밋 뒤의 u2 는 회차 없음(PDT = w)이다. 그 뒤 새 회차 첫 행은
// new_round 다. 닫힘 커밋을 u1 앞에 둔 갈래는 u1 부터 회차 없음이다. 잡는 결함: m22(round_closed_at 무시 — u2 가 R 을 받아
// u1 에서 join · PDT = E).
func TestV2RoundClosureRacesUnattributedRows(t *testing.T) {
	ctx := context.Background()
	for _, tc := range []struct {
		name          string
		closeBeforeU1 bool
	}{{"닫힘 커밋 전 u1", false}, {"닫힘 커밋 뒤 u1", true}} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			store := newSessionStore(pool)
			stream := sessionStream("v2-p17")
			setGateSwitch(t, pool, &versionTwo)
			head := mustInsert(t, store, v2Seg(stream, 0, v2At(0), v2Facts(recorderX, 10, v2At(0)), true), liveIngress())
			if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
				t.Fatalf("EndLive 실패: %v", err)
			}
			closer, err := pool.Begin(ctx)
			if err != nil {
				t.Fatalf("닫힘 트랜잭션 시작 실패: %v", err)
			}
			defer func() { _ = closer.Rollback(ctx) }()
			if _, err := closer.Exec(ctx, `UPDATE stream_sessions SET round_closed_at = now() WHERE session_id = $1`, head.SessionID); err != nil {
				t.Fatalf("회차 닫힘 문장 실패: %v", err)
			}
			if tc.closeBeforeU1 {
				if err := closer.Commit(ctx); err != nil {
					t.Fatalf("닫힘 커밋 실패: %v", err)
				}
			}
			// u1 의 w 는 앞 끝 E 와 같다 — 회차에 들면 join(PDT = E = w)이고 아니면 PDT = w 라 값은 같고 회차 칸이 가른다.
			u1 := mustInsert(t, store, v2Seg(stream, 1, v2At(4000), v2Facts(recorderX, 11, v2At(4000)), true), currentOnly())
			if !tc.closeBeforeU1 {
				if err := closer.Commit(ctx); err != nil {
					t.Fatalf("닫힘 커밋 실패: %v", err)
				}
			}
			wantU1 := map[bool]string{false: head.TimingRoundID, true: ""}[tc.closeBeforeU1]
			if u1.TimingRoundID != wantU1 {
				t.Errorf("u1 회차 = %q, want %q", u1.TimingRoundID, wantU1)
			}
			// u2 의 w 는 앞 끝 + 50ms(θ 안) — 회차에 들면 join 으로 PDT = E 가 되어 w 와 갈린다.
			u2 := mustInsert(t, store, v2Seg(stream, 2, v2At(8000), v2Facts(recorderX, 12, v2At(8050)), true), currentOnly())
			row := timingOf(t, pool, stream, 2)
			mustV2(t, row, "닫힘 뒤 비귀속 행(게이트 판)")
			if u2.TimingRoundID != "" || u2.TimingReset != resetNoRound || row.pdt == nil || !row.pdt.Equal(v2At(8050)) {
				t.Errorf("u2 (회차, 까닭, PDT) = (%q, %s, %v), want (\"\", no_round, w %v)", u2.TimingRoundID, u2.TimingReset, row.pdt, v2At(8050))
			}
			next := mustInsert(t, store, v2Seg(stream, 3, v2At(600_000), v2Facts(recorderX, 13, v2At(600_000)), true), liveIngress())
			if !next.SessionOpened || next.TimingReset != resetNewRound || next.TimingRoundID == head.TimingRoundID {
				t.Errorf("새 회차 첫 행 (개시, 까닭, 회차) = (%t, %s, %q), want (true, new_round, 새 회차)", next.SessionOpened, next.TimingReset, next.TimingRoundID)
			}
			requireExplained(t, pool, stream)
		})
	}
}

// 판 고정 · 스위치를 내린 뒤(P4 판 2 몫 · P19 둘째 문장) — 열린 판 2 회차 중 스위치를 NULL 로 내려도 그 회차를 잇는 행(계속 ·
// 재접속 계승)은 판 2 로 섞고, 그 뒤 새 회차는 판 NULL(판 NULL 이어 쓰기 — 바로 앞 판 2 행을 앵커로 위치를 잇는다)이다. 잡는
// 결함: m5(판 정하기 우회 — 늘 게이트 판이면 계승 행이 판 NULL 이 되어 한 회차 두 판).
func TestV2RoundKeepsVersionAfterSwitchDown(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	ctx := context.Background()
	stream := sessionStream("v2-switch-down")
	setGateSwitch(t, pool, &versionTwo)

	at := func(i int64) time.Time { return v2At(i * 4000) }
	head := mustInsert(t, store, v2Seg(stream, 0, at(0), v2Facts(recorderX, 10, at(0)), true), liveIngress())
	setGateSwitch(t, pool, nil)
	mustInsert(t, store, v2Seg(stream, 1, at(1), v2Facts(recorderX, 11, at(1)), true), liveIngress())
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}
	inherit := mustInsert(t, store, v2Seg(stream, 2, at(2), v2Facts(recorderX, 12, at(2)), true), liveIngress())
	for seq := range int64(3) {
		row := timingOf(t, pool, stream, seq)
		mustV2(t, row, "판 2 회차 행")
		if strPtr(row.round) != head.TimingRoundID {
			t.Errorf("seq %d 회차 = %s, want %s", seq, strPtr(row.round), head.TimingRoundID)
		}
	}
	if !inherit.SessionOpened || inherit.TimingGateRead != nil || inherit.TimingBranch != BranchJoin {
		t.Errorf("계승 행 (개시, 게이트 읽음, 갈래) = (%t, %t, %s), want (true, false, join)", inherit.SessionOpened, inherit.TimingGateRead != nil, inherit.TimingBranch)
	}
	if _, err := EndLive(ctx, pool, stream, testClose, noopEnqueue); err != nil {
		t.Fatalf("EndLive 실패: %v", err)
	}

	legacy := mustInsert(t, store, v2Seg(stream, 3, v2At(600_000), v2Facts(recorderY, 0, v2At(600_000)), true), liveIngress())
	row := timingOf(t, pool, stream, 3)
	if row.version != nil || legacy.TimingBranch != "" || legacy.TimingRoundID != legacy.SessionID || row.pdt == nil ||
		!row.pdt.Equal(v2At(600_000)) || row.dur != 4010 || row.start != 600_000 {
		t.Errorf("새 회차 (판, 갈래, PDT, dur, start) = (%v, %q, %v, %d, %d), want (NULL, \"\", 벽시계, mvhd 4010, 앵커 폭 600000)",
			row.version, legacy.TimingBranch, row.pdt, row.dur, row.start)
	}
	requireExplained(t, pool, stream)
}
