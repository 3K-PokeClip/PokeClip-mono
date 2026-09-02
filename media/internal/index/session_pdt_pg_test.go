package index

// PDT 재귀식·기저항 선택·키 파생의 PG 통합 검증(POK-195 M3 — 설계 5.1.1·5.2 · 계획 4.1 ⑷⑹).
//
// session_tx_pg_test.go 가 "어느 세션인가"를 잰다면 여기는 "그 세션에서 시각이 어떻게
// 이어지는가"를 잰다. 파일을 가른 이유는 두 축의 픽스처가 서로 다르기 때문이다
// (저쪽은 세션 표, 여기는 세그먼트 표의 앞 행).

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 계획 단계 3 ⑸ⓒ — 누적항이 벽시계를 이기는 국면.
// 이 픽스처가 없으면 first_pdt 를 벽시계로 잘못 써도 통과한다(공허한 통과 — r9 F-1).
func TestOpenedSessionInheritsPDTFromPrecedingSessionWhenAccumulatorWins(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("pdt-acc")
	prev := stream + "-prev"
	putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase,
		state: "ended", firstPDT: sessionBase})
	// 직전 세션 마지막 행: pdt = base, dur = 6500ms → 누적항 base+6.5s.
	// 정수 초 나눗셈이면 base+6s 가 되어 500ms 를 잃는다.
	putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 6500,
		sessionID: prev, pdt: sessionBase, key: "dvr/" + stream + "/seg/000000.m4s"})

	// 벽시계는 3초만 흘렀다(정체) — 누적항이 이겨야 한다.
	wall := sessionBase.Add(3 * time.Second)
	if _, _, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 1, wall, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	want := sessionBase.Add(6500 * time.Millisecond)
	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 2 {
		t.Fatalf("세션 수 = %d, want 2", len(sessions))
	}
	if got := sessions[1].FirstPDT; got == nil || !got.Equal(want) {
		t.Fatalf("first_pdt = %v, want %v(= prev.pdt + prev.dur, 벽시계 %v 가 아니다)", got, want, wall)
	}
	if c := carrierOf(t, pool, stream, 1); c.PDT == nil || !c.PDT.Equal(want) {
		t.Fatalf("playback_pdt = %v, want %v", c.PDT, want)
	}
}

// 계획 단계 3 ⑸ⓓ — TD 분할의 경계에서도 기저항은 **분할 직전 세션의 마지막 행**이다.
// 새 세션은 아직 행이 없으므로 자기 자신을 잡으면 언제나 기저항 부재가 되어
// 경계마다 PDT 가 벽시계로 되돌아간다(r8 순서 오류의 재현 방지).
func TestSplitSessionInheritsPDTFromTailOfSessionBeingClosed(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("pdt-split")
	live := stream + "-s1"
	putSession(t, pool, fixtureSession{id: live, stream: stream, startedAt: sessionBase,
		firstPDT: sessionBase, targetDuration: 6})
	putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 6500,
		sessionID: live, pdt: sessionBase, key: "dvr/" + stream + "/seg/000000.m4s"})

	// 벽시계 정체 + TD 초과(6.5초 → 반올림 7 > 6) → 분할.
	wall := sessionBase.Add(3 * time.Second)
	if _, _, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 1, wall, 6500), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	want := sessionBase.Add(6500 * time.Millisecond)
	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 2 {
		t.Fatalf("세션 수 = %d, want 2(분할)", len(sessions))
	}
	if got := sessions[1].FirstPDT; got == nil || !got.Equal(want) {
		t.Fatalf("분할 세션 first_pdt = %v, want %v", got, want)
	}
}

// 계획 단계 3 ⑸ — 배포 경계 국면. M2 DDL 은 컬럼만 추가했으므로 기존 전 행이
// session_id·playback_pdt NULL 이다. 그 꼬리 뒤의 첫 조각은 기저 세션 부재 갈래로
// 들어가 자기 벽시계로 세션을 열고, 옛 NULL 구간은 소급 채우지 않는다.
func TestInsertOpensSessionAcrossDeploymentBoundaryWithNullRows(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("pdt-boundary")
	for seq := int64(0); seq < 5; seq++ {
		putRow(t, pool, fixtureRow{stream: stream, seq: seq,
			wall: sessionBase.Add(time.Duration(seq) * 4 * time.Second), durationMS: 4000})
	}

	wall := sessionBase.Add(20 * time.Second)
	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 5, wall, 4000), eligibleSeed(time.Now().UTC()), liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}
	if !res.Seeded {
		t.Fatalf("배포 경계 첫 조각이 주조되지 않았다: %+v", res)
	}

	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 1 {
		t.Fatalf("세션 수 = %d, want 1", len(sessions))
	}
	c := carrierOf(t, pool, stream, 5)
	if c.PDT == nil || !c.PDT.Equal(wall) {
		t.Fatalf("playback_pdt = %v, want %v(기저 세션 부재 → 벽시계)", c.PDT, wall)
	}
	if sessions[0].FirstPDT == nil || !sessions[0].FirstPDT.Equal(*c.PDT) {
		t.Fatalf("first_pdt(%v) 와 개시 행 pdt(%v) 가 다르다", sessions[0].FirstPDT, c.PDT)
	}
	// 옛 NULL 구간은 그대로다 — 소급 백필 경로를 만들지 않는다.
	for seq := int64(0); seq < 5; seq++ {
		if old := carrierOf(t, pool, stream, seq); old.SessionID != nil || old.PDT != nil {
			t.Fatalf("옛 NULL 행 seq=%d 가 소급 갱신됐다: %+v", seq, old)
		}
	}
}

// 계획 단계 3 ⑸ 대조군 ⓐ·ⓔ — 세션 사이에 끼는 NULL 행(귀속 거부·부분 carrier)은
// 기저항 후보가 아니다. 기저항은 **그 세션에 귀속된** 마지막 행에서만 온다.
func TestBaseRowSkipsRowsThatAreNotSettledInTheBaseSession(t *testing.T) {
	t.Run("귀속_없는_행은_건너뛴다", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("pdt-skip")
		prev := stream + "-prev"
		putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase,
			state: "ended", firstPDT: sessionBase})
		putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 4000,
			sessionID: prev, pdt: sessionBase, key: "dvr/" + stream + "/seg/000000.m4s"})
		// 세션에 귀속되지 못한 행(하한 거부 재현) — 벽시계는 더 뒤다.
		putRow(t, pool, fixtureRow{stream: stream, seq: 1, wall: sessionBase.Add(30 * time.Second),
			durationMS: 4000})

		wall := sessionBase.Add(2 * time.Second)
		if _, _, err := newSessionStore(pool).Insert(context.Background(),
			segAt(stream, 2, wall, 4000), Seed{}, liveIngress()); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
		want := sessionBase.Add(4 * time.Second) // seq 0 에서 이어진다(seq 1 은 후보가 아니다)
		if c := carrierOf(t, pool, stream, 2); c.PDT == nil || !c.PDT.Equal(want) {
			t.Fatalf("playback_pdt = %v, want %v — NULL 행을 기저항으로 잡았다", c.PDT, want)
		}
	})

	t.Run("session_id_만_있고_pdt_가_비면_기저항_부재", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("pdt-partial")
		prev := stream + "-prev"
		putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase,
			state: "ended", firstPDT: sessionBase})
		// 부분 carrier — M3 쓰기 경로가 만들 수 없는 형상이지만 DB 는 막지 않는다(방어 1줄).
		putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 4000,
			sessionID: prev})

		wall := sessionBase.Add(2 * time.Second)
		if _, _, err := newSessionStore(pool).Insert(context.Background(),
			segAt(stream, 1, wall, 4000), Seed{}, liveIngress()); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
		if c := carrierOf(t, pool, stream, 1); c.PDT == nil || !c.PDT.Equal(wall) {
			t.Fatalf("playback_pdt = %v, want %v(기저항 부재 → 벽시계)", c.PDT, wall)
		}
	})
}

// 전 픽스처 공통 불변 — M3 쓰기 경로가 만든 행은 session_id 와 playback_pdt 가 쌍으로 간다.
// "세 열 전부-아니면-전무"가 아니다: 키 파생 실패는 playback_s3_key 만 NULL 로 남긴다(설계 5.2).
func TestWrittenRowsPairSessionIDWithPlaybackPDT(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	stream := sessionStream("pdt-pair")
	ctx := context.Background()

	// 귀속되는 행과 비귀속 행을 섞는다.
	if _, _, err := store.Insert(ctx, segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}
	other := sessionStream("pdt-pair-none")
	if _, _, err := store.Insert(ctx, segAt(other, 0, sessionBase, 4000), Seed{}, currentOnly()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	for _, s := range []string{stream, other} {
		var paired bool
		if err := pool.QueryRow(ctx, `
			SELECT bool_and((session_id IS NULL) = (playback_pdt IS NULL))
			  FROM stream_segments WHERE stream_id = $1`, s).Scan(&paired); err != nil {
			t.Fatalf("쌍 동치 조회 실패: %v", err)
		}
		if !paired {
			t.Fatalf("stream=%q 에 session_id ⟺ playback_pdt 가 깨진 행이 있다", s)
		}
	}
}

// 설계 5.2 — 키 파생 실패는 오류가 아니라 playback_s3_key NULL 이다.
// 세션과 PDT 는 그대로 채워지고, ⓒ 가 주조만 막는다(다음 조각이 재시도한다).
func TestKeyDerivationFailureLeavesOnlyPlaybackKeyNull(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("key-fail")

	_, res, err := newKeylessStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase, 4000), eligibleSeed(time.Now().UTC()), liveIngress())
	if err != nil {
		t.Fatalf("키 파생 실패가 오류로 전파됐다(정책 거부여야 한다): %v", err)
	}
	if res.Seeded || res.Decline != DeclineNotSettleable {
		t.Fatalf("귀속이 not_settleable 이 아니다: %+v", res)
	}
	c := carrierOf(t, pool, stream, 0)
	if c.SessionID == nil || c.PDT == nil {
		t.Fatalf("세션·PDT 까지 비었다(키만 NULL 이어야 한다): %+v", c)
	}
	if c.Key != nil {
		t.Fatalf("playback_s3_key = %v, want NULL", c.Key)
	}
}

// 계획 단계 3 ⑺ — 기저 행 조회가 stream_segments_session_idx(3열)에 도달한다.
// **도달 판정과 비용 플랜 판정을 분리한다**: 소표에서는 계획이 뒤집혀 오판하므로
// enable_seqscan=off 로 경로 실재만 확인하고, 기본 비용 플랜은 기록만 남긴다.
func TestBasePDTQueryReachesSessionIndex(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := sessionStream("explain")
	sess := stream + "-s1"
	putSession(t, pool, fixtureSession{id: sess, stream: stream, startedAt: sessionBase, firstPDT: sessionBase})
	for seq := int64(0); seq < 50; seq++ {
		putRow(t, pool, fixtureRow{stream: stream, seq: seq,
			wall: sessionBase.Add(time.Duration(seq) * 4 * time.Second), durationMS: 4000,
			sessionID: sess, pdt: sessionBase.Add(time.Duration(seq) * 4 * time.Second),
			key: "k"})
	}
	if _, err := pool.Exec(ctx, `ANALYZE stream_segments`); err != nil {
		t.Fatalf("ANALYZE 실패: %v", err)
	}

	t.Logf("비용 플랜(기록용):\n%s", explain(t, pool, false, basePDTSQL, stream, sess, int64(50)))
	got := explain(t, pool, true, basePDTSQL, stream, sess, int64(50))
	if !strings.Contains(got, "stream_segments_session_idx") {
		t.Fatalf("기저 행 조회가 session_idx 에 도달하지 못했다 — 선행 컬럼 결손 의심:\n%s", got)
	}
}

// explain 은 계획 문자열을 돌려준다. noSeqScan 이면 순차 스캔을 끄고 **경로 실재**만 본다.
// 설정은 커넥션 지역이라 같은 커넥션을 잡고 쓰며, 돌려주기 전에 되돌린다.
func explain(t *testing.T, pool *pgxpool.Pool, noSeqScan bool, sql string, args ...any) string {
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
