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
	"slices"
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
var runbookQueryNames = []string{"Q1", "Q2", "Q2-B", "Q3", "Q4"}

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
// 블록이 그 here-document 안에 끝까지 있어야 한다 — 시작 줄과 BEGIN 사이, BEGIN 과 END 사이에 닫는 줄 `SQL` 이 없고
// END 뒤에 닫는 줄이 있다. 그렇지 않으면 첫 줄이 블록과 다른 here-document 의 것이다.
func readOnlyHeredocFirst(text, name string) (string, error) {
	const closing = "SQL"
	lines := strings.Split(text, "\n")
	begin, end := -1, -1
	for i, line := range lines {
		switch line {
		case "-- BEGIN " + name:
			begin = i
		case "-- END " + name:
			end = i
		}
	}
	if begin < 0 || end <= begin {
		return "", fmt.Errorf("표지 %s 한 쌍 없음", name)
	}
	start := -1
	for j := begin - 1; j >= 0 && start < 0; j-- {
		switch {
		case lines[j] == closing:
			return "", fmt.Errorf("%s 앞에서 here-document 가 닫힘(%d행)", name, j+1)
		case strings.HasSuffix(lines[j], "<<'SQL'"):
			start = j
		}
	}
	if start < 0 {
		return "", fmt.Errorf("%s 앞에 here-document 시작이 없음", name)
	}
	if slices.Contains(lines[begin:end], closing) {
		return "", fmt.Errorf("%s 의 END 전에 here-document 가 닫힘", name)
	}
	for _, line := range lines[end+1:] {
		if line == closing {
			return lines[start+1], nil
		}
		if strings.HasSuffix(line, "<<'SQL'") {
			break
		}
	}
	return "", fmt.Errorf("%s 를 담은 here-document 가 닫히지 않음", name)
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
	_, out, err := runReadOnlyColumns(ctx, pool, query)
	return out, err
}

// runReadOnlyColumns 는 runReadOnly 와 같되 열 이름도 낸다(Q4 는 판정 열을 이름으로 본다).
func runReadOnlyColumns(ctx context.Context, pool *pgxpool.Pool, query string) ([]string, [][]any, error) {
	tx, err := pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // 읽기 전용이라 되돌림 실패는 판정과 무관하다.
	rows, err := tx.Query(ctx, query)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	var names []string
	for _, field := range rows.FieldDescriptions() {
		names = append(names, field.Name)
	}
	var out [][]any
	for rows.Next() {
		values, err := rows.Values()
		if err != nil {
			return nil, nil, err
		}
		out = append(out, values)
	}
	return names, out, rows.Err()
}

// axisCount 는 Q2 · Q2-B 의 한 행(waiting · bytes_null)이다. bytes_null 이 NULL(init 행)이면 hasBytesNull 이 거짓이다.
type axisCount struct {
	waiting      int64
	bytesNull    int64
	hasBytesNull bool
}

// axisCounts 는 Q2 · Q2-B 의 행(축 · waiting · bytes_null)을 축 이름 → axisCount 로 편다.
func axisCounts(rows [][]any) (map[string]axisCount, error) {
	out := make(map[string]axisCount, len(rows))
	for _, row := range rows {
		if len(row) != 3 {
			return nil, fmt.Errorf("열 수 %d(기대 3)", len(row))
		}
		axis, ok := row[0].(string)
		waiting, ok2 := row[1].(int64)
		if !ok || !ok2 {
			return nil, fmt.Errorf("행 꼴 %#v", row)
		}
		count := axisCount{waiting: waiting}
		if row[2] != nil {
			bytesNull, ok := row[2].(int64)
			if !ok {
				return nil, fmt.Errorf("bytes_null 꼴 %#v", row)
			}
			count.bytesNull, count.hasBytesNull = bytesNull, true
		}
		out[axis] = count
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

// readOnlyHeredocFirst 는 표지 블록이 here-document 안에 끝까지 들어 있을 때만 그 첫 줄을 낸다 — 블록 앞에서
// here-document 가 닫히거나, END 표지 전에 닫히거나, 닫히지 않으면 오류다(첫 줄만 보면 다른 here-document 의 첫 줄을
// 읽고 통과한다).
func TestRunbookReadOnlyHeredocFirstChecksBoundaries(t *testing.T) {
	const start, first = "cat >\"${payload}/q9.sql\" <<'SQL'", "SET TRANSACTION READ ONLY;"
	join := func(lines ...string) string { return strings.Join(lines, "\n") }
	cases := []struct {
		name, text string
		ok         bool
	}{
		{"안에 끝까지", join(start, first, "-- BEGIN Q9", "SELECT 1;", "-- END Q9", "RESET ROLE;", "SQL"), true},
		{"블록 앞에서 닫힘", join(start, first, "SQL", "-- BEGIN Q9", "SELECT 1;", "-- END Q9"), false},
		{"END 전에 닫힘", join(start, first, "-- BEGIN Q9", "SELECT 1;", "SQL", "-- END Q9"), false},
		{"닫히지 않음", join(start, first, "-- BEGIN Q9", "SELECT 1;", "-- END Q9"), false},
		{"END 없음", join(start, first, "-- BEGIN Q9", "SELECT 1;", "SQL"), false},
		{"닫기 전에 다음 here-document", join(start, first, "-- BEGIN Q9", "SELECT 1;", "-- END Q9", start, first, "SQL"), false},
		{"here-document 밖", join(first, "-- BEGIN Q9", "SELECT 1;", "-- END Q9"), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := readOnlyHeredocFirst(c.text, "Q9")
			if c.ok && (err != nil || got != first) {
				t.Errorf("readOnlyHeredocFirst = %q, %v — want %q, nil", got, err, first)
			}
			if !c.ok && err == nil {
				t.Errorf("readOnlyHeredocFirst = %q, nil — want 오류", got)
			}
		})
	}
}

// waitingSegment 는 ② · ③ 이 모두 대기(pending)이고 ③ 키가 있는 조각 seq 0 이다 — 업로더 술어로는 두 축 모두 집힌다.
func waitingSegment(stream, session string, start time.Time) runbookSegment {
	return runbookSegment{stream: stream, session: session, seq: 0, start: start, archive: "pending",
		playback: "pending", key: fmt.Sprintf("dvr/%s/seg/000000.m4s", stream)}
}

// Q2(A 배출 — E25) · Q2-B(B 배출 — E36)의 축마다 waiting 이 업로더 스위퍼가 집는 수와 같다. 두 질의는 상한 자리
// 표시(<cut> · <cut2>)만 다르고 업로더 술어는 같으므로 한 고정물로 함께 잰다. 고정물은 업로더 술어를 하나씩 어긴 행을
// 축마다 둔다 — 런북 질의가 술어 하나를 빠뜨리면(failed 를 빼 재시도 대기 행을 못 세는 꼴 포함) 그 행을 더 세거나 덜
// 세어 갈린다. bytes 하나만 어긴 행은 런북이 waiting 이 아니라 bytes_null 로 따로 세므로(「0 이 아니면 kty 보고」)
// 그 열도 축마다 본다. 시각은 모두 상한 앞 · 24시간 안 · 꼬리 유예 밖이다(꼬리 예외 · 창 경계는 이 대조의 축이 아니다 —
// TestRunbookQ2ExcludesTestStreamAfterCutAndStale · TestRunbookQ2BCountsBDiskRowsBeforeCut2 가 잰다).
func TestRunbookQ2AndQ2BCountWhatUploaderPicks(t *testing.T) {
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
		// ③ bytes 없음 — 업로더는 안 집고 런북은 waiting 이 아니라 bytes_null 로 센다(② 의 seq 4 와 짝).
		{stream: "pbstream", session: "S-live", seq: 8, start: at, archive: "uploaded", playback: "pending",
			key: "dvr/pbstream/seg/000008.m4s", noBytes: true},
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

	// 업로더가 집는 수는 고정물에서 정해진다(축마다 둘) — 0 끼리 같아 헛통과하지 않게 함께 본다.
	st := NewUploadStore(pool)
	axes := []struct {
		name string
		axis Axis
	}{{"archive", AxisArchive}, {"playback", AxisPlayback}, {"init", AxisInit}}
	picked := make(map[string]int64, len(axes))
	for _, axis := range axes {
		targets, _, err := st.PendingUploads(ctx, axis.axis, 120, 1000, SweepCursor{})
		if err != nil {
			t.Fatalf("%s PendingUploads: %v", axis.name, err)
		}
		if len(targets) != 2 {
			t.Errorf("%s 업로더가 집는 수 = %d, want 2 — 고정물이 의도와 다르다", axis.name, len(targets))
		}
		picked[axis.name] = int64(len(targets))
	}

	for _, q := range []struct{ name, bound string }{{"Q2", "cut"}, {"Q2-B", "cut2"}} {
		t.Run(q.name, func(t *testing.T) {
			query, err := loadRunbookQuery(q.name, map[string]string{q.bound: rfc3339(now.Add(time.Minute)), "test": runbookTestStream})
			if err != nil {
				t.Fatalf("%s 뽑기: %v", q.name, err)
			}

			rows, err := runReadOnly(ctx, pool, query)
			if err != nil {
				t.Fatalf("%s 실행: %v", q.name, err)
			}
			got, err := axisCounts(rows)
			if err != nil {
				t.Fatalf("%s 행: %v", q.name, err)
			}

			// bytes_null = 업로더 술어를 bytes 하나만 어긴 행 — ② arstream seq 4 · ③ pbstream seq 8 각 하나, init 은 NULL.
			wantBytesNull := map[string]axisCount{"archive": {bytesNull: 1, hasBytesNull: true},
				"playback": {bytesNull: 1, hasBytesNull: true}, "init": {}}
			for _, axis := range axes {
				g, want := got[axis.name], wantBytesNull[axis.name]
				if g.waiting != picked[axis.name] {
					t.Errorf("%s %s waiting = %d, 업로더가 집는 수 = %d — 런북 질의 술어가 업로더와 갈렸다",
						q.name, axis.name, g.waiting, picked[axis.name])
				}
				if g.bytesNull != want.bytesNull || g.hasBytesNull != want.hasBytesNull {
					t.Errorf("%s %s bytes_null = %d(NULL 아님 %t), want %d(NULL 아님 %t)",
						q.name, axis.name, g.bytesNull, g.hasBytesNull, want.bytesNull, want.hasBytesNull)
				}
			}
			if len(got) != len(axes) {
				t.Errorf("%s 축 = %v, want archive · playback · init 세 행", q.name, got)
			}
		})
	}
}

// Q2(A 배출)는 시험 스트림(<test>) · <cut> 뒤 · 24시간 밖(②(녹화 파일 그대로) · ③(되감기 조각) 축)을 세지 않는다. 축마다
// 세는 행 하나를 함께 둬 0 끼리 같아 헛통과하지 않게 하고, 빼야 할 행은 업로더 술어로는 모두 집히는 꼴로 둔다 — 그
// 제외 술어 하나를 빼면 그 축이 1 늘어 갈린다. 창 안쪽 중간대(18시간 전) 행은 세어 창을 좁히면 줄어 갈리고,
// <cut> 과 정확히 같은 시각의 행은 세지 않아 경계를 `<=` 로 느슨하게 하면 늘어 갈린다. init 축은 세션 시작 시각의
// <cut> 상한과 시험 스트림만 본다 — 24시간 창이 없다(업로더 init 스위퍼에도 없다). 그래서 25시간 전 시작해 아직 안
// 끝난 방송(S-long — 최근 조각은 ② · ③ 업로드를 마쳐 init 재료만 준다)도 세어, init 에 시작 시각 창을 더하면 줄어
// 갈린다. 25시간 전 S-old 는 init 업로드를 마친 꼴로 둬 ② · ③ 창 판정만 맡는다.
func TestRunbookQ2ExcludesTestStreamAfterCutAndStale(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	// 초로 자른다 — 경계 행의 시각과 <cut> 글자가 PG 에서 같은 값이어야 한다(마이크로초 아래를 드라이버는 버리고
	// PG 는 글자에서 반올림한다).
	now := time.Now().UTC().Truncate(time.Second)
	cut := now.Add(-30 * time.Minute)
	beforeCut := cut.Add(-10 * time.Minute)
	afterCut := cut.Add(10 * time.Second)
	mid := now.Add(-18 * time.Hour)
	stale := now.Add(-25 * time.Hour)
	sessions := []runbookSession{
		{id: "S-b", stream: "bstream", state: "live", started: beforeCut},
		{id: "S-mid", stream: "midstream", state: "live", started: mid},
		{id: "S-edge", stream: "edgestream", state: "live", started: cut},
		{id: "S-test", stream: runbookTestStream, state: "live", started: beforeCut},
		{id: "S-late", stream: "latestream", state: "live", started: afterCut},
		{id: "S-old", stream: "oldstream", state: "live", started: stale.Add(-time.Hour), initUploaded: true},
		{id: "S-long", stream: "longstream", state: "live", started: stale},
	}
	cutoffs := map[string]int64{"bstream": 0, "midstream": 0, "edgestream": 0, runbookTestStream: 0, "latestream": 0,
		"oldstream": 0, "longstream": 0}
	segments := []runbookSegment{
		waitingSegment("bstream", "S-b", beforeCut),            // 셈 — ② · ③ 각 하나 · init 재료
		waitingSegment("midstream", "S-mid", mid),              // 셈 — 24시간 창 안쪽 중간대
		waitingSegment("edgestream", "S-edge", cut),            // <cut> 과 같은 시각 — 상한은 미만이라 세지 않음
		waitingSegment(runbookTestStream, "S-test", beforeCut), // 시험 스트림
		waitingSegment("latestream", "S-late", afterCut),       // <cut> 뒤
		waitingSegment("oldstream", "S-old", stale),            // 24시간 밖
		// 25시간 전 시작한 방송의 최근 조각 — ② · ③ 업로드를 마쳐 init 재료만 준다.
		{stream: "longstream", session: "S-long", seq: 0, start: beforeCut, archive: "uploaded", playback: "uploaded",
			key: "dvr/longstream/seg/000000.m4s"},
	}
	if err := seedRunbook(ctx, pool, sessions, cutoffs, segments); err != nil {
		t.Fatalf("고정물: %v", err)
	}
	query, err := loadRunbookQuery("Q2", map[string]string{"cut": rfc3339(cut), "test": runbookTestStream})
	if err != nil {
		t.Fatalf("Q2 뽑기: %v", err)
	}

	rows, err := runReadOnly(ctx, pool, query)
	if err != nil {
		t.Fatalf("Q2 실행: %v", err)
	}
	got, err := axisCounts(rows)
	if err != nil {
		t.Fatalf("Q2 행: %v", err)
	}

	// init 3 = S-b · S-mid · S-long — init 축은 init_uploaded_at IS NULL · 안 끝남 · started_at < <cut> 만 본다.
	// bytes_null 은 ② · ③ 이 0(고정물 조각은 모두 크기가 있다) · init 은 NULL 이다.
	want := map[string]axisCount{"archive": {waiting: 2, hasBytesNull: true}, "playback": {waiting: 2, hasBytesNull: true},
		"init": {waiting: 3}}
	for axis, n := range want {
		if got[axis] != n {
			t.Errorf("Q2 %s = %+v, want %+v(전체 %+v)", axis, got[axis], n, got)
		}
	}
	if len(got) != len(want) {
		t.Errorf("Q2 축 = %v, want %v", got, want)
	}
}

// Q2-B(켜기 전 A 복귀 때 B 회수 판정 — E36 · P2)는 아래쪽 시각 경계가 없다 — <cut>(ingest 를 B 로 옮긴 시각) 앞에
// B 가 만든 조각도 센다. 위쪽 <cut2> 뒤(A 몫) · 24시간 밖(녹화 보존 밖) · 시험 스트림은 세지 않는다. init 축은
// 세션의 최신 조각에서 다시 만들므로 started_at 의 24시간 창을 두지 않는다 — 오래 이어진 방송의 init 도 센다.
// 창 안쪽 중간대(18시간 전) 행은 세어 창을 좁히면 줄어 갈리고, <cut2> 와 정확히 같은 시각의 행은 세지 않아 경계를
// `<=` 로 느슨하게 하면 늘어 갈린다. 업로더 술어(failed · ended · init 완료 · 경로 · PDT)의 대조는
// TestRunbookQ2AndQ2BCountWhatUploaderPicks 가 맡는다.
func TestRunbookQ2BCountsBDiskRowsBeforeCut2(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	// 초로 자른다 — 경계 행의 시각과 <cut2> 글자가 PG 에서 같은 값이어야 한다.
	now := time.Now().UTC().Truncate(time.Second)
	cut := now.Add(-time.Hour)
	cut2 := now.Add(-30 * time.Minute)
	beforeCut := cut.Add(-10 * time.Second)
	afterCut2 := cut2.Add(10 * time.Second)
	mid := now.Add(-18 * time.Hour)
	stale := now.Add(-25 * time.Hour)
	sessions := []runbookSession{
		{id: "S-b", stream: "bstream", state: "ending", started: beforeCut},
		{id: "S-mid", stream: "midstream", state: "ending", started: mid},
		{id: "S-edge", stream: "edgestream", state: "live", started: cut2},
		{id: "S-long", stream: "longstream", state: "ending", started: stale},
		{id: "S-a", stream: "astream", state: "live", started: afterCut2},
		{id: "S-test", stream: runbookTestStream, state: "live", started: beforeCut},
		{id: "S-old", stream: "oldstream", state: "live", started: stale.Add(-time.Hour), initUploaded: true},
	}
	// 세지 않을 ③ 행(oldstream · 시험 스트림)도 세션 · 컷오프를 둔다 — 조인에서 먼저 떨어지면 창 · 시험 스트림
	// 술어를 빼도 playback 이 그대로라 그 술어를 재지 못한다.
	cutoffs := map[string]int64{"bstream": 0, "midstream": 0, "edgestream": 0, "astream": 0, "oldstream": 0,
		runbookTestStream: 0}
	segments := []runbookSegment{
		// <cut> 직전 B 조각 — ② 대기 · ③ 대기 각 하나(P2).
		{stream: "bstream", session: "S-b", seq: 0, start: beforeCut, archive: "pending", playback: "pending",
			key: "dvr/bstream/seg/000000.m4s"},
		// 24시간 창 안쪽 중간대 B 조각 — 셈.
		waitingSegment("midstream", "S-mid", mid),
		// <cut2> 와 같은 시각 — 상한은 미만이라 세지 않음(세션 시작도 같은 시각이라 init 도 세지 않음).
		waitingSegment("edgestream", "S-edge", cut2),
		// 오래 이어진 방송의 최신 조각(창 안 · 업로드 끝) — init 재료만 준다.
		{stream: "longstream", session: "S-long", seq: 0, start: now.Add(-2 * time.Hour), archive: "uploaded",
			playback: "uploaded", key: "dvr/longstream/seg/000000.m4s"},
		// <cut2> 뒤 A 몫 · 24시간 밖 · 시험 스트림 — 세지 않음.
		{stream: "astream", session: "S-a", seq: 0, start: afterCut2, archive: "pending", playback: "pending",
			key: "dvr/astream/seg/000000.m4s"},
		waitingSegment("oldstream", "S-old", stale),
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
	got, err := axisCounts(rows)
	if err != nil {
		t.Fatalf("Q2-B 행: %v", err)
	}

	// init 3 = S-b · S-mid · S-long — S-old 는 init 업로드를 마쳤고 S-test 는 시험 스트림, S-edge · S-a 는 <cut2> 와 같거나 뒤에 시작했다.
	// bytes_null 은 ② · ③ 이 0(고정물 조각은 모두 크기가 있다) · init 은 NULL 이다.
	want := map[string]axisCount{"archive": {waiting: 2, hasBytesNull: true}, "playback": {waiting: 2, hasBytesNull: true},
		"init": {waiting: 3}}
	for axis, n := range want {
		if got[axis] != n {
			t.Errorf("Q2-B %s = %+v, want %+v(전체 %+v)", axis, got[axis], n, got)
		}
	}
	if len(got) != len(want) {
		t.Errorf("Q2-B 축 = %v, want %v", got, want)
	}
}

// Q1 은 <since> 이후 새 꼴 키 수, Q3 은 <on> 이후 새 꼴 키의 회차 성분이 세션 round_id 와 같은 수 · 다른 수다. 두 시각
// 경계와 같은 시각의 행을 둬 `>=` 를 `>` 로 좁히면 줄어 갈린다. Q3 은 세션을 조인하므로 세션 없는 새 꼴 행을 세지 않고
// (LEFT JOIN 으로 바꾸면 mismatched 가 는다), 세션 round_id 가 NULL 인 새 꼴 행은 불일치로 센다(IS DISTINCT FROM 을
// `<>` 로 바꾸면 NULL 비교가 빠져 줄어 갈린다).
func TestRunbookQ1Q3CountRoundKeys(t *testing.T) {
	ctx := context.Background()
	pool := newTestPool(t)
	// 초로 자른다 — 경계 행의 시각과 <since> · <on> 글자가 PG 에서 같은 값이어야 한다.
	now := time.Now().UTC().Truncate(time.Second)
	since := now.Add(-time.Hour)
	on := now.Add(-30 * time.Minute)
	afterOn := on.Add(time.Minute)
	between := since.Add(time.Minute)
	sessions := []runbookSession{
		{id: "S-r", stream: "rstream", state: "live", round: "S-r", started: since.Add(-time.Hour)},
		{id: "S-n", stream: "nstream", state: "live", started: since.Add(-time.Hour)}, // round_id NULL
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
		seg(6, since, "dvr/rstream/S-r/seg/000006.m4s"),                   // <since> 와 같은 시각 — Q1 셈
		seg(7, on, "dvr/rstream/S-r/seg/000007.m4s"),                      // <on> 과 같은 시각 · 회차 일치
		// 세션 없는 새 꼴 행 — Q1 은 세고 Q3 은 세션 조인에서 빠진다.
		{stream: "rstream", seq: 8, start: afterOn, archive: "uploaded", playback: "uploaded",
			key: "dvr/rstream/S-r/seg/000008.m4s"},
		// 세션 round_id 가 NULL 인 새 꼴 행 — Q3 불일치.
		{stream: "nstream", session: "S-n", seq: 0, start: afterOn, archive: "uploaded", playback: "uploaded",
			key: "dvr/nstream/S-n/seg/000000.m4s"},
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

	// Q1 7 = seq 2 · 3 · 4 · 6 · 7 · 8 · nstream 0 — start_wall_utc >= <since> 인 새 꼴(세션과 무관).
	if len(rows1) != 1 || len(rows1[0]) != 1 || rows1[0][0] != int64(7) {
		t.Errorf("Q1 = %v, want [[7]](<since> 이후 새 꼴 일곱)", rows1)
	}
	// Q3 matched 2 = seq 3 · 7, mismatched 2 = seq 4 · nstream 0 — seq 8 은 세션이 없어 조인에서 빠진다.
	if len(rows3) != 1 || len(rows3[0]) != 2 || rows3[0][0] != int64(2) || rows3[0][1] != int64(2) {
		t.Errorf("Q3 = %v, want [[2 2]](matched 2 · mismatched 2)", rows3)
	}
}

// q4Case 는 Q4(시험 방송 회차 판정 — 교체 걸음 4.10) 사례 하나다. 통과 고정물(seedQ4Base)에 change 를 얹는다.
type q4Case struct {
	name     string
	change   string // 고정물 뒤에 돌리는 SQL(빈 값이면 없음)
	pass     bool
	vodState string // 빈 값이 아니면 vod_state 열(회차의 최신 세션 값)도 본다
}

// seedQ4Base 는 Q4 의 통과 고정물을 넣는다 — <on> 뒤 시험 스트림의 회차 하나(끝남 · 닫힘 · vod_state frozen · 새 꼴 키
// 셋 모두 ③ 업로드). <on> 앞의 옛 회차(③ 실패 · GAP 기록)는 판정 대상이 아니다.
func seedQ4Base(ctx context.Context, pool *pgxpool.Pool, on time.Time) error {
	at := on.Add(5 * time.Minute)
	stream := runbookTestStream
	sessions := []runbookSession{
		{id: "S-old", stream: stream, state: "ended", round: "S-old", started: on.Add(-time.Hour), initUploaded: true},
		{id: "S-t", stream: stream, state: "ended", round: "S-t", started: at, initUploaded: true},
	}
	var segments []runbookSegment
	for seq := int64(0); seq < 2; seq++ {
		segments = append(segments, runbookSegment{stream: stream, session: "S-old", seq: seq, start: on.Add(-time.Hour),
			archive: "uploaded", playback: "failed", key: fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq)})
	}
	for seq := int64(2); seq < 5; seq++ {
		segments = append(segments, runbookSegment{stream: stream, session: "S-t", seq: seq, start: at,
			archive: "uploaded", playback: "uploaded", key: fmt.Sprintf("dvr/%s/S-t/seg/%06d.m4s", stream, seq)})
	}
	if err := seedRunbook(ctx, pool, sessions, map[string]int64{stream: 0}, segments); err != nil {
		return err
	}
	_, err := pool.Exec(ctx, `
UPDATE stream_sessions
   SET ended_at = started_at + interval '10 minutes', round_closed_at = started_at + interval '10 minutes',
       vod_state = 'frozen', vod_s3_key = 'vod/' || stream_id || '/' || session_id || '/index.m3u8'
 WHERE stream_id = $1`, stream)
	if err != nil {
		return err
	}
	_, err = pool.Exec(ctx, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
VALUES ($1, 0, 'S-old', 'settle_deadline')`, stream)
	return err
}

// sqlTextOrNull 은 빈 값을 SQL NULL 로, 나머지를 따옴표 친 글자로 낸다(시험 고정물 값만 — 따옴표를 거르지 않는다).
func sqlTextOrNull(value string) string {
	if value == "" {
		return "NULL"
	}
	return "'" + value + "'"
}

// q4Session 은 시험 회차 머리 세션 S-t 를 본떠 세션 id 를 더하는 SQL 이다 — 스트림 stream · 상태 state · 회차 round(빈 값이면
// NULL) 를 주고 시작 시각을 shift 만큼 옮긴다. 끝 · 닫힘 · VOD 열은 S-t 의 값을 그대로 옮긴다(사례가 따로 바꾼다).
func q4Session(id, stream, state, round, shift string) string {
	return fmt.Sprintf(`INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, first_pdt, round_id, init_uploaded_at, ended_at, round_closed_at,
     vod_state, vod_s3_key)
SELECT '%s', '%s', started_at + interval '%s', '%s', first_pdt, %s, init_uploaded_at, ended_at, round_closed_at,
       vod_state, vod_s3_key
  FROM stream_sessions WHERE session_id = 'S-t'`, id, stream, shift, state, sqlTextOrNull(round))
}

// q4Segment 는 시험 회차 조각 seq 4 를 본떠 stream · seq 조각을 세션 session · ③ 키 key(빈 값이면 NULL) · ③ 상태
// playback 으로 더하는 SQL 이다(playback_s3_key 는 바꿀 수 없어 키 사례는 행을 더한다).
func q4Segment(stream string, seq int64, session, key, playback string) string {
	return fmt.Sprintf(`INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes,
     is_discontinuity, session_id, playback_pdt, playback_s3_key, playback_upload_state)
SELECT '%[1]s', %[2]d, %[2]d * 4000, start_wall_utc, 4000, 'k/%[1]s/%[2]d', '/recordings/%[1]s/%[2]d.x.mp4',
       'uploaded', 1000, false, '%[3]s', playback_pdt, %[4]s, '%[5]s'
  FROM stream_segments WHERE stream_id = '%[6]s' AND seq = 4`, stream, seq, session, sqlTextOrNull(key), playback,
		runbookTestStream)
}

// Q4 는 <on> 뒤 시험 스트림의 가장 늦은 회차가 끝나 닫히고 VOD 로 frozen 동결됐으며, 그 회차의 조각(컷오프 이상)이 모두 ③
// 업로드됐고 키가 그 회차의 새 꼴이며 GAP 기록이 없을 때만 pass 다. 국면 vod 는 frozen_with_gaps 도 포함하고
// (round_playback.go) 미업로드 조각은 정산 기한 뒤 GAP 으로 마감되므로(vod.go settle_deadline) 국면만으로는 판정하지 않는다.
// 사례는 pass 의 항 · 회차 고르기 · 스트림 한정 · 컷오프 · 시각 경계를 하나씩 뒤집는다 — 질의가 그 술어 하나를 빠뜨리거나
// 느슨하게 하면 그 사례만 갈린다.
func TestRunbookQ4PassesOnlyCleanTestRound(t *testing.T) {
	test := runbookTestStream
	newKey := func(round string, seq int) string { return fmt.Sprintf("dvr/%s/%s/seg/%06d.m4s", test, round, seq) }
	cases := []q4Case{
		{name: "깨끗한 회차", pass: true},
		{name: "컷오프 아래 미업로드는 판정 밖", pass: true,
			change: `UPDATE stream_cutoffs SET cutoff_seq = 3; UPDATE stream_segments SET playback_upload_state = 'pending' WHERE seq = 2`},
		{name: "③ 업로드 실패 하나", change: `UPDATE stream_segments SET playback_upload_state = 'failed' WHERE seq = 3`},
		{name: "③ 업로드 대기 하나", change: `UPDATE stream_segments SET playback_upload_state = 'pending' WHERE seq = 4`},
		{name: "GAP 기록 하나", change: `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
VALUES ('` + test + `', 3, 'S-t', 'settle_deadline')`},
		{name: "frozen_with_gaps", change: `UPDATE stream_sessions SET vod_state = 'frozen_with_gaps' WHERE session_id = 'S-t'`},
		// 상태는 frozen 그대로 두고 키만 비운다 — vod_doc 조건 하나만 빼면 이 사례가 갈린다.
		{name: "VOD 문서 키 없음", change: `UPDATE stream_sessions SET vod_s3_key = NULL WHERE session_id = 'S-t'`},
		{name: "회차 안 닫힘", change: `UPDATE stream_sessions SET round_closed_at = NULL WHERE session_id = 'S-t'`},
		{name: "옛 꼴 키", change: q4Segment(test, 5, "S-t", "dvr/"+test+"/seg/000005.m4s", "uploaded")},
		{name: "다른 회차 키", change: q4Segment(test, 5, "S-t", newKey("S-x", 5), "uploaded")},
		{name: "③ 키 없음", change: q4Segment(test, 5, "S-t", "", "uploaded")},
		{name: "on 뒤 회차 없음", change: `UPDATE stream_sessions SET started_at = started_at - interval '1 day' WHERE session_id = 'S-t'`},
		// v.segments > 0 — 컷오프 이상 조각이 없으면 미업로드 · 키 항이 0 이어도 pass 가 아니다.
		{name: "컷오프 이상 조각 없음", change: `UPDATE stream_cutoffs SET cutoff_seq = 5`},
		// t.seq >= 컷오프 — 컷오프와 같은 seq 도 판정 안이다.
		{name: "컷오프 경계 조각 실패", change: `UPDATE stream_cutoffs SET cutoff_seq = 3;
UPDATE stream_segments SET playback_upload_state = 'failed' WHERE seq = 3`},
		// LEFT JOIN · COALESCE 0 — 컷오프 행이 없는 스트림은 모든 조각을 본다.
		{name: "컷오프 행 없음", pass: true, change: `DELETE FROM stream_cutoffs`},
		// v.not_ended = 0 — 닫힘 · frozen 이어도 끝나지 않은 세션이 있으면 아니다.
		{name: "세션 안 끝남", change: `UPDATE stream_sessions SET state = 'ending' WHERE session_id = 'S-t'`},
		// started_at >= <on> — 켬 시각과 같은 시각에 시작한 회차도 본다.
		{name: "on 과 같은 시각 회차", pass: true,
			change: `UPDATE stream_sessions SET started_at = started_at - interval '5 minutes' WHERE session_id = 'S-t'`},
		// r 의 round_id IS NOT NULL — 회차 없는(옛 판) 세션이 더 늦게 시작해도 회차 세션을 고른다.
		{name: "더 늦은 회차 없는 세션", pass: true, change: q4Session("S-n", test, "ended", "", "1 minute")},
		// sess · seg · gaps 의 stream_id = <test> — 다른 스트림의 세션(같은 round_id) · 조각 · GAP(같은 session_id)은 판정 밖.
		{name: "다른 스트림 행 섞임", pass: true, change: q4Session("S-y", "otherstream", "live", "S-t", "0 minutes") + `;
UPDATE stream_sessions SET round_closed_at = NULL WHERE session_id = 'S-y';
` + q4Segment("otherstream", 0, "S-t", "", "failed") + `;
INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason) VALUES ('otherstream', 0, 'S-t', 'settle_deadline')`},
		// r 의 stream_id = <test> — 다른 스트림이 <on> 뒤 더 늦게 연 회차(안 끝남 · 안 닫힘 · ③ 실패)는 고르지 않는다.
		{name: "다른 스트림의 더 늦은 회차", pass: true, change: q4Session("S-z", "otherstream", "live", "S-z", "1 minute") + `;
UPDATE stream_sessions SET ended_at = NULL, round_closed_at = NULL, vod_state = 'none', vod_s3_key = NULL
 WHERE session_id = 'S-z';
` + q4Segment("otherstream", 0, "S-z", "", "failed")},
		// r 의 ORDER BY started_at DESC — on 뒤 회차가 둘이면 가장 늦은 회차를 본다(최신이 실패 · 앞이 깨끗).
		{name: "최신 회차 실패", change: q4Session("S-t2", test, "ended", "S-t2", "1 minute") + `;
UPDATE stream_sessions SET vod_state = 'frozen_with_gaps' WHERE session_id = 'S-t2';
` + q4Segment(test, 5, "S-t2", newKey("S-t2", 5), "uploaded")},
		{name: "앞 회차 실패 · 최신 회차 깨끗", pass: true, change: q4Session("S-t0", test, "ended", "S-t0", "-1 minute") + `;
UPDATE stream_sessions SET vod_state = 'frozen_with_gaps' WHERE session_id = 'S-t0';
` + q4Segment(test, 5, "S-t0", newKey("S-t0", 5), "uploaded")},
		// latest 의 started_at DESC — 한 회차 세션 둘(TD 분할)이면 VOD 국면은 늦게 시작한 세션의 값이다.
		{name: "TD 분할 · 최신 세션 frozen", pass: true, vodState: "frozen",
			change: q4Session("S-t2", test, "ended", "S-t", "1 minute") + `;
UPDATE stream_sessions SET vod_state = 'frozen_with_gaps' WHERE session_id = 'S-t';
` + q4Segment(test, 5, "S-t2", newKey("S-t", 5), "uploaded")},
		// latest 의 (state = 'live') DESC — 회차에 라이브 세션이 있으면 시작 시각보다 먼저 그 세션의 VOD 국면을 낸다.
		{name: "회차 안 라이브 세션 우선", vodState: "none",
			change: q4Session("S-t2", test, "live", "S-t", "-1 minute") + `;
UPDATE stream_sessions SET ended_at = NULL, round_closed_at = NULL, vod_state = 'none', vod_s3_key = NULL
 WHERE session_id = 'S-t2'`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ctx := context.Background()
			pool := newTestPool(t)
			// 초로 자른다 — 경계 사례의 started_at 과 <on> 글자가 PG 에서 같은 값이어야 한다.
			on := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
			if err := seedQ4Base(ctx, pool, on); err != nil {
				t.Fatalf("고정물: %v", err)
			}
			if c.change != "" {
				if _, err := pool.Exec(ctx, c.change); err != nil {
					t.Fatalf("사례 변경: %v", err)
				}
			}
			query, err := loadRunbookQuery("Q4", map[string]string{"on": rfc3339(on), "test": test})
			if err != nil {
				t.Fatalf("Q4 뽑기: %v", err)
			}

			names, rows, err := runReadOnlyColumns(ctx, pool, query)
			if err != nil {
				t.Fatalf("Q4 실행: %v", err)
			}

			if len(rows) != 1 {
				t.Fatalf("Q4 행 수 = %d, want 1", len(rows))
			}
			got := make(map[string]any, len(names))
			for i, name := range names {
				got[name] = rows[0][i]
			}
			if pass, ok := got["pass"].(bool); !ok || pass != c.pass {
				t.Errorf("Q4 pass = %v, want %v(행 %v)", got["pass"], c.pass, got)
			}
			if c.vodState != "" && got["vod_state"] != c.vodState {
				t.Errorf("Q4 vod_state = %v, want %s(행 %v)", got["vod_state"], c.vodState, got)
			}
		})
	}
}
