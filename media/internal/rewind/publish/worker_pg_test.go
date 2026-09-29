package publish

// 발행 워커는 값만 받는다(계획 4.5 A3 결정 1 「동시성」 · 체크리스트 A-6 4 · 6.4 ③·init 통지 줄) — 캐시 · Dirty 를
// 만지지 않는다. 적재 · 감시 작업(커밋 5)도 같다 — 입력 값만 받고 결과를 값으로 돌려준다. 실제 캐시(rewind/cache)와
// 차선으로 잰다. -race 로 돌린다.

import (
	"bytes"
	"context"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/cache"
)

// soloCache 는 픽스처 soloFixture(lastSeq)와 같은 장부를 적재한 캐시다 — 루프 고루틴이 소유하는 쪽이다.
func soloCache(lastSeq int64) *cache.Cache {
	f := soloFixture(lastSeq)
	l := index.RewindLedger{HasCutoff: true, Sessions: []index.RewindSession{
		{SessionID: "S", State: "live", InitUploaded: true, TargetDuration: 6},
	}}
	for _, r := range f.rows {
		l.Rows = append(l.Rows, index.RewindRow(r))
	}
	c := &cache.Cache{}
	c.CompleteLoad(fxStream, c.BeginLoad(fxStream, nil), l)
	return c
}

// 캐시가 내준 목록 값은 워커가 도는 동안 루프가 캐시를 고쳐도 흔들리지 않는다 — 워커가 쓰는 행은 발사 때 복사된
// 값이다. 발사 뒤 · 렌더 전에 루프가 seq 3 의 길이를 교정해도(꼬리 교정 push) 올라간 본문은 발사 때 값(4초)을 싣고,
// 워커가 도는 내내 루프가 캐시에 push 를 이어 가도 경합이 없다(-race). 워커가 스냅숏 슬라이스(Snapshot().RowsFrom
// — 캐시의 저장소를 복사 없이 내준다)를 받았다면 두 쪽 다 깨진다.
func TestWorkerRendersOnlyTheValueItWasGiven(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	store := newFakeStore(t)
	pub := newPublisher(t, pool, store, "w-me", &logRecorder{})
	c := soloCache(20)
	pl, ok := c.Playlist(fxStream, "S", boundary.Window{TailSeq: 0, HeadSeq: 5})
	if !ok {
		t.Fatal("캐시 Playlist = 거짓")
	}
	lanes := NewLanes()
	release, done := make(chan struct{}), make(chan struct{})
	lanes.Start(t.Context(), fxStream, func(ctx context.Context) Result {
		<-release
		defer close(done)
		return Result{Kind: KindPublish, Publish: pub.Tick(ctx, State{}, TickInput{Playlist: pl})}
	})

	c.ApplyTailCorrection(fxStream, 3, 3000) // 발사 뒤 · 렌더 전 교정
	close(release)
	for seq := int64(0); ; seq = (seq + 1) % 20 { // 워커가 도는 내내 push
		select {
		case <-done:
		default:
			c.ApplyTailCorrection(fxStream, seq, 4000)
			c.ApplyPlaybackUploaded(fxStream, seq)
			continue
		}
		break
	}
	var r Result
	select {
	case r = <-lanes.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("워커 결과가 오지 않았다")
	}
	lanes.Finish(r)

	if !r.Publish.Published {
		t.Fatalf("워커의 틱 = %+v, want 발행", r.Publish)
	}
	body, _ := stored(store, soloKey)
	if !bytes.Contains(body, []byte("#EXTINF:4.000,\n"+fxBaseURL+"/dvr/str/seg/000003.m4s\n")) {
		t.Errorf("올라간 본문의 seq 3 길이가 발사 때 값(4초)이 아니다:\n%s", body)
	}
}

// insertRun 은 스트림 fxStream 의 조각 from..to(회차 S · 4초 · ③ 확정 · PDT = fxRow)를 한 문장으로 심고 컷오프를
// cutoff 로 둔다 — 적재 작업이 읽는 장부다.
func insertRun(t *testing.T, pool *pgxpool.Pool, from, to, cutoff int64) {
	t.Helper()
	exec(t, pool, `
		INSERT INTO stream_segments
			(stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes,
			 session_id, playback_pdt, playback_s3_key, playback_upload_state, playback_uploaded_at, playback_bytes)
		SELECT $1::text, g, g * 4000, $4::timestamptz + g * interval '4 seconds', 4000,
		       format('s3/%s', g), format('/recordings/%s', g), 'pending', 1000,
		       'S', $4::timestamptz + g * interval '4 seconds', format('dvr/%s/seg/%s.m4s', $1::text, lpad(g::text, 6, '0')),
		       'uploaded', now(), 900
		  FROM generate_series($2::bigint, $3::bigint) AS g`, fxStream, from, to, fxDay)
	exec(t, pool, `INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
	               VALUES ($1, $2, 'live_ingress', 'watcher')`, fxStream, cutoff)
}

// ledger_load_capped_with_alarm(계획 6.3 #82 · A3 결정 3 둘째 · 장부 421 P-7) — 행 적재도 최신 쪽부터 행 수 상한까지다.
// 힌트(P.MSN 100)가 하한을 되짚기 하한(190) 아래로 내려 상한(50)을 넘으면 최신 50행(150..199)만 싣고 상한 도달이
// 참이다. 적재 결과를 래퍼에 넘기면 ERROR rewind_ledger_load_degraded(reason=row_cap) 한 줄이다. 상한이 없으면 힌트
// 뒤 100행을 모두 싣는다(DB 작업량과 캐시 메모리가 운영 기간을 따라 커진다).
func TestLedgerLoadCappedWithAlarm(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	insertRun(t, pool, 0, 199, 0)
	logs := &logRecorder{}
	pub := newPublisher(t, pool, newFakeStore(t), "w-me", logs)
	hint := int64(100)

	out := pub.LoadLedger(t.Context(), LoadInput{StreamID: fxStream, Token: 7,
		Bounds: index.LedgerBounds{Hint: &hint, Lookback: 40 * time.Second, RowCap: 50}})
	pub.ReportLoad(t.Context(), fxStream, out, false)

	if n := len(out.Rows); out.Err != nil || out.Token != 7 || !out.Capped || out.FloorSeq != 150 || n != 50 ||
		out.Rows[0].Seq != 150 || out.Rows[n-1].Seq != 199 {
		t.Fatalf("적재 = (토큰 %d, 상한 도달 %v, 하한 %d, %d행, %v), want (7, 참, 150, 50행 150..199, nil)",
			out.Token, out.Capped, out.FloorSeq, n, out.Err)
	}
	want := []string{"ERROR rewind_ledger_load_degraded floor=150 reason=row_cap rows=50 stream=str"}
	if got := lines(logs); !reflect.DeepEqual(got, want) {
		t.Errorf("로그 = %q, want %q", got, want)
	}
}

// load_result_applied_on_loop_only(계획 A3 「동시성」 · 체크리스트 419 A-2 8 · A-7 1) — 적재 · 감시 작업은 입력
// 값(스트림 · 토큰 · 범위 / 대조 입력 · SEED_ALARM_AFTER)만 받고 결과를 값으로 돌려준다 — 캐시를 만지지 않는다. 두 작업이
// 차선에서 도는 동안 루프는 캐시에 push 를 이어 가고(적재 중이라 로그에 쌓인다), 결과는 루프가 받아 CompleteLoad ·
// AuditDrift · ReconcileWatch 로 반영한다. 적재분(장부 0..9)에 로그의 행 10 이 재생된다. 감시 작업은 발행자의
// SEED_ALARM_AFTER(10분)를 창으로 싣는다 — 9분 전 조각만 있는 컷오프 없는 스트림이 부재다. -race 로 돈다.
func TestLoadResultAppliedOnLoopOnly(t *testing.T) {
	pool := newPool(t)
	insertSessions(t, pool, soloFixture(0))
	insertRun(t, pool, 0, 9, 0)
	exec(t, pool, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
		                             upload_state, bytes)
		VALUES ('scan-only', 0, 0, now() - interval '9 minutes', 4000, 's3/scan-0', '/recordings/scan-0', 'pending', 1000)`)
	pub := newPublisher(t, pool, newFakeStore(t), "w-me", &logRecorder{})
	c := soloCache(5)
	probes := c.DriftProbes() // 루프가 발사 전에 값으로 뜬다
	token := c.BeginLoad(fxStream, nil)
	lanes := NewLanes()
	lanes.Start(t.Context(), fxStream, func(ctx context.Context) Result {
		o := cache.Options{}.WithDefaults()
		return Result{Kind: KindLoad, Load: pub.LoadLedger(ctx, LoadInput{StreamID: fxStream, Token: uint64(token),
			Bounds: index.LedgerBounds{Lookback: o.LedgerLookback, RowCap: o.LedgerRowCap}})}
	})
	lanes.StartProcess(t.Context(), func(ctx context.Context) Result {
		return Result{Kind: KindWatch, Watch: pub.Watch(ctx, probes)}
	})

	c.ApplyInsert(fxStream, 10, index.SeedResult{SessionID: "S", DurationMS: 4000, PlaybackPDT: fxRow("S", 10).PlaybackPDT,
		PlaybackS3Key: fxRow("S", 10).PlaybackS3Key}) // 두 작업이 도는 동안 루프의 push
	for range 2 {
		var r Result
		select {
		case r = <-lanes.Done():
		case <-time.After(10 * time.Second):
			t.Fatal("작업 결과가 오지 않았다")
		}
		lanes.Finish(r)
		switch r.Kind {
		case KindLoad:
			if applied, _ := c.CompleteLoad(r.StreamID, cache.LoadToken(r.Load.Token), r.Load.RewindLedger); !applied || r.Load.Err != nil {
				t.Fatalf("적재 결과 적용 = %v(%v), want 참", applied, r.Load.Err)
			}
		case KindWatch:
			if r.Watch.Err != nil || len(r.Watch.Drift) != 1 || r.Watch.Drift[0].NextSeq != 6 || !r.Watch.Drift[0].HasNext ||
				len(r.Watch.Absent) != 1 || r.Watch.Absent[0].StreamID != "scan-only" {
				t.Fatalf("감시 결과 = %+v, want 대조 한 행(seq 6 — DB 에 있다) · 부재 scan-only(창 10분 안)", r.Watch)
			}
			c.AuditDrift(r.Watch.Drift)
			c.ReconcileWatch(r.Watch.Live)
		}
	}

	if got := seqsOf(c.Snapshot(fxStream).RowsFrom(0)); len(got) != 11 || got[10] != 10 {
		t.Errorf("반영 뒤 행 = %v, want 0..10(적재분 0..9 + 로그의 10)", got)
	}
}

// 평시 틱은 장부를 읽지 않는다(체크리스트 419 B-2 · 프로필 4절 「매니페스트 합성은 평시 DB·S3 조회 0」) — 렌더 입력은
// 캐시이고, 적재 · 감시 문장은 작업 몸체 둘(LoadLedger · Watch)만 보낸다. 평시 틱이 보내는 문장은 세대 규약의 둘
// (P0 · P4)뿐이다 — 틱 경로에 적재 트랜잭션(BEGIN … READ ONLY)이나 감시 문장이 끼면 이 목록이 늘어난다.
func TestNormalTickSendsOnlyGenerationStatements(t *testing.T) {
	l, _, _ := newGapLoop(t, 20, 6, "pending")
	l.fx.markUploaded(6)
	var sent []string
	l.pub.pool = hookedPool(t, l.pub.pool, traceSQL(func(ctx context.Context, _ *pgx.Conn, sql string) context.Context {
		sent = append(sent, sql)
		return ctx
	}))

	l.mustPublish(t.Context(), 0, 8)

	if want := []string{p0ReserveSQL, p4ConfirmSQL}; !reflect.DeepEqual(sent, want) {
		t.Errorf("평시 틱이 보낸 문장 %d개 = %q, want P0 · P4 둘", len(sent), sent)
	}
}

// seqsOf 는 행들의 seq 다.
func seqsOf(rows []boundary.Row) []int64 {
	var out []int64
	for _, r := range rows {
		out = append(out, r.Seq)
	}
	return out
}
