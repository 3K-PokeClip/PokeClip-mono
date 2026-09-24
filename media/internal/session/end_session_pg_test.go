package session

// 세션 종료 문장 두 벌의 대조(POK-195 M4 — 계획 부기 21).
//
// 세션을 ending 으로 보내는 문장이 두 패키지에 있다: TD 분할의 endSessionSQL 은 여기에,
// init 불일치의 종료 절은 index 의 결속 CTE(MarkPlaybackFailed) 안에 있다. index 는 session 을
// 임포트하지 않으므로(index/session.go 경계) 술어(state='live')와 사유 어휘를 공유할 집이 없다.
// 이 파일이 두 문장을 같은 출발 상태에 돌려 효과를 대조한다 — 한쪽만 바뀌면 여기서 갈린다.

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// endedAtFixture 는 이미 끝난 출발 상태의 시각이다. 문장이 그 행을 건드리지 않았는지를
// 이 값의 보존으로 잰다.
var endedAtFixture = time.Date(2026, 9, 2, 9, 0, 0, 0, time.UTC)

// seedEndingFixture 는 출발 상태 하나의 세션 행과, 그 세션에 귀속된 ③ pending 조각 1행을 심는다.
// 조각은 index 결속 CTE 의 앵커다(이 패키지의 문장은 쓰지 않는다).
func seedEndingFixture(t *testing.T, pool *pgxpool.Pool, sessionID, streamID, state, reason string) {
	t.Helper()
	ctx := context.Background()
	var endReason, endingAt, endedAt any
	if state != "live" {
		endReason, endingAt = reason, endedAtFixture
	}
	if state == "ended" {
		endedAt = endedAtFixture
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, ended_at)
VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		sessionID, streamID, wall.Add(-time.Hour), state, endReason, endingAt, endedAt); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", sessionID, err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
     upload_state, bytes, session_id, playback_pdt, playback_s3_key)
VALUES ($1, 0, 0, $2, 4000, $3, $4, 'pending', 1000, $5, $2, $6)`,
		streamID, wall.Add(-time.Hour), index.S3Key(streamID, 0, wall),
		fmt.Sprintf("/recordings/%s/0.mp4", streamID), sessionID,
		fmt.Sprintf("dvr/%s/seg/000000.m4s", streamID)); err != nil {
		t.Fatalf("조각 픽스처 실패 %s: %v", streamID, err)
	}
}

// endShape 는 종료 문장이 바꾸는 네 열이다.
type endShape struct {
	state     string
	endReason *string
	endingAt  *time.Time
	endedAt   *time.Time
}

func endShapeOf(t *testing.T, pool *pgxpool.Pool, sessionID string) endShape {
	t.Helper()
	var s endShape
	if err := pool.QueryRow(context.Background(),
		`SELECT state, end_reason, ending_at, ended_at FROM stream_sessions WHERE session_id = $1`,
		sessionID).Scan(&s.state, &s.endReason, &s.endingAt, &s.endedAt); err != nil {
		t.Fatalf("세션 조회 실패 %s: %v", sessionID, err)
	}
	return s
}

// reason 은 end_reason 을 비교 가능한 문자열로 편다(NULL 은 "<nil>").
func (s endShape) reason() string {
	if s.endReason == nil {
		return "<nil>"
	}
	return *s.endReason
}

func (s endShape) String() string {
	return fmt.Sprintf("{state %s, end_reason %s, ending_at %v, ended_at %v}", s.state, s.reason(), s.endingAt, s.endedAt)
}

func TestEndSessionStatementsAgreeWithIndexInitMismatchEnding(t *testing.T) {
	for _, c := range []struct {
		name, state, reason string
	}{
		{"live_출발", "live", ""},
		{"이미_ending", "ending", "td_exceeded"},
		{"이미_ended", "ended", "offline"},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			ctx := context.Background()
			seedEndingFixture(t, pool, "S-reg", "regstream", c.state, c.reason)
			seedEndingFixture(t, pool, "S-idx", "idxstream", c.state, c.reason)

			withTx(t, pool, func(tx pgx.Tx) {
				if _, err := tx.Exec(ctx, endSessionSQL, "S-reg", index.ReasonInitMismatch); err != nil {
					t.Fatalf("endSessionSQL 실행 실패: %v", err)
				}
			})
			if _, err := index.NewUploadStore(pool).MarkPlaybackFailed(ctx,
				"idxstream", 0, "S-idx", index.ReasonInitMismatch); err != nil {
				t.Fatalf("index 결속 종료 실패: %v", err)
			}

			reg, idx := endShapeOf(t, pool, "S-reg"), endShapeOf(t, pool, "S-idx")
			if c.state == "live" {
				if reg.state != "ending" || reg.reason() != "init_mismatch" || reg.endingAt == nil || reg.endedAt != nil {
					t.Errorf("endSessionSQL 결과 = %v, want {ending, init_mismatch, ending_at 있음, ended_at nil}", reg)
				}
			} else if reg.state != c.state || reg.reason() != c.reason ||
				reg.endingAt == nil || !reg.endingAt.Equal(endedAtFixture) {
				t.Errorf("endSessionSQL 결과 = %v, want 출발 상태 그대로(%s, %s) — live 가 아닌 세션을 덮었다", reg, c.state, c.reason)
			}
			if reg.state != idx.state || reg.reason() != idx.reason() ||
				(reg.endingAt == nil) != (idx.endingAt == nil) || (reg.endedAt == nil) != (idx.endedAt == nil) {
				t.Errorf("두 종료 문장의 결과가 다르다: session = %v, index = %v", reg, idx)
			}
		})
	}
}
