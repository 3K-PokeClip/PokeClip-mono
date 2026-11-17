//go:build restorefault

package upload

// T-14 실장부 · 실 S3 몫(POK-196 PDT 계획 4절 c10b · 9절 T-14) — Go PG(PG_DSN) · 로컬 MinIO(MINIO_ENDPOINT, 평문 http · 루프백)
// · 고장 주입(이 빌드 태그의 faultObjects — S3 지연 · 접근 거부). 돌리는 법:
//
//	REQUIRE_PG=1 PG_DSN=… MINIO_ENDPOINT=http://127.0.0.1:55445 MINIO_USER=… MINIO_PASSWORD=… \
//	  go test -tags restorefault -run 'RestoreIT' ./internal/upload/
//
// 판정은 셋이다 — 실장부의 구멍 열거 · 닻 원천이 메모리 시험대와 같은 결과를 내는가, S3 가 10초씩 멈추거나 접근을 거부하는 동안
// 인덱서가 부르는 두 통로(RequestUpload · RestoredLink)가 막히지 않는가(장부 커밋 지연 0 의 구조 — 인덱서 루프는 이 둘만 부른다),
// S3 가 돌아오면 복원이 따라잡는가.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

const itBucket = "pdt-c10b-restore"

// minioOptions 는 시험 MinIO 의 접속값이다 — 없으면 건너뛴다(REQUIRE_MINIO=1 이면 실패).
func minioOptions(t *testing.T) S3Options {
	t.Helper()
	ep := os.Getenv("MINIO_ENDPOINT")
	if ep == "" {
		if os.Getenv("REQUIRE_MINIO") == "1" {
			t.Fatal("REQUIRE_MINIO=1 인데 MINIO_ENDPOINT 가 비어 있다")
		}
		t.Skip("MINIO_ENDPOINT 없음")
	}
	t.Setenv("AWS_ACCESS_KEY_ID", os.Getenv("MINIO_USER"))
	t.Setenv("AWS_SECRET_ACCESS_KEY", os.Getenv("MINIO_PASSWORD"))
	t.Setenv("AWS_SESSION_TOKEN", "")
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(t.TempDir(), "none"))
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(t.TempDir(), "none"))
	return S3Options{Bucket: itBucket, Region: "us-east-1", Endpoint: ep, ForcePathStyle: true}
}

// seedClient 는 객체를 놓는 시험용 S3 클라이언트다 — 되감기 가능한 본문이라 평문 http 에서도 서명된다(업로더 PUT 은 쓰지 않는다).
func seedClient(t *testing.T, opt S3Options) *s3.Client {
	t.Helper()
	cfg, err := awsconfig.LoadDefaultConfig(context.Background(), awsconfig.WithRegion(opt.Region),
		awsconfig.WithBaseEndpoint(opt.Endpoint))
	if err != nil {
		t.Fatal(err)
	}
	c := s3.NewFromConfig(cfg, func(o *s3.Options) { o.UsePathStyle = true })
	if _, err := c.CreateBucket(context.Background(), &s3.CreateBucketInput{Bucket: aws.String(opt.Bucket)}); err != nil {
		var owned interface{ ErrorCode() string }
		if !errors.As(err, &owned) || (owned.ErrorCode() != "BucketAlreadyOwnedByYou" && owned.ErrorCode() != "BucketAlreadyExists") {
			t.Fatalf("버킷 만들기 실패: %v", err)
		}
	}
	return c
}

// faultObjects 는 실 S3 Putter · Getter 앞의 고장 주입이다 — delay 가 있으면 GET · HEAD 가 그만큼 멈추고(문맥이 끊으면 그때
// 끝난다), deny 면 접근 거부다. 켜고 끄기는 시험이 한다.
type faultObjects struct {
	Putter
	inner Getter
	mu    sync.Mutex
	delay time.Duration
	deny  bool
	calls int
}

func (f *faultObjects) fault() (time.Duration, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	return f.delay, f.deny
}

func (f *faultObjects) set(delay time.Duration, deny bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.delay, f.deny = delay, deny
}

func (f *faultObjects) wait(ctx context.Context) error {
	delay, deny := f.fault()
	if deny {
		return errors.New("api error AccessDenied: Access Denied(주입)")
	}
	select {
	case <-time.After(delay):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (f *faultObjects) Get(ctx context.Context, key string) ([]byte, error) {
	if err := f.wait(ctx); err != nil {
		return nil, err
	}
	return f.inner.Get(ctx, key)
}

func (f *faultObjects) Head(ctx context.Context, key string) (int64, bool, error) {
	if err := f.wait(ctx); err != nil {
		return 0, false, err
	}
	return f.inner.Head(ctx, key)
}

// pgLedger 는 실장부 위의 ChainLedger 다(cmd 배선 restoreStore 와 같은 꼴).
type pgLedger struct {
	index.UploadStore
	pool *pgxpool.Pool
}

func (l pgLedger) PlaybackHoles(ctx context.Context, sessionID string) ([]index.PlaybackHole, error) {
	return index.LoadPlaybackHoles(ctx, l.pool, sessionID)
}

func (l pgLedger) PlaybackChainRows(ctx context.Context, streamID string, from, to int64) ([]index.PlaybackChainRow, error) {
	return index.LoadPlaybackChainRows(ctx, l.pool, streamID, from, to)
}

// itBench 는 실장부 · 실 S3 시험대다.
type itBench struct {
	t      *testing.T
	pool   *pgxpool.Pool
	seed   *s3.Client
	faults *faultObjects
	u      *Uploader
	cap    *logCapture
	dir    string
	raw    []byte
}

func newITBench(t *testing.T, tune func(*Options)) *itBench {
	t.Helper()
	opt := minioOptions(t)
	pool := newSweepPool(t)
	seed := seedClient(t, opt)
	put, ok, err := NewS3Putter(context.Background(), opt, newLogCapture().logger())
	if err != nil || !ok {
		t.Fatalf("NewS3Putter = (%v, %v)", ok, err)
	}
	raw, err := os.ReadFile(fixture4sPath)
	if err != nil {
		t.Fatal(err)
	}
	b := &itBench{t: t, pool: pool, seed: seed, faults: &faultObjects{Putter: put, inner: put.(Getter)}, raw: raw}
	root, dir := newRoot(t)
	o := DefaultOptions(root, dir)
	o.Producer = playback.Remuxer{}
	if tune != nil {
		tune(&o)
	}
	b.cap, b.dir = newLogCapture(), dir
	b.u = New(pgLedger{index.NewUploadStore(pool), pool}, b.faults, o, b.cap.logger())
	return b
}

// put 은 시험 객체를 놓는다.
func (b *itBench) put(key string, body []byte) {
	b.t.Helper()
	_, err := b.seed.PutObject(context.Background(), &s3.PutObjectInput{Bucket: aws.String(itBucket), Key: aws.String(key),
		Body: bytes.NewReader(body)})
	if err != nil {
		b.t.Fatalf("객체 놓기 실패 %s: %v", key, err)
	}
}

// add 는 회차 session · 스트림 stream 의 행 seq 를 실장부 · 디스크 · MinIO 에 놓는다(녹화기 recA · 번호 seq+1 · 영상 칸이 맞닿는
// mtxi). ③ uploaded 면 그 행의 ③ 객체를 실시간 도장(= mtxi — 리셋이 없는 회차)으로 만들어 놓는다.
func (b *itBench) add(stream, session string, seq int64, playbackState index.UploadState) time.Duration {
	b.t.Helper()
	st0, _ := playback.ReadTiming(bytes.NewReader(b.raw))
	dts := 600*time.Second + time.Duration(seq)*(st0.Video.End-st0.Video.Start)
	src := withMtxi(b.t, b.raw, recA, uint64(seq+1), dts)
	path := filepath.Join(b.dir, stream, fmt.Sprintf("seg%d.mp4", seq))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		b.t.Fatal(err)
	}
	if err := os.WriteFile(path, src, 0o644); err != nil {
		b.t.Fatal(err)
	}
	archiveKey := index.S3Key(stream, seq, fixtureWall)
	b.put(archiveKey, src)
	playKey := segKeyOf(b.t, stream, seq)
	if playbackState == index.UploadStateUploaded {
		out, err := playback.Remuxer{}.Produce(context.Background(), playback.Request{Input: bytes.NewReader(src), PlaybackPos: dts})
		if err != nil {
			b.t.Fatal(err)
		}
		b.put(playKey, out.Seg)
	}
	first, end := dts+st0.Video.Start, dts+st0.Video.End
	_, err := b.pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id,
     playback_pdt, playback_s3_key, playback_upload_state,
     timing_wall_guess, timing_recorder_id, timing_segment_number, timing_video_first_ns, timing_video_end_ns)
VALUES ($1, $2, $3, now() - interval '10 minutes', $4, $5, $6, 'uploaded', $7, $8,
        now() - interval '10 minutes', $9, $10, now() - interval '10 minutes', $11, $12, $13, $14)`,
		stream, seq, seq*4000, int32((end - first).Milliseconds()), archiveKey, path, int64(len(src)), session,
		playKey, string(playbackState), recA, seq+1, int64(first), int64(end))
	if err != nil {
		b.t.Fatalf("행 놓기 실패 %s/%d: %v", stream, seq, err)
	}
	return dts
}

// queued 는 큐에 든 ③ 작업을 꺼낸다.
func (b *itBench) queued() map[int64]index.UploadTarget {
	out := map[int64]index.UploadTarget{}
	for {
		select {
		case j := <-b.u.queue:
			b.u.gate.releaseInflight(j.key())
			out[j.target.Seq] = j.target
		default:
			return out
		}
	}
}

// 실장부 · 실 S3 에서 구멍 열거(seq 9 uploaded → 10 failed → 11 uploaded → 12 pending): 두 구멍을 각자의 닻으로 되살리고, 닻의
// ② 는 로컬에서, 닻의 ③ 은 MinIO 에서 읽는다. 다시 넣은 도장 = 실시간 도장(리셋 없는 회차라 mtxi 그대로).
func TestRestoreITEnumeratesHolesOnRealLedgerAndS3(t *testing.T) {
	b := newITBench(t, nil)
	b.u.armForSweepTest()
	openSession(t, b.pool, "S-it", "itstream", "live", false)
	seedCutoff(t, b.pool, "itstream", 0)
	want := map[int64]time.Duration{}
	for seq, st := range map[int64]index.UploadState{9: up, 10: failed, 11: up, 12: pending} {
		want[seq] = b.add("itstream", "S-it", seq, st)
	}

	b.u.restoreSession(restoreWake{streamID: "itstream", sessionID: "S-it"})

	jobs := b.queued()
	if len(jobs) != 2 || jobs[10].PlaybackPos != want[10] || jobs[12].PlaybackPos != want[12] {
		t.Fatalf("작업 = %+v, want seq 10 · 12 실시간 도장 (%s)", jobs, b.cap.dump())
	}
	if l, ok := b.u.RestoredLink("S-it"); !ok || l.Seq != 12 {
		t.Errorf("복원 링크 = (%+v, %v), want seq 12", l, ok)
	}
}

// 스트림 20개가 한꺼번에 재기동 뒤 위치 모름으로 깨우고 S3 가 GET · HEAD 마다 10초 멈추거나(지연) 접근을 거부하는(거부) 동안,
// 인덱서가 부르는 두 통로는 막히지 않는다 — 부를 때마다의 최대 지연을 잰다(장부 커밋 지연 0 의 구조: 인덱서 루프는 이 둘만
// 부르고, 복원은 업로더의 고루틴 하나에서 돈다). 장부 행은 바뀌지 않는다(③ 상태 그대로). S3 가 돌아오면 복원이 따라잡아
// 20 회차 모두 복원 링크가 생긴다(거부 갈래는 세션 단위 백오프 뒤 다음 깨움에서).
func TestRestoreITIndexerPathNeverWaitsOnS3(t *testing.T) {
	for _, mode := range []string{"delay", "deny"} {
		t.Run(mode, func(t *testing.T) {
			b := newITBench(t, func(o *Options) { o.SweepEvery = 200 * time.Millisecond })
			const streams = 20
			for i := range streams {
				stream, session := fmt.Sprintf("it%02d", i), fmt.Sprintf("S-it%02d", i)
				openSession(t, b.pool, session, stream, "live", false)
				seedCutoff(t, b.pool, stream, 0)
				b.add(stream, session, 0, up)
				b.add(stream, session, 1, pending)
			}
			b.faults.set(10*time.Second, mode == "deny")
			b.u.Start(context.Background())
			defer b.u.Shutdown()

			worst := b.callIndexerPath(streams, 3*time.Second)
			b.faults.set(0, false)
			deadline := time.Now().Add(40 * time.Second)
			for i := 0; i < streams && time.Now().Before(deadline); {
				if _, ok := b.u.RestoredLink(fmt.Sprintf("S-it%02d", i)); ok {
					i++
					continue
				}
				b.callIndexerPath(streams, 300*time.Millisecond) // 백오프가 끝난 회차를 다시 깨운다
			}

			t.Logf("%s: 인덱서 통로 최대 지연 %v · GET/HEAD %d번", mode, worst, b.faults.calls)
			if worst > 5*time.Millisecond {
				t.Errorf("%s: 인덱서 통로 최대 지연 %v, want ≤ 5ms — S3 를 기다렸다", mode, worst)
			}
			for i := range streams {
				if _, ok := b.u.RestoredLink(fmt.Sprintf("S-it%02d", i)); !ok {
					t.Errorf("%s: S-it%02d 복원 링크가 없다 — S3 회복 뒤 따라잡지 못했다 (%s)", mode, i, b.cap.dump())
				}
				if _, state, _ := segmentState(t, b.pool, fmt.Sprintf("it%02d", i), 1); state != "pending" {
					t.Errorf("%s: it%02d/1 ③ = %s, want pending 그대로", mode, i, state)
				}
			}
		})
	}
}

// callIndexerPath 는 for 동안 스트림마다 위치 모름 요청과 복원 링크 읽기를 되풀이하고, 한 번 부름의 최대 지연을 돌려준다.
func (b *itBench) callIndexerPath(streams int, d time.Duration) time.Duration {
	var worst time.Duration
	for end := time.Now().Add(d); time.Now().Before(end); {
		for i := range streams {
			tgt := index.UploadTarget{StreamID: fmt.Sprintf("it%02d", i), Axis: index.AxisPlayback, Seq: 1,
				SessionID: fmt.Sprintf("S-it%02d", i), PosUnknown: true}
			start := time.Now()
			b.u.RequestUpload(tgt)
			b.u.RestoredLink(tgt.SessionID)
			worst = max(worst, time.Since(start))
		}
		time.Sleep(20 * time.Millisecond)
	}
	return worst
}
