package index

// SeedResult 의 세션 개시 통로(POK-195 M4 — 계획 2.1 · 3절 형상 결정 2).
//
// Insert 가 돌려주는 SeedResult 는 이 트랜잭션이 **커밋한** 값의 사본이다. 루프가 DB 를
// 다시 묻지 않고 방금 쓴 행과 새로 연 세션을 아는 통로가 이것 하나라, 결과가 장부와
// 다르면 그 차이가 그대로 캐시의 어긋남이 된다. 그래서 결과를 장부 실물과 대조한다.

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// setDiscontinuityBase 는 픽스처 세션의 불연속 기준을 바꾼다(putSession 은 DDL 기본값 0 을 쓴다).
func setDiscontinuityBase(t *testing.T, pool *pgxpool.Pool, sessionID string, base int64) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`UPDATE stream_sessions SET discontinuity_base = $2 WHERE session_id = $1`,
		sessionID, base); err != nil {
		t.Fatalf("불연속 기준 픽스처 실패 %s: %v", sessionID, err)
	}
}

func TestInsertReportsOpenedSessionAndCommittedCarrier(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("carrier-open")

	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if !res.SessionOpened {
		t.Error("SessionOpened = false, want true — 방송 첫 조각이 세션을 열었다")
	}
	c := carrierOf(t, pool, stream, 0)
	if c.SessionID == nil || res.SessionID != *c.SessionID {
		t.Errorf("SessionID = %q, want 장부의 %v", res.SessionID, c.SessionID)
	}
	if !res.PlaybackPDT.Equal(sessionBase) {
		t.Errorf("PlaybackPDT = %v, want %v(기저항 부재 → 벽시계)", res.PlaybackPDT, sessionBase)
	}
	if want := "dvr/" + stream + "/seg/000000.m4s"; res.PlaybackS3Key != want {
		t.Errorf("PlaybackS3Key = %q, want %q", res.PlaybackS3Key, want)
	}
	if res.DurationMS != 4000 {
		t.Errorf("DurationMS = %d, want 4000", res.DurationMS)
	}
	if res.DiscontinuityBase != 0 || res.InheritsSession != "" || res.PrevFirstLocalPath != "" {
		t.Errorf("(DiscontinuityBase, InheritsSession, PrevFirstLocalPath) = (%d, %q, %q), want (0, \"\", \"\") — 비분할 개시는 승계하지 않는다",
			res.DiscontinuityBase, res.InheritsSession, res.PrevFirstLocalPath)
	}
}

func TestInsertReportsCurrentSessionWithoutOpening(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("carrier-current")
	st := newSessionStore(pool)
	ctx := context.Background()
	_, first, err := st.Insert(ctx, segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("첫 Insert 실패: %v", err)
	}

	// 벽시계가 1초 뒤처진 조각 — PDT 는 누적항(4초)이 이긴다. 결과가 벽시계를 되돌려주면 잡힌다.
	_, res, err := st.Insert(ctx, segAt(stream, 1, sessionBase.Add(3*time.Second), 3500), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("둘째 Insert 실패: %v", err)
	}

	if res.SessionOpened {
		t.Error("SessionOpened = true, want false — 귀속 갈래는 세션을 열지 않는다")
	}
	if res.SessionID == "" || res.SessionID != first.SessionID {
		t.Errorf("SessionID = %q, want 첫 조각이 연 %q", res.SessionID, first.SessionID)
	}
	if want := sessionBase.Add(4 * time.Second); !res.PlaybackPDT.Equal(want) {
		t.Errorf("PlaybackPDT = %v, want %v(누적항 = 직전 PDT + 직전 길이)", res.PlaybackPDT, want)
	}
	if res.DurationMS != 3500 {
		t.Errorf("DurationMS = %d, want 3500 — 이 조각의 길이다", res.DurationMS)
	}
}

func TestInsertReportsInheritedBaseWhenTDSplitOpensSession(t *testing.T) {
	// 새 세션 행에 무엇을 쓸지는 결정자(Plan)가 정하고 index 는 그 계획을 열지 않는다.
	// 결과의 base 가 장부와 다르면 렌더의 DISC-SEQ 가 분할 지점에서 뒤로 간다.
	pool := newTestPool(t)
	stream := sessionStream("carrier-td")
	old := stream + "-s1"
	putSession(t, pool, fixtureSession{id: old, stream: stream, startedAt: sessionBase, firstPDT: sessionBase})
	setDiscontinuityBase(t, pool, old, 3)

	// round(6.5) = 7 > TD 6 → TD 분할.
	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase.Add(4*time.Second), 6500), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if !res.SessionOpened || res.SessionID == "" || res.SessionID == old {
		t.Fatalf("(SessionOpened, SessionID) = (%v, %q), want (true, %q 가 아닌 새 세션)", res.SessionOpened, res.SessionID, old)
	}
	if res.DiscontinuityBase != 3 {
		t.Errorf("DiscontinuityBase = %d, want 3 — TD 분할은 기준을 승계한다(설계 4.9.1)", res.DiscontinuityBase)
	}
	if res.InheritsSession != "" {
		t.Errorf("InheritsSession = %q, want \"\" — TD 분할은 inherits_session 을 쓰지 않는다", res.InheritsSession)
	}
	for _, s := range sessionsOf(t, pool, stream) {
		if s.ID == res.SessionID && s.DiscontinuityBase != res.DiscontinuityBase {
			t.Errorf("장부의 새 세션 base = %d, 결과 = %d — 결과가 장부와 다르다", s.DiscontinuityBase, res.DiscontinuityBase)
		}
	}
}

// TD 운반(계획 「④ 캐시 착수 메모」 r2 cc M1) — 개시 되읽기가 새 회차의 target_duration 을 결과에
// 싣는다. 결과에 없으면 되감기 캐시의 회차 TD 가 0 이 되고, 목록 머리가 #EXT-X-TARGETDURATION:0
// (RFC 8216bis-22 4.4.3.1 「MUST be at least 1」 위반)이라 발행 전 검사 S5 가 모든 발행을 멈춘다.
// 렌더 때 장부를 다시 묻는 우회는 프로필 4절 「합성은 평시 DB 조회 0」에 걸리므로 통로가 이것뿐이다.
//
// 값은 DDL 기본값 6 과 겹치지 않게 골랐다 — 첫 조각 7.6초는 반올림 8, 이어지는 8.6초는 반올림 9 > 8
// 이라 TD 분할로 새 회차(TD 9)를 연다. 분할 개시도 옛 회차가 아니라 새 회차의 값을 실어야 한다.
func TestInsertReportsTargetDurationWrittenAtOpening(t *testing.T) {
	pool := newTestPool(t)
	stream := sessionStream("carrier-td-value")
	st := newSessionStore(pool)
	ctx := context.Background()

	_, opened, err := st.Insert(ctx, segAt(stream, 0, sessionBase, 7600), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("첫 Insert 실패: %v", err)
	}
	_, split, err := st.Insert(ctx, segAt(stream, 1, sessionBase.Add(7600*time.Millisecond), 8600), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("분할 Insert 실패: %v", err)
	}

	if !opened.SessionOpened || opened.TargetDuration != 8 {
		t.Errorf("개시 (SessionOpened, TargetDuration) = (%v, %d), want (true, 8)", opened.SessionOpened, opened.TargetDuration)
	}
	if !split.SessionOpened || split.SessionID == opened.SessionID || split.TargetDuration != 9 {
		t.Errorf("분할 개시 (SessionOpened, 새 회차, TargetDuration) = (%v, %v, %d), want (true, true, 9)",
			split.SessionOpened, split.SessionID != opened.SessionID, split.TargetDuration)
	}
	for _, s := range sessionsOf(t, pool, stream) {
		for _, res := range []SeedResult{opened, split} {
			if s.ID == res.SessionID && s.TargetDuration != res.TargetDuration {
				t.Errorf("장부의 회차 %s TD = %d, 결과 = %d — 결과가 장부와 다르다", s.ID, s.TargetDuration, res.TargetDuration)
			}
		}
	}
}

// inheritingDecider 는 개시 때 inherits_session 까지 쓰는 결정자다 — 재접속 계승 갈래(M4 PR ⓒ)가
// 새 세션 행에 쓸 값을 흉내 낸다. index 는 그 값을 결정자의 계획에서 읽지 않고 행에서 되읽으므로,
// 누가 쓰든 결과에 실린다는 것을 잰다.
type inheritingDecider struct {
	registryDecider
	prev string
}

func (d inheritingDecider) Open(ctx context.Context, tx pgx.Tx, dec SessionDecision, firstPDT time.Time) (string, error) {
	id, err := d.registryDecider.Open(ctx, tx, dec, firstPDT)
	if err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `UPDATE stream_sessions SET inherits_session = $2 WHERE session_id = $1`, id, d.prev); err != nil {
		return "", err
	}
	return id, nil
}

func TestInsertReportsInheritsSessionWrittenAtOpening(t *testing.T) {
	// 계획 뮤테이션 36(SeedResult.InheritsSession 미대입)의 생산 측 — 계승이 실리지 않으면 캐시가
	// 개시 시점에 계승 접두를 세우지 못해 DISC-SEQ 만 승계되고 접두가 빠진 목록이 나간다.
	pool := newTestPool(t)
	stream := sessionStream("carrier-inherits")
	prev := stream + "-prev"
	putSession(t, pool, fixtureSession{id: prev, stream: stream, startedAt: sessionBase.Add(-time.Hour), state: "ended"})
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second})
	store := NewPGStore(pool, inheritingDecider{registryDecider: registryDecider{reg: reg}, prev: prev}, playback.SegKey)

	_, res, err := store.Insert(context.Background(), segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if !res.SessionOpened || res.InheritsSession != prev {
		t.Errorf("(SessionOpened, InheritsSession) = (%v, %q), want (true, %q)", res.SessionOpened, res.InheritsSession, prev)
	}
}

func TestInsertReportsEmptyCarrierWhenSegmentIsUnattributed(t *testing.T) {
	// 현 세션 없는 CurrentOnly — 행은 들어가지만 어느 세션에도 속하지 않는다.
	pool := newTestPool(t)
	stream := sessionStream("carrier-none")

	_, res, err := newSessionStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase, 4000), Seed{}, currentOnly())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if res.SessionOpened || res.SessionID != "" || !res.PlaybackPDT.IsZero() || res.PlaybackS3Key != "" {
		t.Errorf("(SessionOpened, SessionID, PlaybackPDT, PlaybackS3Key) = (%v, %q, %v, %q), want 전부 영값 — 장부 carrier 가 NULL 이다",
			res.SessionOpened, res.SessionID, res.PlaybackPDT, res.PlaybackS3Key)
	}
	if res.DurationMS != 4000 {
		t.Errorf("DurationMS = %d, want 4000 — 행은 들어갔다", res.DurationMS)
	}
}

func TestInsertReportsOpeningWithEmptyKeyWhenKeyDerivationFails(t *testing.T) {
	// 설계 5.2 — 키 파생 실패는 playback_s3_key 만 NULL 로 남긴다. 결과도 그 NULL 을 그대로 싣는다.
	pool := newTestPool(t)
	stream := sessionStream("carrier-keyless")

	_, res, err := newKeylessStore(pool).Insert(context.Background(),
		segAt(stream, 0, sessionBase, 4000), Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if !res.SessionOpened || res.SessionID == "" || res.PlaybackPDT.IsZero() {
		t.Errorf("(SessionOpened, SessionID, PlaybackPDT) = (%v, %q, %v), want 개시·귀속·PDT 는 그대로",
			res.SessionOpened, res.SessionID, res.PlaybackPDT)
	}
	if res.PlaybackS3Key != "" {
		t.Errorf("PlaybackS3Key = %q, want \"\" — 장부의 키가 NULL 이다", res.PlaybackS3Key)
	}
}

func TestInsertDoesNotReportOpeningRolledBackByDuplicatePath(t *testing.T) {
	// 개시는 행 삽입과 한 트랜잭션이다. 삽입이 23505 로 롤백되면 세션 행도 사라지는데,
	// 결과가 개시를 보고하면 루프가 없는 세션의 init 을 요청하고 직전 세션을 강등한다.
	pool := newTestPool(t)
	stream := sessionStream("carrier-dup")
	st := newSessionStore(pool)
	ctx := context.Background()
	first := segAt(stream, 0, sessionBase, 4000)
	if _, _, err := st.Insert(ctx, first, Seed{}, liveIngress()); err != nil {
		t.Fatalf("첫 Insert 실패: %v", err)
	}
	// live 가 없게 만들어 다음 유입이 개시 갈래를 타게 한다.
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state = 'ending' WHERE stream_id = $1`, stream); err != nil {
		t.Fatalf("세션 종료 픽스처 실패: %v", err)
	}
	dup := segAt(stream, 1, sessionBase.Add(4*time.Second), 4000)
	dup.LocalPath = first.LocalPath

	out, res, err := st.Insert(ctx, dup, Seed{}, liveIngress())
	if err != nil {
		t.Fatalf("Insert 실패: %v", err)
	}

	if out != InsertDuplicatePath {
		t.Fatalf("outcome = %v, want %v", out, InsertDuplicatePath)
	}
	if res.SessionOpened || res.SessionID != "" {
		t.Errorf("(SessionOpened, SessionID) = (%v, %q), want (false, \"\") — 롤백된 개시를 보고했다", res.SessionOpened, res.SessionID)
	}
	if n := len(sessionsOf(t, pool, stream)); n != 1 {
		t.Errorf("세션 수 = %d, want 1 — 롤백된 세션 행이 남았다", n)
	}
}
