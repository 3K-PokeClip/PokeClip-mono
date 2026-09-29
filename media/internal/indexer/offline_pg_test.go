package indexer

// 4.1 종료 전이의 회차 귀속 PG 검증(POK-195 M4 PR ⓒ 커밋 6 — 계획 4.1 · 체크리스트 459 B-2 1 · 2 · 3 · 5 · B-1 2 ·
// 판단 J60).
//
// 판정(OfflineDue)은 offline_judgment_test.go 가, 배선(루프 보조 타입)은 cmd/segment-indexer 의 루프 층 시험이 잰다.
// 여기는 실물 세션 결정자 · 장부 위에서 「방송이 끝날 무렵 장부에 드는 조각이 전이 전에 live 회차에 붙는가」를 잰다 —
// 판정 → 점검 수집 → index.EndLive 직접 호출로 루프 한 틱을 흉내 낸다(step). 시각은 두 축이다: 조각 벽시계와 송출 중
// 관측은 실시계 근처에 둔다(세션 결정 · 주조가 DB 시계로 신선도를 잰다). 4.1 판정 시각과 점검 수집 시작은 판정 층
// 시험처럼 합성한다(같은 패키지라 collectStart 를 직접 적는다).
//
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// offlinePG 는 4.1 전이의 PG 픽스처다 — 국면 픽스처(실물 세션 결정자 · 장부 · 키 파생) 위에 여유(IdleTimeout)만 운영
// 기본값 10초로 되돌린 인덱서를 세운다(판정 경계를 판정 층 시험과 같은 수치로 읽으려고).
type offlinePG struct {
	*phaseFixture
	// base 는 첫 조각의 벽시계다 — 실시계 30초 전이라 워처 유입의 ⓐ1 주조가 신선도(60초) 안이다.
	base time.Time
}

func newOfflinePG(t *testing.T, name string, p phase) *offlinePG {
	t.Helper()
	f := newPhaseFixture(t, name, p)
	opt := testOptions()
	opt.SeedEnabled = true
	opt.IdleTimeout = 10 * time.Second
	f.ix = New(f.store, func(string) (int64, error) { return 4000, nil }, f.adopt, nil, f.observer, opt, slog.New(f.logs))
	return &offlinePG{phaseFixture: f, base: time.Now().UTC().Add(-30 * time.Second).Truncate(time.Second)}
}

// at 은 첫 조각 벽시계에서 d 만큼 지난 시각이다 — 조각 벽시계와 4.1 판정 시각을 한 눈금으로 읽는다.
func (f *offlinePG) at(d time.Duration) time.Time { return f.base.Add(d) }

// fileAt 은 wall 에 시작한 조각 파일을 만든다(mtime 한 시간 전 — 유휴 판정을 지난다).
func (f *offlinePG) fileAt(wall time.Time) string {
	f.t.Helper()
	dir := filepath.Join(f.root, f.stream)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		f.t.Fatalf("디렉터리 생성 실패: %v", err)
	}
	p := filepath.Join(dir, segName(wall, 0))
	if err := os.WriteFile(p, make([]byte, 1000), 0o600); err != nil {
		f.t.Fatalf("파일 생성 실패: %v", err)
	}
	f.touch(p, time.Now().Add(-time.Hour))
	return p
}

func (f *offlinePG) touch(path string, at time.Time) {
	f.t.Helper()
	if err := os.Chtimes(path, at, at); err != nil {
		f.t.Fatalf("mtime 조정 실패: %v", err)
	}
}

// handle 은 path 의 조각을 reason 유입으로 넣는다(워처 FIFO · 훅 리더가 하는 일).
func (f *offlinePG) handle(path string, reason recording.CompletionReason) {
	f.t.Helper()
	seg, err := recording.ParseSegmentPath(f.root, path)
	if err != nil {
		f.t.Fatalf("ParseSegmentPath 실패: %v", err)
	}
	seg.Reason = reason
	if err := f.ix.Handle(context.Background(), seg); err != nil {
		f.t.Fatalf("Handle 실패: %v", err)
	}
}

// live 는 송출 중 조각 n 개(벽시계 base · +4초 · …)를 워처 유입으로 넣는다 — 첫 조각이 회차를 열고 컷오프를 주조한다.
func (f *offlinePG) live(n int, reason recording.CompletionReason) {
	f.t.Helper()
	f.publishing(2*time.Second, 10*time.Minute)
	for i := range n {
		f.handle(f.fileAt(f.at(time.Duration(i)*4*time.Second)), reason)
	}
	if got := f.sessionStates(); !slices.Equal(got, []string{"live"}) {
		f.t.Fatalf("송출 중 회차 = %v, want [live] — 준비가 어긋났다", got)
	}
}

// startCollect 는 수집을 발사하고 시작 시각을 start 로 적은 뒤 순회 결과를 받아 둔다(적용은 applyCollect).
func (f *offlinePG) startCollect(start time.Time) collectResult {
	f.t.Helper()
	if !f.ix.StartCollect(context.Background(), f.root) {
		f.t.Fatal("StartCollect 가 거부됐다 — 앞 수집이 아직 비행 중이다")
	}
	f.ix.collectStart = start
	return <-f.ix.CollectDone()
}

// applyCollect 는 받아 둔 수집 결과를 적용한다(루프의 CollectDone case).
func (f *offlinePG) applyCollect(res collectResult) {
	f.t.Helper()
	if _, err := f.ix.ApplyCollect(context.Background(), f.root, res); err != nil {
		f.t.Fatalf("ApplyCollect 실패: %v", err)
	}
}

// collectAt 은 시작 시각이 start 인 수집 한 번을 발사 · 적용한다(주기 수집 · 워처 재스캔 신호 수집).
func (f *offlinePG) collectAt(start time.Time) { f.applyCollect(f.startCollect(start)) }

// step 은 루프의 holdTicks 한 틱을 흉내 낸다 — 관측을 now 의 「송출 중 아님」으로 두고 4.1 판정 → 요구한 점검 수집
// 발사(시작 = now) → due 마다 index.EndLive(1행 · 0행 모두 OfflineTried 참) → 발사한 수집의 결과 적용(루프에서는 뒤의
// CollectDone case 다). 끝낸 회차 ID 를 돌려준다.
func (f *offlinePG) step(now time.Time) (ended []string) {
	f.t.Helper()
	f.observer.set(f.stream, mtxstate.Observation{ObservedAt: now, EpochKnown: true})
	due, collect := f.ix.OfflineDue(now)
	var fired *collectResult
	if collect {
		res := f.startCollect(now)
		fired = &res
	}
	for _, s := range due {
		id, err := index.EndLive(context.Background(), f.pool, s)
		if err != nil {
			f.t.Fatalf("EndLive(%s) 실패: %v", s, err)
		}
		if id != "" {
			ended = append(ended, id)
		}
		f.ix.OfflineTried(s, true, now)
	}
	if fired != nil {
		f.applyCollect(*fired)
	}
	return ended
}

// steps 는 from 부터 to 까지 1초마다 step 을 부르고 끝낸 회차 ID 를 모은다.
func (f *offlinePG) steps(from, to time.Time) (ended []string) {
	f.t.Helper()
	for now := from; !now.After(to); now = now.Add(time.Second) {
		ended = append(ended, f.step(now)...)
	}
	return ended
}

// sessionStates 는 이 스트림 회차들의 state 다(개시 순).
func (f *offlinePG) sessionStates() []string {
	f.t.Helper()
	rows, err := f.pool.Query(context.Background(),
		`SELECT state FROM stream_sessions WHERE stream_id = $1 ORDER BY started_at, session_id`, f.stream)
	if err != nil {
		f.t.Fatalf("회차 조회 실패: %v", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			f.t.Fatalf("회차 스캔 실패: %v", err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		f.t.Fatalf("회차 조회 중 오류: %v", err)
	}
	return out
}

// mustAttach 는 seq 행들이 모두 회차 sessionID 에 붙었는지 본다(비귀속 0).
func (f *offlinePG) mustAttach(sessionID string, seqs ...int64) {
	f.t.Helper()
	for _, seq := range seqs {
		if got := f.carrierAt(seq); got == nil || *got != sessionID {
			f.t.Errorf("행 %d 의 회차 = %v, want %s — 방송 끝 조각이 전이 전에 live 회차에 붙어야 한다", seq, got, sessionID)
		}
	}
}

// mustSettleThrough 는 이 스트림 행의 ③ 을 모두 올린 뒤(③ 업로드 확인 CAS 를 픽스처가 직접 실행한다) 되감기 창 머리가
// seq 까지 닿는지 본다 — 비귀속 행은 ③ 이 올라도 settled 가 아니라 머리가 그 앞에서 멈춘다.
func (f *offlinePG) mustSettleThrough(seq int64) {
	f.t.Helper()
	if _, err := f.pool.Exec(context.Background(), `
		UPDATE stream_segments
		   SET playback_upload_state='uploaded', playback_uploaded_at=now(), playback_bytes=900
		 WHERE stream_id=$1 AND playback_upload_state IN ('pending','failed')`, f.stream); err != nil {
		f.t.Fatalf("③ 업로드 확인 CAS 실패: %v", err)
	}
	if w := f.rewindWindow(); w.HeadSeq < seq {
		f.t.Errorf("③ uploaded 뒤 창 머리 = %d, want %d 이상 — settled 접두가 방송 끝 행을 지나야 한다", w.HeadSeq, seq)
	}
}

// late_scan_rows_attach_to_live_before_offline(계획 4.1 ③ · 체크리스트 B-2 1 · 뮤테이션 131 · 134) — 방송 끝 두
// 조각(A3 · A4)이 FS 래치 트립 창에 워처 유입으로 와서 커밋 없이 버려진다. 방송 중 주기 수집은 그 둘이 생기기 전에
// 걸었고 둘이 버려진 뒤에 적용되어 래치를 풀지만 둘을 모른다 — since + IdleTimeout 앞에 시작한 이 수집은 점검이 아니다.
// 전이 전 점검(since + IdleTimeout 에 발사)이 둘을 ReasonScan 으로 넣어 live 회차에 붙이고(비귀속 0), 그 뒤 전이가
// 회차를 닫는다. ③ 이 오르면 settled 접두가 두 행을 지난다.
func TestLateScanRowsAttachToLiveBeforeOffline(t *testing.T) {
	f := newOfflinePG(t, "latescan", phase{watcher: true})
	f.live(3, recording.ReasonNextFile) // A0–A2, 회차 S
	held := f.startCollect(f.at(10 * time.Second))
	f.ix.fsLatch.Trip(filepath.Join(f.root, f.stream, "멈춘파일.mp4"), "measure")
	f.handle(f.fileAt(f.at(12*time.Second)), recording.ReasonNextFile) // A3 — 트립 중이라 버려진다
	f.handle(f.fileAt(f.at(16*time.Second)), recording.ReasonNextFile) // A4
	f.applyCollect(held)
	if n := f.rowCount(); n != 3 {
		t.Fatalf("방송 끝 두 조각이 장부 밖이어야 한다 — 행 %d개", n)
	}

	since := f.at(21 * time.Second)
	ended := f.steps(since, since.Add(12*time.Second))
	f.collectAt(since.Add(20 * time.Second)) // 전이 뒤 주기 수집 — 전이가 먼저였다면 남은 조각을 비귀속으로 넣는다

	s := f.sessions()
	if len(s) != 1 || !slices.Equal(ended, []string{s[0].id}) {
		t.Fatalf("전이한 회차 = %v · 회차 %d개, want 유일한 회차 하나를 한 번", ended, len(s))
	}
	if got := f.sessionStates(); !slices.Equal(got, []string{"ending"}) {
		t.Errorf("전이 뒤 회차 state = %v, want [ending]", got)
	}
	f.mustAttach(s[0].id, 3, 4)
	f.mustSettleThrough(4)
}

// transient_check_failure_recovers_into_live_session(계획 4.1 ③(b) · 체크리스트 B-2 2 · R3-1 · 뮤테이션 135 · 141) —
// 워처가 놓친 마지막 조각(A3)의 최신 파일 확인 stat 이 점검 때 한 번 실패하면(latest_stat_failed) 그 점검은 결손이라
// 전이하지 않는다. 오류가 풀린 뒤 재점검(30초 뒤)이 A3 을 ReasonScan 으로 넣어 live 회차에 붙이고, 그 뒤 전이한다 —
// settled 접두가 A3 을 지난다.
func TestTransientCheckFailureRecoversIntoLiveSession(t *testing.T) {
	f := newOfflinePG(t, "transient", phase{watcher: true})
	f.live(3, recording.ReasonNextFile)
	a3 := f.fileAt(f.at(12 * time.Second)) // 워처 이벤트를 잃었다
	orig := f.ix.statFn
	failed := false
	f.ix.statFn = func(p string) (os.FileInfo, error) {
		if p == a3 && !failed {
			failed = true
			return nil, os.ErrPermission // 확인 stat 한 번 실패(시간 초과 아님)
		}
		return orig(p)
	}

	since := f.at(17 * time.Second)
	ended := f.steps(since, since.Add(15*time.Second))
	if len(ended) != 0 || f.rowCount() != 3 {
		t.Fatalf("결손 점검 뒤 전이 = %v · 행 %d개, want [] · 3 — A3 이 아직 장부 밖이다", ended, f.rowCount())
	}
	if n := f.logs.count(slog.LevelWarn, "latest_stat_failed"); n != 1 {
		t.Fatalf("latest_stat_failed = %d건, want 1 — 의도한 경로가 아니다", n)
	}
	ended = append(ended, f.steps(since.Add(16*time.Second), since.Add(45*time.Second))...)
	f.collectAt(since.Add(50 * time.Second)) // 전이 뒤 주기 수집

	s := f.sessions()
	if len(s) != 1 || !slices.Equal(ended, []string{s[0].id}) {
		t.Fatalf("전이한 회차 = %v · 회차 %d개, want 유일한 회차 하나를 한 번", ended, len(s))
	}
	f.mustAttach(s[0].id, 3)
	f.mustSettleThrough(3)
}

// last_segment_joins_session_before_offline(계획 4.1 ※ · 체크리스트 B-2 3 · 뮤테이션 131) — RTMP 송출이 끝나면 항목이
// 바로 사라져 !Publishing 이 먼저 보이고, 마지막 조각(A3)은 마지막 쓰기 뒤 IdleTimeout 에 워처(Idle)가 넣거나 점검
// 수집이 ReasonScan 으로 넣는다. 어느 순서든 A3 은 전이 전에 live 회차에 붙고 회차는 하나다 — 전이 뒤 워처 Idle 이
// 1조각 새 회차를 열지 않는다.
func TestLastSegmentJoinsSessionBeforeOffline(t *testing.T) {
	for _, tc := range []struct {
		name, stream string
		idleBefore   bool // 워처 Idle 이 점검 수집보다 먼저 온다
	}{{"워처_Idle_먼저", "lastseg-idle", true}, {"점검_스캔_먼저", "lastseg-scan", false}} {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflinePG(t, tc.stream, phase{watcher: true})
			f.live(3, recording.ReasonNextFile)
			a3 := f.fileAt(f.at(12 * time.Second)) // 마지막 조각 — +16초에 끝나고 송출도 그때 끊긴다
			since := f.at(16500 * time.Millisecond)

			ended := f.steps(since, since.Add(9*time.Second))
			if tc.idleBefore {
				f.handle(a3, recording.ReasonIdle)
			}
			ended = append(ended, f.steps(since.Add(10*time.Second), since.Add(13*time.Second))...)
			f.collectAt(since.Add(20 * time.Second)) // 전이 뒤 주기 수집
			if !tc.idleBefore {
				f.handle(a3, recording.ReasonIdle) // 점검이 이미 넣었다 — 경로 중복으로 건너뛴다
			}

			s := f.sessions()
			if len(s) != 1 || !slices.Equal(ended, []string{s[0].id}) {
				t.Fatalf("회차 %d개 · 전이한 회차 = %v, want 회차 하나를 한 번 — 마지막 조각이 새 회차를 열었다", len(s), ended)
			}
			f.mustAttach(s[0].id, 3)
		})
	}
}

// reconnect_within_check_delay_stays_same_session(계획 4.1 트레이드오프 · 체크리스트 B-2 5 · kty qa #56 대가 고정) —
// 송출이 끊겨 기다림이 열려도 점검(since + IdleTimeout) 전에 다시 송출하면 기다림이 지워지고, 재송출 조각은 같은 live
// 회차에 붙는다(회차 수 1 · 전이 0).
func TestReconnectWithinCheckDelayStaysSameSession(t *testing.T) {
	f := newOfflinePG(t, "reconnect", phase{watcher: true})
	f.live(3, recording.ReasonNextFile)
	since := f.at(13 * time.Second)
	ended := f.steps(since, since.Add(4*time.Second))

	f.publishing(time.Second, 10*time.Minute) // 재송출이 보인다 — 기다림이 지워진다
	f.ix.OfflineDue(time.Now())
	f.handle(f.fileAt(f.at(19*time.Second)), recording.ReasonNextFile) // 재송출 조각 B0 · B1
	f.handle(f.fileAt(f.at(23*time.Second)), recording.ReasonNextFile)
	for now := since.Add(5 * time.Second); now.Before(since.Add(60 * time.Second)); now = now.Add(time.Second) {
		f.publishing(time.Second, 10*time.Minute)
		if due, collect := f.ix.OfflineDue(now); len(due) != 0 || collect {
			t.Fatalf("송출 중 판정(+%v) = %v, %t, want [], false", now.Sub(since), due, collect)
		}
	}

	s := f.sessions()
	if len(s) != 1 || len(ended) != 0 {
		t.Fatalf("회차 %d개 · 전이 %v, want 회차 하나 · 전이 없음", len(s), ended)
	}
	f.mustAttach(s[0].id, 3, 4)
	if got := f.sessionStates(); !slices.Equal(got, []string{"live"}) {
		t.Errorf("회차 state = %v, want [live]", got)
	}
}

// deferred_latest_does_not_qualify_offline 의 PG 몫(체크리스트 B-1 2 · 뮤테이션 135) — 워처 강등 변형: 점검 수집이
// 아직 쓰이는 마지막 파일(A3)을 워처에 넘기면 널 오브젝트가 버린다(adopt_dropped_degraded). 그 점검은 결손이라
// 전이하지 않고, 30초 뒤 재점검이 A3 을 ReasonScan 으로 넣어 live 회차에 붙인 뒤에 전이한다 — 그 회차가 ending 이 된다.
func TestDeferredLatestJoinsLiveSessionWhenWatcherDegraded(t *testing.T) {
	f := newOfflinePG(t, "deferred", phase{watcher: false})
	f.live(3, recording.ReasonHook) // 워처 없이 훅 유입으로 회차를 연다
	a3 := f.fileAt(f.at(12 * time.Second))
	f.touch(a3, time.Now()) // 방금 쓰였다 — 점검 수집의 최신 파일 판정이 워처에 넘긴다

	since := f.at(17 * time.Second)
	ended := f.steps(since, since.Add(11*time.Second))
	if len(ended) != 0 || f.rowCount() != 3 {
		t.Fatalf("인계한 점검 뒤 전이 = %v · 행 %d개, want [] · 3", ended, f.rowCount())
	}
	f.touch(a3, time.Now().Add(-time.Hour)) // 쓰기가 끝났다
	ended = append(ended, f.steps(since.Add(12*time.Second), since.Add(45*time.Second))...)
	f.collectAt(since.Add(50 * time.Second)) // 전이 뒤 주기 수집

	s := f.sessions()
	if len(s) != 1 || !slices.Equal(ended, []string{s[0].id}) {
		t.Fatalf("전이한 회차 = %v · 회차 %d개, want 유일한 회차 하나를 한 번", ended, len(s))
	}
	f.mustAttach(s[0].id, 3)
	if got := f.sessionStates(); !slices.Equal(got, []string{"ending"}) {
		t.Errorf("전이 뒤 회차 state = %v, want [ending]", got)
	}
	if n := f.logs.count(slog.LevelWarn, "adopt_dropped_degraded"); n != 1 {
		t.Errorf("adopt_dropped_degraded = %d건, want 1 — 의도한 경로가 아니다", n)
	}
}
