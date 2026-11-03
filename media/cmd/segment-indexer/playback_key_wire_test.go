package main

// ③ 키 꼴 스위치(playbackKeyFor)의 검증(POK-525 Ⓑ2-4 · 설계 r9.4 2-1 · 6절 · 계획 r2 E5 · E11).
//
// 단위 표는 스위치 × 회차의 네 칸과 회차 성분 검사 실패를 잰다. 실 PG 케이스는 실물 결정자와 같은 함수를 장부에 끼워
// 저장된 키를 잰다 — 키의 회차 성분이 조각이 귀속된 세션의 round_id 와 같아야 한다(엣지 하한이 경로 속 회차를 믿는다).

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 끔이면 회차가 있어도 옛 꼴이다(E5 — 교체 겹침 동안 옛 상자가 못 읽는 키를 쓰지 않는다). 켬이면 회차 있는 세션만 새 꼴이고,
// 회차 성분이 키 문법을 못 지나면 오류다 — 옛 꼴로 물러서지 않는다(⑹ 규약대로 키 NULL 이 된다). 변이: 끔 갈래가 roundID 를
// 씀 → 「끔_회차_있음」 FAIL.
func TestPlaybackKeyForPicksLayoutBySwitch(t *testing.T) {
	const stream, seq = "wirestream", int64(7)
	legacy := "dvr/wirestream/seg/000007.m4s"
	for _, c := range []struct {
		name      string
		roundKeys bool
		roundID   string
		want      string
		wantErr   bool
	}{
		{name: "끔_회차_있음", roundKeys: false, roundID: "S-R", want: legacy},
		{name: "끔_회차_없음", roundKeys: false, roundID: "", want: legacy},
		{name: "끔_회차_성분_불량", roundKeys: false, roundID: strings.Repeat("r", 129), want: legacy},
		{name: "켬_회차_있음", roundKeys: true, roundID: "S-R", want: "dvr/wirestream/S-R/seg/000007.m4s"},
		{name: "켬_회차_없음", roundKeys: true, roundID: "", want: legacy},
		{name: "켬_회차_128자", roundKeys: true, roundID: strings.Repeat("r", 128),
			want: "dvr/wirestream/" + strings.Repeat("r", 128) + "/seg/000007.m4s"},
		{name: "켬_회차_129자", roundKeys: true, roundID: strings.Repeat("r", 129), wantErr: true},
		{name: "켬_회차_경로_구분자", roundKeys: true, roundID: "S-R/x", wantErr: true},
		{name: "켬_회차_상위_참조", roundKeys: true, roundID: "..", wantErr: true},
	} {
		t.Run(c.name, func(t *testing.T) {
			got, err := playbackKeyFor(c.roundKeys)(stream, c.roundID, seq)
			if c.wantErr {
				if err == nil {
					t.Fatalf("키 %q 가 나왔다 — 회차 성분 검사 실패는 오류여야 한다(옛 꼴로 물러서면 안 된다)", got)
				}
				return
			}
			if err != nil || got != c.want {
				t.Fatalf("키 = (%q, %v), want (%q, nil)", got, err, c.want)
			}
		})
	}
}

// ── 실 PG — 장부가 저장한 키 ─────────────────────────────────────────────────

// keyStep 은 장부에 넣는 조각 하나다 — wall 은 wireBase 로부터의 오프셋이다.
type keyStep struct {
	name       string
	seq        int64
	wall       time.Duration
	durationMS int32
}

func keyRecord(stream string, s keyStep) index.Record {
	wall := wireBase.Add(s.wall)
	return index.Record{
		StreamID: stream, Seq: s.seq, StartPTSMS: s.seq * 4000,
		StartWallUTC: wall, DurationMS: s.durationMS,
		S3Key:       index.S3Key(stream, s.seq, wall),
		LocalPath:   "/recordings/" + stream + "/" + s.name + ".mp4",
		UploadState: index.UploadStatePending, Bytes: 1000 + s.seq,
	}
}

func newKeyStore(pool *pgxpool.Pool, roundKeys bool) index.Store {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	return index.NewPGStore(pool, newSessionDecider(time.Second, 30*time.Second, 300*time.Second, broadcast.Gate{}, log),
		playbackKeyFor(roundKeys))
}

// storedCarrier 는 조각의 귀속 세션 · 그 세션의 round_id · ③ 키 · PDT 유무다. NULL 은 "" 다.
type storedCarrier struct {
	sessionID, roundID, key string
	hasPDT                  bool
}

func carrierWithRound(t *testing.T, pool *pgxpool.Pool, stream string, seq int64) storedCarrier {
	t.Helper()
	var sess, round, key *string
	var pdt *time.Time
	if err := pool.QueryRow(context.Background(), `
		SELECT g.session_id, s.round_id, g.playback_s3_key, g.playback_pdt
		  FROM stream_segments g LEFT JOIN stream_sessions s ON s.session_id = g.session_id
		 WHERE g.stream_id = $1 AND g.seq = $2`, stream, seq).Scan(&sess, &round, &key, &pdt); err != nil {
		t.Fatalf("carrier 조회 실패 seq=%d: %v", seq, err)
	}
	deref := func(p *string) string {
		if p == nil {
			return ""
		}
		return *p
	}
	return storedCarrier{sessionID: deref(sess), roundID: deref(round), key: deref(key), hasPDT: pdt != nil}
}

// 장부가 쓴 ③ 키의 꼴은 스위치가 정하고, 켬이면 키의 회차 성분이 조각이 귀속된 세션의 stream_sessions.round_id 다 — 새 회차 ·
// 계속 · TD 분할 · 직전 회차 합류(재접속 계승) 네 갈래 모두(보안 M2 — 엣지 하한은 경로 속 회차를 믿는다). 끔이면 같은 갈래가
// 전부 옛 꼴이다(E5). 변이: store 가 ⑹ 에 dec.RoundID 대신 귀속 세션 id 를 넘김 → 켬의 TD 분할 · 직전 합류 FAIL · 끔 갈래가
// roundID 를 씀 → 끔 FAIL.
func TestStoredKeyRoundIsSessionRoundAndFollowsSwitch(t *testing.T) {
	steps := []keyStep{
		{name: "새_회차", seq: 0, wall: 0, durationMS: 4000},
		{name: "계속", seq: 1, wall: 4 * time.Second, durationMS: 4000},
		{name: "TD_분할", seq: 2, wall: 8 * time.Second, durationMS: 9000},
		{name: "직전_합류", seq: 3, wall: 20 * time.Second, durationMS: 4000},
	}
	for _, c := range []struct {
		name, stream string
		roundKeys    bool
	}{
		{"끔", "keystream-off", false},
		{"켬", "keystream-on", true},
	} {
		roundKeys, stream := c.roundKeys, c.stream
		t.Run(c.name, func(t *testing.T) {
			pool := newWireTestPool(t)
			ctx := context.Background()
			store := newKeyStore(pool, roundKeys)
			var head string
			sessions := map[string]bool{}
			for _, s := range steps {
				if s.name == "직전_합류" {
					// 현 live 가 재접속 창 안에서 끊긴 상태 — 다음 조각은 그 회차를 물려받아 새 세션을 연다(15.2 표 5행).
					if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state = 'ending', ending_at = now(),
						end_reason = 'offline' WHERE stream_id = $1 AND state = 'live'`, stream); err != nil {
						t.Fatalf("직전 회차 끊김 픽스처 실패: %v", err)
					}
				}
				if _, _, err := store.Insert(ctx, keyRecord(stream, s), index.Seed{},
					index.SessionSource{Op: index.SessionOpenOrCurrent}); err != nil {
					t.Fatalf("%s: Insert 실패: %v", s.name, err)
				}
				got := carrierWithRound(t, pool, stream, s.seq)
				if got.sessionID == "" || got.roundID == "" {
					t.Fatalf("%s: 귀속 세션 · 회차가 비었다: %+v", s.name, got)
				}
				if head == "" {
					head = got.roundID
				}
				sessions[got.sessionID] = true
				if got.roundID != head {
					t.Fatalf("%s: 세션 %s 의 round_id = %q, want %q(픽스처가 한 회차가 아니다)", s.name, got.sessionID, got.roundID, head)
				}
				wantRound := ""
				if roundKeys {
					wantRound = got.roundID
				}
				ref, err := playback.ParseSegKey(got.key)
				if err != nil || ref != (playback.SegRef{StreamID: stream, RoundID: wantRound, Seq: s.seq}) {
					t.Errorf("%s: 저장 키 %q = (%+v, %v), want 회차 성분 %q · seq %d", s.name, got.key, ref, err, wantRound, s.seq)
				}
			}
			if len(sessions) != 3 {
				t.Errorf("세션 수 = %d, want 3(새 회차 · TD 분할 · 직전 합류가 각각 연다)", len(sessions))
			}
		})
	}
}

// 켬이라도 round_id 가 NULL 인 세션(M6 전)의 조각은 옛 꼴이다.
func TestStoredKeyIsLegacyForSessionWithoutRound(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	stream := "keystream-noround"
	putLiveSession(t, pool, stream, nil)

	s := keyStep{name: "계속", seq: 1, wall: 4 * time.Second, durationMS: 4000}
	if _, _, err := newKeyStore(pool, true).Insert(ctx, keyRecord(stream, s), index.Seed{},
		index.SessionSource{Op: index.SessionOpenOrCurrent}); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}
	c := carrierWithRound(t, pool, stream, s.seq)
	want, _ := playback.SegKey(stream, s.seq)
	if c.sessionID != stream+"-live" || c.roundID != "" || c.key != want {
		t.Fatalf("carrier = %+v, want 세션 %s-live · 회차 NULL · 키 %q", c, stream, want)
	}
}

// 켬 × 회차 있음에서 회차 성분이 키 문법(128자 · URL 안전 문자 · 상위 참조 금지)을 못 지나면 ③ 키만 NULL 이고 세션 · PDT 는
// 남는다(⑹ 규약 — 옛 꼴로 물러서지도, 조각을 버리지도 않는다). round_id 의 안전은 상류 스트림 id 검사에만 기대므로 장부
// 쪽에서 한 번 더 막히는지 못 박는다(보안 1). 변이: 켬 갈래가 RoundSegKey 실패에서 SegKey 로 물러섬 → FAIL.
func TestStoredKeyIsNullWhenRoundComponentInvalid(t *testing.T) {
	for _, c := range []struct {
		name  string
		round string
	}{
		{"129자", strings.Repeat("r", 129)},
		{"경로_구분자", "S-R/x"},
		{"상위_참조", ".."},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newWireTestPool(t)
			ctx := context.Background()
			stream := "keystream-bad"
			round := c.round
			putLiveSession(t, pool, stream, &round)

			s := keyStep{name: "계속", seq: 1, wall: 4 * time.Second, durationMS: 4000}
			if _, _, err := newKeyStore(pool, true).Insert(ctx, keyRecord(stream, s), index.Seed{},
				index.SessionSource{Op: index.SessionOpenOrCurrent}); err != nil {
				t.Fatalf("Insert 실패(키 실패는 오류가 아니라 NULL 이어야 한다): %v", err)
			}
			got := carrierWithRound(t, pool, stream, s.seq)
			if got.sessionID != stream+"-live" || !got.hasPDT || got.key != "" {
				t.Fatalf("carrier = %+v, want 세션 %s-live · PDT 있음 · 키 NULL", got, stream)
			}
		})
	}
}

// putLiveSession 은 스트림의 live 세션 {stream}-live 를 심는다(round_id 는 round 그대로 — nil 이면 NULL). 조각은 없어 다음
// 조각이 계속 갈래로 귀속되고 PDT 는 벽시계 항에서 나온다.
func putLiveSession(t *testing.T, pool *pgxpool.Pool, stream string, round *string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt, round_id)
		VALUES ($1, $2, $3, 'live', $3, $4)`, stream+"-live", stream, wireBase, round); err != nil {
		t.Fatalf("live 세션 픽스처 실패: %v", err)
	}
}
