package index

// 트랜잭션 형상의 PG 통합 검증(POK-195 M3 — 계획 4.1 ⑴~⑺ · 설계 4.9.1·5.4.1·6.5.2).
//
// 여기서 재는 것은 **조립**이다: 세션 판정 자체는 session 패키지가 자기 테스트로 재고,
// 이 파일은 "한 트랜잭션 안에서 ⑴~⑺ 이 그 순서로 일어나는가"를 장부 실물로 잰다.
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// ★ session·playback 임포트는 **이 테스트 파일 한정**이다.
//
// 비테스트 코드의 index → session 임포트는 0 이다(계획 3절 경계) — 그래서 세션 결정자는
// 이 패키지가 선언한 SessionDecider 로 주입된다. 아래 어댑터는 조립 지점
// (cmd/segment-indexer/session_wire.go)의 어댑터와 같은 매핑을 테스트에서 재현한 것이다.
//
// **쌍둥이가 어긋나지 않는지는 이 파일이 아니라 cmd 쪽 테스트가 지킨다** — 여기 어댑터가
// 맞아도 프로덕션 어댑터가 틀리면 이 패키지 테스트는 전부 통과한다(체크포인트 r2 H2):
//
//	TestSessionOpMapsOneToOne                       — 연산 매핑 1:1
//	TestSessionDeciderCarriesBaseSessionAndPlanEndToEnd — 기저 세션·Plan 왕복(실 PG)
//	TestSessionDeciderCopiesObservationFields       — 관측 5필드 복사(실 PG)
//
// 테스트 파일이 두 패키지를 다 봐도 순환은 없다 — 양쪽 비테스트 코드가 서로를 안 본다.
type registryDecider struct{ reg *session.Registry }

func (d registryDecider) Decide(ctx context.Context, tx pgx.Tx, in SessionInput, now time.Time) (SessionDecision, error) {
	op, err := sessionOpForTest(in.Op)
	if err != nil {
		return SessionDecision{}, err
	}
	dec, err := d.reg.Decide(ctx, tx, session.Input{
		StreamID: in.StreamID, Seq: in.Seq, StartWallUTC: in.StartWallUTC,
		DurationMS: in.DurationMS, Op: op,
		Obs: session.Observation{
			Publishing: in.Obs.Publishing, ObservedAt: in.Obs.ObservedAt,
			EpochStartedAt: in.Obs.EpochStartedAt, EpochKnown: in.Obs.EpochKnown,
			EpochSlack: in.Obs.EpochSlack,
		},
	}, now)
	if err != nil {
		return SessionDecision{}, err
	}
	return SessionDecision{
		Opens:         dec.Outcome == session.OutcomeOpen || dec.Outcome == session.OutcomeOpenFresh,
		SessionID:     dec.SessionID,
		BaseSessionID: dec.BaseSessionID,
		Plan:          dec,
	}, nil
}

func (d registryDecider) Open(ctx context.Context, tx pgx.Tx, dec SessionDecision, firstPDT time.Time) (string, error) {
	plan, ok := dec.Plan.(session.Decision)
	if !ok {
		return "", fmt.Errorf("테스트 어댑터: 개시 계획이 session.Decision 이 아니다(%T)", dec.Plan)
	}
	return d.reg.Open(ctx, tx, plan, firstPDT)
}

func sessionOpForTest(op SessionOp) (session.Op, error) {
	switch op {
	case SessionOpenOrCurrent:
		return session.OpenOrCurrent, nil
	case SessionCurrentOrOpenIfCorroborated:
		return session.CurrentOrOpenIfCorroborated, nil
	case SessionCurrentOnly:
		return session.CurrentOnly, nil
	}
	return 0, fmt.Errorf("테스트 어댑터: 알 수 없는 연산 %d", op)
}

// newSessionStore 는 실물 세션 결정자와 실물 키 파생을 끼운 store 다(M3 형상).
// 정책값은 config 기본값과 같다 — SESSION_FLOOR_SLACK 1초 · OBS_FRESH 30초.
func newSessionStore(pool *pgxpool.Pool) Store {
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second})
	return NewPGStore(pool, registryDecider{reg: reg}, playback.SegKey)
}

// newKeylessStore 는 키 파생만 실패하는 store 다 — 설계 5.2 "파생 실패는
// playback_s3_key 를 NULL 로 남긴다"를 재는 픽스처이며, 오류 전파 경로가 아니다.
func newKeylessStore(pool *pgxpool.Pool) Store {
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second})
	return NewPGStore(pool, registryDecider{reg: reg},
		func(string, int64) (string, error) { return "", errors.New("키 파생 실패(픽스처)") })
}

// 유입별 per-call 입력. 관측이 없는 것이 이 단계의 전제다(폴러 배선은 단계 4) —
// 영값 Observation 은 EpochKnown=false 라 ⓐ2 가 fail-closed 된다.
func liveIngress() SessionSource { return SessionSource{Op: SessionOpenOrCurrent} }
func currentOnly() SessionSource { return SessionSource{Op: SessionCurrentOnly} }
func scanIngress(obs SessionObservation) SessionSource {
	return SessionSource{Op: SessionCurrentOrOpenIfCorroborated, Obs: obs}
}

func sessionStream(name string) string { return "sesstest-" + name }

// sessionBase 는 픽스처의 기준 벽시계다. 마이크로초 미만이 없어 PG 왕복에도 값이 보존된다.
var sessionBase = time.Date(2026, 9, 2, 10, 0, 0, 0, time.UTC)

// segAt 은 벽시계와 길이를 직접 지정하는 조각이다(sampleRecord 는 고정 날짜라 세션 픽스처에 못 쓴다).
func segAt(stream string, seq int64, wall time.Time, durationMS int32) Record {
	return Record{
		StreamID: stream, Seq: seq, StartPTSMS: seq * 4000,
		StartWallUTC: wall.UTC(), DurationMS: durationMS,
		S3Key:       S3Key(stream, seq, wall),
		LocalPath:   fmt.Sprintf("/recordings/%s/%d.mp4", stream, seq),
		UploadState: UploadStatePending, Bytes: 1000 + seq,
	}
}

// fixtureSession 은 직접 SQL 로 심는 세션 행이다(M3 이전 장부·다른 상태의 재현).
type fixtureSession struct {
	id             string
	stream         string
	startedAt      time.Time
	state          string // "" 면 DDL 기본값 'live'
	firstPDT       time.Time
	targetDuration int32 // 0 이면 DDL 기본값 6
}

func putSession(t *testing.T, pool *pgxpool.Pool, s fixtureSession) {
	t.Helper()
	state := s.state
	if state == "" {
		state = "live"
	}
	td := s.targetDuration
	if td == 0 {
		td = 6
	}
	var firstPDT *time.Time
	if !s.firstPDT.IsZero() {
		utc := s.firstPDT.UTC()
		firstPDT = &utc
	}
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt, target_duration)
		VALUES ($1, $2, $3, $4, $5, $6)`,
		s.id, s.stream, s.startedAt.UTC(), state, firstPDT, td)
	if err != nil {
		t.Fatalf("세션 픽스처 삽입 실패 id=%q: %v", s.id, err)
	}
}

// fixtureRow 는 직접 SQL 로 심는 세그먼트 행이다 — carrier 를 부분적으로 비워 둘 수 있다.
type fixtureRow struct {
	stream     string
	seq        int64
	wall       time.Time
	durationMS int32
	sessionID  string // "" 면 NULL
	pdt        time.Time
	key        string // "" 면 NULL
}

func putRow(t *testing.T, pool *pgxpool.Pool, r fixtureRow) {
	t.Helper()
	var sessionID, key *string
	if r.sessionID != "" {
		sessionID = &r.sessionID
	}
	if r.key != "" {
		key = &r.key
	}
	var pdt *time.Time
	if !r.pdt.IsZero() {
		utc := r.pdt.UTC()
		pdt = &utc
	}
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments
			(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
			 upload_state, bytes, session_id, playback_pdt, playback_s3_key)
		VALUES ($1, $2, $3, $4, $5, $6, $7, 'pending', 1000, $8, $9, $10)`,
		r.stream, r.seq, r.seq*4000, r.wall.UTC(), r.durationMS,
		S3Key(r.stream, r.seq, r.wall), fmt.Sprintf("/recordings/%s/raw-%d.mp4", r.stream, r.seq),
		sessionID, pdt, key)
	if err != nil {
		t.Fatalf("행 픽스처 삽입 실패 seq=%d: %v", r.seq, err)
	}
}

// rowCarrier 는 장부에 실제로 남은 carrier 3열이다.
type rowCarrier struct {
	SessionID *string
	PDT       *time.Time
	Key       *string
}

func carrierOf(t *testing.T, pool *pgxpool.Pool, stream string, seq int64) rowCarrier {
	t.Helper()
	var c rowCarrier
	err := pool.QueryRow(context.Background(),
		`SELECT session_id, playback_pdt, playback_s3_key FROM stream_segments WHERE stream_id=$1 AND seq=$2`,
		stream, seq).Scan(&c.SessionID, &c.PDT, &c.Key)
	if err != nil {
		t.Fatalf("carrier 조회 실패 stream=%q seq=%d: %v", stream, seq, err)
	}
	if c.PDT != nil {
		utc := c.PDT.UTC()
		c.PDT = &utc
	}
	return c
}

// sessionRow 는 세션 표 한 줄에서 M3 가 쓰는 값 전부다.
type sessionRow struct {
	ID                string
	State             string
	StartedAt         time.Time
	FirstPDT          *time.Time
	TargetDuration    int32
	DiscontinuityBase int64
	EndReason         *string
	EndingAt          *time.Time
}

func sessionsOf(t *testing.T, pool *pgxpool.Pool, stream string) []sessionRow {
	t.Helper()
	rows, err := pool.Query(context.Background(), `
		SELECT session_id, state, started_at, first_pdt, target_duration,
		       discontinuity_base, end_reason, ending_at
		  FROM stream_sessions WHERE stream_id = $1
		 ORDER BY started_at, session_id`, stream)
	if err != nil {
		t.Fatalf("세션 조회 실패 stream=%q: %v", stream, err)
	}
	defer rows.Close()

	var out []sessionRow
	for rows.Next() {
		var s sessionRow
		if err := rows.Scan(&s.ID, &s.State, &s.StartedAt, &s.FirstPDT, &s.TargetDuration,
			&s.DiscontinuityBase, &s.EndReason, &s.EndingAt); err != nil {
			t.Fatalf("세션 스캔 실패: %v", err)
		}
		s.StartedAt = s.StartedAt.UTC()
		if s.FirstPDT != nil {
			utc := s.FirstPDT.UTC()
			s.FirstPDT = &utc
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("세션 조회 중 오류: %v", err)
	}
	return out
}

// s2_6a_session_live — 현 live 세션이 있으면 그 세션에 귀속되고 새 세션을 열지 않는다.
// carrier 3열이 한 트랜잭션에서 함께 채워진다(⑵ 계속 갈래 → ⑷ → ⑹ → ⑺).
func TestInsertAttributesSegmentToCurrentLiveSession(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("6a")
	sess := stream + "-s1"
	putSession(t, pool, fixtureSession{id: sess, stream: stream, startedAt: sessionBase, firstPDT: sessionBase})

	wall := sessionBase.Add(4 * time.Second)
	if _, _, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, wall, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	c := carrierOf(t, pool, stream, 0)
	if c.SessionID == nil || *c.SessionID != sess {
		t.Fatalf("귀속 세션 = %v, want %q", c.SessionID, sess)
	}
	if c.PDT == nil || !c.PDT.Equal(wall) {
		t.Fatalf("playback_pdt = %v, want %v(기저항 부재 → 벽시계)", c.PDT, wall)
	}
	if want := "dvr/" + stream + "/seg/000000.m4s"; c.Key == nil || *c.Key != want {
		t.Fatalf("playback_s3_key = %v, want %q", c.Key, want)
	}
	if got := sessionsOf(t, pool, stream); len(got) != 1 {
		t.Fatalf("세션 수 = %d, want 1 — 귀속 갈래는 세션을 열지 않는다", len(got))
	}
}

// 개시 갈래(현 live 부재) — 세션 행이 이 트랜잭션에서 생기고, 그 행의 값 셋이
// 계획 3절 (가)의 공통 규칙 그대로다(started_at = 개시 행 벽시계 · first_pdt = 그 행 PDT).
func TestInsertOpensSessionWhenNoLiveSessionExists(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("open")

	if _, _, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 1 {
		t.Fatalf("세션 수 = %d, want 1", len(sessions))
	}
	s := sessions[0]
	if s.State != "live" {
		t.Errorf("state = %q, want live", s.State)
	}
	if !s.StartedAt.Equal(sessionBase) {
		t.Errorf("started_at = %v, want %v(개시 행의 start_wall_utc)", s.StartedAt, sessionBase)
	}
	if s.FirstPDT == nil || !s.FirstPDT.Equal(sessionBase) {
		t.Errorf("first_pdt = %v, want %v", s.FirstPDT, sessionBase)
	}
	if s.TargetDuration != 6 {
		t.Errorf("target_duration = %d, want 6(하한)", s.TargetDuration)
	}

	c := carrierOf(t, pool, stream, 0)
	if c.SessionID == nil || *c.SessionID != s.ID {
		t.Fatalf("개시 행이 새 세션에 귀속되지 않았다: %v", c.SessionID)
	}
	if c.PDT == nil || !c.PDT.Equal(sessionBase) {
		t.Fatalf("개시 행 playback_pdt = %v, want %v(= first_pdt)", c.PDT, sessionBase)
	}
}

// s2_6b_session_ended + 계획 단계 3 ⑷ — ending·ended 세션은 새 INSERT 를 막지 않는다
// (one_live_uq 가 live 만 보므로). 그리고 기저항은 그 직전 세션의 마지막 행에서 온다.
func TestEndedOrEndingSessionDoesNotBlockNewSessionAndCarriesPDT(t *testing.T) {
	for _, state := range []string{"ending", "ended"} {
		t.Run(state, func(t *testing.T) {
			pool := newTestPool(t)
			stream := sessionStream("6b-" + state)
			prev := stream + "-prev"
			putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase,
				state: state, firstPDT: sessionBase})
			putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 4000,
				sessionID: prev, pdt: sessionBase, key: "dvr/" + stream + "/seg/000000.m4s"})

			// 벽시계는 2초만 흘렀지만 직전 행의 누적항은 4초다 — 누적항이 이겨야 한다.
			wall := sessionBase.Add(2 * time.Second)
			if _, _, err := newSessionStore(pool).Insert(context.Background(),
				segAt(stream, 1, wall, 4000), Seed{}, liveIngress()); err != nil {
				t.Fatalf("Insert 실패: %v", err)
			}

			sessions := sessionsOf(t, pool, stream)
			if len(sessions) != 2 {
				t.Fatalf("세션 수 = %d, want 2 — %s 세션이 새 개시를 막았다", len(sessions), state)
			}
			opened := sessions[1]
			wantPDT := sessionBase.Add(4 * time.Second)
			if opened.FirstPDT == nil || !opened.FirstPDT.Equal(wantPDT) {
				t.Fatalf("first_pdt = %v, want %v(직전 세션 마지막 행의 pdt+dur)", opened.FirstPDT, wantPDT)
			}
			if c := carrierOf(t, pool, stream, 1); c.PDT == nil || !c.PDT.Equal(wantPDT) {
				t.Fatalf("개시 행 playback_pdt = %v, want %v", c.PDT, wantPDT)
			}
		})
	}
}

// s2_6e_blind_window — 에폭 모호 구간의 조각은 세션을 열지 않고 carrier 를 비운다.
// 자격(ⓐ)이 참이어도 시작점 자격(ⓒ)이 없어 주조도 되지 않는다(not_settleable).
func TestInsertDoesNotOpenSessionInsideEpochAmbiguousWindow(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("6e")
	epoch := sessionBase.Add(10 * time.Second)
	obs := SessionObservation{
		Publishing: true, EpochKnown: true, ObservedAt: time.Now().UTC(),
		EpochStartedAt: epoch, EpochSlack: 10 * time.Second,
	}

	// 모호 구간 [epoch−10s, epoch) 안이다.
	wall := sessionBase.Add(5 * time.Second)
	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, wall, 4000), eligibleSeed(time.Now().UTC()), scanIngress(obs))
	if err != nil {
		t.Fatalf("Insert 는 성공해야 한다(세션만 거절): %v", err)
	}
	if res.Seeded || res.Decline != DeclineNotSettleable {
		t.Fatalf("귀속이 not_settleable 이 아니다: %+v", res)
	}
	if c := carrierOf(t, pool, stream, 0); c.SessionID != nil || c.PDT != nil || c.Key != nil {
		t.Fatalf("모호 구간인데 carrier 가 채워졌다: %+v", c)
	}
	if got := sessionsOf(t, pool, stream); len(got) != 0 {
		t.Fatalf("모호 구간에서 세션이 열렸다: %+v", got)
	}
}

// s2_6f_exact_epoch_boundary — 경계 자신은 모호 구간 밖이라 세션을 연다(변형 ⒜),
// 에폭 하한 밖은 열지 않는다(변형 ⒝).
func TestInsertOpensSessionAtExactEpochBoundaryButNotBelowFloor(t *testing.T) {
	epoch := sessionBase.Add(10 * time.Second)
	obs := func() SessionObservation {
		return SessionObservation{
			Publishing: true, EpochKnown: true, ObservedAt: time.Now().UTC(),
			EpochStartedAt: epoch, EpochSlack: 10 * time.Second,
		}
	}

	t.Run("경계_자신은_연다", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("6f-at")
		if _, _, err := newSessionStore(pool).Insert(context.Background(),
			segAt(stream, 0, epoch, 4000), Seed{}, scanIngress(obs())); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
		sessions := sessionsOf(t, pool, stream)
		if len(sessions) != 1 {
			t.Fatalf("세션 수 = %d, want 1", len(sessions))
		}
		// 5.4.1 전제 ⓐ — 세션은 그 스트림의 어떤 행보다 늦게 열리지 않는다.
		if !sessions[0].StartedAt.Equal(epoch) {
			t.Fatalf("started_at = %v, want %v(개시 행의 벽시계)", sessions[0].StartedAt, epoch)
		}
	})

	t.Run("하한_밖은_열지_않는다", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("6f-below")
		wall := epoch.Add(-11 * time.Second) // 에폭 − 여유(10초)보다 이르다
		if _, _, err := newSessionStore(pool).Insert(context.Background(),
			segAt(stream, 0, wall, 4000), Seed{}, scanIngress(obs())); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
		if got := sessionsOf(t, pool, stream); len(got) != 0 {
			t.Fatalf("에폭 하한 밖인데 세션이 열렸다: %+v", got)
		}
		if c := carrierOf(t, pool, stream, 0); c.SessionID != nil {
			t.Fatalf("에폭 하한 밖인데 귀속됐다: %+v", c)
		}
	})

	// 5.4.1 전제 ⓑ — 세션 개시와 행 기록이 같은 트랜잭션이다.
	// 행 INSERT 가 23505 로 죽으면 방금 연 세션도 함께 사라져야 한다.
	t.Run("행_INSERT_가_죽으면_세션도_남지_않는다", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("6f-atomic")
		putRow(t, pool, fixtureRow{stream: stream, seq: 7, wall: sessionBase, durationMS: 4000})

		dup := segAt(stream, 7, epoch, 4000)
		dup.LocalPath = "/recordings/" + stream + "/other.mp4" // PK 만 충돌시킨다
		out, _, err := newSessionStore(pool).Insert(context.Background(), dup, Seed{}, scanIngress(obs()))
		if err != nil || out != InsertSeqConflict {
			t.Fatalf("PK 충돌 판정 실패: out=%v err=%v", out, err)
		}
		if got := sessionsOf(t, pool, stream); len(got) != 0 {
			t.Fatalf("행이 롤백됐는데 세션이 남았다(원자성 위반): %+v", got)
		}
	})
}

// T11 — 반올림 EXTINF 가 현 세션 TD 를 넘으면 tx 안에서 분할한다(설계 4.9.1).
// 6.5초는 정수 나눗셈이면 6 이라 분할이 일어나지 않는다 — 반올림 7 이 계약이다.
func TestInsertSplitsSessionWhenRoundedDurationExceedsTargetDuration(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("t11")
	first := stream + "-s1"
	putSession(t, pool, fixtureSession{id: first, stream: stream, startedAt: sessionBase,
		firstPDT: sessionBase, targetDuration: 6})
	putRow(t, pool, fixtureRow{stream: stream, seq: 0, wall: sessionBase, durationMS: 4000,
		sessionID: first, pdt: sessionBase, key: "dvr/" + stream + "/seg/000000.m4s"})

	wall := sessionBase.Add(4 * time.Second)
	if _, _, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 1, wall, 6500), Seed{}, liveIngress()); err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 2 {
		t.Fatalf("세션 수 = %d, want 2(분할)", len(sessions))
	}
	old, opened := sessions[0], sessions[1]
	if old.State != "ending" {
		t.Errorf("직전 세션 state = %q, want ending", old.State)
	}
	if old.EndReason == nil || *old.EndReason != "td_exceeded" {
		t.Errorf("end_reason = %v, want td_exceeded", old.EndReason)
	}
	if old.EndingAt == nil {
		t.Error("ending_at 이 비었다")
	}
	if opened.TargetDuration != 7 {
		t.Errorf("새 세션 target_duration = %d, want 7(6500ms 반올림 — 정수 나눗셈이면 6)", opened.TargetDuration)
	}
	if opened.FirstPDT == nil || !opened.FirstPDT.Equal(wall) {
		t.Errorf("새 세션 first_pdt = %v, want %v", opened.FirstPDT, wall)
	}
	// PDT 단조 — 경계를 넘어도 앞 행보다 뒤로 가지 않는다.
	if c := carrierOf(t, pool, stream, 1); c.PDT == nil || !c.PDT.After(sessionBase) {
		t.Fatalf("분할 행 playback_pdt = %v, want > %v", c.PDT, sessionBase)
	}
}

// 계획 단계 3 ⑹ — 개시 갈래의 target_duration 은 max(6, round(dur/1000)) 이다.
// 값 규칙이 없으면 DDL 기본값 6 이 굳어 8초 조각이 연 세션이 M4 에서 영영 발행되지 않는다.
func TestOpenedSessionTargetDurationFollowsFormula(t *testing.T) {
	for _, tc := range []struct {
		name       string
		durationMS int32
		want       int32
	}{
		{"round_8_은_8", 7600, 8},
		{"round_4_는_하한_6", 4000, 6},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			stream := sessionStream("td-" + tc.name)
			if _, _, err := newSessionStore(pool).Insert(context.Background(),
				segAt(stream, 0, sessionBase, tc.durationMS), Seed{}, liveIngress()); err != nil {
				t.Fatalf("Insert 실패: %v", err)
			}
			sessions := sessionsOf(t, pool, stream)
			if len(sessions) != 1 {
				t.Fatalf("세션 수 = %d, want 1", len(sessions))
			}
			if sessions[0].TargetDuration != tc.want {
				t.Fatalf("target_duration = %d, want %d", sessions[0].TargetDuration, tc.want)
			}
		})
	}

	// ending 직후 첫 조각도 같은 규칙을 탄다(설계 6.2 :1206 국면 — live 부재라 개시 갈래).
	t.Run("ending_직후_첫_조각도_같은_규칙", func(t *testing.T) {
		pool := newTestPool(t)
		stream := sessionStream("td-after-ending")
		prev := stream + "-prev"
		putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase,
			state: "ending", firstPDT: sessionBase, targetDuration: 6})

		if _, _, err := newSessionStore(pool).Insert(context.Background(),
			segAt(stream, 0, sessionBase.Add(time.Minute), 7600), Seed{}, liveIngress()); err != nil {
			t.Fatalf("Insert 실패: %v", err)
		}
		sessions := sessionsOf(t, pool, stream)
		if len(sessions) != 2 {
			t.Fatalf("세션 수 = %d, want 2", len(sessions))
		}
		if sessions[1].TargetDuration != 8 {
			t.Fatalf("target_duration = %d, want 8", sessions[1].TargetDuration)
		}
	})
}

// 계획 단계 3 ⑻ — advisory 대기 중 관측이 낡으면 개시 갈래가 CurrentOnly 로 접힌다.
// 발사 시점 29초(< 30)지만 3.2초 대기 뒤에는 32.2초라 자격을 잃는다. 현 live 가 없으므로
// 결과는 비귀속·비개시이고, 귀속 어휘는 결정적으로 not_settleable 하나다.
//
// m1a(행 잠금·ⓐ1·stale_corroboration)와 다른 것을 잰다: 저 대기는 CTE 문장에서,
// 이 대기는 세션 결정 **앞**에서 일어난다.
func TestSessionOpenFoldsToCurrentOnlyWhenObservationStalesDuringAdvisoryWait(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("adv-obs")
	wg := holdAdvisory(t, pool, stream, 3200*time.Millisecond)
	defer wg.Wait()

	obs := SessionObservation{
		Publishing: true, EpochKnown: true,
		ObservedAt:     time.Now().UTC().Add(-29 * time.Second),
		EpochStartedAt: sessionBase,
	}
	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase.Add(time.Second), 4000),
		eligibleSeed(time.Now().UTC()), scanIngress(obs))
	if err != nil {
		t.Fatalf("INSERT 는 성공해야 한다(세션만 거절): %v", err)
	}
	if res.Seeded || res.Decline != DeclineNotSettleable {
		t.Fatalf("귀속이 not_settleable 이 아니다: %+v", res)
	}
	if got := sessionsOf(t, pool, stream); len(got) != 0 {
		t.Fatalf("대기 뒤 낡은 관측(32초)으로 세션이 열렸다: %+v", got)
	}
	if c := carrierOf(t, pool, stream, 0); c.SessionID != nil || c.PDT != nil {
		t.Fatalf("비귀속인데 carrier 가 채워졌다: %+v", c)
	}
}

// 계획 단계 3 ⑼ — M3 의 결속 한계를 문서화한다.
// 세션 종료 전이(offline → ending → ended)가 M4/M6 라, 같은 stream_id 의 연속 방송은
// 첫 live 세션에 계속 귀속된다. 발행 층이 없어 무해하며, 그 경로가 착지하면 s2_6b 가 실동한다.
func TestConsecutiveBroadcastsStayOnTheFirstLiveSession(t *testing.T) {
	pool := newTestPool(t)
	store := newSessionStore(pool)
	stream := sessionStream("rebroadcast")
	ctx := context.Background()

	if _, _, err := store.Insert(ctx, segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("첫 방송 Insert 실패: %v", err)
	}
	// 두 시간 뒤의 "다른 방송" — 관측도 유입도 새 방송이지만 세션은 그대로다.
	later := sessionBase.Add(2 * time.Hour)
	if _, _, err := store.Insert(ctx, segAt(stream, 1, later, 4000), Seed{}, liveIngress()); err != nil {
		t.Fatalf("두 번째 방송 Insert 실패: %v", err)
	}

	sessions := sessionsOf(t, pool, stream)
	if len(sessions) != 1 {
		t.Fatalf("세션 수 = %d, want 1 — M3 는 세션 종료 전이가 없어 계속 귀속된다(공시된 한계)", len(sessions))
	}
	first, second := carrierOf(t, pool, stream, 0), carrierOf(t, pool, stream, 1)
	if first.SessionID == nil || second.SessionID == nil || *first.SessionID != *second.SessionID {
		t.Fatalf("두 방송의 귀속 세션이 다르다: %v / %v", first.SessionID, second.SessionID)
	}
}

// R1 — one_live_uq 경합은 정상 경합이므로 재시도 가능한 sentinel 로 올라온다.
// 파생 규칙 결함(stream_sessions_pkey)과 달리 fatal 로 가지 않는다.
func TestConcurrentSessionOpenSurfacesContendedSentinel(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := sessionStream("contend")

	// 경쟁 트랜잭션이 같은 스트림의 live 세션을 먼저 만들고 잠시 뒤 커밋한다.
	// 우리 tx 의 Decide 는 미커밋 행을 못 보고 개시로 가며, 세션 INSERT 가 유니크
	// 인덱스에서 대기하다 커밋 순간 23505 를 받는다.
	blocker, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("경쟁 트랜잭션 시작 실패: %v", err)
	}
	if _, err := blocker.Exec(ctx,
		`INSERT INTO stream_sessions (session_id, stream_id, started_at) VALUES ($1, $2, $3)`,
		stream+"-rival", stream, sessionBase); err != nil {
		t.Fatalf("경쟁 세션 INSERT 실패: %v", err)
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		time.Sleep(800 * time.Millisecond)
		_ = blocker.Commit(ctx)
	}()
	defer wg.Wait()

	_, _, err = newSessionStore(pool).Insert(ctx,
		segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if !errors.Is(err, ErrSessionContended) {
		t.Fatalf("세션 경합이 ErrSessionContended 가 아니다: %v", err)
	}
	var rows int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM stream_segments WHERE stream_id=$1`, stream).Scan(&rows); err != nil {
		t.Fatalf("행 수 조회 실패: %v", err)
	}
	if rows != 0 {
		t.Fatalf("경합 실패 트랜잭션의 행이 남았다: %d", rows)
	}
}

// failingDecider 는 DB 오류를 그대로 돌려주는 결정자다. Decide 와 Open 두 자리를 각각 잰다.
type failingDecider struct {
	err   error
	opens bool
}

func (d failingDecider) Decide(context.Context, pgx.Tx, SessionInput, time.Time) (SessionDecision, error) {
	if d.opens {
		return SessionDecision{Opens: true}, nil
	}
	return SessionDecision{}, d.err
}

func (d failingDecider) Open(context.Context, pgx.Tx, SessionDecision, time.Time) (string, error) {
	return "", d.err
}

// 계획 4.3 ⒉ — **세션 결정의 DB 오류는 Decline 으로 접히지 않는다.**
// 접으면 "정책상 안 열었다"(다음 조각이 정상 처리)와 "DB 가 막았다"(재시도해야 한다)가
// 같은 신호가 되어, 장부가 조용히 비어 가는 동안 아무도 재시도하지 않는다.
func TestSessionDecisionDatabaseErrorPropagatesInsteadOfDeclining(t *testing.T) {
	for _, c := range []struct {
		name  string
		opens bool
	}{
		{"Decide_오류", false},
		{"Open_오류", true},
	} {
		t.Run(c.name, func(t *testing.T) {
			pool := newTestPool(t)
			ctx := context.Background()
			stream := sessionStream("decide-err-" + c.name)
			boom := errors.New("세션 표 접근 실패(픽스처)")
			store := NewPGStore(pool, failingDecider{err: boom, opens: c.opens}, playback.SegKey)

			_, res, err := store.Insert(ctx, segAt(stream, 0, sessionBase, 4000),
				eligibleSeed(time.Now().UTC()), liveIngress())
			if !errors.Is(err, boom) {
				t.Fatalf("DB 오류가 전파되지 않았다: %v", err)
			}
			if res.Seeded || res.Decline != DeclineNone {
				t.Fatalf("오류가 귀속 어휘로 접혔다: %+v", res)
			}
			var rows int
			if err := pool.QueryRow(ctx,
				`SELECT count(*) FROM stream_segments WHERE stream_id = $1`, stream).Scan(&rows); err != nil {
				t.Fatalf("행 수 조회 실패: %v", err)
			}
			if rows != 0 {
				t.Fatalf("오류 트랜잭션의 행이 남았다: %d", rows)
			}
		})
	}
}

// 계획 단계 3 ⑼ 대조군 — 현 live 세션이 있으면 **낡은 ⓐ2 스캔 조각도 귀속은 된다.**
//
// 접히는 것은 개시 권한뿐이고(CurrentOnly), carrier 3열은 채워지며 주조만 시간 재검에
// 걸려 거부된다(stale_corroboration). "ⓐ 불성립이면 언제나 carrier NULL"이 아니라는 것이
// 요점이다 — 그렇게 접으면 세션 중간에 NULL 구멍이 생겨 그 조각이 되감기 목록에서 사라진다
// (설계 6.5.2 "ⓐ 불성립 → CurrentOnly").
func TestStaleObservationStillAttributesToCurrentLiveSession(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	stream := sessionStream("stale-current")
	sess := stream + "-s1"
	putSession(t, pool, fixtureSession{id: sess, stream: stream, startedAt: sessionBase, firstPDT: sessionBase})

	// OBS_FRESH(30초)를 막 넘긴 관측 — ⓐ2 자격을 잃었다.
	stale := time.Now().UTC().Add(-31 * time.Second)
	obs := SessionObservation{
		Publishing: true, EpochKnown: true, ObservedAt: stale, EpochStartedAt: sessionBase,
	}
	// seed 도 ⓐ2 쌍이다 — 앵커가 ObservedAt, 신선도가 OBS_FRESH(설계 6.5.2).
	seed := Seed{
		Eligible: true, Reason: SeedReasonStateObs, Channel: SeedChannelScan,
		AnchorUTC: stale, Freshness: 30 * time.Second,
	}

	_, res, err := newSessionStore(pool).Insert(ctx,
		segAt(stream, 0, sessionBase.Add(4*time.Second), 4000), seed, scanIngress(obs))
	if err != nil {
		t.Fatalf("INSERT 는 성공해야 한다(주조만 거절): %v", err)
	}
	if res.Seeded || res.Decline != DeclineStaleCorroboration {
		t.Fatalf("귀속이 stale_corroboration 이 아니다: %+v", res)
	}
	c := carrierOf(t, pool, stream, 0)
	if c.SessionID == nil || *c.SessionID != sess || c.PDT == nil || c.Key == nil {
		t.Fatalf("낡은 관측이 귀속까지 지웠다(세션 중간 NULL 구멍): %+v", c)
	}
	if _, _, _, ok := cutoffRow(t, pool, stream); ok {
		t.Fatal("낡은 방증으로 주조됐다")
	}
}
