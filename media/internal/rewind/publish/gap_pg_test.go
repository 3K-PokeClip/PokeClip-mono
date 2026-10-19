package publish

// GAP 원장(설계 6.3 · 계획 PR ⓒ in 줄 · 커밋 순서 4 · 체크리스트 A-3 · 판단 J17–J28) — GAP 틱의 트랜잭션이다(P4 의
// put_confirmed 전이는 gapconfirm_pg_test.go). fake Store + PG 통합이다(PG_DSN 이 없으면 skip).

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// insertGap 은 스트림 stream 의 GAP 원장 행 하나를 넣는다 — 회차 S · 사유 upload_stall · 발행 상태 state 다.
func insertGap(t *testing.T, pool *pgxpool.Pool, stream string, seq int64, state string) {
	t.Helper()
	exec(t, pool, `
		INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason, published_state)
		VALUES ($1, $2, 'S', 'upload_stall', $3)`, stream, seq, state)
}

// gapRow 는 GAP 원장 한 행의 발행 상태다. confirmedAt 이 nil 이면 put_confirmed_at 이 NULL 이다.
type gapRow struct {
	state       string
	confirmedAt *time.Time
}

// readGaps 는 스트림 stream 의 GAP 원장을 seq 로 읽는다.
func readGaps(t *testing.T, pool *pgxpool.Pool, stream string) map[int64]gapRow {
	t.Helper()
	rows, err := pool.Query(context.Background(),
		`SELECT seq, published_state, put_confirmed_at FROM stream_published_gaps WHERE stream_id = $1`, stream)
	if err != nil {
		t.Fatalf("GAP 원장 읽기 실패: %v", err)
	}
	defer rows.Close()
	out := map[int64]gapRow{}
	for rows.Next() {
		var seq int64
		var g gapRow
		if err := rows.Scan(&seq, &g.state, &g.confirmedAt); err != nil {
			t.Fatalf("GAP 원장 행 읽기 실패: %v", err)
		}
		out[seq] = g
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("GAP 원장 읽기 실패: %v", err)
	}
	return out
}

// wantGapStates 는 GAP 원장의 발행 상태가 want(seq → published_state)와 같은지 본다.
func wantGapStates(t *testing.T, got map[int64]gapRow, want map[int64]string) {
	t.Helper()
	if len(got) != len(want) {
		t.Errorf("GAP 원장 행 %d개, want %d개(%v)", len(got), len(want), want)
	}
	for seq, state := range want {
		if g, ok := got[seq]; !ok || g.state != state || (g.confirmedAt != nil) != (state == "put_confirmed") {
			t.Errorf("GAP 원장 seq %d = %+v(있음 %v), want %s(확정 시각은 put_confirmed 일 때만)", seq, g, ok, state)
		}
	}
}

// insertLedgerRow 는 장부(stream_segments)에 행 r 을 ③ 상태 state 로 넣는다 — GAP 트랜잭션이 DB 에서 다시 보는 값이다.
// 영값 열(회차 · PDT · ③ 키)은 NULL 로 넣는다(boundary.Row 규약).
func insertLedgerRow(t *testing.T, pool *pgxpool.Pool, r boundary.Row, state string) {
	t.Helper()
	var pdt any
	if !r.PlaybackPDT.IsZero() {
		pdt = r.PlaybackPDT
	}
	exec(t, pool, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
		                             session_id, playback_pdt, playback_s3_key, playback_upload_state)
		VALUES ($1, $2, 0, now(), $3, $4, NULLIF($5, ''), $6, NULLIF($7, ''), $8)`,
		fxStream, r.Seq, r.DurationMS, fmt.Sprintf("archive/%s/%06d.mp4", fxStream, r.Seq),
		r.SessionID, pdt, r.PlaybackS3Key, state)
}

// insertCutoff 는 픽스처 스트림의 컷오프를 seq 로 둔다.
func insertCutoff(t *testing.T, pool *pgxpool.Pool, seq int64) {
	t.Helper()
	exec(t, pool, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
		VALUES ($1, $2, 'live_ingress', 'watcher')`, fxStream, seq)
}

// newStalledLoop 은 계승 없는 회차 S(행 0..last)에서 k 행만 캐시가 ③ 미확정으로 보는 루프다 — 창 [0, k−1] 을 한 번
// 발행해 fence 와 P 가 있다. 장부 행 · 컷오프는 넣지 않는다.
func newStalledLoop(t *testing.T, last, k int64) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newSoloLoop(t, last)
	l.fx.rows[k].PlaybackUploaded = false
	l.mustPublish(t.Context(), 0, k-1)
	return l, store, logs
}

// newGapLoop 은 newStalledLoop 에 장부의 k 행(③ 상태 state · 캐시와 같은 값)과 컷오프 0 을 더한 GAP 틱 픽스처다.
func newGapLoop(t *testing.T, last, k int64, state string) (*loop, *fakeStore, *logRecorder) {
	t.Helper()
	l, store, logs := newStalledLoop(t, last, k)
	insertLedgerRow(t, l.pub.pool, l.fx.rows[k], state)
	insertCutoff(t, l.pub.pool, 0)
	return l, store, logs
}

// gapTick 은 창 [from, to] 에 GAP 후보 k 를 실은 GAP 틱이다(판단 J17) — 창은 루프가 겹침 스냅숏으로 정한 것이다.
func (l *loop) gapTick(ctx context.Context, from, to, k int64) Outcome {
	l.t.Helper()
	in := l.input(from, to)
	in.GapCandidate = &k
	return l.run(ctx, in)
}

// segURI 는 픽스처 조각 seq 의 목록 URI 다.
func segURI(seq int64) string { return fxBaseURL + "/" + fxRow("S", seq).PlaybackS3Key }

// gapURIs 는 본문에서 #EXT-X-GAP 이 붙은 조각 URI 들이다.
func gapURIs(body []byte) []string {
	var out []string
	gap := false
	for _, line := range strings.Split(string(body), "\n") {
		switch {
		case line == "#EXT-X-GAP":
			gap = true
		case line != "" && !strings.HasPrefix(line, "#"):
			if gap {
				out = append(out, line)
			}
			gap = false
		}
	}
	return out
}

// traceSQL 은 문장마다 부르는 추적기다(pgx.QueryTracer) — 돌려준 ctx 로 그 문장을 보낸다. conn 은 그 문장이 나가는
// 연결이다. pgx 는 BEGIN · COMMIT · ROLLBACK 도 문장("begin" · "commit" · "rollback")으로 보내 여기를 지난다.
type traceSQL func(ctx context.Context, conn *pgx.Conn, sql string) context.Context

func (f traceSQL) TraceQueryStart(ctx context.Context, conn *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	return f(ctx, conn, d.SQL)
}

func (traceSQL) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}

// traceSQLEnd 는 traceSQL 에 문장 끝을 더한 추적기다 — end 는 끝난 문장과 그 결과 오류를 받는다.
type traceSQLEnd struct {
	start traceSQL
	end   func(sql string, err error)
}

// traceSQLKey 는 문장 시작이 ctx 에 적어 끝에서 읽는 그 문장이다 — pgx 는 시작이 돌려준 ctx 로 끝을 부른다.
type traceSQLKey struct{}

func (f traceSQLEnd) TraceQueryStart(ctx context.Context, conn *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	return context.WithValue(f.start(ctx, conn, d.SQL), traceSQLKey{}, d.SQL)
}

func (f traceSQLEnd) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	sql, _ := ctx.Value(traceSQLKey{}).(string)
	f.end(sql, d.Err)
}

// expiredCtx 는 ctx 의 끝난 자식이다 — 이 ctx 로 보낸 문장은 서버에 닿지 않고 ctx 오류로 끝난다(pgconn 은 끝난 ctx
// 로 보내지 않는다). 연결은 그대로 쓸 수 있다.
func expiredCtx(ctx context.Context) context.Context {
	c, cancel := context.WithDeadline(ctx, time.Now().Add(-time.Second))
	cancel()
	return c
}

// eventLog 는 문장 · 저장소 호출의 차례 기록이다 — 되돌림과 화해의 순서를 테스트가 본다.
type eventLog struct {
	mu     sync.Mutex
	events []string
}

func (e *eventLog) add(s string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.events = append(e.events, s)
}

func (e *eventLog) list() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return slices.Clone(e.events)
}

// headRecorder 는 Head 가 저장소에 닿을 때 차례 기록에 "head" 를 적는 저장소다. 나머지는 가짜 그대로다.
type headRecorder struct {
	*fakeStore
	log *eventLog
}

func (s headRecorder) Head(ctx context.Context, key string) (Stat, error) {
	s.log.add("head")
	return s.fakeStore.Head(ctx, key)
}

// wantAbort 는 중단 · 포기 로그가 사유 reason(WARN) 한 줄인지 본다 — 속성 attrs(키 · 값 쌍)도 담았는지 본다.
func wantAbort(t *testing.T, logs *logRecorder, reason string, attrs ...string) {
	t.Helper()
	recs := logs.abortRecs()
	if len(recs) != 1 || recs[0].attrs["reason"] != reason || recs[0].level != slog.LevelWarn {
		t.Fatalf("중단 · 포기 로그 %+v, want %s(WARN) 한 줄", recs, reason)
	}
	for i := 0; i+1 < len(attrs); i += 2 {
		if got := recs[0].attrs[attrs[i]]; !strings.Contains(got, attrs[i+1]) {
			t.Errorf("속성 %s = %q, want %q 를 담음", attrs[i], got, attrs[i+1])
		}
	}
}

// 원자성(설계 6.3 「INSERT 가 먼저」 · 계획 검증 표 PG 행 · 체크리스트 A-3 8–11) — GAP 틱은 원장 INSERT 를 커밋한 뒤에
// PUT 한다. PUT 이 실패해도(결과 모름) 원장 행은 남고 결과에 GAP seq 가 실린다 — 루프가 캐시에 넣으면 다음 틱이 GAP
// 줄을 싣고, 그 틱의 P4 가 원장 행을 확정한다. 새로 선 GAP 은 rewind_gap_published_total WARN 한 줄이다(J28).
func TestGapTickRecordsGapBeforePut(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	var atPut map[int64]gapRow
	store.setOnPut(func(putCall) error {
		store.setOnPut(nil)
		atPut = readGaps(t, l.pub.pool, fxStream)
		return errors.New("connection reset")
	})
	in, k := l.input(0, 8), int64(6)
	in.GapCandidate = &k

	out := l.run(t.Context(), in)

	if in.Playlist.Rows[6].IsGap {
		t.Error("GAP 틱이 루프가 준 목록 값의 행을 바꿨다, want 그대로(GAP 줄은 사본에서 둔다)")
	}
	wantGapStates(t, atPut, map[int64]string{6: "recorded"})
	if out.Published || out.GapSeq == nil || *out.GapSeq != 6 || out.UploadedSeq != nil || out.Err != nil {
		t.Errorf("GAP 틱 = %+v, want 발행 없음 · GapSeq 6(PUT 이 실패해도 싣는다)", out)
	}
	wantAbort(t, logs, reasonPutUnknown)
	recs := logs.records(gapPublishedLog)
	if len(recs) != 1 || recs[0].level != slog.LevelWarn || recs[0].attrs["stream"] != fxStream ||
		recs[0].attrs["session"] != "S" || recs[0].attrs["seq"] != "6" || recs[0].attrs["reason"] != "upload_stall" {
		t.Errorf("%s 로그 %+v, want WARN 한 줄(stream=%s · session=S · seq=6 · reason=upload_stall)", gapPublishedLog, recs, fxStream)
	}

	l.mustPublish(t.Context(), 0, 8)

	body, _ := stored(store, soloKey)
	if got := gapURIs(body); !slices.Equal(got, []string{segURI(6)}) {
		t.Errorf("다음 틱 본문의 GAP 줄 %v, want [%s]", got, segURI(6))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
}

// 적격 ④ 의 DB 재확인(설계 4.6.3 GAP_ELIGIBLE ④ · 계획 PR ⓒ in 줄 · 뮤테이션 21 · 6.4 드리프트 줄) — 캐시는 k 를 ③
// 미확정으로 보는데 DB 는 이미 uploaded 면 GAP 을 넣지 않는다(허위 GAP 방어). PUT 없이 끝나고 결과에 「DB 가 uploaded
// 를 봤다」를 싣는다(J18 · J26). 트랜잭션은 커밋한다 — P0 의 세대 소모 · lease 갱신은 남는다. 루프가 그 관측을 캐시에
// 넣으면 다음 평시 틱이 k 를 일반 줄로 싣는다.
func TestGapTickRechecksUploadInDB(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "uploaded")
	gen := readSession(t, l.pub.pool, "S").gen

	out := l.gapTick(t.Context(), 0, 8, 6)

	if out.Published || out.GapSeq != nil || out.UploadedSeq == nil || *out.UploadedSeq != 6 || len(store.putCalls()) != 1 {
		t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · UploadedSeq 6 · PUT 1번", out, len(store.putCalls()))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
	if db := readSession(t, l.pub.pool, "S").gen; db != gen+1 || out.State.Gen != gen+1 {
		t.Errorf("DB 세대 %d · 상태 세대 %d, want %d(트랜잭션 커밋 — P0 이 남는다)", db, out.State.Gen, gen+1)
	}
	if got := logs.aborts(); len(got) != 0 {
		t.Errorf("중단 · 포기 로그 %v, want 없음", got)
	}

	l.mustPublish(t.Context(), 0, 8)

	if body, _ := stored(store, soloKey); len(gapURIs(body)) != 0 {
		t.Errorf("다음 틱 본문의 GAP 줄 %v, want 없음(k 는 일반 줄)", gapURIs(body))
	}
}

// GAP 부적격(설계 4.6.3 GAP_ELIGIBLE ① 행 · ② 네 열 · ⑤ 컷오프 — GAP 트랜잭션이 DB 에서 다시 본다)이면 원장에 아무것도
// 넣지 않고 PUT 없이 끝난다(판단 J26). 트랜잭션은 커밋한다 — 세대가 하나 오르고 lease 가 갱신된다. 로그도 결과 칸도
// 없다(부적격은 싣지 않는다 — J18).
func TestGapTickIneligibleRowRecordsNothing(t *testing.T) {
	cases := []struct {
		name   string
		cutoff int64
		row    func(r boundary.Row) (boundary.Row, bool) // 장부에 넣을 k 행 — 거짓이면 넣지 않는다
	}{
		{"행_없음", 0, func(r boundary.Row) (boundary.Row, bool) { return r, false }},
		{"회차_NULL", 0, func(r boundary.Row) (boundary.Row, bool) { r.SessionID = ""; return r, true }},
		{"PDT_NULL", 0, func(r boundary.Row) (boundary.Row, bool) { r.PlaybackPDT = time.Time{}; return r, true }},
		{"③_키_NULL", 0, func(r boundary.Row) (boundary.Row, bool) { r.PlaybackS3Key = ""; return r, true }},
		{"길이_0", 0, func(r boundary.Row) (boundary.Row, bool) { r.DurationMS = 0; return r, true }},
		{"컷오프_아래", 7, func(r boundary.Row) (boundary.Row, bool) { return r, true }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newStalledLoop(t, 20, 6)
			if r, ok := tc.row(l.fx.rows[6]); ok {
				insertLedgerRow(t, l.pub.pool, r, "pending")
			}
			insertCutoff(t, l.pub.pool, tc.cutoff)
			gen, renewed := readSession(t, l.pub.pool, "S").gen, l.st.RenewedAt

			out := l.gapTick(t.Context(), 0, 8, 6)

			if out.Published || out.GapSeq != nil || out.UploadedSeq != nil || out.Err != nil || len(store.putCalls()) != 1 {
				t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · 결과 칸 없음 · PUT 1번", out, len(store.putCalls()))
			}
			wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
			if db := readSession(t, l.pub.pool, "S").gen; db != gen+1 || out.State.Gen != gen+1 || !out.State.RenewedAt.After(renewed) {
				t.Errorf("DB 세대 %d · 상태 %+v, want 세대 %d · lease 갱신 시각이 %v 뒤(트랜잭션 커밋)", db, out.State, gen+1, renewed)
			}
			if got := logs.aborts(); len(got) != 0 || len(logs.records(gapPublishedLog)) != 0 {
				t.Errorf("로그 %v · GAP 신호 %d줄, want 없음", got, len(logs.records(gapPublishedLog)))
			}
		})
	}
}

// 원장의 스트림 결합(보안 r1 H-1 · c4-fix1 개정 2) — 원장 행의 스트림은 입력이 아니라 같은 트랜잭션의 P0 이 fence 를
// 확인한 소유 회차의 스트림이다. 입력 스트림만 다른 스트림(그 스트림 회차의 fence 는 쥐지 않았다)으로 어긋난 GAP 틱은
// 그 스트림 원장에 쓰지 않고, 그 스트림의 원장 행 · ③ 상태도 GAP 이 섰다는 근거로 읽지 않는다 — 부적격처럼 PUT 없이
// 끝나고 결과 칸 · 신호가 없다. 소유 스트림 원장도 그대로다.
func TestGapTickMismatchedStreamRecordsNothing(t *testing.T) {
	cases := []struct {
		name  string
		state string // victim 장부 행 6 의 ③ 상태
		gap   bool   // victim 원장에 행 6 이 이미 있다
	}{
		{"적격_행", "pending", false},
		{"원장_행_이미_있음", "pending", true},
		{"uploaded_행", "uploaded", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newGapLoop(t, 20, 6, "pending")
			pool := l.pub.pool
			insertSession(t, pool, "victim", rewind.Session{ID: "V", TargetDuration: 6}, "live")
			exec(t, pool, `
				INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key,
				                             session_id, playback_pdt, playback_s3_key, playback_upload_state)
				VALUES ('victim', 6, 0, now(), 4000, 'archive/victim/000006.mp4', 'V', now(), 'dvr/victim/seg/000006.m4s', $1)`,
				tc.state)
			exec(t, pool, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
				VALUES ('victim', 0, 'live_ingress', 'watcher')`)
			want := map[int64]string{}
			if tc.gap {
				exec(t, pool, `INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
					VALUES ('victim', 6, 'V', 'upload_stall')`)
				want[6] = "recorded"
			}
			in, k := l.input(0, 8), int64(6)
			in.Playlist.StreamID, in.GapCandidate = "victim", &k

			out := l.run(t.Context(), in)

			if out.Published || out.GapSeq != nil || out.UploadedSeq != nil || len(store.putCalls()) != 1 {
				t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · 결과 칸 없음 · PUT 1번", out, len(store.putCalls()))
			}
			wantGapStates(t, readGaps(t, pool, "victim"), want)
			wantGapStates(t, readGaps(t, pool, fxStream), map[int64]string{})
			if n := len(logs.records(gapPublishedLog)); n != 0 {
				t.Errorf("%s %d줄, want 0", gapPublishedLog, n)
			}
		})
	}
}

// 멱등(판단 J23 · J18) — k 의 원장 행이 이미 있으면 새 행 없이 그 GAP 이 선 것으로 본다. GAP 줄을 싣고 결과에 GAP
// seq 를 싣는다(「이미 있었음」을 빼면 COMMIT 결과를 잃은 뒤 캐시가 그 GAP 을 모른 채 GAP 틱이 되풀이된다). 새로 선
// GAP 이 아니라 신호는 남기지 않는다.
func TestGapTickReusesExistingGap(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	insertGap(t, l.pub.pool, fxStream, 6, "recorded")

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !out.Published || out.GapSeq == nil || *out.GapSeq != 6 {
		t.Errorf("GAP 틱 = %+v, want 발행 · GapSeq 6", out)
	}
	if body, _ := stored(store, soloKey); !slices.Equal(gapURIs(body), []string{segURI(6)}) {
		t.Errorf("본문의 GAP 줄 %v, want [%s]", gapURIs(body), segURI(6))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
	if n := len(logs.records(gapPublishedLog)); n != 0 {
		t.Errorf("%s %d줄, want 0(새로 선 GAP 이 아니다)", gapPublishedLog, n)
	}
}

// GAP 트랜잭션의 첫 문장 P0 이 0행이거나(p0_no_row) 오류로 끝나면(p0_unknown) 트랜잭션을 되돌리고 화해한다 — 원장 행은
// 없다(적격 재확인 · INSERT 는 P0 뒤다 — 체크리스트 A-3 4 · 판단 J25 — 사유는 커밋 3 그대로).
func TestGapTickP0FailureRollsBack(t *testing.T) {
	cases := []struct {
		name   string
		reason string
		setup  func(t *testing.T, l *loop)
	}{
		{"0행", reasonP0NoRow, func(t *testing.T, l *loop) {
			exec(t, l.pub.pool, `UPDATE stream_sessions SET state = 'ending', ending_at = now() WHERE session_id = 'S'`)
		}},
		{"오류", reasonP0Unknown, func(t *testing.T, l *loop) {
			injectUpdateFault(t, l.pub.pool, "true").arm() // 다음 UPDATE = GAP 트랜잭션의 P0
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newGapLoop(t, 20, 6, "pending")
			tc.setup(t, l)
			heads := len(store.headKeys())

			out := l.gapTick(t.Context(), 0, 8, 6)

			wantAbort(t, logs, tc.reason)
			if out.Published || out.GapSeq != nil || len(store.putCalls()) != 1 || len(store.headKeys()) != heads+1 {
				t.Errorf("GAP 틱 = %+v · PUT %d번 · Head %d번 늘어남, want 발행 없음 · PUT 1번 · 화해 한 번",
					out, len(store.putCalls()), len(store.headKeys())-heads)
			}
			wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
		})
	}
}

// 출처 대조(계획 4.5 A1 결정 8 · 체크리스트 A-3 5 · 판단 J22) — GAP 트랜잭션의 P0 이 돌려준 manifest_etag 가 루프의
// P.ETag 와 다르면 GAP 을 넣지 않는다. P0 은 커밋한다(평시 틱과 같은 DB 결과 — 세대 소모 · lease 갱신). 사유는
// etag_source_mismatch 이고 화해가 P 와 DB 를 저장된 판으로 맞춘다.
func TestGapTickSourceMismatchCommitsWithoutGap(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	published := *l.st.Prev
	exec(t, l.pub.pool, `UPDATE stream_sessions SET manifest_etag = '"elsewhere"' WHERE session_id = 'S'`)
	gen := readSession(t, l.pub.pool, "S").gen

	out := l.gapTick(t.Context(), 0, 8, 6)

	wantAbort(t, logs, reasonETagSourceMismatch)
	if out.Published || out.GapSeq != nil || len(store.putCalls()) != 1 {
		t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · GAP 없음 · PUT 1번", out, len(store.putCalls()))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
	row := readSession(t, l.pub.pool, "S")
	if row.gen != gen+1 || strOrNil(row.etag) != published.ETag || !samePrev(out.State.Prev, &published) {
		t.Errorf("DB 세대 %d · ETag %s · P %+v, want %d(P0 커밋) · 저장된 판 %s(화해)", row.gen, strOrNil(row.etag), out.State.Prev, gen+1, published.ETag)
	}
}

// GAP 이 서지 않은 GAP 틱은 PUT 하지 않는다(판단 J26 · 6.4 음성) — 겹침 창(k 를 settled 로 본 창)은 k 가 GAP 이 될
// 때만 참이다. 1시간 창에서 그 목록을 GAP 없이 올리면 다음 평시 틱의 창(실제 스냅숏 — k 앞에서 머리가 멈춘다)이 더
// 앞선 MSN 으로 계산돼 S2 가 발행을 멈춘다. k 가 부적격이면 GAP 틱이 조용히 끝나고, 다음 평시 틱은 검사를 지난다.
func TestUnstoodGapTickKeepsNextWindowValid(t *testing.T) {
	l, store, logs := newSoloLoop(t, 1000)
	l.fx.rows[901].PlaybackUploaded = false
	ineligible := l.fx.rows[901]
	ineligible.PlaybackS3Key = ""
	insertLedgerRow(t, l.pub.pool, ineligible, "pending")
	insertCutoff(t, l.pub.pool, 0)
	l.mustPublish(t.Context(), 1, 900) // 1시간 창 — 머리 900 · 다음 행 901 이 정체

	gap := l.gapTick(t.Context(), 3, 902, 901) // 겹침 창 — 머리가 902 로 가며 꼬리가 3 으로 밀렸다
	next := l.tick(t.Context(), 1, 900)        // 실제 스냅숏의 창

	if gap.Published || len(store.putCalls()) != 1 {
		t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · PUT 1번", gap, len(store.putCalls()))
	}
	if got := logs.aborts(); len(got) != 0 || next.Err != nil {
		t.Errorf("다음 평시 틱 = %+v · 로그 %v, want 검사 통과(로그 없음)", next, got)
	}
}

// 잠금 순서(판단 J24) — GAP 트랜잭션은 P0 으로 소유 회차 행을 쥔 채 k 를 잠그지 않고 읽는다. init 불일치 확정 문장
// (index.MarkPlaybackFailed 의 init_mismatch 갈래 — 조각 행 → 회차 행 순으로 잠근다)이 k 를 잠그고 회차 행을 기다리는
// 동안 적격 재확인이 돌아도 교착이 없다 — GAP 트랜잭션이 끝나고, 기다리던 문장이 뒤이어 선다.
func TestGapTickLockOrderWithInitMismatch(t *testing.T) {
	l, _, _ := newGapLoop(t, 20, 6, "pending")
	base := l.pub.pool
	done := make(chan error, 1)
	var marked, started bool
	l.pub.pool = hookedPool(t, base, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		if sql != gapRecordSQL {
			return ctx
		}
		started = true
		go func() {
			var err error
			marked, err = index.NewUploadStore(base).MarkPlaybackFailed(context.Background(), fxStream, 6, "S", index.ReasonInitMismatch)
			done <- err
		}()
		waitLockWait(t, base)
		return ctx
	}))

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !started {
		t.Fatal("GAP 트랜잭션의 적격 재확인 문장이 나가지 않았다")
	}
	select {
	case err := <-done:
		if err != nil || !marked {
			t.Fatalf("init 불일치 확정 = (%v, %v), want (true, nil) — 교착 없이 뒤이어 선다", marked, err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("init 불일치 확정 문장이 10초 안에 끝나지 않았다")
	}
	if !out.Published || out.GapSeq == nil || out.Err != nil {
		t.Errorf("GAP 틱 = %+v, want 발행 · GapSeq 6", out)
	}
	if row := readSession(t, base, "S"); row.state != "ending" {
		t.Errorf("회차 state = %s, want ending(init 불일치 확정이 섰다)", row.state)
	}
}

// waitLockWait 는 테스트 DB 에서 행 잠금을 기다리는 백엔드가 생길 때까지 기다린다(5초까지).
func waitLockWait(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var n int
		err := pool.QueryRow(context.Background(), `SELECT count(*) FROM pg_stat_activity
			WHERE datname = current_database() AND wait_event_type = 'Lock'`).Scan(&n)
		if err != nil {
			t.Errorf("잠금 대기 조회 실패: %v", err)
			return
		}
		if n > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Error("5초 안에 잠금을 기다리는 백엔드가 생기지 않았다")
}

// INSERT 실패(판단 J25 · 체크리스트 B-1) — P0 뒤 적격 재확인 · INSERT 문장이 실패하면 트랜잭션을 되돌린다(적용이
// 없음을 아는 갈래). 사유는 gap_tx_failed(stage=insert) 한 줄이고 P0 사유가 아니다. 원장 행이 없고 DB 세대가 그대로다.
// 되돌린 P0 은 없던 일이라 상태의 세대 · lease 갱신 시각도 그대로다(되돌린 갱신 시각을 들면 lazy 갱신이 늦는다). 화해는
// 되돌림 뒤에만 나간다(A-3 15 — 열린 트랜잭션의 행 잠금을 화해의 R3 가 기다리지 않게). 요구 적재는 없다.
func TestGapTickFailedInsertRollsBack(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	before, gen := l.st, readSession(t, l.pub.pool, "S").gen
	events := &eventLog{}
	l.pub.store = headRecorder{fakeStore: store, log: events}
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		switch sql {
		case gapRecordSQL:
			return expiredCtx(ctx)
		case "rollback":
			events.add("rollback")
		}
		return ctx
	}))

	out := l.gapTick(t.Context(), 0, 8, 6)

	wantAbort(t, logs, reasonGapTxFailed, "stage", "insert", "err", "deadline exceeded")
	if out.Published || out.GapSeq != nil || out.DemandLoad || out.Err != nil || len(store.putCalls()) != 1 {
		t.Errorf("GAP 틱 = %+v · PUT %d번, want 발행 없음 · 요구 적재 없음 · PUT 1번", out, len(store.putCalls()))
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
	if db := readSession(t, l.pub.pool, "S").gen; db != gen || out.State.Gen != before.Gen || !out.State.RenewedAt.Equal(before.RenewedAt) {
		t.Errorf("DB 세대 %d · 상태 %+v, want 세대 %d 그대로 · lease 갱신 시각 %v 그대로(되돌렸다)", db, out.State, gen, before.RenewedAt)
	}
	if got := events.list(); !slices.Equal(got, []string{"rollback", "head"}) {
		t.Errorf("차례 %v, want [rollback head](되돌림 뒤 화해)", got)
	}
}

// COMMIT 결과 모름(판단 J25 · 계획 게이트 [세부] HIGH 장부 388 G-1) — 서버는 GAP 트랜잭션을 커밋했는데 워커는 COMMIT
// 오류를 받았다. 사유는 gap_tx_failed(stage=commit)이고 되돌리지 않는다(pgx 는 결과와 상관없이 트랜잭션을 닫는다).
// 곧바로 화해하고 요구 적재를 낸다 — 캐시가 서버의 GAP 행을 모른 채 k 의 업로드 결과가 먼저 닿으면 평시 틱이 k 를 일반
// 줄로 올리고, 뒤의 적재가 그 줄을 GAP 으로 바꾼다. 적재 전에 k 가 여전히 정체면 다음 GAP 틱의 INSERT 가 「이미
// 있었음」을 돌려줘 GAP 이 선다(J18 · J23).
func TestGapTickUnknownCommitDemandsLoad(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	before := l.st
	heads := len(store.headKeys())
	var rollbacks int
	var once sync.Once
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, conn *pgx.Conn, sql string) context.Context {
		switch sql {
		case "commit":
			committed := false
			once.Do(func() { // 서버에 커밋을 적용하고, 워커의 COMMIT 은 끝난 ctx 로 보낸다(결과를 잃었다)
				if _, err := conn.PgConn().Exec(context.Background(), "commit").ReadAll(); err != nil {
					t.Errorf("서버 커밋 실패: %v", err)
				}
				committed = true
			})
			if committed {
				return expiredCtx(ctx)
			}
		case "rollback":
			rollbacks++
		}
		return ctx
	}))

	out := l.gapTick(t.Context(), 0, 8, 6)

	wantAbort(t, logs, reasonGapTxFailed, "stage", "commit", "err", "deadline exceeded")
	if !out.DemandLoad || out.Published || out.GapSeq != nil || out.Err != nil || rollbacks != 0 {
		t.Errorf("GAP 틱 = %+v · 되돌림 %d번, want 요구 적재 · 발행 없음 · 되돌림 0", out, rollbacks)
	}
	if n := len(store.headKeys()) - heads; n != 1 || out.State.ReconcileDue || !out.State.RenewedAt.Equal(before.RenewedAt) {
		t.Errorf("Head %d번 · 상태 %+v, want 화해 한 번 · lease 갱신 시각 그대로(COMMIT 결과를 모른다)", n, out.State)
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "recorded"})

	next := l.gapTick(t.Context(), 0, 8, 6) // 적재 전 — 캐시는 아직 k 를 GAP 으로 모른다

	if !next.Published || next.GapSeq == nil || *next.GapSeq != 6 {
		t.Errorf("다음 GAP 틱 = %+v, want 발행 · GapSeq 6(이미 있었음)", next)
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{6: "put_confirmed"})
}

// BEGIN 실패(판단 J25 · 장부 388 G-5) — P0 전이라 중단 · 포기 표의 어느 사유에도 들지 않는다. 표 밖 실패로
// Outcome.Err 에 싣고 로그 · 화해 없이 끝낸다(커밋 3 관례). 원장 행 · 상태 변화가 없다.
func TestGapTickBeginFailureIsErr(t *testing.T) {
	l, store, logs := newGapLoop(t, 20, 6, "pending")
	before := l.st
	heads := len(store.headKeys())
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		if sql == "begin" {
			return expiredCtx(ctx)
		}
		return ctx
	}))

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !errors.Is(out.Err, context.DeadlineExceeded) || out.Published || out.GapSeq != nil || out.DemandLoad {
		t.Errorf("GAP 틱 = %+v, want Err(BEGIN 실패) · 발행 없음", out)
	}
	if got := logs.aborts(); len(got) != 0 || len(store.headKeys()) != heads || out.State != before {
		t.Errorf("로그 %v · Head %d번 늘어남 · 상태 %+v, want 로그 없음 · 화해 없음 · 상태 그대로", got, len(store.headKeys())-heads, out.State)
	}
	wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
}

// 되돌림 ctx(체크리스트 A-3 15 · c4-fix1 개정 1) — 되돌림은 되돌리는 순간에 만든 끝나지 않은 짧은 ctx 로 보낸다. 끝난
// ctx(문장 시한이 지난 stmtCtx · 끝난 루프 ctx)로 보내면 pgx 가 그 연결을 버린다. 연결이 이미 선(데운) 풀에서 재므로 새
// 연결의 수립 시간이 되돌림의 시한을 벌어 주지 않는다 — 되돌림이 오류 없이 끝나고, 새 연결 없이 같은 풀의 다음 문장이
// 선다. 루프 ctx 가 끝나 멈춘 GAP 틱은 되돌리고 Outcome.Err 만 남긴다 — 로그 · 화해 없음(stopped · 커밋 3 그대로).
func TestGapTickRollsBackWithLiveContext(t *testing.T) {
	cases := []struct {
		name string
		// inject 는 INSERT 문장을 실패시킨다 — 루프 ctx 의 cancel 을 받는다.
		inject  func(ctx context.Context, cancel context.CancelFunc) context.Context
		stopped bool
	}{
		{"문장_시한_지남", func(ctx context.Context, _ context.CancelFunc) context.Context {
			<-ctx.Done()                      // 트랜잭션의 문장 시한(stmtCtx)이 지날 때까지 붙잡고
			time.Sleep(20 * time.Millisecond) // 시한을 넘겨 늦게 돌아온다
			return ctx
		}, false},
		{"루프_ctx_끝남", func(ctx context.Context, cancel context.CancelFunc) context.Context {
			cancel()
			return ctx
		}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			l, store, logs := newGapLoop(t, 20, 6, "pending")
			l.pub.opt.statementTimeout = 200 * time.Millisecond
			gen, heads := readSession(t, l.pub.pool, "S").gen, len(store.headKeys())
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			var rollbacks []error
			pool := hookedPool(t, l.pub.pool, traceSQLEnd{
				start: func(sctx context.Context, _ *pgx.Conn, sql string) context.Context {
					if sql == gapRecordSQL {
						return tc.inject(sctx, cancel)
					}
					return sctx
				},
				end: func(sql string, err error) {
					if sql == "rollback" {
						rollbacks = append(rollbacks, err)
					}
				},
			})
			if _, err := pool.Exec(t.Context(), "select 1"); err != nil { // 연결 하나를 세워 둔다
				t.Fatalf("풀 데우기 실패: %v", err)
			}
			conns := pool.Stat().NewConnsCount()
			l.pub.pool = pool

			out := l.gapTick(ctx, 0, 8, 6)

			if len(rollbacks) != 1 || rollbacks[0] != nil {
				t.Errorf("되돌림 결과 %v, want 한 번 · 오류 nil(끝나지 않은 ctx)", rollbacks)
			}
			if db := readSession(t, pool, "S").gen; db != gen { // 같은 풀의 다음 문장
				t.Errorf("DB 세대 %d, want %d(되돌렸다)", db, gen)
			}
			if n := pool.Stat().NewConnsCount() - conns; n != 0 {
				t.Errorf("새 연결 %d개, want 0(되돌림이 연결을 버리지 않았다)", n)
			}
			wantGapStates(t, readGaps(t, l.pub.pool, fxStream), map[int64]string{})
			if !tc.stopped {
				wantAbort(t, logs, reasonGapTxFailed, "stage", "insert")
				return
			}
			if !errors.Is(out.Err, context.Canceled) || len(logs.aborts()) != 0 || len(store.headKeys()) != heads {
				t.Errorf("멈춘 GAP 틱 = %+v · 로그 %v · Head %d번 늘어남, want Err = Canceled · 로그 없음 · 화해 없음",
					out, logs.aborts(), len(store.headKeys())-heads)
			}
		})
	}
}

// m0 는 BEGIN 전이다(보안 lease 인계 · 판단 J20 · 계획 4.5 A1 참고 블록) — 트랜잭션 안의 now() 는 BEGIN 시각이라,
// m0 를 BEGIN 뒤에 잡으면 m0 ≤ t0 이 깨지고 P3 마감과 lease 끝 사이 여유가 준다. BEGIN 이 나갈 때 주입 시계를 읽어
// m0(GAP 틱 뒤 상태의 lease 갱신 시각)와 견준다.
func TestGapTickTakesM0BeforeBegin(t *testing.T) {
	l, _, _ := newGapLoop(t, 20, 6, "pending")
	clock := newStepClock(slices.Repeat([]time.Duration{time.Millisecond}, 10)...)
	l.pub.now = clock.Now
	var beginAt time.Time
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		if sql == "begin" {
			beginAt = clock.Now()
		}
		return ctx
	}))

	out := l.gapTick(t.Context(), 0, 8, 6)

	if !out.Published || beginAt.IsZero() || !out.State.RenewedAt.Before(beginAt) {
		t.Errorf("GAP 틱 = %+v · BEGIN 시각 %v, want 발행 · m0(lease 갱신 시각) < BEGIN", out, beginAt)
	}
}

// 평시 틱(GAP 후보 없음)은 트랜잭션을 열지 않고 적격 재확인 문장을 보내지 않는다(6.4 음성 · 평시 DB 왕복 불변) —
// GAP 틱만 BEGIN · INSERT · COMMIT 으로 왕복이 는다(체크리스트 A-3 14).
func TestNormalTickSendsNoGapTransaction(t *testing.T) {
	l, _, _ := newGapLoop(t, 20, 6, "pending")
	l.fx.markUploaded(6)
	var sent []string
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		if sql == "begin" || sql == gapRecordSQL {
			sent = append(sent, sql)
		}
		return ctx
	}))

	l.mustPublish(t.Context(), 0, 8)

	if len(sent) != 0 {
		t.Errorf("평시 틱이 보낸 문장 %q, want BEGIN · 적격 재확인 0", sent)
	}
}
