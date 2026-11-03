package index

// 런북 읽기 질의 시험(POK-525 계획 r2 4-7 · 설계 r9.4 6절 · E25 · E36) — 런타임 1단-가 런북의 「POK-525 읽기 질의」
// 표지 블록(`-- BEGIN <이름>` · `-- END <이름>`)을 그대로 뽑아 PG 에서 돌린다. 사본을 두지 않는다 — 런북 글자를
// 바꾸면 이 시험이 바뀐 글자를 잰다. 자리 표시(<cut> 등)는 시험 값으로 바꾼다. 블록 밖의 첫 줄 READ ONLY ·
// SET ROLE media_owner 는 운영 실행기 몫이라, 시험은 블록을 읽기 전용 트랜잭션 안에서 돌린다.

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// runtimeRunbookPath 는 이 패키지 디렉터리에서 본 런타임 1단-가 런북이다. media-ci 의 paths 에 같은 경로가 있다 —
// 그 줄이 없으면 런북만 고치는 PR 에서 이 시험이 돌지 않는다.
var runtimeRunbookPath = filepath.Join("..", "..", "..", "infra", "terraform", "runbooks", "runtime-1a.md")

// runbookQueryNames 는 런북이 표지로 감싼 POK-525 읽기 질의다.
var runbookQueryNames = []string{"Q1", "Q2", "Q2-B", "Q3"}

// runbookPlaceholder 는 채우지 않은 자리 표시다(`<>` 연산자는 글자가 없어 걸리지 않는다).
var runbookPlaceholder = regexp.MustCompile(`<[a-z][a-z0-9]*>`)

// runbookTestStream 은 시험 값 <test>(루프백 시험 스트림)다.
const runbookTestStream = "loopbacktest"

// runbookQuery 는 런북 글자에서 표지 한 쌍 사이의 질의를 낸다. 표지는 줄 전체로 대조하고 정확히 한 번씩
// 있어야 한다 — `-- BEGIN Q2` 가 `-- BEGIN Q2-B` 의 앞부분이라 줄 대조가 아니면 둘을 가르지 못한다.
func runbookQuery(text, name string) (string, error) {
	begin, end := "-- BEGIN "+name, "-- END "+name
	lines := strings.Split(text, "\n")
	from, to := -1, -1
	for i, line := range lines {
		switch line {
		case begin:
			if from >= 0 {
				return "", fmt.Errorf("표지 %q 가 둘 이상", begin)
			}
			from = i
		case end:
			if to >= 0 {
				return "", fmt.Errorf("표지 %q 가 둘 이상", end)
			}
			to = i
		}
	}
	if from < 0 || to <= from {
		return "", fmt.Errorf("표지 %q · %q 한 쌍이 없음(%d · %d)", begin, end, from, to)
	}
	return strings.Join(lines[from+1:to], "\n"), nil
}

// readOnlyHeredocFirst 는 표지 블록을 담은 here-document 의 첫 줄을 낸다 — 런북의 make_read 가 첫 줄
// `SET TRANSACTION READ ONLY;` 를 요구하므로, 블록을 옮기다 그 줄을 떨어뜨리면 운영에서 읽기 전용 보장이 빠진다.
func readOnlyHeredocFirst(text, name string) (string, error) {
	lines := strings.Split(text, "\n")
	for i, line := range lines {
		if line != "-- BEGIN "+name {
			continue
		}
		for j := i - 1; j >= 0; j-- {
			if strings.HasSuffix(lines[j], "<<'SQL'") {
				return lines[j+1], nil
			}
		}
		return "", fmt.Errorf("%s 앞에 here-document 시작이 없음", name)
	}
	return "", fmt.Errorf("표지 %s 없음", name)
}

// fillRunbookQuery 는 자리 표시를 값으로 바꾸고, 남은 자리 표시가 있으면 오류를 낸다.
func fillRunbookQuery(query string, values map[string]string) (string, error) {
	for key, value := range values {
		query = strings.ReplaceAll(query, "<"+key+">", value)
	}
	if left := runbookPlaceholder.FindString(query); left != "" {
		return "", fmt.Errorf("채우지 않은 자리 표시 %s", left)
	}
	return query, nil
}

// loadRunbookQuery 는 런북에서 질의를 뽑아 값을 채운다.
func loadRunbookQuery(name string, values map[string]string) (string, error) {
	raw, err := os.ReadFile(runtimeRunbookPath)
	if err != nil {
		return "", err
	}
	query, err := runbookQuery(string(raw), name)
	if err != nil {
		return "", err
	}
	return fillRunbookQuery(query, values)
}

// runReadOnly 는 질의를 읽기 전용 트랜잭션 안에서 돌려 행을 낸다(첫 열 = 축 이름 또는 값).
func runReadOnly(ctx context.Context, pool *pgxpool.Pool, query string) ([][]any, error) {
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // 읽기 전용이라 되돌림 실패는 판정과 무관하다.
	rows, err := tx.Query(ctx, query)
	if err != nil {
		return nil, err
	}
	var out [][]any
	for rows.Next() {
		values, err := rows.Values()
		if err != nil {
			return nil, err
		}
		out = append(out, values)
	}
	return out, rows.Err()
}

// axisWaiting 은 Q2 · Q2-B 의 행(축 · waiting · bytes_null)을 축 이름 → waiting 으로 편다.
func axisWaiting(rows [][]any) (map[string]int64, error) {
	out := make(map[string]int64, len(rows))
	for _, row := range rows {
		if len(row) != 3 {
			return nil, fmt.Errorf("열 수 %d(기대 3)", len(row))
		}
		axis, ok := row[0].(string)
		waiting, ok2 := row[1].(int64)
		if !ok || !ok2 {
			return nil, fmt.Errorf("행 꼴 %#v", row)
		}
		out[axis] = waiting
	}
	return out, nil
}

// runbookSession 은 시험 세션 한 행이다.
type runbookSession struct {
	id, stream, state, round string
	started                  time.Time
	initUploaded             bool
}

func insertRunbookSession(ctx context.Context, pool *pgxpool.Pool, s runbookSession) error {
	var round, initAt any
	if s.round != "" {
		round = s.round
	}
	if s.initUploaded {
		initAt = s.started
	}
	_, err := pool.Exec(ctx, `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, first_pdt, round_id, init_uploaded_at)
VALUES ($1, $2, $3, $4, $3, $5, $6)`, s.id, s.stream, s.started.UTC(), s.state, round, initAt)
	return err
}

// runbookSegment 는 시험 조각 한 행이다. 빈 session · key 는 NULL, noPath · noBytes · noPDT 는 그 열 NULL 이다.
type runbookSegment struct {
	stream, session   string
	seq               int64
	start             time.Time
	archive, playback string
	key               string
	noPath, noBytes   bool
	noPDT             bool
}

func insertRunbookSegment(ctx context.Context, pool *pgxpool.Pool, s runbookSegment) error {
	var session, key, path, size, pdt any
	if s.session != "" {
		session = s.session
	}
	if s.key != "" {
		key = s.key
	}
	if !s.noPath {
		path = fmt.Sprintf("/recordings/%s/%d.mp4", s.stream, s.seq)
	}
	if !s.noBytes {
		size = int64(1000)
	}
	if !s.noPDT {
		pdt = s.start.UTC()
	}
	_, err := pool.Exec(ctx, `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes,
     is_discontinuity, session_id, playback_pdt, playback_s3_key, playback_upload_state)
VALUES ($1, $2, $3, $4, 4000, $5, $6, $7, $8, false, $9, $10, $11, $12)`,
		s.stream, s.seq, s.seq*4000, s.start.UTC(), S3Key(s.stream, s.seq, s.start), path, s.archive, size,
		session, pdt, key, s.playback)
	return err
}

func insertRunbookCutoff(ctx context.Context, pool *pgxpool.Pool, stream string, seq int64) error {
	_, err := pool.Exec(ctx, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
VALUES ($1, $2, 'live_ingress', 'watcher')`, stream, seq)
	return err
}

// seedRunbook 은 세션 · 컷오프 · 조각을 차례로 넣는다.
func seedRunbook(ctx context.Context, pool *pgxpool.Pool, sessions []runbookSession, cutoffs map[string]int64, segments []runbookSegment) error {
	for _, s := range sessions {
		if err := insertRunbookSession(ctx, pool, s); err != nil {
			return fmt.Errorf("세션 %s: %w", s.id, err)
		}
	}
	for stream, seq := range cutoffs {
		if err := insertRunbookCutoff(ctx, pool, stream, seq); err != nil {
			return fmt.Errorf("컷오프 %s: %w", stream, err)
		}
	}
	for _, s := range segments {
		if err := insertRunbookSegment(ctx, pool, s); err != nil {
			return fmt.Errorf("조각 %s/%d: %w", s.stream, s.seq, err)
		}
	}
	return nil
}

func rfc3339(at time.Time) string { return at.UTC().Format(time.RFC3339Nano) }

func TestRunbookQueryBlocksSitInReadOnlyHeredocs(t *testing.T) {
	raw, err := os.ReadFile(runtimeRunbookPath)
	if err != nil {
		t.Fatalf("런북 읽기: %v", err)
	}
	for _, name := range runbookQueryNames {
		if _, err := runbookQuery(string(raw), name); err != nil {
			t.Errorf("%s 블록: %v", name, err)
			continue
		}
		first, err := readOnlyHeredocFirst(string(raw), name)
		if err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if first != "SET TRANSACTION READ ONLY;" {
			t.Errorf("%s 를 담은 here-document 첫 줄 = %q — make_read 가 요구하는 SET TRANSACTION READ ONLY; 가 아니다", name, first)
		}
	}
}

// Q2(A 배출 — E25)의 축마다 waiting 이 업로더 스위퍼가 집는 수와 같다. 고정물은 업로더 술어를 하나씩 어긴 행을
// 축마다 둔다 — 런북 질의가 술어 하나를 빠뜨리면 그 행을 더 세어 갈린다. 시각은 모두 <cut> 앞 · 24시간 안 · 꼬리
// 유예 밖이다(꼬리 예외 · 창 경계는 이 대조의 축이 아니다).
func TestRunbookQ2CountsWhatUploaderPicks(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	now := time.Now().UTC()
	at := now.Add(-10 * time.Minute)
	sessions := []runbookSession{
		{id: "S-live", stream: "pbstream", state: "live", started: at},
		{id: "S-ended", stream: "endstream", state: "ended", started: at},
		{id: "S-done", stream: "donestream", state: "live", started: at, initUploaded: true},
		{id: "S-nocut", stream: "nocutstream", state: "live", started: at},
	}
	cutoffs := map[string]int64{"pbstream": 2, "endstream": 0, "donestream": 0}
	pb := func(seq int64, playback string) runbookSegment {
		return runbookSegment{stream: "pbstream", session: "S-live", seq: seq, start: at, archive: "uploaded",
			playback: playback, key: fmt.Sprintf("dvr/pbstream/seg/%06d.m4s", seq)}
	}
	segments := []runbookSegment{
		// ② — 집힘 둘(pending · failed) · 안 집힘 셋(uploaded · local_path 없음 · bytes 없음).
		{stream: "arstream", seq: 0, start: at, archive: "pending", playback: "pending"},
		{stream: "arstream", seq: 1, start: at, archive: "failed", playback: "pending"},
		{stream: "arstream", seq: 2, start: at, archive: "uploaded", playback: "pending"},
		{stream: "arstream", seq: 3, start: at, archive: "pending", playback: "pending", noPath: true},
		{stream: "arstream", seq: 4, start: at, archive: "pending", playback: "pending", noBytes: true},
		// ③ — 컷오프 2 미만(0 · 1) · 키 없음 · PDT 없음 · local_path 없음은 안 집힘, pending · failed 는 집힘.
		pb(0, "pending"), pb(1, "pending"), pb(2, "pending"), pb(3, "failed"), pb(4, "uploaded"),
		{stream: "pbstream", session: "S-live", seq: 5, start: at, archive: "uploaded", playback: "pending"},
		{stream: "pbstream", session: "S-live", seq: 6, start: at, archive: "uploaded", playback: "pending",
			key: "dvr/pbstream/seg/000006.m4s", noPDT: true},
		{stream: "pbstream", session: "S-live", seq: 7, start: at, archive: "uploaded", playback: "pending",
			key: "dvr/pbstream/seg/000007.m4s", noPath: true},
		// ended 세션 · 컷오프 없는 스트림의 ③ 은 안 집힘. 세 세션 모두 init 재료(경로 · 크기 있는 조각)가 있다.
		{stream: "endstream", session: "S-ended", seq: 0, start: at, archive: "uploaded", playback: "pending",
			key: "dvr/endstream/seg/000000.m4s"},
		{stream: "donestream", session: "S-done", seq: 0, start: at, archive: "uploaded", playback: "uploaded",
			key: "dvr/donestream/seg/000000.m4s"},
		{stream: "nocutstream", session: "S-nocut", seq: 0, start: at, archive: "uploaded", playback: "pending",
			key: "dvr/nocutstream/seg/000000.m4s"},
	}
	if err := seedRunbook(ctx, pool, sessions, cutoffs, segments); err != nil {
		t.Fatalf("고정물: %v", err)
	}
	query, err := loadRunbookQuery("Q2", map[string]string{"cut": rfc3339(now.Add(time.Minute)), "test": runbookTestStream})
	if err != nil {
		t.Fatalf("Q2 뽑기: %v", err)
	}

	rows, err := runReadOnly(ctx, pool, query)
	if err != nil {
		t.Fatalf("Q2 실행: %v", err)
	}
	got, err := axisWaiting(rows)
	if err != nil {
		t.Fatalf("Q2 행: %v", err)
	}

	// 업로더가 집는 수는 고정물에서 정해진다(축마다 둘) — 0 끼리 같아 헛통과하지 않게 함께 본다.
	st := NewUploadStore(pool)
	for _, axis := range []struct {
		name string
		axis Axis
	}{{"archive", AxisArchive}, {"playback", AxisPlayback}, {"init", AxisInit}} {
		picked, _, err := st.PendingUploads(ctx, axis.axis, 120, 1000, SweepCursor{})
		if err != nil {
			t.Fatalf("%s PendingUploads: %v", axis.name, err)
		}
		if len(picked) != 2 {
			t.Errorf("%s 업로더가 집는 수 = %d, want 2 — 고정물이 의도와 다르다", axis.name, len(picked))
		}
		if got[axis.name] != int64(len(picked)) {
			t.Errorf("Q2 %s waiting = %d, 업로더가 집는 수 = %d — 런북 질의 술어가 업로더와 갈렸다", axis.name, got[axis.name], len(picked))
		}
	}
	if len(got) != 3 {
		t.Errorf("Q2 축 = %v, want archive · playback · init 세 행", got)
	}
}

// Q2-B(켜기 전 A 복귀 때 B 회수 판정 — E36 · P2)는 아래쪽 시각 경계가 없다 — <cut>(ingest 를 B 로 옮긴 시각) 앞에
// B 가 만든 조각도 센다. 위쪽 <cut2> 뒤(A 몫) · 24시간 밖(녹화 보존 밖) · 시험 스트림은 세지 않는다. init 축은
// 세션의 최신 조각에서 다시 만들므로 started_at 의 24시간 창을 두지 않는다 — 오래 이어진 방송의 init 도 센다.
func TestRunbookQ2BCountsBDiskRowsBeforeCut2(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	now := time.Now().UTC()
	cut := now.Add(-time.Hour)
	cut2 := now.Add(-30 * time.Minute)
	beforeCut := cut.Add(-10 * time.Second)
	afterCut2 := cut2.Add(10 * time.Second)
	stale := now.Add(-25 * time.Hour)
	sessions := []runbookSession{
		{id: "S-b", stream: "bstream", state: "ending", started: beforeCut},
		{id: "S-long", stream: "longstream", state: "ending", started: stale},
		{id: "S-a", stream: "astream", state: "live", started: afterCut2},
		{id: "S-test", stream: runbookTestStream, state: "live", started: beforeCut},
	}
	cutoffs := map[string]int64{"bstream": 0, "astream": 0}
	segments := []runbookSegment{
		// <cut> 직전 B 조각 — ② 대기 · ③ 대기 각 하나(P2).
		{stream: "bstream", session: "S-b", seq: 0, start: beforeCut, archive: "pending", playback: "pending",
			key: "dvr/bstream/seg/000000.m4s"},
		// 오래 이어진 방송의 최신 조각(창 안 · 업로드 끝) — init 재료만 준다.
		{stream: "longstream", session: "S-long", seq: 0, start: now.Add(-2 * time.Hour), archive: "uploaded",
			playback: "uploaded", key: "dvr/longstream/seg/000000.m4s"},
		// <cut2> 뒤 A 몫 · 24시간 밖 · 시험 스트림 — 세지 않음.
		{stream: "astream", session: "S-a", seq: 0, start: afterCut2, archive: "pending", playback: "pending",
			key: "dvr/astream/seg/000000.m4s"},
		{stream: "oldstream", seq: 0, start: stale, archive: "pending", playback: "pending"},
		{stream: runbookTestStream, session: "S-test", seq: 0, start: beforeCut, archive: "pending",
			playback: "pending", key: "dvr/" + runbookTestStream + "/seg/000000.m4s"},
	}
	if err := seedRunbook(ctx, pool, sessions, cutoffs, segments); err != nil {
		t.Fatalf("고정물: %v", err)
	}
	query, err := loadRunbookQuery("Q2-B", map[string]string{"cut2": rfc3339(cut2), "test": runbookTestStream})
	if err != nil {
		t.Fatalf("Q2-B 뽑기: %v", err)
	}

	rows, err := runReadOnly(ctx, pool, query)
	if err != nil {
		t.Fatalf("Q2-B 실행: %v", err)
	}
	got, err := axisWaiting(rows)
	if err != nil {
		t.Fatalf("Q2-B 행: %v", err)
	}

	want := map[string]int64{"archive": 1, "playback": 1, "init": 2}
	for axis, n := range want {
		if got[axis] != n {
			t.Errorf("Q2-B %s waiting = %d, want %d(전체 %v)", axis, got[axis], n, got)
		}
	}
	if len(got) != len(want) {
		t.Errorf("Q2-B 축 = %v, want %v", got, want)
	}
}

// Q1 은 <since> 뒤 새 꼴 키 수, Q3 은 <on> 뒤 새 꼴 키의 회차 성분이 세션 round_id 와 같은 수 · 다른 수다.
func TestRunbookQ1Q3CountRoundKeys(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	now := time.Now().UTC()
	since := now.Add(-time.Hour)
	on := now.Add(-30 * time.Minute)
	afterOn := on.Add(time.Minute)
	between := since.Add(time.Minute)
	sessions := []runbookSession{
		{id: "S-r", stream: "rstream", state: "live", round: "S-r", started: since.Add(-time.Hour)},
	}
	seg := func(seq int64, start time.Time, key string) runbookSegment {
		return runbookSegment{stream: "rstream", session: "S-r", seq: seq, start: start, archive: "uploaded",
			playback: "uploaded", key: key}
	}
	segments := []runbookSegment{
		seg(0, since.Add(-time.Minute), "dvr/rstream/S-r/seg/000000.m4s"), // <since> 앞 — Q1 밖
		seg(1, between, "dvr/rstream/seg/000001.m4s"),                     // 옛 꼴
		seg(2, between, "dvr/rstream/S-r/seg/000002.m4s"),                 // <since> 와 <on> 사이 새 꼴
		seg(3, afterOn, "dvr/rstream/S-r/seg/000003.m4s"),                 // 켬 뒤 · 회차 일치
		seg(4, afterOn, "dvr/rstream/S-other/seg/000004.m4s"),             // 켬 뒤 · 회차 불일치
		seg(5, afterOn, "dvr/rstream/seg/000005.m4s"),                     // 켬 뒤 옛 꼴 — Q3 밖
	}
	if err := seedRunbook(ctx, pool, sessions, nil, segments); err != nil {
		t.Fatalf("고정물: %v", err)
	}
	q1, err := loadRunbookQuery("Q1", map[string]string{"since": rfc3339(since)})
	if err != nil {
		t.Fatalf("Q1 뽑기: %v", err)
	}
	q3, err := loadRunbookQuery("Q3", map[string]string{"on": rfc3339(on)})
	if err != nil {
		t.Fatalf("Q3 뽑기: %v", err)
	}

	rows1, err := runReadOnly(ctx, pool, q1)
	if err != nil {
		t.Fatalf("Q1 실행: %v", err)
	}
	rows3, err := runReadOnly(ctx, pool, q3)
	if err != nil {
		t.Fatalf("Q3 실행: %v", err)
	}

	if len(rows1) != 1 || len(rows1[0]) != 1 || rows1[0][0] != int64(3) {
		t.Errorf("Q1 = %v, want [[3]](<since> 뒤 새 꼴 셋)", rows1)
	}
	if len(rows3) != 1 || len(rows3[0]) != 2 || rows3[0][0] != int64(1) || rows3[0][1] != int64(1) {
		t.Errorf("Q3 = %v, want [[1 1]](matched 1 · mismatched 1)", rows3)
	}
}
