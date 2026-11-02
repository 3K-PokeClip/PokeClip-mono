package index

// 30초 감시 한 문장(POK-195 M4 PR ⓒ 커밋 5 — 설계 4.1 정합성 감시 (a) (c) · 계획 4.5 A2 결정 11 · 체크리스트 A-6 1 ·
// 판단 J41). CTE 넷(live · drift · ids · absent)과 종류 열로 한 결과를 돌려준다. 여기서는 문장이 어느 행을 어떤 값으로
// 가져오는지를 장부 실물로 잰다 — drift 는 재료만 싣고 settled 판정은 rewind/boundary.Settled 하나다(B #14).
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"fmt"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// watchFixture 는 감시가 읽는 장부를 심는다. 벽시계는 지금(now) 기준이다 — (c) 는 now() − 마지막 조각 시각을 본다.
//
//	rwA  컷오프 10 · 회차 A1(live · init 확정 · 계승 없음) · 행 10(③ 확정) 11(③ 대기) 12(③ 대기 · GAP 원장) — 1분 전
//	rwB  컷오프 없음 · 회차 B0(ended) · B1(live · init 미확정 · B0 계승) · 행 0 — 1분 전
//	rwC  컷오프 없음 · 회차 없음 — 스캔 INSERT 만 들어온 비귀속 행 0(f4 · 설계 9.1 ⒡-S1) — 30초 전
//	rwD  컷오프 없음 · 비귀속 행 0 — 11분 전(옛 백로그 소진 국면 — SEED_ALARM_AFTER 10분 밖)
func watchFixture(t *testing.T, pool *pgxpool.Pool, now time.Time) {
	t.Helper()
	ago := func(d time.Duration) time.Time { return now.Add(-d).Truncate(time.Millisecond) }
	for _, s := range []rewindSessionRow{
		{RewindSession{SessionID: "A1", State: "live", InitUploaded: true, FirstPDT: ago(time.Minute), TargetDuration: 6},
			"rwA", ago(time.Minute)},
		{RewindSession{SessionID: "B0", State: "ended", EndReason: "offline", InitUploaded: true,
			FirstPDT: ago(10 * time.Minute), TargetDuration: 6}, "rwB", ago(10 * time.Minute)},
		{RewindSession{SessionID: "B1", State: "live", FirstPDT: ago(time.Minute), InheritsSession: "B0", TargetDuration: 6},
			"rwB", ago(time.Minute)},
	} {
		putRewindSession(t, pool, s)
	}
	putRewindRow(t, pool, "rwA", 10, ago(time.Minute), 4000, "A1", true)
	putRewindRow(t, pool, "rwA", 11, ago(56*time.Second), 4000, "A1", false)
	putRewindRow(t, pool, "rwA", 12, ago(52*time.Second), 4000, "A1", false)
	putPublishedGap(t, pool, "rwA", 12, "A1")
	cutoffAt(t, pool, "rwA", 10)
	putRewindRow(t, pool, "rwB", 0, ago(time.Minute), 4000, "B1", false)
	putRewindRow(t, pool, "rwC", 0, ago(30*time.Second), 4000, "", false)
	putRewindRow(t, pool, "rwD", 0, ago(11*time.Minute), 4000, "", false)
}

// 종류 열 셋이 갈리고, live 는 state = 'live' 회차의 열 다섯이다(A2 결정 11 — 스트림 · 회차 · init 확정 · 계승
// 없음 · 컷오프 있음). 끝난 회차(B0)는 없다. drift 는 입력 스트림마다 한 행이고, absent 는 컷오프 없는 최근
// 스트림이다. 문장은 이름 const 하나(rewindWatchSQL)를 글자 그대로 보내고 바인드는 셋(스트림 배열 · H+1 배열 ·
// SEED_ALARM_AFTER)뿐이다 — 스트림 ID 를 문자열로 이어 붙이지 않는다(보안 판정).
func TestRewindWatchReadsThreeKindsInOneStatement(t *testing.T) {
	base := newTestPool(t)
	watchFixture(t, base, time.Now())
	var sent []string
	var args [][]any
	pool := hookedPool(t, base, traceArgs(func(sql string, a []any) {
		sent, args = append(sent, sql), append(args, a)
	}))

	got, err := LoadRewindWatch(context.Background(), pool, []WatchProbe{{StreamID: "rwA", NextSeq: 11}}, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch 실패: %v", err)
	}
	wantLive := []WatchLive{
		{StreamID: "rwA", SessionID: "A1", InitUploaded: true, InheritAbsent: true, HasCutoff: true},
		{StreamID: "rwB", SessionID: "B1"},
	}
	if !reflect.DeepEqual(got.Live, wantLive) {
		t.Errorf("live =\n%+v\nwant\n%+v", got.Live, wantLive)
	}
	if len(got.Drift) != 1 || got.Drift[0].StreamID != "rwA" || got.Drift[0].NextSeq != 11 {
		t.Errorf("drift = %+v, want rwA 의 seq 11 한 행", got.Drift)
	}
	if ids := absentIDs(got.Absent); !reflect.DeepEqual(ids, []string{"rwB", "rwC"}) {
		t.Errorf("absent = %v, want [rwB rwC]", ids)
	}
	if len(sent) != 1 || sent[0] != rewindWatchSQL || len(args[0]) != 3 {
		t.Errorf("보낸 문장 %d개(바인드 %v), want rewindWatchSQL 한 문장 · 바인드 셋", len(sent), args)
	}
}

// drift 재료(B #14 · J39) — 입력 스트림마다 DB 컷오프와 그 H+1 행의 settled 재료(회차 · PDT · ③ 키 · 길이 · ③ 상태 ·
// GAP 원장 소속)를 싣는다. 행이 없으면 행 없음, 컷오프가 없으면 컷오프 없음이다. 판정(settled 인가)은 싣지 않는다.
func TestRewindWatchCarriesDriftMaterial(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	watchFixture(t, pool, now)
	probes := []WatchProbe{{"rwA", 10}, {"rwA", 11}, {"rwA", 12}, {"rwA", 13}, {"rwB", 0}}

	got, err := LoadRewindWatch(context.Background(), pool, probes, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch 실패: %v", err)
	}
	ago := func(d time.Duration) time.Time { return now.Add(-d).Truncate(time.Millisecond).UTC() }
	row := func(seq int64, wall time.Time, session string) RewindRow {
		return RewindRow{Seq: seq, SessionID: session, DurationMS: 4000, PlaybackPDT: wall,
			PlaybackS3Key: fmt.Sprintf("dvr/%s/seg/%06d.m4s", "rwA", seq)}
	}
	uploaded := row(10, ago(time.Minute), "A1")
	uploaded.PlaybackUploaded = true
	gap := row(12, ago(52*time.Second), "A1")
	gap.IsGap = true
	want := []WatchDrift{
		{StreamID: "rwA", NextSeq: 10, CutoffSeq: 10, HasCutoff: true, Next: uploaded, HasNext: true},
		{StreamID: "rwA", NextSeq: 11, CutoffSeq: 10, HasCutoff: true, Next: row(11, ago(56*time.Second), "A1"), HasNext: true},
		{StreamID: "rwA", NextSeq: 12, CutoffSeq: 10, HasCutoff: true, Next: gap, HasNext: true},
		{StreamID: "rwA", NextSeq: 13, CutoffSeq: 10, HasCutoff: true},
		{StreamID: "rwB", NextSeq: 0, Next: RewindRow{Seq: 0, SessionID: "B1", DurationMS: 4000,
			PlaybackPDT: ago(time.Minute), PlaybackS3Key: "dvr/rwB/seg/000000.m4s"}, HasNext: true},
	}
	if !reflect.DeepEqual(got.Drift, want) {
		t.Errorf("drift =\n%+v\nwant\n%+v", got.Drift, want)
	}
}

// absent 조건(설계 4.1 (c) · J41) — 컷오프가 없고 마지막 조각(max(start_wall_utc))이 SEED_ALARM_AFTER 안인 스트림이다.
// 컷오프가 있으면 없고(rwA — 최근이어도), 마지막 조각이 창보다 오래되면 없다(rwD — 옛 백로그 소진 국면은 발화하지
// 않는 쪽으로 갈린다). 마지막 조각 시각을 함께 싣는다.
func TestRewindWatchCutoffAbsentConditions(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	watchFixture(t, pool, now)

	got, err := LoadRewindWatch(context.Background(), pool, nil, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch 실패: %v", err)
	}
	ago := func(d time.Duration) time.Time { return now.Add(-d).Truncate(time.Millisecond).UTC() }
	want := []WatchAbsent{{StreamID: "rwB", LastWall: ago(time.Minute)}, {StreamID: "rwC", LastWall: ago(30 * time.Second)}}
	if !reflect.DeepEqual(got.Absent, want) {
		t.Errorf("absent =\n%+v\nwant\n%+v", got.Absent, want)
	}
	if len(got.Drift) != 0 {
		t.Errorf("입력 없는 drift = %+v, want 없음", got.Drift)
	}
	wider, err := LoadRewindWatch(context.Background(), pool, nil, 12*time.Minute)
	if err != nil || !reflect.DeepEqual(absentIDs(wider.Absent), []string{"rwB", "rwC", "rwD"}) {
		t.Errorf("창 12분의 absent = %v(%v), want [rwB rwC rwD] — 창이 바인드 값을 따른다", absentIDs(wider.Absent), err)
	}
}

// f4_all_lost 의 (c) 쪽(설계 9.1 ⒡-S1 f4 · 판단 J48) — 워처 · 훅 · mtxstate 를 모두 잃고 스캔만 산 국면에서는 live
// 회차가 없고 스캔 INSERT 의 비귀속 행만 있다. absent 는 live 와 독립인 CTE 라 그래도 참이다 — live 에 묶으면 이
// 국면에서 알람이 뜨지 않는다. leaf 이름을 단 시험은 커밋 8 이다.
func TestRewindWatchCutoffAbsentWithoutLiveSession(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	putRewindRow(t, pool, "rwF4", 0, now.Add(-8*time.Second), 4000, "", false)
	putRewindRow(t, pool, "rwF4", 1, now.Add(-4*time.Second), 4000, "", false)

	got, err := LoadRewindWatch(context.Background(), pool, nil, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch 실패: %v", err)
	}
	if len(got.Live) != 0 || !reflect.DeepEqual(absentIDs(got.Absent), []string{"rwF4"}) {
		t.Errorf("(live, absent) = (%+v, %v), want (없음, [rwF4])", got.Live, absentIDs(got.Absent))
	}
}

// absent 의 스트림 목록(ids)은 조각 표의 가장 작은 스트림 ID 로 시작한다 — 조각이 하나도 없으면 그 첫 행이
// NULL 이다. 재귀는 그 행에서 멈추고, absent 에 오르는 행도 오류도 없다.
func TestRewindWatchCutoffAbsentOnEmptyLedger(t *testing.T) {
	pool := newTestPool(t)

	got, err := LoadRewindWatch(context.Background(), pool, nil, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch(빈 장부) 실패: %v", err)
	}
	if !reflect.DeepEqual(got, RewindWatch{}) {
		t.Errorf("LoadRewindWatch(빈 장부) = %+v, want 빈 결과", got)
	}
}

// absent 의 스트림 목록(ids)은 색인에서 「지금보다 큰 첫 스트림 ID」만 골라 건너뛰며 뽑는다. 스트림마다 행이 여럿이고
// ID 가 사전순으로 떨어져 있으며(rwP 와 rwP2 는 앞머리가 같다) 컷오프 있는 스트림이 사이사이에 끼어도 빠뜨리는
// 스트림이 없다. 마지막 조각 시각은 스트림마다 가장 늦은 행이다 — 첫 행은 모두 창(10분) 밖이다.
//
//	rwA   컷오프 없음 · 행 20분 · 6분 · 1분 전  → absent(1분 전)
//	rwH   컷오프 0    · 행 2분 · 1분 전         → 없음(컷오프)
//	rwP   컷오프 없음 · 행 30분 · 2분 전        → absent(2분 전)
//	rwP2  컷오프 없음 · 행 40분 · 11분 전       → 없음(마지막 조각이 창 밖)
//	rwT   컷오프 0    · 행 1분 · 30초 전        → 없음(컷오프)
//	rwZ   컷오프 없음 · 행 15분 · 5초 전        → absent(5초 전)
func TestRewindWatchCutoffAbsentVisitsEveryStream(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	ago := func(d time.Duration) time.Time { return now.Add(-d).Truncate(time.Millisecond) }
	for _, r := range []struct {
		stream string
		seq    int64
		ago    time.Duration
	}{
		{"rwA", 0, 20 * time.Minute}, {"rwA", 1, 6 * time.Minute}, {"rwA", 2, time.Minute},
		{"rwH", 0, 2 * time.Minute}, {"rwH", 1, time.Minute},
		{"rwP", 0, 30 * time.Minute}, {"rwP", 1, 2 * time.Minute},
		{"rwP2", 0, 40 * time.Minute}, {"rwP2", 1, 11 * time.Minute},
		{"rwT", 0, time.Minute}, {"rwT", 1, 30 * time.Second},
		{"rwZ", 0, 15 * time.Minute}, {"rwZ", 1, 5 * time.Second},
	} {
		putRewindRow(t, pool, r.stream, r.seq, ago(r.ago), 4000, "", false)
	}
	cutoffAt(t, pool, "rwH", 0)
	cutoffAt(t, pool, "rwT", 0)

	got, err := LoadRewindWatch(context.Background(), pool, nil, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch 실패: %v", err)
	}
	want := []WatchAbsent{
		{StreamID: "rwA", LastWall: ago(time.Minute).UTC()},
		{StreamID: "rwP", LastWall: ago(2 * time.Minute).UTC()},
		{StreamID: "rwZ", LastWall: ago(5 * time.Second).UTC()},
	}
	if !reflect.DeepEqual(got.Absent, want) {
		t.Errorf("absent =\n%+v\nwant\n%+v", got.Absent, want)
	}
}

// absent 의 스트림 목록(ids)은 재귀라 마지막 스트림 뒤의 NULL 한 행에서 멈춰야 문장이 돌아온다. 멈추지 않는
// 재귀(건너뛰기가 제자리를 돌거나 NULL 뒤로도 이어지는 형)는 다른 감시 시험에서도 TxnDeadline(10초) 뒤 오류로
// 죽지만, 여기서는 그보다 짧은 시한으로 곧바로 가른다. 사전순 마지막 스트림(rwZ)까지 absent 에 오른다 — 목록이
// 끝까지 간다.
func TestRewindWatchCutoffAbsentStopsAfterLastStream(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	putRewindRow(t, pool, "rwM", 0, now.Add(-time.Minute), 4000, "", false)
	putRewindRow(t, pool, "rwZ", 0, now.Add(-time.Minute), 4000, "", false)
	const bound = 2 * time.Second // 정상이면 수 ms 다
	ctx, cancel := context.WithTimeout(context.Background(), bound)
	defer cancel()

	got, err := LoadRewindWatch(ctx, pool, nil, 10*time.Minute)

	if err != nil {
		t.Fatalf("LoadRewindWatch(시한 %v) 오류 = %v — 재귀가 NULL 에서 멈추지 않으면 시한에 걸린다", bound, err)
	}
	if ids := absentIDs(got.Absent); !reflect.DeepEqual(ids, []string{"rwM", "rwZ"}) {
		t.Errorf("absent = %v, want [rwM rwZ] — 마지막 스트림까지 간다", ids)
	}
}

// 감시를 못 읽으면 오류다 — 그 틱의 (a) · (c) · 화해를 건너뛰는 것은 부르는 쪽 몫이다(J46).
func TestRewindWatchReportsQueryFailure(t *testing.T) {
	pool := newTestPool(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := LoadRewindWatch(ctx, pool, nil, 10*time.Minute); err == nil {
		t.Fatal("취소된 ctx 로 읽었는데 오류가 없다")
	}
}

// absentIDs 는 absent 행의 스트림이다.
func absentIDs(rows []WatchAbsent) []string {
	var out []string
	for _, r := range rows {
		out = append(out, r.StreamID)
	}
	return out
}

// traceArgs 는 문장마다 SQL 과 바인드 값을 받는 추적기다.
type traceArgs func(sql string, args []any)

func (f traceArgs) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	f(d.SQL, d.Args)
	return ctx
}

func (traceArgs) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}
