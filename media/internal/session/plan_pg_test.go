package session

// 세션 조회 두 개의 인덱스 도달 확인(POK-195 M3 — 계획 단계 3 ⑺).
//
// **도달 판정과 비용 판정을 분리한다**: 소표에서는 계획이 뒤집혀 순차 스캔이 더 싸다고
// 나오므로, enable_seqscan=off 로 **경로가 실재하는지**만 단언하고 기본 비용 플랜은
// 기록만 남긴다. Seq Scan 이 남으면 인덱스를 추가하는 것이 아니라 술어·형상을 고친다
// (새 DDL 은 3번 승인 표면을 다시 여는 일이다).

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestSessionQueriesReachTheirIndexes(t *testing.T) {
	pool := newTestPool(t)
	stream := "planstream"
	base := time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)
	// 계획이 인덱스를 고를 만한 규모를 만든다 — 스트림 하나에 세션 여럿(종료된 회차들).
	for i := range 200 {
		state := "ended"
		if i == 199 {
			state = "live"
		}
		seedSession(t, pool, sessionRow{
			id:             fmt.Sprintf("%s-s%03d", stream, i),
			streamID:       stream,
			startedAt:      base.Add(time.Duration(i) * time.Minute),
			state:          state,
			targetDuration: 6,
		})
	}
	if _, err := pool.Exec(context.Background(), `ANALYZE stream_sessions`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}

	for _, c := range []struct {
		name  string
		sql   string
		index string
	}{
		{"현_live_조회", currentLiveSQL, "stream_sessions_one_live_uq"},
		{"직전_세션_조회", latestSessionSQL, "stream_sessions_stream_idx"},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Logf("비용 플랜(기록용):\n%s", explainPlan(t, pool, false, c.sql, stream))
			got := explainPlan(t, pool, true, c.sql, stream)
			if !strings.Contains(got, c.index) {
				t.Fatalf("%s 에 도달하지 못했다 — 선행 컬럼 결손 의심:\n%s", c.index, got)
			}
		})
	}
}

// explainPlan 은 계획 문자열을 돌려준다. noSeqScan 이면 순차 스캔을 끄고 경로 실재만 본다.
// 설정은 커넥션 지역이라 같은 커넥션을 잡고 쓰며, 돌려주기 전에 되돌린다.
func explainPlan(t *testing.T, pool *pgxpool.Pool, noSeqScan bool, sql string, args ...any) string {
	t.Helper()
	ctx := context.Background()
	conn, err := pool.Acquire(ctx)
	if err != nil {
		t.Fatalf("커넥션 획득 실패: %v", err)
	}
	defer conn.Release()
	if noSeqScan {
		if _, err := conn.Exec(ctx, `SET enable_seqscan = off`); err != nil {
			t.Fatalf("enable_seqscan 설정 실패: %v", err)
		}
		defer func() { _, _ = conn.Exec(ctx, `RESET enable_seqscan`) }()
	}

	rows, err := conn.Query(ctx, "EXPLAIN "+sql, args...)
	if err != nil {
		t.Fatalf("EXPLAIN 실패: %v", err)
	}
	defer rows.Close()
	var b strings.Builder
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			t.Fatalf("EXPLAIN 스캔 실패: %v", err)
		}
		b.WriteString(line)
		b.WriteByte('\n')
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("EXPLAIN 조회 중 오류: %v", err)
	}
	return b.String()
}
