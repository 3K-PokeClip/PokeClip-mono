package upload

// 스위퍼 축 3벌을 실 PG 로 잰다(계획 4절 PR ⓐ 검증 「PG 통합 — 축별 스위퍼 조회·backlog 3벌·컷오프
// 미만 면제」 · 설계 5.5.6 T4·T7·T12·T13 · r22→r23 처분표 ⑦-1). 조회(index 의 축별 문장) → 접수 →
// 워커의 축 본문 → CAS → 장부까지 한 번에 관통한다. 조회 문장 하나하나의 자격 술어는 index 의 PG
// 테스트가 재고, 여기서는 **스위퍼가 그 조회를 축마다 소비해 장부 확정까지 가는가**를 본다.
//
// PUT 은 가짜(fakePutter)이고 재포장은 실물(playback.Remuxer — 기본 생산자)이다. 녹화 파일은 실물
// 조각을 녹화 루트에 복사해 만든다 — 스위퍼 ③ 작업은 파일에서 mtxi 를 읽어 도장 위치를 정한다.
//
// PG_DSN 미설정이면 전량 skip 된다(REQUIRE_PG=1 인 CI 가 실주행 게이트다).

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄). 이 파일의 케이스는
// PG_DSN 이 없으면 skip 되는데 skip 은 성공으로 집계된다 — REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 스위퍼 PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// newSweepPool 은 이 패키지 전용 테스트 DB 에 붙은 풀이다. 접미 upload 로 가른다 — 패키지 테스트
// 바이너리끼리 병렬로 돌아도 서로의 행을 비우지 않는다(pgtest.Pool).
func newSweepPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "upload", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx, "TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
		return err
	})
}

// newSweepPGUploader 는 실 장부에 붙은 업로더다(고루틴 없음 — 회차와 워커를 테스트가 돌린다).
func newSweepPGUploader(t *testing.T, pool *pgxpool.Pool, tune func(*Options)) (*Uploader, *logCapture, string, *fakePutter) {
	t.Helper()
	put := &fakePutter{}
	u, cap, dir, _ := newPlaybackUploader(t, index.NewUploadStore(pool), put, nil, tune)
	return u, cap, dir, put
}

// openSession 은 세션 1행을 넣는다. confirmed 면 init 이 이미 올라간 세션이다(실물 재포장의 골든 해시).
func openSession(t *testing.T, pool *pgxpool.Pool, sessionID, streamID, state string, confirmed bool) {
	t.Helper()
	var key, sha, size any
	if confirmed {
		key, sha, size = "dvr/"+streamID+"/init/"+sessionID+".mp4", mustHexBytes(t, goldenInitSHA), goldenInitLen
	}
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state,
                             init_s3_key, init_sha256, init_bytes, init_uploaded_at)
VALUES ($1, $2, now() - interval '10 minutes', $3, $4, $5, $6,
        CASE WHEN $5::bytea IS NULL THEN NULL ELSE now() END)`,
		sessionID, streamID, state, key, sha, size)
	if err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", sessionID, err)
	}
}

func seedCutoff(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) {
	t.Helper()
	_, err := pool.Exec(context.Background(),
		`INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel)
		 VALUES ($1, $2, 'live_ingress', 'watcher')`, streamID, seq)
	if err != nil {
		t.Fatalf("컷오프 픽스처 실패 %s: %v", streamID, err)
	}
}

// segRow 는 조각 1행이다. session 이 비면 세션 없는 조각(SQL NULL)이다.
type segRow struct {
	stream, session string
	seq             int64
	archive         string // upload_state
	playback        string // playback_upload_state
}

// insertSegment 는 실물 조각을 녹화 루트에 복사하고 그 행을 장부에 넣는다. 파일 경로와 크기를
// 돌려준다 — 실시간 요청을 같은 파일로 흉내 낼 때 쓴다.
func insertSegment(t *testing.T, pool *pgxpool.Pool, dir string, r segRow) (string, int64) {
	t.Helper()
	path, size := copyFixture(t, dir, r.stream, fmt.Sprintf("seg%d.mp4", r.seq))
	var session any
	if r.session != "" {
		session = r.session
	}
	_, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path,
     upload_state, bytes, session_id, playback_pdt, playback_s3_key, playback_upload_state)
VALUES ($1, $2, $3, now() - interval '10 minutes', 4000, $4, $5,
        $6, $7, $8, now() - interval '10 minutes', $9, $10)`,
		r.stream, r.seq, r.seq*4000, index.S3Key(r.stream, r.seq, fixtureWall), path,
		r.archive, size, session, segKeyOf(t, r.stream, r.seq), r.playback)
	if err != nil {
		t.Fatalf("조각 픽스처 실패 %s/%d: %v", r.stream, r.seq, err)
	}
	return path, size
}

// openTable 은 실시간 ③ 요청 하나로 그 회차의 보정값 표를 연다({seq → 0}) — 스위퍼 ③ 작업이 도장
// 위치를 다시 만드는 재료다(계획 4.2-R R3). 접수된 작업은 버린다(표에 남은 줄만 쓴다).
func openTable(t *testing.T, u *Uploader, streamID, sessionID string, seq int64) {
	t.Helper()
	if !request(u, playbackTarget(t, streamID, seq, sessionID, "/unused", 1)) {
		t.Fatalf("표를 여는 실시간 ③ 요청이 접수되지 않았다 %s/%s", streamID, sessionID)
	}
}

// segmentState 는 조각 행의 두 축 상태와 ③ 크기다.
func segmentState(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) (archive, playbackState string, playbackBytes *int64) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT upload_state, playback_upload_state, playback_bytes
		   FROM stream_segments WHERE stream_id = $1 AND seq = $2`, streamID, seq).
		Scan(&archive, &playbackState, &playbackBytes)
	if err != nil {
		t.Fatalf("조각 상태 조회 실패 %s/%d: %v", streamID, seq, err)
	}
	return archive, playbackState, playbackBytes
}

// sessionInitOf 는 세션의 init 3열과 확정 여부다.
func sessionInitOf(t *testing.T, pool *pgxpool.Pool, sessionID string) (sha []byte, key *string, size *int64, confirmed bool) {
	t.Helper()
	err := pool.QueryRow(context.Background(),
		`SELECT init_sha256, init_s3_key, init_bytes, init_uploaded_at IS NOT NULL
		   FROM stream_sessions WHERE session_id = $1`, sessionID).
		Scan(&sha, &key, &size, &confirmed)
	if err != nil {
		t.Fatalf("세션 조회 실패 %s: %v", sessionID, err)
	}
	return sha, key, size, confirmed
}

// assertPlaybackUploaded 는 ③ 이 실물 재포장 산출(골든)의 길이로 확정됐는지 본다. ② 는 그대로다.
func assertPlaybackUploaded(t *testing.T, pool *pgxpool.Pool, streamID string, seq int64) {
	t.Helper()
	archive, state, size := segmentState(t, pool, streamID, seq)
	if state != "uploaded" || size == nil || *size != goldenSegLen {
		t.Errorf("%s/%d ③ = %s · bytes %v, want uploaded · %d", streamID, seq, state, size, goldenSegLen)
	}
	if archive != "uploaded" {
		t.Errorf("%s/%d ② = %s, want uploaded 그대로 — ③ 확정이 ② 열을 건드렸다", streamID, seq, archive)
	}
}

// assertInitConfirmed 는 세션 init 이 실물 산출(골든)의 해시·키·크기로 확정됐는지 본다.
func assertInitConfirmed(t *testing.T, pool *pgxpool.Pool, streamID, sessionID string) {
	t.Helper()
	sha, key, size, confirmed := sessionInitOf(t, pool, sessionID)
	wantKey := "dvr/" + streamID + "/init/" + sessionID + ".mp4"
	if !confirmed || fmt.Sprintf("%x", sha) != goldenInitSHA ||
		key == nil || *key != wantKey || size == nil || *size != goldenInitLen {
		t.Errorf("%s init = 확정 %v · sha %x · key %v · bytes %v, want 확정 · %s · %s · %d",
			sessionID, confirmed, sha, key, size, goldenInitSHA, wantKey, goldenInitLen)
	}
}

// putKeys 는 가짜 PUT 이 받은 키 집합이다.
func putKeys(put *fakePutter) map[string]bool {
	keys := map[string]bool{}
	for _, c := range put.putCalls() {
		keys[c.key] = true
	}
	return keys
}

// T4 — ③ 축 수거(설계 5.5.6 · 계약 5-5 재수거 규범 5항): ② 는 이미 올랐는데 ③ 만 pending(크래시
// 창·추출 지연)이거나 failed(재시도 소진)인 조각을 스위퍼가 ③ 조회로 집고, 워커가 회차 표로 도장
// 위치를 정해 재포장·PUT 한 뒤 ③ CAS 로 확정한다. 잡는 결함: 스위퍼가 ② 상태만 보면(M3 형상) 이
// 조각들은 재수거에서 영구 이탈하고 되감기 접두가 그 자리에서 멈춘다.
func TestSweepPGCollectsPlaybackRowsWhoseArchiveIsUploaded(t *testing.T) {
	pool := newSweepPool(t)
	u, cap, dir, put := newSweepPGUploader(t, pool, nil)
	openSession(t, pool, "S-t4", "t4", "live", true)
	seedCutoff(t, pool, "t4", 0)
	cases := []struct {
		name     string
		seq      int64
		playback string
	}{
		{"크래시_창", 0, "pending"},
		{"추출_지연", 1, "pending"},
		{"재시도_소진", 2, "failed"},
	}
	for _, c := range cases {
		insertSegment(t, pool, dir, segRow{stream: "t4", session: "S-t4", seq: c.seq, archive: "uploaded", playback: c.playback})
	}
	openTable(t, u, "t4", "S-t4", 0)

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			assertPlaybackUploaded(t, pool, "t4", c.seq)
		})
	}
	for _, c := range put.putCalls() {
		if sha256Hex(c.body) != goldenSegSHA {
			t.Errorf("PUT %s 본문 sha = %s, want 골든 %s — 표의 보정값(0)으로 도장이 mtxi 그대로여야 한다",
				c.key, sha256Hex(c.body), goldenSegSHA)
		}
	}
	if got := uploadedFrom(cap, index.AxisPlayback, OriginSweep); len(got) != len(cases) {
		t.Errorf("③ segment_uploaded(origin=sweep) = %v, want %d건", got, len(cases))
	}
}

// T12 — 3종 재수거(설계 5.5.6): ② failed 조각 · ③ failed 조각 · init 미확정 세션이 한 회차에 모두
// 회수된다. 세 축의 장부 열(upload_state · playback_upload_state · init 3열)이 각자 확정된다. 잡는
// 결함: 스위퍼가 한 축이라도 빠뜨리면 그 축의 실패분은 영원히 남는다.
func TestSweepPGRecoversFailedWorkOfEveryAxis(t *testing.T) {
	pool := newSweepPool(t)
	u, _, dir, _ := newSweepPGUploader(t, pool, nil)
	// ② — 세션도 컷오프도 없는 스트림(③ 은 면제라 ② 만 남는다).
	insertSegment(t, pool, dir, segRow{stream: "t12a", seq: 0, archive: "failed", playback: "pending"})
	// ③ — init 이 확정된 회차의 조각. ② 는 이미 올랐다.
	openSession(t, pool, "S-t12p", "t12p", "live", true)
	seedCutoff(t, pool, "t12p", 0)
	insertSegment(t, pool, dir, segRow{stream: "t12p", session: "S-t12p", seq: 0, archive: "uploaded", playback: "failed"})
	openTable(t, u, "t12p", "S-t12p", 0)
	// init — 확정 전인 회차(컷오프 없음 — init 조회는 컷오프와 무관하다).
	openSession(t, pool, "S-t12i", "t12i", "live", false)
	insertSegment(t, pool, dir, segRow{stream: "t12i", session: "S-t12i", seq: 0, archive: "uploaded", playback: "pending"})

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	if archive, _, _ := segmentState(t, pool, "t12a", 0); archive != "uploaded" {
		t.Errorf("t12a/0 ② = %s, want uploaded", archive)
	}
	assertPlaybackUploaded(t, pool, "t12p", 0)
	assertInitConfirmed(t, pool, "t12i", "S-t12i")
}

// T7 — 설계 5.5.6 라벨 「ended 제외 / ending 포함」: 정산 창(ending) 세션의 ③·init 은 계속 집고,
// 확정(ended) 세션의 것은 집지 않는다. 잡는 결함: ending 에서 멈추면 마지막 조각들이 빠진 채 목록이
// 굳고, ended 까지 집으면 끝난 방송을 계속 다시 만들어 올린다.
func TestSweepPGCollectsEndingButNotEndedSessions(t *testing.T) {
	pool := newSweepPool(t)
	u, _, dir, put := newSweepPGUploader(t, pool, nil)
	for _, s := range []struct{ stream, session, state string }{
		{"t7a", "S-t7a", "ending"},
		{"t7b", "S-t7b", "ended"},
	} {
		openSession(t, pool, s.session, s.stream, s.state, true)
		seedCutoff(t, pool, s.stream, 0)
		insertSegment(t, pool, dir, segRow{stream: s.stream, session: s.session, seq: 0, archive: "uploaded", playback: "failed"})
		openTable(t, u, s.stream, s.session, 0)
	}
	for _, s := range []struct{ stream, session, state string }{
		{"t7c", "S-t7c", "ending"},
		{"t7d", "S-t7d", "ended"},
	} {
		openSession(t, pool, s.session, s.stream, s.state, false)
		insertSegment(t, pool, dir, segRow{stream: s.stream, session: s.session, seq: 0, archive: "uploaded", playback: "pending"})
	}

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	t.Run("ending_은_집는다", func(t *testing.T) {
		assertPlaybackUploaded(t, pool, "t7a", 0)
		assertInitConfirmed(t, pool, "t7c", "S-t7c")
	})
	t.Run("ended_는_집지_않는다", func(t *testing.T) {
		if _, state, _ := segmentState(t, pool, "t7b", 0); state != "failed" {
			t.Errorf("t7b/0 ③ = %s, want failed 그대로", state)
		}
		if _, _, _, confirmed := sessionInitOf(t, pool, "S-t7d"); confirmed {
			t.Error("S-t7d init 이 확정됐다 — ended 세션을 집었다")
		}
		keys := putKeys(put)
		for _, k := range []string{segKeyOf(t, "t7b", 0), "dvr/t7d/init/S-t7d.mp4"} {
			if keys[k] {
				t.Errorf("PUT %s — ended 세션의 것을 올렸다", k)
			}
		}
	})
}

// T13 — 컷오프 미만 면제(계약 5-5 6항 ⑴·⑵): 컷오프 미만 행과 컷오프가 없는 스트림의 행은 ③
// 재수거도 ③ 잔량 집계도 하지 않는다. 스위퍼는 두 조회를 ③ 라벨로 소비한다. 잡는 결함: 한 축이라도
// 술어가 빠지면 재수거는 과거 행을 끝없이 집고(원본이 사라졌으면 영구 실패), 알람은 영구 적체를
// 보고한다.
func TestSweepPGExemptsRowsOutsideCutoff(t *testing.T) {
	pool := newSweepPool(t)
	// 임계를 음수로 두어 잔량이 얼마든 upload_backlog 가 나오게 한다 — ③ 잔량 수치를 읽기 위해서다.
	u, cap, dir, put := newSweepPGUploader(t, pool, func(o *Options) { o.BacklogWarn = -1 })
	openSession(t, pool, "S-t13", "t13", "live", true)
	seedCutoff(t, pool, "t13", 2)
	for seq := int64(0); seq <= 3; seq++ {
		insertSegment(t, pool, dir, segRow{stream: "t13", session: "S-t13", seq: seq, archive: "uploaded", playback: "pending"})
	}
	openSession(t, pool, "S-t13n", "t13n", "live", true) // 컷오프가 없는 스트림
	for seq := int64(0); seq <= 1; seq++ {
		insertSegment(t, pool, dir, segRow{stream: "t13n", session: "S-t13n", seq: seq, archive: "uploaded", playback: "pending"})
	}
	// 표는 두 회차 모두 seq 0 부터 연다 — 면제가 깨지면 그 행은 표로 올라가 버려 차이가 드러난다.
	openTable(t, u, "t13", "S-t13", 0)
	openTable(t, u, "t13n", "S-t13n", 0)

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	for _, seq := range []int64{2, 3} {
		assertPlaybackUploaded(t, pool, "t13", seq)
	}
	keys := putKeys(put)
	for _, r := range []struct {
		stream string
		seq    int64
	}{{"t13", 0}, {"t13", 1}, {"t13n", 0}, {"t13n", 1}} {
		if _, state, _ := segmentState(t, pool, r.stream, r.seq); state != "pending" {
			t.Errorf("%s/%d ③ = %s, want pending 그대로 — 면제 행을 집었다", r.stream, r.seq, state)
		}
		if keys[segKeyOf(t, r.stream, r.seq)] {
			t.Errorf("%s/%d 를 PUT 했다 — 면제 행이다", r.stream, r.seq)
		}
	}
	// ③ 잔량은 적용 행(t13 의 2·3)만 센다 — 회차 집계는 워커가 돌기 전 시점이다.
	backlog := ofAxis(cap.find("upload_backlog"), index.AxisPlayback)
	if len(backlog) != 1 || backlog[0].attrs["pending"] != int64(2) || backlog[0].attrs["failed"] != int64(0) {
		t.Errorf("upload_backlog(axis=playback) = %v, want 1건 pending 2 · failed 0", backlog)
	}
}

// ⑦-1(계획 r22→r23 처분표 — 커밋 4 통합 테스트 필수)을 실 장부로: 실시간 init 요청이 큐 포화로 접수
// 거부돼 세션의 init_uploaded_at 이 NULL 로 남는다 → 실시간 ③ 은 기대 init 을 몰라 보류된다 → 스위퍼
// init 벌이 그 세션을 집어(조회 행은 꼬리 예외가 없다 — IsTail=false) 세션 최신 조각에서 init 을 만들어
// 올리고 CAS 가 확정한다 → sessionInit 이 채워져 보류 ③ 이 다시 들어 장부에 확정된다.
func TestSweepPGInitRecoveryReleasesHeldPlayback(t *testing.T) {
	pool := newSweepPool(t)
	u, cap, dir, _ := newSweepPGUploader(t, pool, func(o *Options) { o.QueueLen = 3 })
	openSession(t, pool, "S-71", "s71", "live", false)
	seedCutoff(t, pool, "s71", 0)
	var live []index.UploadTarget
	for seq := int64(0); seq <= 1; seq++ {
		path, size := insertSegment(t, pool, dir, segRow{stream: "s71", session: "S-71", seq: seq, archive: "uploaded", playback: "pending"})
		tgt := playbackTarget(t, "s71", seq, "S-71", path, size)
		tgt.PlaybackPos = fixture4sMtxi // 실시간 도장 = mtxi + 보정값 0
		live = append(live, tgt)
	}
	first, firstSize := live[0].LocalPath, live[0].Bytes

	// 실시간 init 요청 — 큐가 차 있어 접수되지 않는다.
	for i := int64(0); i < 3; i++ {
		u.RequestUpload(newTarget("filler", i, "/recordings/filler/x.mp4", 1, false))
	}
	if u.RequestUpload(index.UploadTarget{StreamID: "s71", Axis: index.AxisInit, SessionID: "S-71", LocalPath: first, Bytes: firstSize, IsTail: true}) {
		t.Fatal("큐가 찼는데 init 요청이 접수됐다")
	}
	u.drainQueue()

	// 실시간 ③ 두 조각 — 기대 init 을 몰라 보류된다(표는 이 요청이 연다).
	for _, tgt := range live {
		if !u.RequestUpload(tgt) {
			t.Fatalf("③ 요청이 접수되지 않았다 seq %d", tgt.Seq)
		}
	}
	runQueue(u)
	for _, tgt := range live {
		if _, state, _ := segmentState(t, pool, "s71", tgt.Seq); state != "pending" {
			t.Fatalf("init 확정 전인데 ③ seq %d = %s", tgt.Seq, state)
		}
	}

	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	assertInitConfirmed(t, pool, "s71", "S-71")
	if got := uploadedFrom(cap, index.AxisInit, OriginSweep); len(got) != 1 {
		t.Errorf("init segment_uploaded(origin=sweep) = %v, want 1건", got)
	}
	for _, tgt := range live {
		assertPlaybackUploaded(t, pool, "s71", tgt.Seq)
	}
	if got := uploadedFrom(cap, index.AxisPlayback, OriginLive); len(got) != 2 {
		t.Errorf("③ segment_uploaded(origin=live) = %v, want 2건 — 보류 재요청이 올렸다", got)
	}
}

// emptyTrackProducer 는 이름이 name 인 녹화 파일만 ErrEmptyTrack 으로 거절하고 나머지는 실물 재포장에
// 맡기는 생산자다 — 첫 조각만 소리가 늦게 시작해(빈 소리 트랙) 같은 입력이면 늘 같은 산출 실패인 회차를
// 흉내 낸다.
type emptyTrackProducer struct{ name string }

func (p emptyTrackProducer) Produce(ctx context.Context, req playback.Request) (playback.Output, error) {
	if f, ok := req.Input.(interface{ Name() string }); ok && filepath.Base(f.Name()) == p.name {
		return playback.Output{}, fmt.Errorf("%w: 트랙 2", playback.ErrEmptyTrack)
	}
	return playback.Remuxer{}.Produce(ctx, req)
}

// init 스위퍼의 원천은 세션의 최신 조각이다(계획 r25 A3 「init 파급」 · Phase 3 r4 cc #2). 새 회차의 첫
// 조각만 결정적으로 실패하면(빈 트랙 — 소리가 늦게 시작한 첫 조각) 그 조각으로 만든 init 은 매번 같은
// 실패로 끝나지만, 다음 조각이 오면 백오프가 풀린 회차에서 그 조각으로 확정된다. 잡는 결함: 원천을 첫
// 조각에 묶으면 백오프가 풀릴 때마다 같은 실패를 되풀이해 그 회차의 init 이 영영 확정되지 않는다 — 그
// 회차 ③ 은 전부 대조 보류로 남고 회복은 재기동뿐이다.
func TestSweepPGInitRecoversFromUnproducibleFirstSegment(t *testing.T) {
	pool := newSweepPool(t)
	u, cap, dir, clock := newPlaybackUploader(t, index.NewUploadStore(pool), &fakePutter{}, emptyTrackProducer{name: "seg0.mp4"}, nil)
	openSession(t, pool, "S-e1", "e1", "live", false)
	insertSegment(t, pool, dir, segRow{stream: "e1", session: "S-e1", seq: 0, archive: "uploaded", playback: "pending"})

	u.sweepOnce(context.Background(), nil)
	runQueue(u)
	if n := len(ofAxis(cap.find("upload_failed"), index.AxisInit)); n != 1 {
		t.Fatalf("첫 조각의 init upload_failed = %d건, want 1건 (%s)", n, cap.dump())
	}
	if _, _, _, confirmed := sessionInitOf(t, pool, "S-e1"); confirmed {
		t.Fatal("산출이 안 되는 첫 조각뿐인데 init 이 확정됐다")
	}

	insertSegment(t, pool, dir, segRow{stream: "e1", session: "S-e1", seq: 1, archive: "uploaded", playback: "pending"})
	clock.advance(time.Hour) // 첫 실패가 건 키 백오프(BackoffMax 30분)를 넘긴다
	u.sweepOnce(context.Background(), nil)
	runQueue(u)

	assertInitConfirmed(t, pool, "e1", "S-e1")
}
