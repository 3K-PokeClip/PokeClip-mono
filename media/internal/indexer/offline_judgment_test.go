package indexer

// 4.1 종료 판정(판정 층)의 단위 검증이다 — 계획 4.1 「테스트」 목록 가운데 PG · EndLive · 루프
// 배선 없이 서는 것. 전이 실행(EndLive · push · 요구 적재)과 회차 귀속(PG)은 커밋 6 이 붙이므로
// 여기서는 「due 에 든다 / 안 든다」와 「점검 수집을 요구한다 / 안 한다」로 단언한다.
//
// 시각은 전부 offlineT0 기준 합성 시각이다. StartCollect 는 실시계로 수집 시작을 적으므로
// collectWith 가 발사 직후 그 값만 합성 시각으로 바꾼다 — 판정 경계를 sleep 없이 재기 위해서다.
// 동기 Scan() 은 수집 시작을 쓰지 않으므로 여기서는 쓰지 않는다(계획 4.1 영향).

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
)

// offlineT0 은 4.1 테스트의 시각 원점이다. 조각 벽시계(baseWall 부근)보다 한 시간 뒤라 유입
// 정지(②)는 늘 참이다 — ② 를 재는 테스트만 조각을 이 원점 가까이에 둔다.
var offlineT0 = baseWall.Add(time.Hour)

// offlineAt 은 원점에서 d 만큼 지난 합성 시각이다.
func offlineAt(d time.Duration) time.Time { return offlineT0.Add(d) }

// newOfflineFixture 는 4.1 판정용 픽스처다. 여유(IdleTimeout)를 운영 기본값 10초로 되돌린다 —
// 판정이 since + IdleTimeout 을 경계로 쓰는데 testOptions 의 80ms 로는 경계 행을 읽기 어렵다.
func newOfflineFixture(t *testing.T) *fixture {
	t.Helper()
	f := newFixture(t)
	f.opt.IdleTimeout = 10 * time.Second
	f.reload()
	return f
}

// boot 는 스트림마다 조각 둘(baseWall · +4초)을 만들고 부트 수집 한 번(시작 = 원점 1분 전)으로
// 장부에 넣는다 — 커서 꼬리 seq 1 이 선다. 이 수집은 어떤 기다림의 점검 자격(③(a))도 없다.
func (f *fixture) boot(streams ...string) {
	f.t.Helper()
	for _, s := range streams {
		f.makeFile(s, segName(baseWall, 0), 1000)
		f.makeFile(s, segName(baseWall, 4*time.Second), 1000)
	}
	f.collectAt(offlineAt(-time.Minute))
	for _, s := range streams {
		if cur := f.ix.cursors[s]; cur == nil || cur.Tail == nil || cur.Tail.Seq != 1 {
			f.t.Fatalf("부트 수집 뒤 %s 커서 꼬리가 seq 1 이 아니다: %+v", s, cur)
		}
	}
}

// offAir 는 스트림의 관측을 「송출 중 아님」(항목 부재 형상 — 폴 성공 · Publishing 거짓)으로 둔다.
func (f *fixture) offAir(streamID string, observedAt time.Time) {
	f.observer.set(streamID, mtxstate.Observation{ObservedAt: observedAt, EpochKnown: true})
}

// onAir 는 스트림의 관측을 「송출 중」(tier ⓘ)으로 둔다.
func (f *fixture) onAir(streamID string, observedAt time.Time) {
	f.observer.set(streamID, mtxstate.Observation{
		Publishing: true, ObservedAt: observedAt, EpochStartedAt: observedAt.Add(-time.Minute),
		EpochKnown: true, Tier: mtxstate.TierOnlineTime,
	})
}

// judge 는 스트림들의 관측을 원점+d 의 「송출 중 아님」으로 새로 두고 그 시각의 4.1 판정을
// 돌린다. 관측 시각을 옮겨도 열린 기다림의 since 는 그대로다 — 관측만 신선하게 잇는다.
func (f *fixture) judge(d time.Duration, streams ...string) (due []string, collect bool) {
	for _, s := range streams {
		f.offAir(s, offlineAt(d))
	}
	return f.ix.OfflineDue(offlineAt(d))
}

// collectWith 는 수집 한 번을 발사하고 시작 시각을 start 로 바꾼 뒤, 결과를 edit 로 고쳐(nil
// 이면 그대로) ctx 로 적용한다. 반환은 ApplyCollect 의 firstComplete 다.
func (f *fixture) collectWith(ctx context.Context, start time.Time, edit func(*collectResult)) bool {
	f.t.Helper()
	if !f.ix.StartCollect(context.Background(), f.root) {
		f.t.Fatal("StartCollect 가 거부됐다 — 앞 수집이 아직 비행 중이다")
	}
	f.ix.collectStart = start
	res := <-f.ix.CollectDone()
	if edit != nil {
		edit(&res)
	}
	first, err := f.ix.ApplyCollect(ctx, f.root, res)
	if err != nil {
		f.t.Fatalf("ApplyCollect 실패: %v", err)
	}
	return first
}

// collectAt 은 시작 시각이 start 인 수집 한 번을 그대로 적용한다.
func (f *fixture) collectAt(start time.Time) bool {
	f.t.Helper()
	return f.collectWith(context.Background(), start, nil)
}

// collectTruncatedAt 은 시작 시각이 start 인 수집 한 번을 절단된 결과로 적용한다(완주 아님).
func (f *fixture) collectTruncatedAt(start time.Time) {
	f.t.Helper()
	f.collectWith(context.Background(), start, func(r *collectResult) { r.truncated = true })
}

// runCheck 는 원점+d 의 판정이 요구한 점검 수집을 루프처럼 그 시각에 발사·적용한다. 요구가
// 없으면 준비 단계가 어긋난 것이라 테스트를 멈춘다.
func (f *fixture) runCheck(d time.Duration, streams ...string) {
	f.t.Helper()
	if _, collect := f.judge(d, streams...); !collect {
		f.t.Fatalf("원점+%v 의 판정이 점검 수집을 요구하지 않았다", d)
	}
	f.collectAt(offlineAt(d))
}

// driveOffline 은 loop 의 holdTicks 를 흉내 낸다 — 원점+from 부터 원점+to 앞까지 1초마다 judge 를
// 부르고, 점검 수집을 요구받으면 그 시각을 시작으로 onFire 가 수집 한 번을 적용한다. 돌려주는
// 값은 4.1 이 발사한 시각(원점 기준)들과 due 가 비지 않은 틱 수다.
func (f *fixture) driveOffline(from, to time.Duration, onFire func(start time.Time), streams ...string) (fires []time.Duration, dueTicks int) {
	f.t.Helper()
	for d := from; d < to; d += time.Second {
		due, collect := f.judge(d, streams...)
		if len(due) > 0 {
			dueTicks++
		}
		if collect {
			fires = append(fires, d)
			onFire(offlineAt(d))
		}
	}
	return fires, dueTicks
}

// failStat 은 path 의 n 번째 stat 만 err 로 실패시킨다(나머지는 원래 stat) — 점검 수집에서 같은
// 파일을 최신 파일 확인(scan_latest)과 측정(measure)이 차례로 stat 하므로 차례로 자리를 가른다.
func (f *fixture) failStat(path string, n int, err error) {
	orig := f.ix.statFn
	calls := 0
	f.ix.statFn = func(p string) (os.FileInfo, error) {
		if p == path {
			calls++
			if calls == n {
				return nil, err
			}
		}
		return orig(p)
	}
}

// unreadable 은 dir 의 권한을 모두 거둔다. 돌려준 함수를 부르거나 테스트가 끝나면 되돌린다.
func unreadable(t *testing.T, dir string) (restore func()) {
	t.Helper()
	if err := os.Chmod(dir, 0o000); err != nil {
		t.Fatalf("권한 변경 실패: %v", err)
	}
	restore = func() {
		if err := os.Chmod(dir, 0o755); err != nil {
			t.Errorf("권한 복구 실패: %v", err)
		}
	}
	t.Cleanup(restore)
	return restore
}

// offline_waits_for_check_collect_after_not_publishing(계획 4.1 · 뮤테이션 131) — 관측이 신선하고
// 송출 중이 아니며(①) 유입이 멈췄어도(②) 점검 수집이 완주하기 전에는 전이하지 않는다. 점검은
// since + IdleTimeout 에 한 번 요구되고, 그 수집이 완주하면 전이 대상이 된다. 점검을 기다리는
// 동안 session_offline result=awaiting_check 가 한 줄 남는다(기다림 한 번에 한 줄).
func TestOfflineWaitsForCheckCollectAfterNotPublishing(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1")

	for _, d := range []time.Duration{0, 5 * time.Second, 9 * time.Second} {
		if due, collect := f.judge(d, "s1"); len(due) != 0 || collect {
			t.Fatalf("OfflineDue(원점+%v) = %v, %t, want [], false — 점검 전이고 여유(10초) 전이다", d, due, collect)
		}
	}
	due, collect := f.judge(10*time.Second, "s1")
	if len(due) != 0 || !collect {
		t.Fatalf("OfflineDue(원점+10s) = %v, %t, want [], true — since + IdleTimeout 에 점검을 요구해야 한다", due, collect)
	}
	f.collectAt(offlineAt(10 * time.Second))

	due, collect = f.judge(11*time.Second, "s1")
	if !slices.Equal(due, []string{"s1"}) || collect {
		t.Errorf("OfflineDue(원점+11s) = %v, %t, want [s1], false — 완주한 점검 뒤에는 전이 대상이다", due, collect)
	}
	if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 1 {
		t.Errorf("session_offline INFO = %d건, want 1(기다림 한 번에 한 줄)", n)
	}
	got := f.logs.attrs("session_offline")
	if got["result"] != "awaiting_check" || got["stream_id"] != "s1" {
		t.Errorf("session_offline 속성 = %v, want result=awaiting_check stream_id=s1", got)
	}
	if at, _ := got["wait_started_at"].(time.Time); !at.Equal(offlineAt(0)) {
		t.Errorf("session_offline wait_started_at = %v, want %v(기다림을 연 관측 시각)", got["wait_started_at"], offlineAt(0))
	}
}

// 커서 꼬리가 없는 스트림은 판정하지 않는다(계획 4.1 ② · 6.4 음성 대조) — 파일이 있어도 장부에 든
// 행이 없으면 유입 정지를 잴 수 없다. 전이 대상도 점검 요구도 만들지 않고, 꼬리 없는 스트림에
// 전이 결과를 되돌려도 아무것도 바뀌지 않는다.
func TestOfflineSkipsStreamWithoutTail(t *testing.T) {
	f := newOfflineFixture(t)
	f.makeFile("s1", segName(baseWall, 0), 1000)
	f.probe.vals = []int64{0} // 유일한 조각이 길이 0 이라 장부에 못 든다 — 커서는 서되 꼬리가 없다
	f.collectAt(offlineAt(-time.Minute))
	if cur := f.ix.cursors["s1"]; cur == nil || cur.Tail != nil {
		t.Fatalf("부트 수집 뒤 s1 커서 = %+v, want 꼬리 없는 커서 — 준비가 어긋났다", cur)
	}
	f.ix.OfflineTried("s1", true, offlineAt(0))
	f.ix.OfflineTried("없는스트림", true, offlineAt(0))
	for _, d := range []time.Duration{0, 10 * time.Second, 300 * time.Second} {
		if due, collect := f.judge(d, "s1"); len(due) != 0 || collect {
			t.Errorf("꼬리 없는 스트림의 OfflineDue(원점+%v) = %v, %t, want [], false", d, due, collect)
		}
	}
}

// offline_waits_for_ingest_stop(계획 4.1 ② — kty ⑸) — 점검이 완주해도 마지막 조각 끝으로부터
// 3 × 그 조각의 실제 길이가 지나기 전에는 전이하지 않는다. 경계는 정확히 3배에서 연다. 점검
// 대기 로그는 유입이 멈춘 뒤 점검을 기다릴 때만 남는다 — 유입 정지를 기다리는 동안은 남지 않는다.
func TestOfflineWaitsForIngestStop(t *testing.T) {
	f := newOfflineFixture(t)
	f.makeFile("s1", segName(offlineT0, 0), 1000) // 꼬리 = 원점 시작 · 4초(프로브 4000) → 끝 원점+4초
	f.collectAt(offlineAt(4 * time.Second))
	f.judge(4500*time.Millisecond, "s1") // since = 원점+4.5초(RTMP 는 끊기면 바로 항목이 사라진다)
	f.runCheck(14500*time.Millisecond, "s1")

	if due, _ := f.judge(15999*time.Millisecond, "s1"); len(due) != 0 {
		t.Errorf("OfflineDue(꼬리 끝 + 11.999초) = %v, want [] — 유입 정지(3 × 4초)가 아직이다", due)
	}
	if due, _ := f.judge(16*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Errorf("OfflineDue(꼬리 끝 + 12초) = %v, want [s1] — 3 × 조각 길이에서 연다", due)
	}
	if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 0 {
		t.Errorf("session_offline = %d건, want 0 — 점검이 아니라 유입 정지를 기다렸다", n)
	}
}

// offline_check_incomplete_results_do_not_qualify(계획 4.1 ③(a) · 뮤테이션 132–134) — 완주하지
// 않은 수집(절단 · 래치 중단 · 수집 오류 · ctx 취소)과 since + IdleTimeout 앞에서 시작한 수집(since
// 앞 · 여유 안)은 점검 자격이 없다. 대조군(완주 · 여유 뒤 시작)만 전이 대상을 만든다.
func TestOfflineCheckIncompleteResultsDoNotQualify(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	tests := []struct {
		name string
		// check 는 since = 원점인 기다림에서 점검 수집 한 번을 적용한다.
		check   func(f *fixture)
		wantDue bool
	}{{
		name:    "완주_대조군",
		check:   func(f *fixture) { f.collectAt(offlineAt(10 * time.Second)) },
		wantDue: true,
	}, {
		name:  "절단",
		check: func(f *fixture) { f.collectTruncatedAt(offlineAt(10 * time.Second)) },
	}, {
		name: "래치_중단",
		check: func(f *fixture) {
			// 꼬리 복구 stat 이 멈춘다 — 스트림 처리 중 래치가 트립돼 수집이 중단으로 끝난다.
			f.failStat(f.ix.cursors["s1"].Tail.LocalPath, 1, stalledErr("꼬리"))
			f.collectAt(offlineAt(10 * time.Second))
			// ③(c) 와 떼어 ③(a) 만 잰다 — 래치를 풀어도 중단된 수집은 자격이 없어야 한다.
			f.ix.fsLatch.Reset(f.root, time.Second)
		},
	}, {
		name: "수집_오류",
		check: func(f *fixture) {
			f.collectWith(context.Background(), offlineAt(10*time.Second), func(r *collectResult) {
				r.err = errors.New("순회 실패")
			})
		},
	}, {
		name:  "ctx_취소",
		check: func(f *fixture) { f.collectWith(cancelled, offlineAt(10*time.Second), nil) },
	}, {
		name:  "since_앞_시작",
		check: func(f *fixture) { f.collectAt(offlineAt(-time.Second)) },
	}, {
		name:  "여유_안_시작",
		check: func(f *fixture) { f.collectAt(offlineAt(5 * time.Second)) },
	}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineFixture(t)
			f.boot("s1")
			f.judge(0, "s1") // since = 원점
			tc.check(f)
			due, _ := f.judge(11*time.Second, "s1")
			if got := slices.Equal(due, []string{"s1"}); got != tc.wantDue {
				t.Errorf("OfflineDue(원점+11s) = %v, want due=%t", due, tc.wantDue)
			}
		})
	}
}

// offline_latest_applied_collect_must_be_complete(계획 4.1 ③(a) · 코드 검수 r1 처분 2) — 점검이 자격을
// 준 뒤라도 가장 최근에 적용된 수집이 완주가 아니면(루트 순회 오류 · 절단 · 래치 중단 · 수집 오류 · ctx
// 취소) 전이 자격이 없다(fail-closed): 래치를 푼 수집이 완주가 아니면 트립 창에 버려진 조각이 장부 밖에
// 남을 수 있다. 점검 발사 규칙은 그대로라 그 수집 뒤 offlineRetryAfter 에 재점검을 한 번 요구하고,
// 재점검이 완주하면 다시 전이 대상이다. TestOfflineCheckIncompleteResultsDoNotQualify 의 짝이다 — 그쪽은
// 점검 수집 자신의 결과를, 이쪽은 자격 뒤에 적용된 수집의 결과를 본다.
func TestOfflineLatestAppliedCollectMustBeComplete(t *testing.T) {
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	tests := []struct {
		name string
		// later 는 자격을 받은 뒤(원점+20초) 수집 한 번을 적용한다 — 주기 수집이나 워처 재스캔 신호 수집이다.
		later   func(f *fixture)
		wantDue bool
	}{{
		name:    "완주_대조군",
		later:   func(f *fixture) { f.collectAt(offlineAt(20 * time.Second)) },
		wantDue: true,
	}, {
		name: "루트_오류",
		later: func(f *fixture) {
			f.collectWith(context.Background(), offlineAt(20*time.Second), func(r *collectResult) {
				r.walkErrs = append(r.walkErrs, f.root)
			})
		},
	}, {
		name:  "절단",
		later: func(f *fixture) { f.collectTruncatedAt(offlineAt(20 * time.Second)) },
	}, {
		name: "래치_중단",
		later: func(f *fixture) {
			// 꼬리 복구 stat 이 멈춘다 — 스트림 처리 중 래치가 트립돼 수집이 중단으로 끝난다.
			f.failStat(f.ix.cursors["s1"].Tail.LocalPath, 1, stalledErr("꼬리"))
			f.collectAt(offlineAt(20 * time.Second))
			// ③(c) 와 떼어 이 조건만 잰다 — 래치를 풀어도 중단된 수집 뒤에는 자격이 없어야 한다.
			f.ix.fsLatch.Reset(f.root, time.Second)
		},
	}, {
		name: "수집_오류",
		later: func(f *fixture) {
			f.collectWith(context.Background(), offlineAt(20*time.Second), func(r *collectResult) {
				r.err = errors.New("순회 실패")
			})
		},
	}, {
		name:  "ctx_취소",
		later: func(f *fixture) { f.collectWith(cancelled, offlineAt(20*time.Second), nil) },
	}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineFixture(t)
			f.boot("s1")
			f.judge(0, "s1")                 // since = 원점
			f.runCheck(10*time.Second, "s1") // 자격을 주는 점검 — 완주 · 결손 없음
			if due, _ := f.judge(11*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
				t.Fatalf("점검 뒤 OfflineDue = %v, want [s1] — 준비가 어긋났다", due)
			}
			tc.later(f)
			due, _ := f.judge(21*time.Second, "s1")
			if got := slices.Equal(due, []string{"s1"}); got != tc.wantDue {
				t.Errorf("자격 뒤 수집을 적용한 뒤 OfflineDue(원점+21s) = %v, want due=%t", due, tc.wantDue)
			}
			if tc.wantDue {
				return
			}
			f.runCheck(50*time.Second, "s1") // 그 수집 + offlineRetryAfter — 재점검 한 번
			if due, _ := f.judge(51*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
				t.Errorf("재점검이 완주한 뒤 OfflineDue = %v, want [s1]", due)
			}
		})
	}
}

// offline_blocked_while_fs_latch_tripped(계획 4.1 ③(c) · 뮤테이션 136 · 145) — 점검이 완주했어도
// 지금 FS 래치가 트립이면 전이하지 않고, 트립 동안 4.1 은 점검 수집을 요구하지 않는다. 래치는
// 주기 수집이 풀고, 그 수집이 완주하면 전이 대상이 된다.
func TestOfflineBlockedWhileFSLatchTripped(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1")
	f.judge(0, "s1")
	f.runCheck(10*time.Second, "s1")
	f.ix.fsLatch.Trip(filepath.Join(f.root, "s1", "멈춘파일.mp4"), "measure")

	for _, d := range []time.Duration{11 * time.Second, 45 * time.Second, 200 * time.Second} {
		if due, collect := f.judge(d, "s1"); len(due) != 0 || collect {
			t.Errorf("트립 중 OfflineDue(원점+%v) = %v, %t, want [], false — 전이도 4.1 발사도 없어야 한다", d, due, collect)
		}
	}

	f.collectAt(offlineAt(300 * time.Second)) // 주기 수집 — 머리에서 래치를 풀고 완주한다
	if f.ix.fsLatch.Tripped() {
		t.Fatal("주기 수집이 래치를 풀지 못했다 — 준비가 어긋났다")
	}
	if due, _ := f.judge(301*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Errorf("래치를 푼 완주 뒤 OfflineDue = %v, want [s1]", due)
	}
}

// deferred_latest_does_not_qualify_offline(계획 4.1 ③(b) 결손 ⅱ · 뮤테이션 135) — 점검 수집이 마지막
// 파일을 워처에 넘기면(아직 쓰이는 중일 수 있다) 그 수집은 완주여도 점검 자격이 없다.
// offlineRetryAfter(30초) 뒤 재점검을 한 번 요구하고, 그 재점검이 파일을 ReasonScan 으로 넣으면
// 전이 대상이 된다. 워처 강등 변형은 널 오브젝트가 파일을 버려 재점검만이 넣을 수 있는 국면이다.
// 그 행이 live 회차에 붙는지(회차 귀속)는 PG 몫이라 커밋 6 이 단언한다.
func TestDeferredLatestDoesNotQualifyOffline(t *testing.T) {
	for _, tc := range []struct {
		name     string
		degraded bool
	}{{name: "워처"}, {name: "워처_강등", degraded: true}} {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineFixture(t)
			if tc.degraded {
				f.ix.SetAdopter(nil) // 널 오브젝트 — 되돌림을 계수하며 버린다
			}
			f.boot("s1")
			last := f.makeFile("s1", segName(baseWall, 8*time.Second), 1000)
			f.touch(last, time.Now()) // 방금 쓰였다 — 점검 수집의 최신 파일 판정이 워처에 넘긴다
			f.judge(0, "s1")
			f.runCheck(10*time.Second, "s1")

			if due, _ := f.judge(11*time.Second, "s1"); len(due) != 0 {
				t.Fatalf("인계한 점검 뒤 OfflineDue = %v, want [] — 마지막 파일이 아직 장부 밖이다", due)
			}
			if _, collect := f.judge(39*time.Second, "s1"); collect {
				t.Error("점검 29초 뒤에 재점검을 요구했다 — offlineRetryAfter(30초) 전이다")
			}
			f.touch(last, time.Now().Add(-time.Hour)) // 쓰기가 끝났다
			f.runCheck(40*time.Second, "s1")

			recs := f.store.records("s1")
			if len(recs) != 3 || recs[2].LocalPath != last {
				t.Fatalf("재점검 뒤 행 = %d개, want 3(마지막 파일을 재점검이 넣는다)", len(recs))
			}
			if op := f.store.lastSource.Op; op != index.SessionCurrentOnly {
				t.Errorf("마지막 파일의 세션 연산 = %v, want CurrentOnly(ReasonScan · 방증 없음)", op)
			}
			if due, _ := f.judge(41*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
				t.Errorf("재점검 뒤 OfflineDue = %v, want [s1]", due)
			}
			wantHanded, wantDropped := 1, 0
			if tc.degraded {
				wantHanded, wantDropped = 0, 1
			}
			if n := f.adopter.count(); n != wantHanded {
				t.Errorf("워처 인계 = %d번, want %d", n, wantHanded)
			}
			if n := f.logs.count(slog.LevelWarn, "adopt_dropped_degraded"); n != wantDropped {
				t.Errorf("adopt_dropped_degraded = %d건, want %d", n, wantDropped)
			}
		})
	}
}

// offline_check_collect_shared_and_rate_limited(계획 4.1 점검 발사 · 뮤테이션 140) — 기다리는
// 스트림이 여럿이어도 점검 수집은 한 번이다(수집은 트리 전체). 완주하지 못한 점검 뒤에는
// offlineRetryAfter 가 지나야 재점검을 한 번 요구하고, 그 뒤로 4.1 은 발사하지 않는다(판정 입력은
// 주기 수집이 만든다). 수집이 비행 중이면 요구하지 않으므로 4.1 발사는
// scan_collect_skipped_inflight 를 내지 않는다.
func TestOfflineCheckCollectSharedAndRateLimited(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1", "s2")
	f.judge(0, "s1", "s2")

	if !f.ix.StartCollect(context.Background(), f.root) { // 비행 중인 주기 수집
		t.Fatal("주기 수집 발사가 거부됐다")
	}
	f.ix.collectStart = offlineAt(9 * time.Second)
	if _, collect := f.judge(10*time.Second, "s1", "s2"); collect {
		t.Error("수집이 비행 중인데 4.1 이 점검을 요구했다")
	}
	res := <-f.ix.CollectDone()
	res.truncated = true
	if _, err := f.ix.ApplyCollect(context.Background(), f.root, res); err != nil {
		t.Fatalf("ApplyCollect 실패: %v", err)
	}

	fires, dueTicks := f.driveOffline(10*time.Second, 300*time.Second, f.collectTruncatedAt, "s1", "s2")
	if want := []time.Duration{10 * time.Second, 40 * time.Second}; !slices.Equal(fires, want) {
		t.Errorf("4.1 발사 시각 = %v, want %v — 두 스트림이 한 발사를 나누고 재점검은 30초 뒤 한 번이다", fires, want)
	}
	if dueTicks != 0 {
		t.Errorf("완주 없이 due 가 %d틱 섰다, want 0", dueTicks)
	}
	f.collectAt(offlineAt(300 * time.Second)) // 주기 수집
	if due, _ := f.judge(301*time.Second, "s1", "s2"); !slices.Equal(due, []string{"s1", "s2"}) {
		t.Errorf("주기 수집 완주 뒤 OfflineDue = %v, want [s1 s2]", due)
	}
	if n := f.logs.count(slog.LevelWarn, "scan_collect_skipped_inflight"); n != 0 {
		t.Errorf("scan_collect_skipped_inflight = %d건, want 0", n)
	}
}

// offline_since_resets_on_publishing_or_stale(계획 4.1 ① · 뮤테이션 137) — 송출이 다시 보이거나
// 관측이 낡으면 기다림(since · 재점검 표시 · 점검 대기 로그 표시)을 통째로 지운다. 다음 기다림은
// 새 since 에서 여유를 다시 센다 — 옛 since 뒤에 시작한 수집은 새 기다림의 점검이 아니다. 관측이
// 없으면(폴러 미배선 · 첫 성공 전) 기다림 자체가 서지 않는다(fail-closed).
func TestOfflineSinceResetsOnPublishingOrStale(t *testing.T) {
	t.Run("송출_재개", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		f.judge(0, "s1") // since = 원점
		f.onAir("s1", offlineAt(5*time.Second))
		f.ix.OfflineDue(offlineAt(5 * time.Second))
		f.judge(20*time.Second, "s1")            // since = 원점+20초
		f.collectAt(offlineAt(25 * time.Second)) // 옛 since + 여유 뒤 · 새 since + 여유 앞
		if due, _ := f.judge(26*time.Second, "s1"); len(due) != 0 {
			t.Errorf("OfflineDue(원점+26s) = %v, want [] — since 는 송출 재개 뒤 새로 섰다", due)
		}
		f.collectAt(offlineAt(30 * time.Second))
		if due, _ := f.judge(31*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("새 since + 여유 뒤 완주 뒤 OfflineDue = %v, want [s1]", due)
		}
	})
	t.Run("관측_낡음", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		f.judge(0, "s1")                             // since = 원점
		f.ix.OfflineDue(offlineAt(31 * time.Second)) // 관측 나이 31초 > OBS_FRESH 30초
		f.judge(40*time.Second, "s1")                // since = 원점+40초
		f.collectAt(offlineAt(45 * time.Second))
		if due, _ := f.judge(46*time.Second, "s1"); len(due) != 0 {
			t.Errorf("OfflineDue(원점+46s) = %v, want [] — since 는 관측 낡음 뒤 새로 섰다", due)
		}
		f.collectAt(offlineAt(50 * time.Second))
		if due, _ := f.judge(51*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("새 since + 여유 뒤 완주 뒤 OfflineDue = %v, want [s1]", due)
		}
	})
	t.Run("신선도_경계", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		f.judge(0, "s1") // since = 원점
		f.runCheck(10*time.Second, "s1")
		f.offAir("s1", offlineAt(10*time.Second))
		if due, _ := f.ix.OfflineDue(offlineAt(40 * time.Second)); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("관측 나이 30초의 OfflineDue = %v, want [s1] — 나이 ≤ OBS_FRESH 는 신선하다", due)
		}
		if due, _ := f.ix.OfflineDue(offlineAt(40*time.Second + time.Millisecond)); len(due) != 0 {
			t.Errorf("관측 나이 30.001초의 OfflineDue = %v, want [] — OBS_FRESH 를 넘으면 낡았다", due)
		}
	})
	t.Run("관측_없음", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1") // 관측을 두지 않는다 — 영값(폴러 미배선 · 첫 성공 전)
		f.collectAt(offlineAt(45 * time.Second))
		for _, d := range []time.Duration{0, 46 * time.Second, 300 * time.Second} {
			if due, collect := f.ix.OfflineDue(offlineAt(d)); len(due) != 0 || collect {
				t.Errorf("관측 없이 OfflineDue(원점+%v) = %v, %t, want [], false", d, due, collect)
			}
		}
	})
	t.Run("재점검_표시와_대기_로그도_지운다", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		first, _ := f.driveOffline(0, 80*time.Second, f.collectTruncatedAt, "s1")
		f.onAir("s1", offlineAt(80*time.Second))
		f.ix.OfflineDue(offlineAt(80 * time.Second))
		second, _ := f.driveOffline(90*time.Second, 160*time.Second, f.collectTruncatedAt, "s1")
		if want := []time.Duration{10 * time.Second, 40 * time.Second}; !slices.Equal(first, want) {
			t.Errorf("첫 기다림의 4.1 발사 = %v, want %v", first, want)
		}
		if want := []time.Duration{100 * time.Second, 130 * time.Second}; !slices.Equal(second, want) {
			t.Errorf("둘째 기다림의 4.1 발사 = %v, want %v — 재점검 표시가 새 기다림으로 넘어왔다", second, want)
		}
		if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 2 {
			t.Errorf("session_offline = %d건, want 2(기다림마다 한 줄)", n)
		}
	})
}

// offline_wait_restarts_when_tail_starts_after_since(계획 4.1 ① · 코드 검수 r1 처분 1) — 기다림 도중
// since 뒤에 시작한 조각이 꼬리가 되면(!Publishing 을 본 뒤에 시작한 송출 — 관측 폴 사이에 끝난 짧은
// 재송출) 기다림을 그 틱부터 다시 센다: 앞서 자격을 준 점검은 그 송출의 파일을 다 보지 못했을 수 있다.
// since 앞에 시작한 조각이 늦게 꼬리가 되는 평시 끝(워처 Idle 이 넣는 마지막 조각)은 기다림을 그대로
// 둔다 — 평시 종료를 늦추지 않는다.
func TestOfflineWaitRestartsWhenTailStartsAfterSince(t *testing.T) {
	t.Run("관측_사이_짧은_재송출", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.makeFile("s1", segName(offlineT0, 0), 1000) // B1 의 마지막 조각(원점..+4초)
		f.collectAt(offlineAt(4 * time.Second))
		f.judge(4500*time.Millisecond, "s1")                         // 관측 P0 — since = +4.5초
		f.runCheck(14500*time.Millisecond, "s1")                     // 점검 — 완주 · 결손 없음(③ 참)
		f.ix.OfflineTried("other", false, offlineAt(12*time.Second)) // 다른 스트림의 전이 실패 — +42초까지 쉰다
		f.judge(24500*time.Millisecond, "s1")                        // 관측 P1 — 송출 아님

		// 재송출 B2(+25.5..+33.5초)는 P1 과 P2(+34.5초) 사이에 끝나 관측되지 않는다. b1 은 b2 파일이 생긴
		// +29.5초에 워처(NextFile)가 넣어 꼬리가 되고, b2 는 마지막 쓰기 10초 뒤(+43.5초)에 워처(Idle)가 넣는다.
		b1 := f.segment("s1", segName(offlineT0, 25500*time.Millisecond), 1000, recording.ReasonNextFile)
		b2 := f.segment("s1", segName(offlineT0, 29500*time.Millisecond), 1000, recording.ReasonIdle)
		f.mustHandle(b1)
		f.offAir("s1", offlineAt(34500*time.Millisecond)) // 관측 P2 — 송출 아님
		f.ix.OfflineDue(offlineAt(35 * time.Second))      // b1 을 본 첫 판정 — 기다림을 이 틱부터 다시 센다

		if due, _ := f.judge(42*time.Second, "s1"); len(due) != 0 {
			t.Errorf("쉼이 끝난 OfflineDue(원점+42s) = %v, want [] — b2 가 장부 밖이다(옛 점검은 B2 파일보다 먼저 걸었다)", due)
		}
		f.mustHandle(b2) // +43.5초 — since 앞에 시작한 조각이라 기다림은 그대로다
		if due, collect := f.judge(44500*time.Millisecond, "s1"); len(due) != 0 || collect {
			t.Errorf("OfflineDue(원점+44.5s) = %v, %t, want [], false — 다시 센 since(+35초) + 여유 전이다", due, collect)
		}
		f.runCheck(45*time.Second, "s1") // 다시 센 기다림의 첫 점검
		if due, _ := f.judge(46*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("b2 가 든 뒤 새 점검이 완주한 뒤 OfflineDue = %v, want [s1]", due)
		}
		if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 1 {
			t.Errorf("session_offline = %d건, want 1(다시 센 기다림의 한 줄)", n)
		}
		if at, _ := f.logs.attrs("session_offline")["wait_started_at"].(time.Time); !at.Equal(offlineAt(35 * time.Second)) {
			t.Errorf("session_offline wait_started_at = %v, want %v(기다림을 다시 센 틱)", at, offlineAt(35*time.Second))
		}
	})
	t.Run("평시_끝_마지막_조각이_늦게_듦", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.makeFile("s1", segName(offlineT0, 0), 1000) // 끝에서 둘째 조각(원점..+4초)
		f.collectAt(offlineAt(4 * time.Second))
		f.judge(8500*time.Millisecond, "s1") // RTMP 가 +8초에 끊겨 항목이 바로 사라졌다 — since = +8.5초
		// 마지막 조각(+4..+8초)은 마지막 쓰기 10초 뒤(+18초)에 워처(Idle)가 넣는다 — since 앞에 시작한 조각이다.
		f.mustHandle(f.segment("s1", segName(offlineT0, 4*time.Second), 1000, recording.ReasonIdle))
		f.runCheck(18500*time.Millisecond, "s1") // 첫 점검은 since + 여유 그대로다
		if due, _ := f.judge(20*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("OfflineDue(원점+20s) = %v, want [s1] — 유입 정지(마지막 조각 끝 + 12초)에서 늦지 않고 연다", due)
		}
	})
	t.Run("경계", func(t *testing.T) {
		for _, tc := range []struct {
			name string
			// start 는 새 꼬리 조각의 시작에서 since(원점+8.5초)를 뺀 값이다.
			start       time.Duration
			wantRestart bool
		}{
			{name: "since_앞", start: -time.Microsecond},
			{name: "since_같음"},
			{name: "since_뒤", start: time.Microsecond, wantRestart: true},
		} {
			t.Run(tc.name, func(t *testing.T) {
				f := newOfflineFixture(t)
				f.makeFile("s1", segName(offlineT0, 0), 1000)
				f.collectAt(offlineAt(4 * time.Second))
				f.judge(8500*time.Millisecond, "s1") // since = +8.5초
				f.mustHandle(f.segment("s1", segName(offlineAt(8500*time.Millisecond), tc.start), 1000, recording.ReasonIdle))
				f.judge(10*time.Second, "s1") // 새 꼬리를 보는 판정 — 다시 세면 since = +10초
				// 첫 점검은 since + 여유에 선다 — 옛 since 면 +18.5초, 다시 셌으면 +20초다.
				if _, collect := f.judge(18500*time.Millisecond, "s1"); collect == tc.wantRestart {
					t.Errorf("꼬리 시작 - since = %v 일 때 OfflineDue(원점+18.5s) collect = %t, want %t", tc.start, collect, !tc.wantRestart)
				}
			})
		}
	})
	t.Run("여는_틱에_이미_since_뒤_꼬리", func(t *testing.T) {
		// 지난 폴(원점)은 송출 아님을 봤고, 그 뒤에 시작한 조각(+2초)이 기다림이 열리기 전에 꼬리가
		// 됐다(전이 직후의 짧은 다음 송출 · 루프가 다른 case 에 묶였다 풀린 틱). 여는 틱에도 같은
		// 비교를 적용해 기다림을 그 틱(+6초)부터 센다.
		f := newOfflineFixture(t)
		f.offAir("s1", offlineAt(0))
		f.mustHandle(f.segment("s1", segName(offlineT0, 2*time.Second), 1000, recording.ReasonIdle))
		f.ix.OfflineDue(offlineAt(6 * time.Second)) // 기다림을 여는 판정
		if _, collect := f.ix.OfflineDue(offlineAt(10 * time.Second)); collect {
			t.Error("OfflineDue(원점+10s) collect = true, want false — 관측 시각 + 여유가 아니라 여는 틱 + 여유(+16초)에 점검한다")
		}
		if _, collect := f.ix.OfflineDue(offlineAt(16 * time.Second)); !collect {
			t.Error("OfflineDue(원점+16s) collect = false, want true — 여는 틱(+6초) + 여유")
		}
	})
	t.Run("재점검_표시와_대기_로그도_지운다", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.makeFile("s1", segName(offlineT0, 0), 1000)
		f.collectAt(offlineAt(4 * time.Second))
		first, _ := f.driveOffline(5*time.Second, 60*time.Second, f.collectTruncatedAt, "s1") // since = +5초
		// 관측되지 않은 송출의 조각(+50초 시작)이 꼬리가 된다.
		f.mustHandle(f.segment("s1", segName(offlineT0, 50*time.Second), 1000, recording.ReasonIdle))
		second, _ := f.driveOffline(60*time.Second, 150*time.Second, f.collectTruncatedAt, "s1") // since = +60초
		if want := []time.Duration{15 * time.Second, 45 * time.Second}; !slices.Equal(first, want) {
			t.Errorf("다시 세기 전 4.1 발사 = %v, want %v", first, want)
		}
		if want := []time.Duration{70 * time.Second, 100 * time.Second}; !slices.Equal(second, want) {
			t.Errorf("다시 센 기다림의 4.1 발사 = %v, want %v — 첫 점검과 재점검을 새로 받는다", second, want)
		}
		if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 2 {
			t.Errorf("session_offline = %d건, want 2(기다림마다 한 줄)", n)
		}
	})
}

// offline_check_gap_blocks_on_unresolved_late_file(계획 4.1 ③(b) 결손 ⅱ · 뮤테이션 141 · 142) —
// 점검 수집을 처리한 뒤 그 스트림에 커서 마지막 시작보다 늦은 미기록 파일이 남으면 원인과 무관하게
// 결손이다: 행마다 전이 대상이 아니고 offlineRetryAfter 뒤 재점검을 한 번 요구한다. 커서 마지막
// 시작 이하의 미기록 파일(구멍)은 H3 가 영구히 거르므로 결손이 아니다.
func TestOfflineCheckGapBlocksOnUnresolvedLateFile(t *testing.T) {
	lastName := segName(baseWall, 8*time.Second)
	tests := []struct {
		name string
		// fault 는 부트 수집 뒤, 점검 수집 전에 그 스트림의 미기록 파일과 그 파일의 사정을 만든다.
		fault   func(f *fixture)
		wantDue bool
	}{{
		name: "확인_stat_오류", // 최신 파일 확인 stat 이 시간 초과 아닌 오류 — latest_stat_failed
		fault: func(f *fixture) {
			f.failStat(f.makeFile("s1", lastName, 1000), 1, os.ErrPermission)
		},
	}, {
		name: "측정_중_크기_계속_변함", // H5 상한 소진 — unsettled_giving_up 뒤 워처 인계
		fault: func(f *fixture) {
			f.probe.growTo = f.makeFile("s1", lastName, 100)
		},
	}, {
		name: "측정_stat_오류", // 확인 stat 은 통과하고 측정 stat 이 오류 — stat_failed
		fault: func(f *fixture) {
			f.failStat(f.makeFile("s1", lastName, 1000), 2, os.ErrPermission)
		},
	}, {
		name: "길이_0", // 닫히지 않은 파일(mvhd 길이 0) — invalid_duration
		fault: func(f *fixture) {
			f.makeFile("s1", lastName, 1000)
			f.probe.vals = []int64{0}
		},
	}, {
		name: "poison",
		fault: func(f *fixture) {
			f.makeFile("s1", lastName, 1000)
			f.store.insertErrs = []error{&pgconn.PgError{Code: "22021", Message: "테스트용"}}
		},
	}, {
		name: "구멍", // 꼬리(+4초)보다 이른 미기록 파일 — H3 가 거른다(late_segment_skipped)
		fault: func(f *fixture) {
			f.makeFile("s1", segName(baseWall, 2*time.Second), 1000)
		},
		wantDue: true,
	}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineFixture(t)
			f.boot("s1")
			tc.fault(f)
			f.judge(0, "s1")
			f.runCheck(10*time.Second, "s1")

			due, _ := f.judge(11*time.Second, "s1")
			if got := slices.Equal(due, []string{"s1"}); got != tc.wantDue {
				t.Fatalf("점검 뒤 OfflineDue = %v, want due=%t", due, tc.wantDue)
			}
			if tc.wantDue {
				return
			}
			if _, collect := f.judge(40*time.Second, "s1"); !collect {
				t.Error("결손 뒤 offlineRetryAfter 에 재점검을 요구하지 않았다")
			}
		})
	}
}

// offline_walk_error_scoped_to_stream_dir(계획 4.1 ③(b) 결손 ⅰ · 뮤테이션 143 · 144) — 순회 항목
// 오류는 오류 경로의 루트 기준 첫 요소 스트림만 막는다. 루트 자체의 순회 오류는 어느 스트림의
// 목록도 온전하지 않으므로 그 수집이 4.1 완주가 아니다 — 원인이 풀린 뒤 주기 수집이 완주하면 전이
// 대상이 된다. 스위퍼 arm 의 첫 완주는 루트 오류와 무관하게 그대로다(r47 미확인 3 — PR ⓒ 는
// 바꾸지 않는다).
func TestOfflineWalkErrorScopedToStreamDir(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	t.Run("스트림_디렉터리", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("a", "b")
		unreadable(t, filepath.Join(f.root, "a"))
		f.judge(0, "a", "b")
		f.runCheck(10*time.Second, "a", "b")
		if due, _ := f.judge(11*time.Second, "a", "b"); !slices.Equal(due, []string{"b"}) {
			t.Errorf("OfflineDue = %v, want [b] — a 디렉터리의 순회 오류는 a 만 막는다", due)
		}
		if n := f.logs.count(slog.LevelWarn, "walk_entry_failed"); n != 1 {
			t.Errorf("walk_entry_failed = %d건, want 1 — 의도한 경로가 아니다", n)
		}
	})
	t.Run("루트", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("a", "b")
		restore := unreadable(t, f.root)
		f.judge(0, "a", "b")
		f.runCheck(10*time.Second, "a", "b")
		if due, _ := f.judge(11*time.Second, "a", "b"); len(due) != 0 {
			t.Errorf("루트 순회 오류 뒤 OfflineDue = %v, want [] — 그 수집은 4.1 완주가 아니다", due)
		}
		restore()
		f.collectAt(offlineAt(300 * time.Second)) // 주기 수집
		if due, _ := f.judge(301*time.Second, "a", "b"); !slices.Equal(due, []string{"a", "b"}) {
			t.Errorf("주기 수집 완주 뒤 OfflineDue = %v, want [a b]", due)
		}
	})
	t.Run("루트_오류여도_스위퍼_첫_완주는_그대로", func(t *testing.T) {
		f := newOfflineFixture(t)
		unreadable(t, f.root)
		if !f.collectAt(offlineAt(0)) {
			t.Error("루트 순회 오류 수집이 첫 완주가 아니다 — 스위퍼 arm 시점이 바뀌었다")
		}
	})
}

// offline_recheck_once_then_periodic(계획 4.1 점검 발사 · 뮤테이션 145 · 146) — 결손이 이어지면 4.1
// 은 첫 점검 한 번과 재점검 한 번만 발사하고 그 뒤로는 발사하지 않는다 — 판정 입력은 주기 수집이
// 만든다. 결손이 풀린 주기 수집이 완주하면 전이 대상이 된다. 래치 트립 중에는 4.1 이 발사하지
// 않는다(ADR-063 결정 4 — 래치는 주기 수집이 푼다).
func TestOfflineRecheckOnceThenPeriodic(t *testing.T) {
	collect := func(f *fixture) func(time.Time) {
		return func(start time.Time) { f.collectAt(start) }
	}
	t.Run("결손_지속", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		last := f.makeFile("s1", segName(baseWall, 8*time.Second), 1000)
		orig := f.ix.statFn
		f.ix.statFn = func(p string) (os.FileInfo, error) {
			if p == last {
				return nil, os.ErrPermission // 확인 stat 이 번번이 실패한다 — 결손 ⅱ 가 이어진다
			}
			return orig(p)
		}
		fires, dueTicks := f.driveOffline(0, 300*time.Second, collect(f), "s1")
		if want := []time.Duration{10 * time.Second, 40 * time.Second}; !slices.Equal(fires, want) {
			t.Errorf("4.1 발사 시각 = %v, want %v(첫 점검 + 재점검 한 번)", fires, want)
		}
		if dueTicks != 0 {
			t.Errorf("결손이 이어지는데 due 가 %d틱 섰다, want 0", dueTicks)
		}

		f.ix.statFn = orig                        // 결손이 풀렸다
		f.collectAt(offlineAt(300 * time.Second)) // 주기 수집
		if due, _ := f.judge(301*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("결손 없는 주기 수집 뒤 OfflineDue = %v, want [s1]", due)
		}
	})
	t.Run("래치_트립", func(t *testing.T) {
		f := newOfflineFixture(t)
		f.boot("s1")
		f.ix.fsLatch.Trip(filepath.Join(f.root, "s1", "멈춘파일.mp4"), "measure")
		if fires, _ := f.driveOffline(0, 120*time.Second, collect(f), "s1"); len(fires) != 0 {
			t.Errorf("래치 트립 중 4.1 발사 시각 = %v, want 없음", fires)
		}
		f.collectAt(offlineAt(300 * time.Second)) // 주기 수집이 래치를 풀고 완주한다
		if due, _ := f.judge(301*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
			t.Errorf("래치를 푼 완주 뒤 OfflineDue = %v, want [s1]", due)
		}
	})
}

// permanent_check_gap_holds_until_file_gone(계획 4.1 트레이드오프 — kty qa #59 대가 고정 · 뮤테이션
// 146) — 마지막 파일의 길이가 0 이면(MediaMTX 비정상 종료로 닫히지 않은 파일) 결손이 영구라 회차가
// 닫히지 않는다. 4.1 발사는 기다림 한 번에 둘뿐이고, 그 파일이 지워진 뒤 주기 수집이 완주하면 전이
// 대상이 된다.
func TestPermanentCheckGapHoldsUntilFileGone(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1")
	last := f.makeFile("s1", segName(baseWall, 8*time.Second), 1000)
	f.probe.vals = []int64{0} // 부트 뒤 새로 재는 파일은 마지막 파일뿐이다

	fires, dueTicks := f.driveOffline(0, 300*time.Second, func(start time.Time) { f.collectAt(start) }, "s1")
	if len(fires) != 2 {
		t.Errorf("4.1 발사 = %v, want 2번(첫 점검 + 재점검)", fires)
	}
	if dueTicks != 0 {
		t.Errorf("길이 0 파일이 남았는데 due 가 %d틱 섰다, want 0", dueTicks)
	}
	if n := f.logs.count(slog.LevelError, "invalid_duration"); n == 0 {
		t.Error("invalid_duration 이 없다 — 의도한 경로가 아니다")
	}

	if err := os.Remove(last); err != nil { // MediaMTX 보존 만료가 지웠다
		t.Fatalf("파일 삭제 실패: %v", err)
	}
	f.collectAt(offlineAt(300 * time.Second)) // 주기 수집
	if due, _ := f.judge(301*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Errorf("파일이 지워진 뒤 주기 수집 완주 뒤 OfflineDue = %v, want [s1]", due)
	}
}

// offline_failure_pauses_all_transitions 의 판정 층 절반(계획 4.1 실패 · 뮤테이션 139) — 전이
// 실패(서버 커밋 여부 모름)를 되돌리면 offlineRetryAfter 동안 어느 스트림도 due 에 들지 않고,
// 한 번 가드는 쓰지 않으므로 쉼이 끝나면 같은 꼬리로 다시 due 에 든다. 같은 틱의 나머지 전이를
// 멈추는 것 · ERROR 한 줄 · 요구 적재는 루프 몫이라 커밋 6 이 단언한다.
func TestOfflineFailurePausesAllTransitions(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1", "s2")
	f.judge(0, "s1", "s2")
	f.runCheck(10*time.Second, "s1", "s2")
	if due, _ := f.judge(11*time.Second, "s1", "s2"); !slices.Equal(due, []string{"s1", "s2"}) {
		t.Fatalf("점검 뒤 OfflineDue = %v, want [s1 s2] — 준비가 어긋났다", due)
	}

	f.ix.OfflineTried("s1", false, offlineAt(11*time.Second))
	for _, d := range []time.Duration{11 * time.Second, 25 * time.Second, 40*time.Second + 999*time.Millisecond} {
		if due, _ := f.judge(d, "s1", "s2"); len(due) != 0 {
			t.Errorf("실패 뒤 OfflineDue(원점+%v) = %v, want [] — offlineRetryAfter 동안 쉰다", d, due)
		}
	}
	if due, _ := f.judge(41*time.Second, "s1", "s2"); !slices.Equal(due, []string{"s1", "s2"}) {
		t.Errorf("쉼 뒤 OfflineDue = %v, want [s1 s2] — 실패는 한 번 가드를 쓰지 않는다", due)
	}
}

// offline_update_once_per_tail 의 판정 층 절반(계획 4.1 대상 · 뮤테이션 92) — 전이를 시도하면(1행 ·
// 0행) 그 꼬리에 한 번 가드가 서서, 같은 꼬리로는 다시 due 에 들지 않고 점검도 요구하지 않는다(끝난
// 옛 방송). 다음 방송의 조각이 꼬리를 옮기면 다시 대상이다. UPDATE 호출 수 1 은 커밋 6 이
// 단언한다.
func TestOfflineUpdateOncePerTail(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1")
	f.judge(0, "s1")
	f.runCheck(10*time.Second, "s1")
	if due, _ := f.judge(11*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Fatalf("점검 뒤 OfflineDue = %v, want [s1] — 준비가 어긋났다", due)
	}
	f.ix.OfflineTried("s1", true, offlineAt(11*time.Second))

	for _, d := range []time.Duration{12 * time.Second, 60 * time.Second, 600 * time.Second} {
		if due, collect := f.judge(d, "s1"); len(due) != 0 || collect {
			t.Errorf("시도한 꼬리로 OfflineDue(원점+%v) = %v, %t, want [], false", d, due, collect)
		}
	}

	// 다음 방송 — 송출이 보였다가 끝나고, 그 조각이 꼬리를 옮긴다.
	f.onAir("s1", offlineAt(610*time.Second))
	f.ix.OfflineDue(offlineAt(610 * time.Second))
	f.makeFile("s1", segName(baseWall, 60*time.Second), 1000)
	f.collectAt(offlineAt(620 * time.Second))
	f.judge(630*time.Second, "s1") // since = 원점+630초
	f.runCheck(640*time.Second, "s1")
	if due, _ := f.judge(641*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Errorf("새 꼬리의 점검 뒤 OfflineDue = %v, want [s1]", due)
	}
}

// OfflineTried 의 ok 는 그 기다림도 끝낸다 — 계획 4.1 이 정하지 않은 자리(내가 얹은 것 · 거부
// 가능). 송출 관측 없이(폴 간격보다 짧은 다음 방송) 새 조각이 꼬리를 옮겨도 옛 기다림의 since 와
// 점검을 이어 쓰지 않고, 새 기다림이 점검을 새로 받은 뒤에야 전이 대상이 된다.
func TestOfflineTriedEndsTheWait(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1")
	f.judge(0, "s1")
	f.runCheck(10*time.Second, "s1")
	f.judge(11*time.Second, "s1")
	f.ix.OfflineTried("s1", true, offlineAt(11*time.Second))

	f.makeFile("s1", segName(baseWall, 60*time.Second), 1000)
	f.collectAt(offlineAt(20 * time.Second)) // 꼬리가 옮겨 간다 — 옛 since + 여유 뒤에 시작한 수집이다
	if due, _ := f.judge(21*time.Second, "s1"); len(due) != 0 {
		t.Errorf("새 꼬리 직후 OfflineDue = %v, want [] — 옛 기다림의 점검을 이어 썼다", due)
	}
	f.runCheck(31*time.Second, "s1") // 새 since(원점+21초) + 여유
	if due, _ := f.judge(32*time.Second, "s1"); !slices.Equal(due, []string{"s1"}) {
		t.Errorf("새 기다림의 점검 뒤 OfflineDue = %v, want [s1]", due)
	}
	if n := f.logs.count(slog.LevelInfo, "session_offline"); n != 2 {
		t.Errorf("session_offline = %d건, want 2(기다림마다 한 줄)", n)
	}
}
