package session

// 사이드카 DB 계정 media_indexer 최소 권한 역할 시험 — session 패키지 몫(POK-311 C4).
//
// 권한 목록 · 시험 꼴은 index/media_indexer_role_pg_test.go 머리 주석과 같다. 이 패키지의 비시험 SQL 전수:
//
//	문장                 메서드 · 갈래                              표 · 동작
//	currentLiveSQL       Decide                                    sessions SELECT
//	previousSessionSQL   Decide 개시 갈래(현 live 없음)             sessions SELECT · segments SELECT(첫 · 마지막 조각)
//	endSessionSQL        Open TD 분할 갈래                          sessions UPDATE
//	openSessionSQL       Open                                      sessions INSERT
//
// 레지스트리는 트랜잭션을 받기만 한다 — 시험은 역할 풀로 연 트랜잭션을 준다(운영의 index.Insert 와 같은 꼴).

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// roleRegistry 는 config 기본값(하한 여유 1초 · 관측 30초 · 재접속 창 300초)의 레지스트리다.
func roleRegistry() *Registry {
	return New(Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second, ReconnectWindow: 300 * time.Second})
}

// decideAndOpen 은 pool 로 연 트랜잭션 하나에서 조각 하나의 결정과(개시 갈래면) 개시를 하고 커밋한다 — 오류는
// 되돌리고 돌려준다.
func decideAndOpen(pool *pgxpool.Pool, in Input) (Decision, string, error) {
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		return Decision{}, "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	r := roleRegistry()
	d, err := r.Decide(ctx, tx, in, in.StartWallUTC)
	if err != nil {
		return d, "", err
	}
	id := d.SessionID
	if d.Outcome == OutcomeOpen || d.Outcome == OutcomeOpenFresh {
		if id, err = r.Open(ctx, tx, d, in.StartWallUTC); err != nil {
			return d, "", err
		}
	}
	return d, id, tx.Commit(ctx)
}

// roleInput 은 스트림 stream 의 seq 조각(벽시계 wall 기준 4초 간격 · 길이 durationMS) 입력이다.
func roleInput(stream string, seq int64, durationMS int32) Input {
	return Input{
		StreamID: stream, Seq: seq, StartWallUTC: wall.Add(time.Duration(seq) * 4 * time.Second),
		DurationMS: durationMS, Op: OpenOrCurrent,
	}
}

// stateOfSession 은 회차 id 의 state 와 end_reason("" = NULL)이다.
func stateOfSession(t *testing.T, pool *pgxpool.Pool, id string) (string, string) {
	t.Helper()
	var state string
	var reason *string
	if err := pool.QueryRow(context.Background(),
		`SELECT state, end_reason FROM stream_sessions WHERE session_id = $1`, id).Scan(&state, &reason); err != nil {
		t.Fatalf("회차 %s 읽기 실패: %v", id, err)
	}
	if reason == nil {
		return state, ""
	}
	return state, *reason
}

// 세션 결정 · 개시 경로 — 첫 개시, TD 분할(직전 회차 ending), 직전 회차가 있는 개시가 media_indexer 권한만으로
// 돌고 회차 표가 기대대로 바뀐다.
func TestMediaIndexerRoleDecidesAndOpensSessions(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	stream := "role-sess"

	d1, id1, err := decideAndOpen(role.Pool, roleInput(stream, 0, 4000))
	if err != nil || d1.Outcome != OutcomeOpen || id1 == "" {
		t.Fatalf("첫 조각 = (%v · %q, %v), want 개시", d1.Outcome, id1, err)
	}
	d2, id2, err := decideAndOpen(role.Pool, roleInput(stream, 1, 7000))
	if err != nil || d2.Outcome != OutcomeOpenFresh || d2.EndingSessionID != id1 {
		t.Fatalf("7초 조각 = (%+v, %v), want TD 분할 · %s ending", d2, err, id1)
	}
	if state, reason := stateOfSession(t, admin, id1); state != "ending" || reason != endReasonTDExceeded {
		t.Errorf("회차 %s = %s(%s), want ending(%s)", id1, state, reason, endReasonTDExceeded)
	}
	if _, err := admin.Exec(context.Background(),
		`UPDATE stream_sessions SET state = 'ending', end_reason = 'offline' WHERE session_id = $1`, id2); err != nil {
		t.Fatalf("픽스처(회차 종료) 실패: %v", err)
	}
	d3, id3, err := decideAndOpen(role.Pool, roleInput(stream, 2, 4000))
	if err != nil || d3.Outcome != OutcomeOpen || d3.BaseSessionID != id2 || id3 == "" {
		t.Fatalf("현 live 없는 조각 = (%+v · %q, %v), want 개시 · 기저 %s", d3, id3, err, id2)
	}
	if state, _ := stateOfSession(t, admin, id3); state != "live" {
		t.Errorf("회차 %s = %s, want live", id3, state)
	}
	role.RequireSent(t, map[string]string{
		"currentLiveSQL": currentLiveSQL, "previousSessionSQL": previousSessionSQL,
		"endSessionSQL": endSessionSQL, "openSessionSQL": openSessionSQL,
	})
}

// 음성 대조 — 권한 하나를 거두면 그 권한을 쓰는 문장이 42501 이다.
func TestMediaIndexerRoleSessionWithoutEachGrantIsDenied(t *testing.T) {
	cases := []struct {
		privilege, table string
		sql              string
		// prepare 는 회수 전에 역할 풀로 세우는 상태다 — 기본은 live 회차 하나.
		prepare func(t *testing.T, role *pgtest.Role, admin *pgxpool.Pool)
		in      Input
	}{
		{"SELECT", "stream_sessions", currentLiveSQL, nil, roleInput("role-sd", 1, 4000)},
		{"UPDATE", "stream_sessions", endSessionSQL, nil, roleInput("role-sd", 1, 7000)},
		{"INSERT", "stream_sessions", openSessionSQL, nil, roleInput("role-sd-new", 0, 4000)},
		{"SELECT", "stream_segments", previousSessionSQL, func(t *testing.T, _ *pgtest.Role, admin *pgxpool.Pool) {
			if _, err := admin.Exec(context.Background(), `UPDATE stream_sessions SET state = 'ending'`); err != nil {
				t.Fatalf("픽스처(회차 종료) 실패: %v", err)
			}
		}, roleInput("role-sd", 1, 4000)},
	}
	for _, c := range cases {
		t.Run(c.privilege+"_"+c.table, func(t *testing.T) {
			admin := newTestPool(t)
			role := pgtest.NewMediaIndexerRole(t, admin)
			if _, _, err := decideAndOpen(role.Pool, roleInput("role-sd", 0, 4000)); err != nil {
				t.Fatalf("픽스처(첫 개시) 실패: %v", err)
			}
			if c.prepare != nil {
				c.prepare(t, role, admin)
			}
			role.Revoke(t, c.privilege, c.table)

			_, _, err := decideAndOpen(role.Pool, c.in)

			if !pgtest.IsInsufficientPrivilege(err) {
				t.Errorf("%s ON %s 회수 뒤 오류 = %v, want 42501", c.privilege, c.table, err)
			}
			role.RequireDenied(t, c.table+" "+c.privilege, c.sql)
		})
	}
}
