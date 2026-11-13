package index

// 60일 만료(POK-551)가 PDT 판 2 행(timing_version = 2)이 섞인 장부에서도 판 NULL(판 1) 장부와 같게 동작하는가의 PG 통합
// 시험. 만료는 범위를 start_wall_utc 로, 문서 겹침을 seq 와 vod_first_seq · vod_last_seq 로, 미룸을 round_id 로 정하고 판 2
// 칸(timing_*)은 보지 않는다. 생산자는 판과 무관하게 start_wall_utc 를 파일명 시각으로 쓰고(indexer.go buildRecord), 판 2 는
// playback_pdt · 위치 · 길이만 섞기로 바꾼다(timing_v2_store.go settleV2). 그래서 판만 다른 두 장부가 같은 결과를 내야 한다.
//
// 판 2 행의 timing_wall_guess · playback_pdt 는 start_wall_utc 보다 v2Lag 만큼 이르게 심는다 — 만료가 범위를 그 칸으로
// 재면 구간 첫 조각이 빠지고 뒤 방송 첫 조각이 들어 결과가 갈린다. 판 2 래치가 선 뒤에는 옛 쓰기자의 행(timing_wall_guess NULL)을 넣을 수 없으므로(쓰기자
// 거부 트리거) 섞인 장부는 판 NULL 행 → 래치 → 판 2 행 순서로 심는다. PG_DSN 이 없으면 skip(REQUIRE_PG=1 이면 실패).

import (
	"context"
	"fmt"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// v2Lag 는 판 2 행의 PDT 칸 둘(timing_wall_guess · playback_pdt)이 start_wall_utc 보다 이른 정도다 — mtxi NTP 와 파일명
// 시각의 차를 흉내 낸다.
const v2Lag = 700 * time.Millisecond

// v2PutSeg 는 세션 session 의 조각을 판 2 행으로 seq 마다 하나씩 넣는다(벽시각 expAt(seq)). 열은 expInsertSeg 와 같고 판 2
// 칸(판 · w · 이어 계산 회차)을 더한다 — 회차는 그 세션의 round_id 다.
func v2PutSeg(t *testing.T, pool *pgxpool.Pool, stream, session string, seqs ...int64) {
	t.Helper()
	for _, seq := range seqs {
		v2PutSegAt(t, pool, stream, session, seq, expAt(seq))
	}
}

// v2PutSegAt 은 v2PutSeg 의 한 행을 벽시각 start 로 넣는다.
func v2PutSegAt(t *testing.T, pool *pgxpool.Pool, stream, session string, seq int64, start time.Time) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state, uploaded_at, bytes,
     session_id, playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at,
     timing_version, timing_wall_guess, timing_round_id)
VALUES ($1, $2, $3, $4, 4000, $5, 'uploaded', $4, 1000, $6, $7, $8, 'uploaded', $4,
        2, $7, (SELECT round_id FROM stream_sessions WHERE session_id = $6))`,
		stream, seq, seq*4000, start, S3Key(stream, seq, start), session, start.Add(-v2Lag),
		fmt.Sprintf("dvr/%s/seg/%06d.m4s", stream, seq))
	if err != nil {
		t.Fatalf("판 2 조각 픽스처 삽입 실패 %s/%d: %v", stream, seq, err)
	}
}

// v2Latch 는 새 회차 판 스위치를 2 로 올려 래치를 세운다 — 이 뒤로는 timing_wall_guess 가 NULL 인 행(옛 쓰기자)이
// 23514 legacy_writer_refused 로 막힌다.
func v2Latch(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	mustTimingExec(t, pool, `UPDATE stream_timing_gate SET new_round_version = 2`)
}

// v2Rows 는 스트림의 판 2 행 수다 — 픽스처가 정말 판 2 로 심겼는지 확인한다.
func v2Rows(t *testing.T, pool *pgxpool.Pool, stream string) int {
	t.Helper()
	return expCount(t, pool, `SELECT count(*) FROM stream_segments WHERE stream_id = $1 AND timing_version = 2`, stream)
}

// expDocNames 는 문서 목록을 「종류:세션」 으로 편다(지우는 순서 그대로).
func expDocNames(docs []ExpiryDocument) []string {
	out := make([]string, 0, len(docs))
	for _, d := range docs {
		out = append(out, fmt.Sprintf("%d:%s", d.Kind, d.SessionID))
	}
	return out
}

// T3 을 판 2 로 — 회차 P · B · N 이 이어 붙은 스트림에서 B 구간을 지우면, 판과 무관하게 B 의 dvr · vod · init 문서를 고르고
// B 조각만 지우며 컷오프는 N 의 첫 자격 행(③ 세 열이 찬 판 2 행)으로 옮긴다. P · N 의 행 · 국면은 그대로다.
func TestExpiryV2RoundExpiresLikeV1(t *testing.T) {
	for _, tc := range []struct {
		name   string
		v2     bool
		wantV2 int
	}{
		{"v1", false, 0},
		{"v2", true, 9},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			ctx := context.Background()
			stream := "v2exp-round"
			ovPut(t, pool, stream,
				ovSession{id: "P", round: "P", vod: "frozen", key: docKey(stream, "P"), first: 0, last: 2},
				ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1},
				ovSession{id: "N", round: "N", vod: "frozen", key: docKey(stream, "N"), first: 6, last: 8, at: 2})
			mustTimingExec(t, pool, `UPDATE stream_sessions SET dvr_s3_key = 'dvr/B/index.m3u8', init_s3_key = 'dvr/B/init.mp4'
 WHERE session_id = 'B'`)
			put := ovSeg
			if tc.v2 {
				v2Latch(t, pool)
				put = v2PutSeg
			}
			put(t, pool, stream, "P", 0, 1, 2)
			put(t, pool, stream, "B", 3, 4, 5)
			put(t, pool, stream, "N", 6, 7, 8)
			lcPutCutoff(t, pool, stream, 3)
			if n := v2Rows(t, pool, stream); n != tc.wantV2 {
				t.Fatalf("판 2 행 %d, want %d", n, tc.wantV2)
			}
			before := map[string]PlaybackPhase{"P": ovPhase(t, pool, stream, "P"), "N": ovPhase(t, pool, stream, "N")}
			q := ovSpan(stream, 3, 5)

			docs, err := ExpiryDocuments(ctx, pool, q)
			if err != nil {
				t.Fatalf("ExpiryDocuments: %v", err)
			}
			out := ovSweep(t, pool, q)

			if out.deferred {
				t.Fatal("닫힌 · 확정 회차인데 미뤘다")
			}
			if got, want := expDocNames(docs), []string{"1:B", "2:B", "3:B"}; !slices.Equal(got, want) {
				t.Errorf("문서 %v, want %v (B 의 dvr · vod · init)", got, want)
			}
			ovWantSeqs(t, pool, stream, 0, 1, 2, 6, 7, 8)
			if got := ovMarked(t, pool, stream); len(got) != 0 {
				t.Errorf("표시 세션 %v (없음 기대 — B 는 세션 행까지 지워진다)", got)
			}
			if seq, _, _, ok := cutoffRow(t, pool, stream); !ok || seq != 6 {
				t.Errorf("컷오프 seq=%d ok=%v, want 6 (N 의 첫 자격 행)", seq, ok)
			}
			for r, ph := range before {
				if got := ovPhase(t, pool, stream, r); got != ph {
					t.Errorf("회차 %s 국면 %v → %v", r, ph, got)
				}
			}
		})
	}
}

// 래치 전 판 NULL 회차 A · B 와 래치 뒤 판 2 회차 C · D 가 한 스트림에 이어 있다. 판 NULL 회차 B 를 지우든 판 2 회차 C 를
// 지우든 그 회차 조각 · 문서만 고르고, 범위 밖 회차(판이 같은 이웃 · 다른 이웃 모두)의 행 · 표시 · 국면은 그대로다.
func TestExpiryMixedVersionStreamLeavesOutOfRangeRoundsUntouched(t *testing.T) {
	for _, tc := range []struct {
		name     string
		target   string
		a, b     int64
		wantSeqs []int64
	}{
		{"v1 round B", "B", 3, 5, []int64{0, 1, 2, 6, 7, 8, 9, 10}},
		{"v2 round C", "C", 6, 8, []int64{0, 1, 2, 3, 4, 5, 9, 10}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			stream := "v2exp-mixed"
			ovPut(t, pool, stream,
				ovSession{id: "A", round: "A", vod: "frozen", key: docKey(stream, "A"), first: 0, last: 2},
				ovSession{id: "B", round: "B", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1},
				ovSession{id: "C", round: "C", vod: "frozen", key: docKey(stream, "C"), first: 6, last: 8, at: 2},
				ovSession{id: "D", round: "D", vod: "frozen", key: docKey(stream, "D"), first: 9, last: 10, at: 3})
			ovSeg(t, pool, stream, "A", 0, 1, 2)
			ovSeg(t, pool, stream, "B", 3, 4, 5)
			v2Latch(t, pool)
			v2PutSeg(t, pool, stream, "C", 6, 7, 8)
			v2PutSeg(t, pool, stream, "D", 9, 10)
			if n := v2Rows(t, pool, stream); n != 5 {
				t.Fatalf("판 2 행 %d, want 5", n)
			}
			before := map[string]PlaybackPhase{}
			for _, r := range []string{"A", "B", "C", "D"} {
				if r != tc.target {
					before[r] = ovPhase(t, pool, stream, r)
				}
			}

			out := ovSweep(t, pool, ovSpan(stream, tc.a, tc.b))

			want := []string{tc.target + ":" + docKey(stream, tc.target)}
			if out.deferred || !slices.Equal(out.vodDocs, want) {
				t.Fatalf("결과 %+v, want 미루지 않고 VOD 문서 %v", out, want)
			}
			ovWantSeqs(t, pool, stream, tc.wantSeqs...)
			if got := ovMarked(t, pool, stream); len(got) != 0 {
				t.Errorf("표시 세션 %v (없음 기대 — 범위 밖 회차는 표시하지 않는다)", got)
			}
			for r, ph := range before {
				if got := ovPhase(t, pool, stream, r); got != ph {
					t.Errorf("회차 %s 국면 %v → %v", r, ph, got)
				}
			}
		})
	}
}

// 구간 경계 — 판 2 행도 판 NULL 행처럼 start_wall_utc 로 [From, Until) 에 갈린다. 조각 여섯을 From · Until 의 1ms 앞 ·
// 같음 · 1ms 뒤에 두면 판과 무관하게 From · From + 1ms · Until − 1ms 셋만 대상이고 그 셋만 지워진다. 판 2 행의 PDT 칸 둘은
// v2Lag 만큼 이르므로, 범위를 그 칸으로 재면 From · From + 1ms 가 빠지고 Until · Until + 1ms 가 든다.
func TestExpiryV2RowsSplitAtRangeEdgesLikeV1(t *testing.T) {
	from, until := expAt(10), expAt(20)
	edges := []time.Time{
		from.Add(-time.Millisecond), from, from.Add(time.Millisecond),
		until.Add(-time.Millisecond), until, until.Add(time.Millisecond),
	}
	for _, tc := range []struct {
		name   string
		v2     bool
		wantV2 int
	}{
		{"v1", false, 0},
		{"v2", true, 6},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			ctx := context.Background()
			stream := "v2exp-edge"
			ovPut(t, pool, stream, ovSession{id: "S", round: "S"})
			if tc.v2 {
				v2Latch(t, pool)
			}
			for i, start := range edges {
				if tc.v2 {
					v2PutSegAt(t, pool, stream, "S", int64(i), start)
					continue
				}
				expPutSeg(t, pool, expSegRow{stream: stream, seq: int64(i), session: "S", start: start})
			}
			if n := v2Rows(t, pool, stream); n != tc.wantV2 {
				t.Fatalf("판 2 행 %d, want %d", n, tc.wantV2)
			}
			q := expRange(stream, &from, until)

			rows, err := ExpiryRows(ctx, pool, q)
			if err != nil {
				t.Fatalf("ExpiryRows: %v", err)
			}
			var got []int64
			for _, r := range rows {
				if r.Skip != "" {
					t.Errorf("seq %d 건너뜀 사유 %q (ended 세션 행은 대상)", r.Seq, r.Skip)
				}
				got = append(got, r.Seq)
			}
			if want := []int64{1, 2, 3}; !slices.Equal(got, want) {
				t.Errorf("대상 행 %v, want %v (From · From + 1ms · Until − 1ms)", got, want)
			}
			if out := ovSweep(t, pool, q); out.deferred {
				t.Fatal("닫힌 회차인데 미뤘다")
			}
			ovWantSeqs(t, pool, stream, 0, 4, 5)
		})
	}
}

// runbookHeredoc 은 런북에서 "${payload}/<file>" 로 쓰는 here-document 의 본문을 낸다. 세션 설정 줄(SET · RESET —
// 읽기 전용 · 상한 · search_path · 역할)은 운영 실행기 몫이라 빼고, 남은 문장을 「;」 줄 끝으로 나눠 돌려준다. 시험은 각
// 문장을 읽기 전용 트랜잭션 안에서 돌린다.
func runbookHeredoc(text, file string) ([]string, error) {
	start := `"${payload}/` + file + `" <<'SQL'`
	lines := strings.Split(text, "\n")
	from := slices.IndexFunc(lines, func(line string) bool { return strings.HasSuffix(line, start) })
	if from < 0 {
		return nil, fmt.Errorf("here-document %s 시작이 없음", file)
	}
	to := slices.Index(lines[from+1:], "SQL")
	if to < 0 {
		return nil, fmt.Errorf("here-document %s 가 닫히지 않음", file)
	}
	var body []string
	for _, line := range lines[from+1 : from+1+to] {
		if strings.HasPrefix(line, "SET ") || strings.HasPrefix(line, "RESET ") {
			continue
		}
		body = append(body, line)
	}
	var out []string
	for _, stmt := range strings.Split(strings.Join(body, "\n"), ";\n") {
		if stmt = strings.TrimSuffix(strings.TrimSpace(stmt), ";"); stmt != "" {
			out = append(out, stmt)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("here-document %s 에 문장이 없음", file)
	}
	return out, nil
}

// v2Diagnostics 는 켜기 전 진단 셋(문서 구멍 · 런북 만료 켜기 전 진단 · 런북 미룸 질의)의 결과를 글자로 편다.
func v2Diagnostics(t *testing.T, pool *pgxpool.Pool, stream string) map[string]string {
	t.Helper()
	ctx := context.Background()
	text, err := os.ReadFile(runtimeRunbookPath)
	if err != nil {
		t.Fatalf("런북 읽기 실패: %v", err)
	}
	out := map[string]string{"VODDocumentHolesSQL": fmt.Sprint(queryVODHoles(t, pool))}
	for _, file := range []string{"expiry_preflight.sql", "expiry_deferred.sql"} {
		stmts, err := runbookHeredoc(string(text), file)
		if err != nil {
			t.Fatalf("런북 질의 %s: %v", file, err)
		}
		var got []string
		for _, stmt := range stmts {
			if stmt, err = fillRunbookQuery(stmt, map[string]string{"stream_id": stream}); err != nil {
				t.Fatalf("런북 질의 %s: %v", file, err)
			}
			rows, err := runReadOnly(ctx, pool, stmt)
			if err != nil {
				t.Fatalf("런북 질의 %s 실행 실패: %v", file, err)
			}
			got = append(got, fmt.Sprint(rows))
		}
		out[file] = strings.Join(got, " | ")
	}
	return out
}

// 켜기 전 진단은 판 2 행이 있어도 같은 판정을 낸다. 닫힌 회차 R(A · B — B 문서 범위의 seq 4 를 지운 구멍)과 확정 전 회차
// U(ending · freezing — 미정 단위)를 판 NULL 로만 심은 장부와, A 만 판 NULL 로 심고 래치 뒤 B · U 를 판 2 로 심은 장부가
// 문서 구멍 · 런북 「만료 켜기 전 진단」 · 「만료 미룸 질의」 에서 같은 결과를 낸다.
func TestExpiryPreflightDiagnosticsSameWithV2Rows(t *testing.T) {
	const stream = "v2exp-diag"
	got := map[string]map[string]string{}
	for _, tc := range []struct {
		name   string
		v2     bool
		wantV2 int
	}{
		{"v1", false, 0},
		{"mixed", true, 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			pool := newTestPool(t)
			ovPut(t, pool, stream,
				// A 는 래치 전 판 NULL 회차 Q 다 — 판은 회차를 이어 물려받아 한 회차 안에 판이 섞이지 않는다.
				ovSession{id: "A", round: "Q", vod: "frozen", key: docKey(stream, "A"), first: 0, last: 2},
				ovSession{id: "B", round: "R", vod: "frozen", key: docKey(stream, "B"), first: 3, last: 5, at: 1},
				ovSession{id: "U", round: "U", state: "ending", vod: "freezing", at: 2})
			ovSeg(t, pool, stream, "A", 0, 1, 2)
			put := ovSeg
			if tc.v2 {
				v2Latch(t, pool)
				put = v2PutSeg
			}
			put(t, pool, stream, "B", 3, 4, 5)
			put(t, pool, stream, "U", 6, 7)
			mustTimingExec(t, pool, `DELETE FROM stream_segments WHERE stream_id = 'v2exp-diag' AND seq = 4`)
			if n := v2Rows(t, pool, stream); n != tc.wantV2 {
				t.Fatalf("판 2 행 %d, want %d", n, tc.wantV2)
			}

			got[tc.name] = v2Diagnostics(t, pool, stream)

			wantHoles := fmt.Sprint([]vodHole{{stream, "B", docKey(stream, "B"), 3, 5, 1}})
			if h := got[tc.name]["VODDocumentHolesSQL"]; h != wantHoles {
				t.Errorf("문서 구멍 %s, want %s", h, wantHoles)
			}
			if d := got[tc.name]["expiry_deferred.sql"]; strings.Count(d, " U ") != 1 {
				t.Errorf("미룸 질의 %s (미정 회차 U 한 행 기대)", d)
			}
		})
	}
	for _, name := range []string{"VODDocumentHolesSQL", "expiry_preflight.sql", "expiry_deferred.sql"} {
		if v1, mixed := got["v1"][name], got["mixed"][name]; v1 != mixed {
			t.Errorf("%s 판 NULL 장부 %s, 판 2 섞인 장부 %s — 같아야 한다", name, v1, mixed)
		}
	}
}
