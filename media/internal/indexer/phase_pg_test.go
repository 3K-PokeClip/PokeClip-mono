package indexer

// 국면별 주조 검증(POK-195 M3 단계 4 — 설계 6.5.3 S1 6국면 · 9.1 f0~f6d · s2_* · s3).
//
// 여기서 재는 것은 **국면 → 주조 결과**다: 어느 유입이 살아 있고 관측이 어떤 상태일 때
// 실제로 컷오프가 생기는가(또는 생기지 않는가). 판정 단위 검증은 seed_judgment_test.go 가,
// 트랜잭션 형상은 index/session_tx_pg_test.go 가 각자 맡는다 — 이 파일은 **인덱서부터
// 장부까지 관통**해야만 드러나는 것(배선·유입 채널·실관측)을 잰다.
//
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄).
// 이 파일의 케이스는 PG_DSN 이 없으면 전부 skip 되는데 skip 은 성공으로 집계된다 —
// DB 를 안 띄운 실행이 "녹색"으로 보이고 국면 게이트는 하나도 검증되지 않는다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 국면 게이트가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

func resetPhaseTables(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx,
		"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
	return err
}

// ---------------------------------------------------------------------------
// 세션 결정자 어댑터 — **이 테스트 파일 한정**이다.
//
// 비테스트 코드의 indexer → session 임포트는 0 이다(계획 3절 경계): 장부(index)가 자기
// 인터페이스를 선언하고 조립 지점(cmd/segment-indexer/session_wire.go)이 이어 붙인다.
// 여기 어댑터는 그 조립을 테스트에서 재현한 것이며, **프로덕션 어댑터가 어긋나지 않는지는
// 이 파일이 아니라 cmd 쪽 테스트가 지킨다**(session_wire_test.go — 체크포인트 r2 H2).
// ---------------------------------------------------------------------------

type phaseDecider struct{ reg *session.Registry }

func (d phaseDecider) Decide(ctx context.Context, tx pgx.Tx, in index.SessionInput, now time.Time) (index.SessionDecision, error) {
	var op session.Op
	switch in.Op {
	case index.SessionOpenOrCurrent:
		op = session.OpenOrCurrent
	case index.SessionCurrentOrOpenIfCorroborated:
		op = session.CurrentOrOpenIfCorroborated
	case index.SessionCurrentOnly:
		op = session.CurrentOnly
	default:
		return index.SessionDecision{}, fmt.Errorf("테스트 어댑터: 알 수 없는 연산 %d", in.Op)
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
		return index.SessionDecision{}, err
	}
	return index.SessionDecision{
		Opens:         dec.Outcome == session.OutcomeOpen || dec.Outcome == session.OutcomeOpenFresh,
		SessionID:     dec.SessionID,
		BaseSessionID: dec.BaseSessionID,
		Plan:          dec,
	}, nil
}

func (d phaseDecider) Open(ctx context.Context, tx pgx.Tx, dec index.SessionDecision, firstPDT time.Time) (string, error) {
	plan, ok := dec.Plan.(session.Decision)
	if !ok {
		return "", fmt.Errorf("테스트 어댑터: 개시 계획이 session.Decision 이 아니다(%T)", dec.Plan)
	}
	return d.reg.Open(ctx, tx, plan, firstPDT)
}

// ---------------------------------------------------------------------------
// 국면 픽스처
// ---------------------------------------------------------------------------

// phase 는 국면 하나의 주입 상태다 — "무엇이 죽어 있는가"를 값으로 적는다.
//
// **이 파일이 재는 것은 워처 실패의 *결과 상태*이지 실패 자체가 아니다.** 조립·기동
// 실패는 recording.Watcher 안에서 일어나 이 패키지에서는 만들 수 없고, 만들려고
// 프로덕션에 고장 주입 이음매를 뚫는 것은 원칙 5 위반이다. 실패를 실제로 일으켜
// **w=nil → 널 오브젝트 adopt** 까지 잇는 것은 cmd 쪽 두 케이스다:
//
//	TestAssembleWatcherFailurePoints              — 조립(NewWatcher)·기동(Start) 실패가 에러로 합쳐진다
//	TestWatcherStartFailureFeedsNullObjectAdopter — **실제 Start 실패**의 w=nil 이 indexer.New 에서 널 오브젝트가 된다
//
// 여기 아래 표는 **그 널 오브젝트 상태에서 훅·스캔 주조가 유지되는가**(f6c·f6d)를
// 실 장부로 잇는 뒷절반이다. 두 절반이 합쳐져야 f6a·f6b 국면의 주조가 단언된다.
type phase struct {
	// watcher 가 거짓이면 워처 부재(강등)다 — 되돌림이 널 오브젝트로 간다.
	watcher bool
	// reattached 가 참이면 널 오브젝트에 **재장착 실패**(SetAdopter(nil)) 경로로 도달한다.
	// 거짓이면 조립 시점 경로(New 의 nil 가드)다 — 인덱서 층에서 갈리는 것은 이 두 가드뿐이고,
	// 어느 워처 실패점이 그 상태를 만들었는지는 위 cmd 케이스가 잰다.
	reattached bool
}

type phaseFixture struct {
	t        *testing.T
	pool     *pgxpool.Pool
	root     string
	stream   string
	store    index.Store
	ix       *Indexer
	adopt    Adopter
	degraded phase
	observer *fakeObserver
	logs     *logCapture
}

func newPhaseFixture(t *testing.T, name string, p phase) *phaseFixture {
	t.Helper()
	pool := pgtest.Pool(t, "indexer", index.EnsureSchema, resetPhaseTables)

	// 정책값은 config 기본값과 같다 — SESSION_FLOOR_SLACK 1초 · OBS_FRESH 30초.
	reg := session.New(session.Options{FloorSlack: time.Second, ObsFresh: 30 * time.Second})
	store := index.NewPGStore(pool, phaseDecider{reg: reg}, playback.SegKey)

	f := &phaseFixture{
		t: t, pool: pool, root: t.TempDir(),
		stream:   "phase-" + name,
		degraded: p,
		observer: newObserver(),
		logs:     &logCapture{},
	}
	if p.watcher {
		f.adopt = &fakeAdopter{}
	}
	f.store = store
	f.rebuild(4000)
	return f
}

// rebuild 는 같은 장부 위에 인덱서를 **새로** 만든다 — 메모리 이력(커서·경로)이 비므로
// 프로세스 재기동의 재현이기도 하다. 조각 길이는 프로브가 정하므로 인자로 받는다.
func (f *phaseFixture) rebuild(durationMS int64) {
	f.t.Helper()
	opt := testOptions()
	opt.SeedEnabled = true
	f.ix = New(f.store, func(string) (int64, error) { return durationMS, nil },
		f.adopt, nil, f.observer, opt, slog.New(f.logs))
	if f.degraded.reattached {
		// 재장착이 실패한 경로 — 같은 널 오브젝트에 다른 가드로 도달한다.
		f.ix.SetAdopter(nil)
	}
}

// publishing 은 "지금 송출 중"인 관측을 심는다(tier ⓘ). epochAgo 는 에폭이 얼마나 전인가다.
func (f *phaseFixture) publishing(observedAgo, epochAgo time.Duration) {
	f.observer.set(f.stream, mtxstate.Observation{
		Publishing:     true,
		ObservedAt:     time.Now().UTC().Add(-observedAgo),
		EpochStartedAt: time.Now().UTC().Add(-epochAgo),
		EpochKnown:     true,
		Tier:           mtxstate.TierOnlineTime,
	})
}

// segment 는 wallAgo 만큼 과거의 벽시계를 가진 조각 파일을 만든다.
// mtime 은 충분히 과거로 돌린다 — 성장 중이 아니어야 유휴 판정을 통과한다.
func (f *phaseFixture) segment(wallAgo time.Duration, reason recording.CompletionReason) recording.Segment {
	f.t.Helper()
	wall := time.Now().UTC().Add(-wallAgo)
	dir := f.root + "/" + f.stream
	if err := os.MkdirAll(dir, 0o755); err != nil {
		f.t.Fatalf("디렉토리 생성 실패: %v", err)
	}
	p := dir + "/" + segName(wall, 0)
	if err := os.WriteFile(p, make([]byte, 1000), 0o600); err != nil {
		f.t.Fatalf("파일 생성 실패: %v", err)
	}
	if err := os.Chtimes(p, time.Now().Add(-time.Hour), time.Now().Add(-time.Hour)); err != nil {
		f.t.Fatalf("mtime 조정 실패: %v", err)
	}
	seg, err := recording.ParseSegmentPath(f.root, p)
	if err != nil {
		f.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = reason
	return seg
}

// ingest 는 유입 채널 하나로 조각을 흘려 넣는다.
//
//	watcher·hook — Handle 직행(워처 FIFO·훅 리더가 하는 일이 그것이다)
//	scan         — 실물 Scan(전수 수집 → 스트림 루프 → ReasonScan Handle)
func (f *phaseFixture) ingest(reason recording.CompletionReason, wallAgo time.Duration) {
	f.t.Helper()
	seg := f.segment(wallAgo, reason)
	if reason == recording.ReasonScan {
		if err := f.ix.Scan(context.Background(), f.root); err != nil {
			f.t.Fatalf("Scan 실패: %v", err)
		}
		return
	}
	if err := f.ix.Handle(context.Background(), seg); err != nil {
		f.t.Fatalf("Handle 실패: %v", err)
	}
}

// cutoff 는 장부에 남은 컷오프다. ok=false 면 미주조다.
func (f *phaseFixture) cutoff() (seq int64, reason, channel string, ok bool) {
	f.t.Helper()
	err := f.pool.QueryRow(context.Background(),
		`SELECT cutoff_seq, seed_reason, seed_channel FROM stream_cutoffs WHERE stream_id = $1`,
		f.stream).Scan(&seq, &reason, &channel)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, "", "", false
	}
	if err != nil {
		f.t.Fatalf("컷오프 조회 실패: %v", err)
	}
	return seq, reason, channel, true
}

// phaseSession 은 이 파일이 세션 표에서 보는 값 전부다(개시 여부·TD 판정).
type phaseSession struct {
	id             string
	targetDuration int32
}

func (f *phaseFixture) sessions() []phaseSession {
	f.t.Helper()
	rows, err := f.pool.Query(context.Background(),
		`SELECT session_id, target_duration FROM stream_sessions WHERE stream_id = $1 ORDER BY started_at`,
		f.stream)
	if err != nil {
		f.t.Fatalf("세션 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []phaseSession
	for rows.Next() {
		var s phaseSession
		if err := rows.Scan(&s.id, &s.targetDuration); err != nil {
			f.t.Fatalf("세션 스캔 실패: %v", err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		f.t.Fatalf("세션 조회 중 오류: %v", err)
	}
	return out
}

// carrierAt 은 그 행의 세션 귀속 여부다.
func (f *phaseFixture) carrierAt(seq int64) (sessionID *string) {
	f.t.Helper()
	if err := f.pool.QueryRow(context.Background(),
		`SELECT session_id FROM stream_segments WHERE stream_id = $1 AND seq = $2`,
		f.stream, seq).Scan(&sessionID); err != nil {
		f.t.Fatalf("carrier 조회 실패 seq=%d: %v", seq, err)
	}
	return sessionID
}

func (f *phaseFixture) rowCount() int {
	f.t.Helper()
	var n int
	if err := f.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM stream_segments WHERE stream_id = $1`, f.stream).Scan(&n); err != nil {
		f.t.Fatalf("행 수 조회 실패: %v", err)
	}
	return n
}

// mustSeed 는 주조와 그 방증·채널을 함께 단언한다(f0~f3·f6c·f6d 의 공통 형태).
func (f *phaseFixture) mustSeed(wantReason index.SeedReason, wantChannels ...index.SeedChannel) {
	f.t.Helper()
	seq, reason, channel, ok := f.cutoff()
	if !ok {
		f.t.Fatalf("이 국면에서 컷오프가 주조되지 않았다(행 %d개)", f.rowCount())
	}
	if seq != 0 {
		f.t.Errorf("cutoff_seq = %d, want 0 — 첫 조각이 컷오프여야 한다", seq)
	}
	if reason != string(wantReason) {
		f.t.Errorf("seed_reason = %q, want %q", reason, wantReason)
	}
	matched := false
	for _, c := range wantChannels {
		if channel == string(c) {
			matched = true
		}
	}
	if !matched {
		f.t.Errorf("seed_channel = %q, want one of %v", channel, wantChannels)
	}
}

func (f *phaseFixture) mustNotSeed(why string) {
	f.t.Helper()
	if seq, reason, channel, ok := f.cutoff(); ok {
		f.t.Fatalf("%s — 그런데 컷오프가 생겼다(seq=%d reason=%q channel=%q)", why, seq, reason, channel)
	}
}

// ---------------------------------------------------------------------------
// ⒡-S1 라이브 진행성 — 국면별 주조
// ---------------------------------------------------------------------------

// f0_normal_seeds — 정상 국면(워처·훅·mtxstate 전부 생존, 실패 주입 0)에서 첫 조각이
// 컷오프를 만든다. 계약 S1 문언이 첫 자리로 든 "정상 운영"이며, 모든 강등 경로를 재면서
// 주 경로를 안 재는 상태를 막는 leaf 다.
func TestPhaseF0NormalSeeds(t *testing.T) {
	f := newPhaseFixture(t, "f0", phase{watcher: true})
	f.publishing(2*time.Second, 10*time.Minute)

	start := time.Now()
	f.ingest(recording.ReasonNextFile, 4*time.Second)

	f.mustSeed(index.SeedReasonLiveIngress, index.SeedChannelWatcher, index.SeedChannelHook)
	// normal_to_seed_seconds — 판정이 아니라 기록이다(설계 9.1 f0).
	t.Logf("normal_to_seed_seconds=%.3f", time.Since(start).Seconds())
}

// f1_hook_degraded — 훅만 차단(워처·mtxstate 정상) → 워처가 그대로 ⓐ1 로 주조한다.
// 상한이 불변인 것이 요점이다: 훅은 1차 신호일 뿐이고 워처가 같은 방증을 준다.
func TestPhaseF1HookDegraded(t *testing.T) {
	f := newPhaseFixture(t, "f1", phase{watcher: true})
	f.publishing(2*time.Second, 10*time.Minute)

	f.ingest(recording.ReasonNextFile, 4*time.Second)

	f.mustSeed(index.SeedReasonLiveIngress, index.SeedChannelWatcher)
}

// f2_watch_unobserved — mtxstate 만 차단(관측 없음) → 훅·워처가 ⓐ1 로 주조한다.
// ⓐ2 는 ⓐ1 의 보강재이지 전제가 아니다.
func TestPhaseF2WatchUnobserved(t *testing.T) {
	f := newPhaseFixture(t, "f2", phase{watcher: true})
	// 관측을 심지 않는다 — 폴러 사망·미배선과 같은 상태(EpochKnown=false).

	f.ingest(recording.ReasonHook, 4*time.Second)

	f.mustSeed(index.SeedReasonLiveIngress, index.SeedChannelHook)
}

// f3_scan_only — 워처·훅을 함께 차단하고 스캔만 살린다(+ publishing 관측) →
// ⓐ2 로 주조하고, **그 트랜잭션이 CurrentOrOpenIfCorroborated 로 세션을 연다**.
//
// 이월 단언(계획 단계 4 검증): 스캔 유입이 연 세션에도 개시 갈래 공통 규칙
// target_duration = max(6, round(dur/1000)) 이 그대로 적용된다 — 값 규칙이 유입 채널과
// 무관함을 여기서 실측한다(단계 3 은 널 관측 전제라 ⓐ1 개시만 쟀다).
func TestPhaseF3ScanOnlySeedsAndOpensSession(t *testing.T) {
	f := newPhaseFixture(t, "f3", phase{watcher: false})
	f.publishing(2*time.Second, 10*time.Minute)

	f.ingest(recording.ReasonScan, 4*time.Second)

	f.mustSeed(index.SeedReasonStateObs, index.SeedChannelScan)

	sessions := f.sessions()
	if len(sessions) != 1 {
		t.Fatalf("세션이 %d개다 — 스캔 유입이 ⓐ2 로 세션을 열어야 한다", len(sessions))
	}
	if sid := f.carrierAt(0); sid == nil || *sid != sessions[0].id {
		t.Fatalf("컷오프 행이 그 세션에 귀속되지 않았다: %v", sid)
	}
	// 4초 조각 → round(4000/1000)=4 → 하한 6 이 이긴다.
	if sessions[0].targetDuration != 6 {
		t.Errorf("target_duration = %d, want 6 — 개시 갈래 공통 규칙이 스캔 유입에도 적용돼야 한다",
			sessions[0].targetDuration)
	}
}

// f3 의 이월 단언 둘째 갈래 — 하한이 아니라 반올림이 이기는 국면.
// 정수 나눗셈이면 6 이 나오는 6.5초 조각으로 잰다(6500ms → round 7).
func TestPhaseF3ScanOpenedSessionTargetDurationRounds(t *testing.T) {
	f := newPhaseFixture(t, "f3td", phase{watcher: false})
	f.publishing(2*time.Second, 10*time.Minute)
	// 조각 길이 6.5초 — 기대 길이(4초)에서 벗어나 H8 이 불연속으로 볼 뿐, 기록은 그대로다.
	f.rebuild(6500)

	f.ingest(recording.ReasonScan, 4*time.Second)

	sessions := f.sessions()
	if len(sessions) != 1 {
		t.Fatalf("세션이 %d개다", len(sessions))
	}
	if sessions[0].targetDuration != 7 {
		t.Errorf("target_duration = %d, want 7 — 정수 나눗셈(6)이 아니라 반올림이다",
			sessions[0].targetDuration)
	}
}

// f4_all_lost — 워처·훅·mtxstate 전부 차단(스캔만 생존, publishing 관측 없음) → 미주조.
// **미주조가 정상**인 국면이다: 알람(rewind_cutoff_absent)은 4.1(c) tick 소유라 M4 다.
func TestPhaseF4AllLostDoesNotSeed(t *testing.T) {
	f := newPhaseFixture(t, "f4", phase{watcher: false})

	f.ingest(recording.ReasonScan, 4*time.Second)

	if f.rowCount() != 1 {
		t.Fatalf("행이 %d개다 — 인덱싱 자체는 계속돼야 한다", f.rowCount())
	}
	f.mustNotSeed("전 유입 상실 국면에서는 방증이 없다")
	if sid := f.carrierAt(0); sid != nil {
		t.Errorf("세션이 열렸다(session_id=%q) — 관측 없는 스캔은 열지 않는다", *sid)
	}
}

// f6c_hook_alive — 워처 부재 국면에서 훅만 살린다 → 훅이 ⓐ1 로 주조한다.
// f6d_scan_only — 같은 국면에서 훅도 끈다 → 스캔이 ⓐ2 로 주조한다.
//
// **두 국면(f6a 조립 실패 · f6b 기동 실패)의 결과 상태를 각각 재현한다**(설계 11.3 ⒡
// "픽스처 2벌"). 워처 실패 자체는 cmd 가 실물로 일으킨다 — 위 phase 타입 주석의 두
// 케이스, 특히 f6b 는 TestWatcherStartFailureFeedsNullObjectAdopter 가
// **실제 Start 실패 → w=nil → 널 오브젝트** 까지 잇고, 여기서는 그 상태의 주조를 잰다.
// 하위 이름을 널 오브젝트 도달 경로로 적는 이유는 이 파일이 가르는 것이 그것뿐이기 때문이다.
func TestPhaseF6DegradedWatcherStillSeeds(t *testing.T) {
	phases := []struct {
		name string
		p    phase
	}{
		// f6a 국면의 결과 상태 — 조립 실패로 처음부터 워처가 없다(New 의 nil 가드).
		{"f6a_setup_fail_state", phase{watcher: false}},
		// f6b 국면의 결과 상태 — 기동 실패 뒤 재장착도 실패했다(SetAdopter 의 nil 가드).
		{"f6b_start_fail_state", phase{watcher: false, reattached: true}},
	}
	for _, ph := range phases {
		t.Run(ph.name+"/f6c_hook_alive", func(t *testing.T) {
			f := newPhaseFixture(t, "f6c-"+ph.name, ph.p)
			f.publishing(2*time.Second, 10*time.Minute)

			f.ingest(recording.ReasonHook, 4*time.Second)

			f.mustSeed(index.SeedReasonLiveIngress, index.SeedChannelHook)
		})
		t.Run(ph.name+"/f6d_scan_only", func(t *testing.T) {
			f := newPhaseFixture(t, "f6d-"+ph.name, ph.p)
			f.publishing(2*time.Second, 10*time.Minute)

			start := time.Now()
			f.ingest(recording.ReasonScan, 4*time.Second)

			f.mustSeed(index.SeedReasonStateObs, index.SeedChannelScan)
			// degraded_to_seed_seconds — 판정이 아니라 기록이다(설계 9.1 f6d).
			t.Logf("degraded_to_seed_seconds=%.3f", time.Since(start).Seconds())
		})
	}
}

// ---------------------------------------------------------------------------
// ⒡-S2 소급 금지
// ---------------------------------------------------------------------------

// s2_1_duplicate — 같은 경로의 재삽입은 새 행이 아니므로(ⓑ) 주조하지 않는다.
// 첫 유입이 이미 컷오프를 만들었다면 그것은 첫 조각의 것이지 재삽입의 것이 아니다.
func TestPhaseS2DuplicateDoesNotSeed(t *testing.T) {
	f := newPhaseFixture(t, "s2-1", phase{watcher: false})
	f.publishing(2*time.Second, 10*time.Minute)
	f.ingest(recording.ReasonScan, 4*time.Second)
	f.mustSeed(index.SeedReasonStateObs, index.SeedChannelScan)

	// 같은 파일을 **메모리 이력이 없는 프로세스**로 다시 흘린다(재기동 후 재생 재현).
	// 메모리 맵이 비어 있어 중복 판정이 장부(ExistingPaths)까지 내려가는 경로다.
	f.rebuild(4000)
	if err := f.ix.Scan(context.Background(), f.root); err != nil {
		t.Fatalf("재스캔 실패: %v", err)
	}

	if n := f.rowCount(); n != 1 {
		t.Fatalf("중복 유입이 행을 늘렸다: %d행 — seq 재사용 금지가 깨진다", n)
	}
	seq, _, _, ok := f.cutoff()
	if !ok || seq != 0 {
		t.Fatalf("중복 유입 뒤 컷오프가 %d(있음=%v)로 바뀌었다 — 소급 금지 위반", seq, ok)
	}
}

// s2_2_recovery_scan — 복구 스캔(publishing=false) → 비주조.
// 항목 부재(송출 종료)는 EpochKnown 축이 아니라 Publishing 축이다 — 폴은 성공했고
// 답도 나왔다("송출 중이 아니다"). 그 답으로 ⓐ2 가 거짓이 된다.
func TestPhaseS2RecoveryScanDoesNotSeed(t *testing.T) {
	f := newPhaseFixture(t, "s2-2", phase{watcher: false})
	f.observer.set(f.stream, mtxstate.Observation{
		Publishing: false, ObservedAt: time.Now().UTC(),
		EpochKnown: true, Tier: mtxstate.TierOnlineTime,
	})

	f.ingest(recording.ReasonScan, 4*time.Second)

	f.mustNotSeed("송출 중이 아닌 스트림의 복구 스캔")
	if sid := f.carrierAt(0); sid != nil {
		t.Errorf("세션이 열렸다(session_id=%q)", *sid)
	}
}

// s2_3_replayed — 과거 이벤트 재생 → 비주조.
// 유입 채널은 ⓐ1(훅)이지만 앵커(start_wall)가 LIVE_FRESH 를 넘었고, ⓐ2 도 백로그 하한에
// 걸린다. 두 쌍 모두 탈락하므로 옛 방송의 조각이 컷오프가 되지 않는다.
func TestPhaseS2ReplayedEventDoesNotSeed(t *testing.T) {
	f := newPhaseFixture(t, "s2-3", phase{watcher: true})
	f.publishing(2*time.Second, 10*time.Minute)

	f.ingest(recording.ReasonHook, 5*time.Minute)

	f.mustNotSeed("5분 전 조각의 재생 유입")
}

// s2_4_backlog_head — 백로그 머리(start_wall < ObservedAt − OBS_BACKFILL) → 비주조.
// 관측은 신선하고 송출도 중이지만, 그 조각이 지금 방송의 증거는 아니다.
func TestPhaseS2BacklogHeadDoesNotSeed(t *testing.T) {
	f := newPhaseFixture(t, "s2-4", phase{watcher: false})
	f.publishing(2*time.Second, 30*time.Minute)

	// OBS_BACKFILL 60초 + 관측 2초 전 → 62초보다 과거면 하한 밖이다.
	f.ingest(recording.ReasonScan, 90*time.Second)

	f.mustNotSeed("백로그 머리는 지금 방송의 증거가 아니다")
	if sid := f.carrierAt(0); sid != nil {
		t.Errorf("세션이 열렸다(session_id=%q) — 백로그 하한은 세션 축에도 걸린다", *sid)
	}
}

// s2_5_short_segments — 4초 미만 조각 20행 주입. **판정이 아니라 기록이다**(F-37 공시 근거):
// 시간 창(90초)은 지켜지되 그 창 안의 행 수는 조각 길이에 따라 늘 수 있음을 남긴다.
func TestPhaseS2ShortSegmentsRecordRowCount(t *testing.T) {
	f := newPhaseFixture(t, "s2-5", phase{watcher: true})
	f.publishing(2*time.Second, 10*time.Minute)

	const n = 20
	for i := range n {
		// 1초 간격 조각 20개 = 20초. 전부 판정 창(90초) 안에 있다.
		f.ingest(recording.ReasonNextFile, time.Duration(30-i)*time.Second)
	}

	rows := f.rowCount()
	if rows != n {
		t.Fatalf("행이 %d개다(%d 기대)", rows, n)
	}
	seq, _, _, ok := f.cutoff()
	if !ok {
		t.Fatal("첫 조각이 컷오프를 만들지 못했다")
	}
	// 컷오프는 스트림당 1개뿐이다 — 뒤 19행은 승계한다(existing_cutoff).
	t.Logf("s2_5 기록: 판정 창 90초 안의 행 수 = %d, cutoff_seq = %d", rows, seq)
}

// s2_6c_epoch_known — 에폭을 아는 관측(tier ⓘ)에서 **에폭 이전 조각은 ⓐ2 에서 탈락**하고
// 에폭 이후 조각은 세션을 연다. 단계 3 은 널 관측 전제라 이 갈래를 실관측으로 재지 못했다.
func TestPhaseS2EpochKnownGatesByEpoch(t *testing.T) {
	t.Run("에폭 이전 잔존물은 열지도 주조하지도 않는다", func(t *testing.T) {
		f := newPhaseFixture(t, "s2-6c-before", phase{watcher: false})
		// 에폭이 10초 전인데 조각은 30초 전이다 — 앞 방송의 꼬리다.
		f.publishing(2*time.Second, 10*time.Second)

		f.ingest(recording.ReasonScan, 30*time.Second)

		f.mustNotSeed("에폭 이전 조각은 이번 송출의 것이 아니다")
		if sid := f.carrierAt(0); sid != nil {
			t.Errorf("세션이 열렸다(session_id=%q)", *sid)
		}
	})
	t.Run("에폭 이후 조각은 CurrentOrOpenIfCorroborated 로 연다", func(t *testing.T) {
		f := newPhaseFixture(t, "s2-6c-after", phase{watcher: false})
		f.publishing(2*time.Second, 30*time.Second)

		f.ingest(recording.ReasonScan, 10*time.Second)

		f.mustSeed(index.SeedReasonStateObs, index.SeedChannelScan)
		if len(f.sessions()) != 1 {
			t.Fatalf("세션이 %d개다 — 에폭 이후 조각은 열어야 한다", len(f.sessions()))
		}
	})
}

// s2_6d_epoch_unknown — EpochKnown=false → ⓐ2 fail-closed(비개시·비주조).
// 항목은 실재하는데 onlineTime 이 없는 예상 밖 응답이 이 상태를 만든다.
func TestPhaseS2EpochUnknownFailsClosed(t *testing.T) {
	f := newPhaseFixture(t, "s2-6d", phase{watcher: false})
	f.observer.set(f.stream, mtxstate.Observation{
		Publishing: true, ObservedAt: time.Now().UTC(),
		// EpochKnown=false — onlineTime 부재. Tier 도 Unknown 이다.
	})

	f.ingest(recording.ReasonScan, 4*time.Second)

	f.mustNotSeed("에폭을 모르면 ⓐ2 는 성립하지 않는다")
	if sid := f.carrierAt(0); sid != nil {
		t.Errorf("세션이 열렸다(session_id=%q) — fail-closed 여야 한다", *sid)
	}
}

// ---------------------------------------------------------------------------
// ⒡-S3 안전 방향
// ---------------------------------------------------------------------------

// s3_unknown_declines — 세 갈래 모두 주조하지 않는다.
//
//	ReasonUnknown  — H0 이 문 앞에서 되돌린다(새 행 자체가 없다) + unknown_completion_reason
//	ReasonRegrown  — H1 이 교정 경로로 보낸다(기존 행을 고칠 뿐 새 행이 아니다 = ⓑ 배제)
//	ReasonScan + 관측 사망 — 행은 생기되 방증이 없어 비주조·비개시
//
// 앞 둘이 "행이 0"인 것은 우연이 아니라 계약이다: 주조 자격 ⓑ 가 "그 INSERT 가 새 행을
// 만들었다"이므로, 새 행이 없는 유입은 판정에 닿기 전에 이미 주조가 불가능하다.
func TestPhaseS3UnknownAndRegrownDecline(t *testing.T) {
	t.Run("사유 미상은 문 앞에서 되돌아간다", func(t *testing.T) {
		f := newPhaseFixture(t, "s3-unknown", phase{watcher: true})
		f.publishing(2*time.Second, 10*time.Minute)

		f.ingest(recording.ReasonUnknown, 4*time.Second)

		if n := f.rowCount(); n != 0 {
			t.Fatalf("사유 미상 조각이 %d행 기록됐다 — H0 이 막아야 한다", n)
		}
		f.mustNotSeed("사유 미상은 어느 방증도 쓰지 않는다")
		if n := f.logs.count(slog.LevelError, "unknown_completion_reason"); n != 1 {
			t.Errorf("unknown_completion_reason 이 %d건이다(1건 기대) — 무징후로 되돌아가면 안 된다", n)
		}
	})

	t.Run("재성장은 교정 경로라 새 행이 아니다", func(t *testing.T) {
		f := newPhaseFixture(t, "s3-regrown", phase{watcher: true})
		f.publishing(2*time.Second, 10*time.Minute)

		f.ingest(recording.ReasonRegrown, 4*time.Second)

		if n := f.rowCount(); n != 0 {
			t.Fatalf("재성장 조각이 %d행 기록됐다 — 교정 경로는 새 행을 만들지 않는다", n)
		}
		f.mustNotSeed("재성장은 ⓑ(새 행)가 배제한다")
	})

	t.Run("스캔 유입 + 관측 사망은 행만 남기고 비주조·비개시", func(t *testing.T) {
		f := newPhaseFixture(t, "s3-scan", phase{watcher: false})
		// 관측을 심지 않는다 — 폴러 사망(EpochKnown=false).

		f.ingest(recording.ReasonScan, 4*time.Second)

		if n := f.rowCount(); n != 1 {
			t.Fatalf("행이 %d개다 — 인덱싱 자체는 계속돼야 한다", n)
		}
		f.mustNotSeed("관측이 죽으면 스캔 유입에는 방증이 없다")
		if sid := f.carrierAt(0); sid != nil {
			t.Errorf("세션이 열렸다(session_id=%q) — fail-closed 여야 한다", *sid)
		}
	})
}
