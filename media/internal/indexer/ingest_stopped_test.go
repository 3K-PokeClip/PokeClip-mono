package indexer

// IngestStopped(제어면 경보의 「송출 중」 술어 — 설계 M5 2.1 · 4.4 · 7절 judgment 행 · cc M2)의 단위
// 검증이다 — POK-200 ②r. 4.1 종료 판정의 ② 유입 정지(ingestStopped)와 같은 술어여야 「송출 중」의 정의가
// 한 곳에만 있고, 읽기 접근자라 판정 상태(기다림 · 가드 · 쉼)를 건드리면 안 된다.

import (
	"log/slog"
	"maps"
	"reflect"
	"testing"
	"time"
)

// 꼬리가 원점 시작 · 4초(프로브 4000)면 끝은 원점+4초이고, 정지는 끝 + 3 × 4초 = 원점+16초부터다(경계 포함 —
// ingestStopped 의 >=).
func TestIngestStoppedUsesTailEndPlusThreeDurations(t *testing.T) {
	f := newOfflineFixture(t)
	f.makeFile("s1", segName(offlineT0, 0), 1000)
	f.collectAt(offlineAt(4 * time.Second))

	for _, tc := range []struct {
		name string
		now  time.Duration
		want bool
	}{
		{"끝_직후", 4 * time.Second, false},
		{"경계_1ms_전", 16*time.Second - time.Millisecond, false},
		{"경계", 16 * time.Second, true},
		{"한참_뒤", time.Minute, true},
	} {
		if stopped, ok := f.ix.IngestStopped("s1", offlineAt(tc.now)); !ok || stopped != tc.want {
			t.Errorf("%s: IngestStopped(원점+%v) = (%v, %v), want (%v, true)", tc.name, tc.now, stopped, ok, tc.want)
		}
	}
}

// 꼬리가 없으면(커서가 없거나 꼬리 없는 커서) 잴 것이 없다 — ok 거짓.
func TestIngestStoppedNotOKWithoutTail(t *testing.T) {
	f := newOfflineFixture(t)
	if stopped, ok := f.ix.IngestStopped("없는스트림", offlineT0); ok {
		t.Errorf("커서 없는 스트림의 IngestStopped = (%v, true), want ok 거짓", stopped)
	}

	g := newOfflineFixture(t)
	g.makeFile("s1", segName(baseWall, 0), 1000)
	g.probe.vals = []int64{0} // 유일한 조각이 길이 0 이라 장부에 못 든다 — 커서는 서되 꼬리가 없다
	g.collectAt(offlineAt(-time.Minute))
	if stopped, ok := g.ix.IngestStopped("s1", offlineT0); ok {
		t.Errorf("꼬리 없는 커서의 IngestStopped = (%v, true), want ok 거짓", stopped)
	}
}

// 판정 상태는 그대로다 — 열린 기다림이 있는 스트림 · 기다림이 없는 스트림 모두 IngestStopped 앞뒤로 판정 칸
// (offline · 쉼 · 점검 결손)이 같고 대기 로그도 늘지 않는다. 그 뒤 판정 결과도 부르지 않은 것과 같다.
func TestIngestStoppedLeavesJudgmentStateUntouched(t *testing.T) {
	f := newOfflineFixture(t)
	f.boot("s1", "s2")
	f.judge(0, "s1") // s1 만 기다림을 연다 · s2 는 판정 상태가 없다
	beforeOffline := maps.Clone(f.ix.offline)
	beforePaused, beforeGaps := f.ix.offlinePausedUntil, maps.Clone(f.ix.checkGaps)
	beforeLogs := f.logs.count(slog.LevelInfo, "session_offline")

	for _, s := range []string{"s1", "s2"} {
		if stopped, ok := f.ix.IngestStopped(s, offlineAt(5*time.Second)); !ok || !stopped {
			t.Errorf("IngestStopped(%s) = (%v, %v), want (true, true) — 꼬리가 한 시간 전이다", s, stopped, ok)
		}
	}

	if !reflect.DeepEqual(f.ix.offline, beforeOffline) {
		t.Errorf("offline 판정 상태가 바뀌었다: %+v, want %+v", f.ix.offline, beforeOffline)
	}
	if !f.ix.offlinePausedUntil.Equal(beforePaused) || !reflect.DeepEqual(f.ix.checkGaps, beforeGaps) {
		t.Errorf("쉼 · 점검 결손이 바뀌었다: (%v, %v), want (%v, %v)", f.ix.offlinePausedUntil, f.ix.checkGaps, beforePaused, beforeGaps)
	}
	if n := f.logs.count(slog.LevelInfo, "session_offline"); n != beforeLogs {
		t.Errorf("session_offline = %d건, want %d — 읽기가 판정 로그를 남겼다", n, beforeLogs)
	}
}
