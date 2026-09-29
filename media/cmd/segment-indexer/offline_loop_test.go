package main

// 4.1 종료 전이의 루프 층 검증(POK-195 M4 PR ⓒ 커밋 6 — 계획 4.1 · 체크리스트 459 B-1 · B-2 4 · 판단 J56–J59 · J61).
//
// 판정 층 시험(internal/indexer/offline_judgment_test.go)이 「due 에 든다 · 점검을 요구한다」를 단언했다. 여기는 그
// 판정이 루프 보조 타입(rewindLoop)을 거쳐 EndLive 호출 · 점검 수집 발사 · 캐시 push · 요구 적재로 이어지는지를 호출
// 수로 잰다. 픽스처와 시각을 세우는 법은 offline_loop_fixture_test.go 에 있다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/indexer"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/recording"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// ---------------------------------------------------------------------------
// B-1 — 판정 층 단언을 EndLive 호출 수로 잇는다
// ---------------------------------------------------------------------------

// offline_waits_for_check_collect_after_not_publishing 의 루프 절반(체크리스트 B-1 1 · 뮤테이션 131) — 관측이
// 신선하고 송출 중이 아니며 유입이 멈췄어도 점검 수집이 완주하기 전에는 EndLive 를 부르지 않는다. since + IdleTimeout
// 틱에 루프가 점검 수집을 한 번 발사하고, 그 수집이 완주한 다음 틱에 EndLive 를 한 번 부른다.
func TestOfflineLoopWaitsForCheckCollectAfterNotPublishing(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")

	for _, d := range []time.Duration{0, 5 * time.Second, 9 * time.Second} {
		if fired := f.judge(f.at(d), "s1"); fired || len(f.ends.called()) != 0 {
			t.Fatalf("since+%v 틱 = 발사 %t · EndLive %v, want false · [] — 점검 전이고 여유(10초) 전이다", d, fired, f.ends.called())
		}
	}
	if fired := f.judge(f.at(10*time.Second), "s1"); !fired || len(f.ends.called()) != 0 {
		t.Fatalf("since+10s 틱 = 발사 %t · EndLive %v, want true · [] — 그 틱에 점검 수집을 발사하고 전이는 결과 뒤다", fired, f.ends.called())
	}
	f.judge(f.at(11*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("점검이 완주한 뒤 EndLive = %v, want [s1]", got)
	}
}

// deferred_latest_does_not_qualify_offline 의 루프 절반(체크리스트 B-1 2 · 뮤테이션 135) — 점검 수집이 마지막 파일을
// 워처에 넘기면(아직 쓰이는 중일 수 있다) EndLive 를 부르지 않는다. 루프는 그 점검 30초 뒤 재점검을 한 번 발사하고,
// 재점검이 그 파일을 장부에 넣으면 EndLive 를 부른다. 그 행이 live 회차에 붙는지(PG)는 indexer 의 PG 시험이 잰다.
func TestOfflineLoopDeferredLatestDoesNotQualify(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")
	last := f.makeFile("s1", f.wall(8*time.Second))
	f.touch(last, time.Now()) // 방금 쓰였다 — 점검 수집의 최신 파일 판정이 워처에 넘긴다
	f.judge(f.at(0), "s1")
	if !f.judge(f.at(10*time.Second), "s1") {
		t.Fatal("since + IdleTimeout 에 점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	check := f.last

	f.judge(f.at(11*time.Second), "s1")
	if got := f.ends.called(); len(got) != 0 {
		t.Fatalf("인계한 점검 뒤 EndLive = %v, want [] — 마지막 파일이 아직 장부 밖이다", got)
	}
	if f.judge(check.before.Add(29*time.Second), "s1") {
		t.Error("점검 29초 뒤에 재점검을 발사했다 — offlineRetryAfter(30초) 전이다")
	}
	f.touch(last, time.Now().Add(-time.Hour)) // 쓰기가 끝났다
	if !f.judge(check.after.Add(30*time.Second), "s1") {
		t.Fatal("점검 30초 뒤에 재점검을 발사하지 않았다")
	}
	f.judge(check.after.Add(31*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("재점검이 마지막 파일을 넣은 뒤 EndLive = %v, want [s1]", got)
	}
	if rows := f.store.rowsOf("s1"); len(rows) != 3 || rows[2].LocalPath != last {
		t.Errorf("재점검 뒤 행 = %d개, want 3(마지막 파일을 재점검이 넣는다)", len(rows))
	}
}

// offline_blocked_while_fs_latch_tripped 의 루프 절반(체크리스트 B-1 3 · 뮤테이션 136 · 145) — 점검이 완주했어도
// FS 래치가 트립이면 EndLive 를 부르지 않고, 트립 동안 루프는 4.1 점검 수집을 발사하지 않는다. 주기 수집이 래치를
// 풀고 완주하면 EndLive 를 부른다.
func TestOfflineLoopBlockedWhileFSLatchTripped(t *testing.T) {
	f := newOfflineLoop(t, func(o *indexer.Options) { o.FSOpTimeout = 500 * time.Millisecond })
	f.boot("s1")
	f.judge(f.at(0), "s1")
	if !f.judge(f.at(10*time.Second), "s1") {
		t.Fatal("since + IdleTimeout 에 점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	f.tripLatch("s1")

	for _, d := range []time.Duration{11 * time.Second, 45 * time.Second, 200 * time.Second} {
		if fired := f.judge(f.at(d), "s1"); fired || len(f.ends.called()) != 0 {
			t.Errorf("트립 중 since+%v 틱 = 발사 %t · EndLive %v, want false · [] — 전이도 4.1 발사도 없어야 한다", d, fired, f.ends.called())
		}
	}
	f.periodic() // 주기 수집 — 머리에서 래치를 풀고 완주한다
	f.judge(f.at(201*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("래치를 푼 완주 뒤 EndLive = %v, want [s1]", got)
	}
}

// offline_check_collect_shared_and_rate_limited 의 루프 절반(체크리스트 B-1 4 · 뮤테이션 140) — 두 스트림이 기다려도
// 루프가 실제로 발사하는 점검 수집은 한 번이다. 완주하지 못한 점검 뒤에는 30초가 지나서야 재점검을 한 번 발사하고, 그
// 뒤로 4.1 은 발사하지 않는다(주기 수집이 판정 입력을 만든다). 4.1 발사는 비행 중 수집과 부딪치지 않는다
// (scan_collect_skipped_inflight 0).
func TestOfflineLoopCheckCollectSharedAndRateLimited(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1", "s2")
	notComplete := cancelledContext(t) // 발사한 수집을 끝난 ctx 로 적용한다 — 완주가 아니다
	f.judge(f.at(0), "s1", "s2")

	var fires []fireStamp
	var fireAt []time.Time
	for d := 10 * time.Second; d < 300*time.Second; d += time.Second {
		f.offAir("s1", f.at(d))
		f.offAir("s2", f.at(d))
		if f.tickApplying(notComplete, f.at(d)) {
			fires, fireAt = append(fires, f.last), append(fireAt, f.at(d))
		}
	}
	if len(fires) != 2 || !fireAt[0].Equal(f.at(10*time.Second)) {
		t.Fatalf("4.1 발사 = %v, want since+10s 와 재점검 둘 — 두 스트림이 한 발사를 나눈다", fireAt)
	}
	if gap := fireAt[1].Sub(fires[0].before); gap < 30*time.Second || fireAt[1].Sub(fires[0].after) >= 31*time.Second {
		t.Errorf("재점검 = 점검 시작 뒤 %v, want 30초에 닿은 첫 틱 — offlineRetryAfter 가 재점검을 묶는다", gap)
	}
	if got := f.ends.called(); len(got) != 0 {
		t.Errorf("완주 없이 EndLive = %v, want []", got)
	}
	f.periodic()
	f.judge(f.at(301*time.Second), "s1", "s2")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1", "s2"}) {
		t.Errorf("주기 수집 완주 뒤 EndLive = %v, want [s1 s2]", got)
	}
	if n := f.logs.countLevel(slog.LevelWarn, "scan_collect_skipped_inflight"); n != 0 {
		t.Errorf("scan_collect_skipped_inflight = %d건, want 0", n)
	}
}

// offline_check_gap_blocks_on_unresolved_late_file 의 루프 절반(체크리스트 B-1 5 · 뮤테이션 141 · 142) — 점검 수집을
// 처리한 뒤 그 스트림에 꼬리보다 늦은 미기록 파일이 남으면(원인 무관) EndLive 를 부르지 않고, 30초 뒤 재점검을 한 번
// 발사한다. 꼬리보다 이른 미기록 파일(구멍)은 결손이 아니라 EndLive 를 부른다.
func TestOfflineLoopCheckGapBlocksOnUnresolvedLateFile(t *testing.T) {
	tests := []struct {
		name string
		// fault 는 부트 뒤 · 점검 전에 그 스트림의 미기록 파일과 그 파일의 사정을 만든다.
		fault   func(f *offlineLoop)
		wantEnd bool
	}{{
		name:  "확인_stat_오류", // 최신 파일 확인 stat 이 실패한다 — latest_stat_failed
		fault: func(f *offlineLoop) { f.danglingSegment("s1", f.wall(8*time.Second)) },
	}, {
		name:  "측정_중_크기_계속_변함", // 측정 상한 소진 — unsettled_giving_up 뒤 워처 인계
		fault: func(f *offlineLoop) { f.probe.growOn(f.makeFile("s1", f.wall(8*time.Second))) },
	}, {
		name:  "측정_stat_오류", // 확인 stat 은 지나고 측정 stat 이 실패한다 — stat_failed
		fault: func(f *offlineLoop) { f.probe.removeOn(f.makeFile("s1", f.wall(8*time.Second))) },
	}, {
		name:  "길이_0", // 닫히지 않은 파일(mvhd 길이 0) — invalid_duration
		fault: func(f *offlineLoop) { f.probe.zeroOn(f.makeFile("s1", f.wall(8*time.Second))) },
	}, {
		name: "poison",
		fault: func(f *offlineLoop) {
			f.store.insertErrs = map[string]error{
				f.makeFile("s1", f.wall(8*time.Second)): &pgconn.PgError{Code: "22021", Message: "시험용"},
			}
		},
	}, {
		name:    "구멍", // 꼬리(+4초)보다 이른 미기록 파일 — H3 가 거른다
		fault:   func(f *offlineLoop) { f.makeFile("s1", f.wall(2*time.Second)) },
		wantEnd: true,
	}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineLoop(t, nil)
			f.boot("s1")
			tc.fault(f)
			f.judge(f.at(0), "s1")
			if !f.judge(f.at(10*time.Second), "s1") {
				t.Fatal("since + IdleTimeout 에 점검을 발사하지 않았다 — 준비가 어긋났다")
			}
			check := f.last
			f.judge(f.at(11*time.Second), "s1")
			if got := len(f.ends.called()) == 1; got != tc.wantEnd {
				t.Fatalf("점검 뒤 EndLive = %v, want 호출 %t", f.ends.called(), tc.wantEnd)
			}
			if !tc.wantEnd && !f.judge(check.after.Add(30*time.Second), "s1") {
				t.Error("결손 뒤 offlineRetryAfter 에 재점검을 발사하지 않았다")
			}
		})
	}
}

// offline_walk_error_scoped_to_stream_dir 의 루프 절반(체크리스트 B-1 5 · 뮤테이션 143 · 144) — 스트림 디렉터리의
// 순회 오류는 그 스트림의 EndLive 만 막는다. 루트 순회 오류는 둘 다 막고, 원인이 풀린 뒤 주기 수집이 완주하면 둘 다
// 부른다.
func TestOfflineLoopWalkErrorScopedToStreamDir(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 는 권한 검사를 우회한다")
	}
	t.Run("스트림_디렉터리", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.boot("a", "b")
		unreadableDir(t, filepath.Join(f.root, "a"))
		f.judge(f.at(0), "a", "b")
		if !f.judge(f.at(10*time.Second), "a", "b") {
			t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
		}
		f.judge(f.at(11*time.Second), "a", "b")
		if got := f.ends.called(); !slices.Equal(got, []string{"b"}) {
			t.Errorf("EndLive = %v, want [b] — a 디렉터리의 순회 오류는 a 만 막는다", got)
		}
	})
	t.Run("루트", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.boot("a", "b")
		restore := unreadableDir(t, f.root)
		f.judge(f.at(0), "a", "b")
		if !f.judge(f.at(10*time.Second), "a", "b") {
			t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
		}
		f.judge(f.at(11*time.Second), "a", "b")
		if got := f.ends.called(); len(got) != 0 {
			t.Errorf("루트 순회 오류 뒤 EndLive = %v, want [] — 그 수집은 4.1 완주가 아니다", got)
		}
		restore()
		f.periodic()
		f.judge(f.at(301*time.Second), "a", "b")
		if got := f.ends.called(); !slices.Equal(got, []string{"a", "b"}) {
			t.Errorf("주기 수집 완주 뒤 EndLive = %v, want [a b]", got)
		}
	})
}

// offline_recheck_once_then_periodic 의 루프 절반(체크리스트 B-1 5 · 뮤테이션 145 · 146) — 결손이 이어지면 루프는
// 첫 점검 한 번과 재점검 한 번만 발사한다. 판정이 요구한 발사를 루프가 반드시 하는지(A-2 2 — 재점검 갈래는 판정이
// 이미 표시를 썼다)를 이 발사 수가 잰다. 결손이 풀린 주기 수집 뒤에는 EndLive 를 부른다. 래치 트립 중에는 4.1 이
// 발사하지 않는다.
func TestOfflineLoopRecheckOnceThenPeriodic(t *testing.T) {
	t.Run("결손_지속", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.boot("s1")
		link := f.danglingSegment("s1", f.wall(8*time.Second)) // 최신 파일 확인 stat 이 번번이 실패한다
		if fires := f.drive(0, 300*time.Second, "s1"); fires != 2 {
			t.Errorf("4.1 발사 = %d번, want 2(첫 점검 + 재점검 한 번)", fires)
		}
		if got := f.ends.called(); len(got) != 0 {
			t.Errorf("결손이 이어지는데 EndLive = %v, want []", got)
		}
		if err := os.Remove(link); err != nil { // 결손이 풀렸다
			t.Fatalf("링크 삭제 실패: %v", err)
		}
		f.periodic()
		f.judge(f.at(300*time.Second), "s1")
		if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
			t.Errorf("결손 없는 주기 수집 뒤 EndLive = %v, want [s1]", got)
		}
	})
	t.Run("래치_트립", func(t *testing.T) {
		f := newOfflineLoop(t, func(o *indexer.Options) { o.FSOpTimeout = 500 * time.Millisecond })
		f.boot("s1")
		f.tripLatch("s1")
		if fires := f.drive(0, 120*time.Second, "s1"); fires != 0 {
			t.Errorf("래치 트립 중 4.1 발사 = %d번, want 0", fires)
		}
		f.periodic() // 주기 수집이 래치를 풀고 완주한다
		f.judge(f.at(120*time.Second), "s1")
		if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
			t.Errorf("래치를 푼 완주 뒤 EndLive = %v, want [s1]", got)
		}
	})
}

// permanent_check_gap_holds_until_file_gone 의 루프 절반(체크리스트 B-1 5 · 뮤테이션 146) — 마지막 파일의 길이가
// 0 이면 결손이 영구라 EndLive 를 부르지 않고 4.1 발사는 기다림 한 번에 둘뿐이다. 그 파일이 지워진 뒤 주기 수집이
// 완주하면 EndLive 를 부른다.
func TestOfflineLoopPermanentCheckGapHoldsUntilFileGone(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")
	last := f.makeFile("s1", f.wall(8*time.Second))
	f.probe.zeroOn(last)

	if fires := f.drive(0, 300*time.Second, "s1"); fires != 2 {
		t.Errorf("4.1 발사 = %d번, want 2(첫 점검 + 재점검)", fires)
	}
	if got := f.ends.called(); len(got) != 0 {
		t.Errorf("길이 0 파일이 남았는데 EndLive = %v, want []", got)
	}
	if n := f.logs.countLevel(slog.LevelError, "invalid_duration"); n == 0 {
		t.Error("invalid_duration 이 없다 — 의도한 경로가 아니다")
	}
	if err := os.Remove(last); err != nil { // MediaMTX 보존 만료가 지웠다
		t.Fatalf("파일 삭제 실패: %v", err)
	}
	f.periodic()
	f.judge(f.at(300*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("파일이 지워진 뒤 주기 수집 완주 뒤 EndLive = %v, want [s1]", got)
	}
}

// offline_failure_pauses_all_transitions(체크리스트 B-1 6 · B-2 6 루프 쪽 · 뮤테이션 139 · 판단 J56 · J61) — 전이
// 결과를 모르는 실패(시한 · lock_timeout · DB 오류)가 나면 session_offline result=failed ERROR 한 줄을 남기고, 그
// 스트림에 뷰나 진행 중인 적재가 있으면 적재를 요구하며(힌트 없음 — 부정 표식 · 모르는 스트림은 요구 0), 같은 틱의
// 남은 전이를 멈추고 offlineRetryAfter(30초) 동안 어느 스트림도 시도하지 않는다. 한 번 가드는 쓰지 않아 쉼 뒤 같은
// 꼬리로 다시 시도한다.
func TestOfflineLoopFailurePausesAllTransitions(t *testing.T) {
	tests := []struct {
		name       string
		cached     func(f *offlineLoop)
		wantDemand bool
	}{
		{"뷰_있음", func(f *offlineLoop) { f.view("s1", "S-s1", "live") }, true},
		{"적재_중", func(f *offlineLoop) { f.cache.BeginLoad("s1", nil) }, true},
		{"부정_표식", func(f *offlineLoop) {
			f.cache.CompleteLoad("s1", f.cache.BeginLoad("s1", nil), index.RewindLedger{})
		}, false},
		{"모르는_스트림", func(*offlineLoop) {}, false},
	}
	errDB := errors.New("lock timeout(시험용)")
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineLoop(t, nil)
			f.boot("s1", "s2", "s3")
			tc.cached(f)
			failures := 1
			f.ends.result = func(_ context.Context, s string) (string, error) {
				if s == "s1" && failures > 0 {
					failures--
					return "", errDB
				}
				return "S-" + s, nil
			}
			all := []string{"s1", "s2", "s3"}
			f.judge(f.at(0), all...)
			if !f.judge(f.at(10*time.Second), all...) {
				t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
			}
			f.judge(f.at(11*time.Second), all...)

			if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
				t.Fatalf("실패한 틱의 EndLive = %v, want [s1] — 첫 실패 뒤 같은 틱의 남은 전이를 멈춘다", got)
			}
			if n := f.logs.countLevel(slog.LevelError, "session_offline"); n != 1 {
				t.Errorf("session_offline ERROR = %d건, want 1", n)
			}
			attrs := f.logs.attrs("session_offline")
			if attrs["stream_id"] != "s1" || attrs["result"] != "failed" || !strings.Contains(fmt.Sprint(attrs["err"]), errDB.Error()) {
				t.Errorf("session_offline 속성 = %v, want stream_id=s1 · result=failed · err 에 %q", attrs, errDB)
			}
			demands := f.cache.DemandedLoads()
			switch {
			case tc.wantDemand && (len(demands) != 1 || demands[0].StreamID != "s1" || demands[0].Hint != nil):
				t.Errorf("요구 적재 = %+v, want [{s1 <nil>}] — 결과를 모르면 적재가 캐시 회차를 맞춘다", demands)
			case !tc.wantDemand && len(demands) != 0:
				t.Errorf("요구 적재 = %+v, want [] — 뷰도 적재도 없는 스트림은 요구하지 않는다", demands)
			}
			for _, d := range []time.Duration{12 * time.Second, 25 * time.Second, 40*time.Second + 999*time.Millisecond} {
				f.judge(f.at(d), all...)
			}
			if got := f.ends.called(); len(got) != 1 {
				t.Errorf("쉼 동안 EndLive = %v, want [s1] 그대로 — offlineRetryAfter 동안 쉰다", got)
			}
			f.judge(f.at(41*time.Second), all...)
			if got := f.ends.called(); !slices.Equal(got, []string{"s1", "s1", "s2", "s3"}) {
				t.Errorf("쉼 뒤 EndLive = %v, want [s1 s1 s2 s3] — 실패는 한 번 가드를 쓰지 않는다", got)
			}
		})
	}
}

// offline_update_once_per_tail 의 루프 절반(체크리스트 B-1 7 · 뮤테이션 92) — 같은 꼬리로는 EndLive 를 한 번만 부른다
// (1행이든 0행이든 한 번 가드를 쓴다 — 끝난 옛 방송은 점검도 부르지 않는다). 다음 방송의 조각이 꼬리를 옮기면 다시
// 대상이다.
func TestOfflineLoopUpdateOncePerTail(t *testing.T) {
	for _, tc := range []struct{ name, sessionID string }{{"1행", "S-s1"}, {"0행", ""}} {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineLoop(t, nil)
			f.boot("s1")
			f.ends.result = func(context.Context, string) (string, error) { return tc.sessionID, nil }
			f.judge(f.at(0), "s1")
			if !f.judge(f.at(10*time.Second), "s1") {
				t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
			}
			f.judge(f.at(11*time.Second), "s1")
			for _, d := range []time.Duration{12 * time.Second, 60 * time.Second, 600 * time.Second} {
				if fired := f.judge(f.at(d), "s1"); fired {
					t.Errorf("시도한 꼬리로 since+%v 에 점검을 발사했다", d)
				}
			}
			if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
				t.Fatalf("같은 꼬리의 EndLive = %v, want [s1] 한 번", got)
			}

			// 다음 방송의 조각이 꼬리를 옮긴다. 판정은 틱 사이 시각의 차례를 보지 않으므로 새 기다림의 관측을
			// 실시계 앞에 둬서, 그 조각을 넣은 주기 수집이 새 기다림의 점검 자격을 갖게 한다.
			f.makeFile("s1", f.wall(60*time.Second))
			f.periodic()
			f.judge(time.Now().Add(-15*time.Second), "s1")
			if got := f.ends.called(); !slices.Equal(got, []string{"s1", "s1"}) {
				t.Errorf("새 꼬리의 EndLive = %v, want [s1 s1]", got)
			}
		})
	}
}

// offline_wait_restarts_when_tail_starts_after_since 의 루프 절반(체크리스트 B-1 8 · 뮤테이션 147 · 148) — 점검이 자격을
// 준 뒤 since 뒤에 시작한 조각이 꼬리가 되면(관측 사이의 짧은 재송출) 그 틱부터 기다림을 다시 세고, 새 기다림의 점검이
// 완주하기 전에는 EndLive 를 부르지 않는다. since 앞에 시작한 조각이 늦게 꼬리가 되는 평시 끝은 다시 세지 않는다.
// 다시 센 since 는 그 틱의 시각이라 실시계를 따라야 한다 — 이 시험은 여유를 200ms 로 줄이고 새 점검을 여유가 실제로
// 지난 뒤에 발사한다.
func TestOfflineLoopWaitRestartsWhenTailStartsAfterSince(t *testing.T) {
	const idle = 200 * time.Millisecond
	shortIdle := func(o *indexer.Options) { o.IdleTimeout = idle }
	t.Run("관측_사이_짧은_재송출", func(t *testing.T) {
		f := newOfflineLoop(t, shortIdle)
		f.boot("s1")
		since := time.Now().Add(-time.Second)
		f.judge(since, "s1")
		if !f.judge(since.Add(idle), "s1") {
			t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
		}
		// 자격을 준 점검 뒤, 관측되지 않은 짧은 재송출의 조각이 꼬리가 된다(since 뒤에 시작 · 워처 유입).
		f.handle(f.makeFile("s1", since.Add(500*time.Millisecond)), recording.ReasonNextFile)
		recount := time.Now()
		f.judge(recount, "s1")                                          // 그 조각을 본 첫 판정 — 기다림을 이 틱부터 다시 센다
		time.Sleep(time.Until(recount.Add(idle + 50*time.Millisecond))) // 새 점검이 다시 센 여유 뒤에 시작하도록
		stopped := since.Add(17 * time.Second)                          // 새 꼬리 끝(+4.5초) + 3 × 4초 뒤
		if fired := f.judge(stopped, "s1"); !fired || len(f.ends.called()) != 0 {
			t.Fatalf("유입이 멈춘 틱 = 발사 %t · EndLive %v, want true · [] — 옛 점검은 다시 센 기다림의 점검이 아니다", fired, f.ends.called())
		}
		f.judge(stopped.Add(time.Second), "s1")
		if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
			t.Errorf("새 점검이 완주한 뒤 EndLive = %v, want [s1]", got)
		}
	})
	t.Run("평시_끝_마지막_조각이_늦게_듦", func(t *testing.T) {
		f := newOfflineLoop(t, nil)
		f.boot("s1")
		f.judge(f.at(0), "s1")
		// 마지막 조각(since 앞에 시작)은 마지막 쓰기 뒤에 워처(Idle)가 넣는다 — 기다림은 그대로다.
		f.handle(f.makeFile("s1", f.at(-20*time.Second)), recording.ReasonIdle)
		if !f.judge(f.at(10*time.Second), "s1") {
			t.Fatal("since + IdleTimeout 에 점검을 발사하지 않았다 — 늦게 든 마지막 조각이 기다림을 다시 셌다")
		}
		f.judge(f.at(11*time.Second), "s1")
		if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
			t.Errorf("EndLive = %v, want [s1] — 평시 끝은 늦어지지 않는다", got)
		}
	})
}

// offline_latest_applied_collect_must_be_complete 의 루프 절반(체크리스트 B-1 8 · 뮤테이션 154–156) — 점검이 자격을
// 준 뒤라도 가장 최근에 적용된 수집이 완주가 아니면 EndLive 를 부르지 않는다. 그 수집 30초 뒤 재점검이 완주하면
// 부른다. 래치 중단 행은 ③(c) 와 떼어 잴 수 없어(래치가 서 있는 동안 4.1 이 발사하지 않는다) 주기 수집으로 되돌린다.
func TestOfflineLoopLatestAppliedCollectMustBeComplete(t *testing.T) {
	tests := []struct {
		name string
		// later 는 자격을 받은 뒤 수집 한 번을 적용한다 — 주기 수집이나 워처 재스캔 신호 수집이다.
		later   func(f *offlineLoop)
		wantEnd bool
	}{{
		name:    "완주_대조군",
		later:   func(f *offlineLoop) { f.periodic() },
		wantEnd: true,
	}, {
		name: "루트_오류",
		later: func(f *offlineLoop) {
			restore := unreadableDir(f.t, f.root)
			f.periodic()
			restore()
		},
	}, {
		name: "래치_중단",
		later: func(f *offlineLoop) {
			p := f.makeFile("s1", f.wall(20*time.Second)) // 수집이 처리하다 멈추는 조각
			f.t.Cleanup(f.probe.stallOn(p))
			f.periodic()
			if err := os.Remove(p); err != nil {
				f.t.Fatalf("멈춘 조각 삭제 실패: %v", err)
			}
		},
	}, {
		name:  "수집_오류", // 순회가 끝난 ctx 로 멈춘다
		later: func(f *offlineLoop) { f.periodicWith(cancelledContext(f.t), f.t.Context()) },
	}, {
		name:  "ctx_취소", // 적용 도중 ctx 가 끝난다
		later: func(f *offlineLoop) { f.periodicWith(f.t.Context(), cancelledContext(f.t)) },
	}}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if tc.name == "루트_오류" && os.Geteuid() == 0 {
				t.Skip("root 는 권한 검사를 우회한다")
			}
			f := newOfflineLoop(t, func(o *indexer.Options) { o.FSOpTimeout = 500 * time.Millisecond })
			f.boot("s1")
			f.judge(f.at(0), "s1")
			if !f.judge(f.at(10*time.Second), "s1") { // 자격을 주는 점검 — 완주 · 결손 없음
				t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
			}
			tc.later(f)
			later := f.last
			f.judge(f.at(21*time.Second), "s1")
			if got := len(f.ends.called()) == 1; got != tc.wantEnd {
				t.Fatalf("자격 뒤 수집을 적용한 뒤 EndLive = %v, want 호출 %t", f.ends.called(), tc.wantEnd)
			}
			if tc.wantEnd {
				return
			}
			if tc.name == "래치_중단" {
				f.periodic() // 래치는 주기 수집이 푼다
			} else if !f.judge(later.after.Add(30*time.Second), "s1") {
				t.Fatal("그 수집 30초 뒤에 재점검을 발사하지 않았다")
			}
			f.judge(later.after.Add(31*time.Second), "s1")
			if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
				t.Errorf("완주한 수집 뒤 EndLive = %v, want [s1]", got)
			}
		})
	}
}

// 결과가 안 온(비행 중) 수집은 점검 뒤면 전이를 막지 않는다(체크리스트 B-1 9 · 계획 r48 미확인 6) — 비행 중 수집은
// 점검 발사만 막는다. 자격을 준 점검 뒤 주기 수집이 비행 중인 틱에도 EndLive 를 부른다.
func TestOfflineLoopInflightCollectDoesNotHoldTransition(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")
	f.judge(f.at(0), "s1")
	if !f.judge(f.at(10*time.Second), "s1") {
		t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	if !f.ix.StartCollect(t.Context(), f.root) { // 주기 수집이 발사됐고 결과는 아직 적용되지 않았다
		t.Fatal("주기 수집 발사가 거부됐다")
	}
	f.offAir("s1", f.at(11*time.Second))
	f.rewind.endOfflineSessions(t.Context(), f.at(11*time.Second))
	if got := f.ends.called(); !slices.Equal(got, []string{"s1"}) {
		t.Errorf("수집이 비행 중인 틱의 EndLive = %v, want [s1]", got)
	}
	f.apply(t.Context())
}

// holdTicks case 의 판정 시각(체크리스트 B-1 10 · 판단 J58) — case 는 틱 채널의 값이 아니라 case 안의 현재 시각으로
// 4.1 판정을 돌린다. 루프가 밀려 틱 값이 과거여도 관측 신선도가 느슨해지지 않고(과거 틱 행), 틱 값이 앞서도 지금
// 신선한 관측이 낡은 것으로 읽히지 않는다(미래 틱 행 — 이 행은 holdTicks case 가 보조 타입을 부르는지도 잰다).
func TestHoldTickJudgesOfflineWithCaseClock(t *testing.T) {
	tests := []struct {
		name string
		// obsAge 는 틱을 보낼 때 관측의 나이이고, tickShift 는 틱 채널 값을 지금에서 옮긴 만큼이다.
		obsAge, tickShift time.Duration
		want              []string
	}{
		{"과거_틱_낡은_관측", 40 * time.Second, -35 * time.Second, nil},
		{"미래_틱_신선한_관측", time.Second, time.Hour, []string{"s1"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := newOfflineLoop(t, nil)
			f.boot("s1")
			f.judge(f.at(0), "s1")
			if !f.judge(f.at(10*time.Second), "s1") { // 자격을 주는 점검
				t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
			}
			lf := newLoopFixture(t, false)
			holds := make(chan time.Time)
			lf.deps.ix, lf.deps.rewind, lf.deps.holdTicks = f.ix, f.rewind, holds
			cancel := lf.run()

			now := time.Now()
			f.offAir("s1", now.Add(-tc.obsAge))
			for _, v := range []time.Time{now.Add(tc.tickShift), now} { // 둘째가 받아지면 첫 틱의 case 가 끝났다
				select {
				case holds <- v:
				case <-time.After(2 * time.Second):
					t.Fatal("루프가 보류 틱을 받지 않는다")
				}
			}
			if err := lf.stop(cancel); err != nil {
				t.Fatalf("loop() = %v, want nil", err)
			}
			if got := f.ends.called(); !slices.Equal(got, tc.want) {
				t.Errorf("EndLive = %v, want %v — 판정은 틱 값이 아니라 case 안의 현재 시각으로 선다", got, tc.want)
			}
		})
	}
}

// 배선 불변(체크리스트 B-1 11 · B-6 4.1 전이 · A-2 5 ⑵) — due 가 빈 틱(송출 중)에는 EndLive 를 부르지 않는다. 한
// 틱의 due 는 그 차례대로 부르고, 1행이면 그 회차의 ending 을 캐시에 넘기며 0행이면(live 회차 없음) 넘기지 않는다.
// push 를 받은 회차는 발행 게이트가 닫힌다(ShouldTick 거짓 — 받지 않은 회차는 그대로 열려 있다).
func TestOfflineLoopCallsDueInOrderAndPushesEndings(t *testing.T) {
	f := newOfflineLoop(t, nil)
	all := []string{"s1", "s2", "s3"}
	f.boot(all...)
	for _, s := range all {
		f.view(s, "S-"+s, "live")
	}
	f.ends.result = func(_ context.Context, s string) (string, error) {
		if s == "s2" {
			return "", nil // DB 에 live 회차가 없다(0행)
		}
		return "S-" + s, nil
	}
	for _, d := range []time.Duration{-20 * time.Second, -10 * time.Second} {
		for _, s := range all {
			f.onAir(s, f.at(d))
		}
		if fired := f.tick(f.at(d)); fired || len(f.ends.called()) != 0 {
			t.Fatalf("송출 중 틱 = 발사 %t · EndLive %v, want false · []", fired, f.ends.called())
		}
	}
	f.judge(f.at(0), all...)
	if !f.judge(f.at(10*time.Second), all...) {
		t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	f.judge(f.at(11*time.Second), all...)

	if got := f.ends.called(); !slices.Equal(got, all) {
		t.Errorf("EndLive = %v, want %v(due 차례)", got, all)
	}
	for s, want := range map[string]string{"s1": "ending", "s2": "live", "s3": "ending"} {
		owner, _ := f.cache.Session(s, "S-"+s)
		if owner.State != want {
			t.Errorf("캐시 회차 S-%s state = %q, want %q", s, owner.State, want)
		}
		if got := publish.ShouldTick(owner.RewindSession, f.cache.Loading(s)); got != (want == "live") {
			t.Errorf("ShouldTick(S-%s) = %t, want %t", s, got, want == "live")
		}
	}
}

// 루프가 끝나는 중의 실패(판단 J57 · 발행 층 stopped 관례) — 부른 쪽 ctx 가 끝나 EndLive 가 실패하면 ERROR · 요구 적재 ·
// 쉼을 남기지 않는다: ctx 가 끊은 실패는 DB 상태를 말하지 않는다. 쉼이 없으므로 같은 시각의 다음 틱이 다시 시도한다.
func TestOfflineLoopStopsQuietlyWhenLoopContextEnds(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")
	f.view("s1", "S-s1", "live")
	f.ends.result = func(ctx context.Context, s string) (string, error) {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		return "S-" + s, nil
	}
	f.judge(f.at(0), "s1")
	if !f.judge(f.at(10*time.Second), "s1") {
		t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	f.offAir("s1", f.at(11*time.Second))
	f.rewind.endOfflineSessions(cancelledContext(t), f.at(11*time.Second))

	if n := f.logs.countLevel(slog.LevelError, "session_offline"); n != 0 {
		t.Errorf("session_offline ERROR = %d건, want 0 — 루프 종료는 전이 실패가 아니다", n)
	}
	if d := f.cache.DemandedLoads(); len(d) != 0 {
		t.Errorf("요구 적재 = %+v, want []", d)
	}
	f.judge(f.at(11*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1", "s1"}) {
		t.Errorf("EndLive = %v, want [s1 s1] — 쉼을 쓰지 않았으니 다음 틱이 다시 시도한다", got)
	}
}

// ---------------------------------------------------------------------------
// B-2 4 — 결과 모름 요구 적재
// ---------------------------------------------------------------------------

// offline_unknown_result_demands_load(체크리스트 B-2 4 · 뮤테이션 138 · 판단 J61) — 서버는 전이를 커밋했는데 응답이
// ctx 시한에 끊겨 결과를 모르면, 루프는 캐시 회차를 바꾸지 않고(push 없음) 그 스트림의 적재를 요구한다(힌트 없음).
// 적재(발사는 커밋 7 — 여기서는 BeginLoad · CompleteLoad 로 흉내)가 장부의 ending 을 캐시에 맞추면 그 회차는 발행
// 게이트가 닫히고(ShouldTick 거짓) 정체 사다리가 꺼진다(할 일 영값). 쉼 뒤 재시도는 0행이고 한 번 가드를 쓴다.
func TestOfflineUnknownResultDemandsLoad(t *testing.T) {
	f := newOfflineLoop(t, nil)
	f.boot("s1")
	f.view("s1", "S-s1", "live")
	committed := false
	f.ends.result = func(context.Context, string) (string, error) {
		if !committed {
			committed = true
			return "", context.DeadlineExceeded // 서버는 커밋했고 응답이 시한에 끊겼다
		}
		return "", nil // 이미 ending 이라 live 회차가 없다
	}
	f.judge(f.at(0), "s1")
	if !f.judge(f.at(10*time.Second), "s1") {
		t.Fatal("점검을 발사하지 않았다 — 준비가 어긋났다")
	}
	f.judge(f.at(11*time.Second), "s1")

	demands := f.cache.DemandedLoads()
	if len(demands) != 1 || demands[0].StreamID != "s1" || demands[0].Hint != nil {
		t.Fatalf("요구 적재 = %+v, want [{s1 <nil>}]", demands)
	}
	if got := f.sessionState("s1", "S-s1"); got != "live" {
		t.Errorf("적재 전 캐시 회차 state = %q, want live — 결과를 모르면 push 하지 않는다", got)
	}
	f.cache.CompleteLoad("s1", f.cache.BeginLoad("s1", nil), index.RewindLedger{
		HasCutoff: true,
		Sessions: []index.RewindSession{{
			SessionID: "S-s1", State: "ending", EndReason: "offline", InitUploaded: true, TargetDuration: 6,
		}},
	})
	owner, ok := f.cache.Session("s1", "S-s1")
	if !ok || owner.State != "ending" {
		t.Fatalf("적재 뒤 캐시 회차 = %+v(%t), want ending", owner, ok)
	}
	if publish.ShouldTick(owner.RewindSession, f.cache.Loading("s1")) {
		t.Error("ShouldTick(ending 회차) = true, want false — 발행이 멈춰야 한다")
	}
	_, step := ladderPublisher(t).EvaluateLadder(t.Context(), publish.LadderState{}, publish.LadderInput{
		StreamID: "s1", Snapshot: f.cache.Snapshot("s1"), Owner: owner.RewindSession,
		Now: time.Now(), OwnedSince: time.Now(),
	})
	if step != (publish.LadderStep{}) {
		t.Errorf("ending 회차의 정체 사다리 = %+v, want 영값", step)
	}

	f.judge(f.at(41*time.Second), "s1") // 쉼 뒤 재시도 — 0행
	f.judge(f.at(100*time.Second), "s1")
	if got := f.ends.called(); !slices.Equal(got, []string{"s1", "s1"}) {
		t.Errorf("EndLive = %v, want [s1 s1] — 0행 재시도가 한 번 가드를 쓴다", got)
	}
}
