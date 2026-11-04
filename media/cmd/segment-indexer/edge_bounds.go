package main

// /dvr 하한 Writer 의 배선(POK-525 설계 3-3 · 8-1 · 계획 Ⓒ-6) — 기동 때 KVS ARN 설정으로 Writer 를 띄우거나 끄고, 되감기
// 발행 성공을 Writer 에 알린다. 정책(올림 · 해제 · 신호)은 rewind/edgebounds 에, 원장은 index.EdgeBoundsLedger 에, KVS 는
// edgebounds/kvsstore 에 있다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/cloudfrontkeyvaluestore"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/edgebounds/kvsstore"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// Writer 의 설계값(설계 3-3 Options). 웹 계약 불변식 「CDN 이 주는 그 회차의 최신 사슬 목록의 첫 조각은 엣지가 막지
// 않는다」가 Margin(60초) · 목록 max-age=1 에 기댄다 — 이 값들을 바꾸면 그 불변식을 다시 점검한다.
const (
	edgeBoundsFlushEvery    = 300 * time.Second
	edgeBoundsReleaseEvery  = 30 * time.Second
	edgeBoundsReleaseSettle = 120 * time.Second
	edgeBoundsMargin        = 60 * time.Second
	edgeBoundsCallTimeout   = 5 * time.Second
	edgeBoundsSizeWarnBytes = 4 << 20
)

// 미해제 감시(ARN 없이 기동 — 설계 3-3 Writer 계약 7 · E10)의 신호다. without_writer 는 set(value=1) · clear(value=0)
// 를 같은 이름 · 같은 등급(ERROR)으로 낸다 — edgebounds 신호 관례(signal.go)와 같다. check_failed(WARN)는 확인 한 번
// (해제 행 삭제 + 미해제 조회)의 실패 구간당 한 줄, released_rows_deleted(INFO)는 해제 행을 지운 확인마다 한 줄이다.
const (
	signalUnreleasedWithoutWriter = "edge_bounds_unreleased_without_writer"
	signalUnreleasedCheckFailed   = "edge_bounds_unreleased_check_failed"
	signalReleasedRowsDeleted     = "edge_bounds_released_rows_deleted"
)

// unreleasedProbeLimit 는 미해제 감시 조회 한 번이 세는 행의 상한이다 — rows 는 이 값에서 멈춘다(있는가가 판정이다).
const unreleasedProbeLimit = 100

// observeFloor 는 발행 성공 하나를 Writer 에 알린다 — roundID 는 그 목록 마지막 행 ③ 키의 회차, msn 은 그 목록의
// EXT-X-MEDIA-SEQUENCE 다. 관측 시각은 Writer 의 시계로 Writer 가 찍는다(0 시각 · 미래 시각이 들어올 자리가 없다).
type observeFloor func(streamID, roundID string, msn int64)

// tickFunc 는 차선 작업의 발행 틱이다(laneWork.tick).
type tickFunc = func(ctx context.Context, st publish.State, in publish.TickInput) publish.Outcome

// edgeBoundsDeps 는 배선의 협력자다 — 운영은 PG 원장 · KVS 클라이언트 · 설계값이고 시험이 가짜 · 짧은 주기로 바꾼다.
type edgeBoundsDeps struct {
	ledger edgebounds.Ledger
	// newStore 는 검증된 ARN(cfg.EdgeBoundsKVSARN)의 KVS 를 연다.
	newStore func(ctx context.Context, kvsARN string) (edgebounds.Store, error)
	// opt 는 Writer 설정이다. 미해제 감시도 ReleaseEvery 주기 · ReleaseSettle 유예 · CallTimeout 상한 · Log 를 쓴다.
	opt edgebounds.Options
}

// startEdgeBounds 는 /dvr 하한 Writer 를 띄우고 발행 성공을 알릴 함수와 정지 함수를 돌려준다. Writer 는 인덱서 풀 위의
// 원장을 쓴다(동시 1 — indexerPoolMaxConns 주석). 끔이면 첫 반환값이 nil 이다. ★ 정지 함수는 pool.Close 보다 먼저
// 돌아야 한다(run 이 풀 뒤에 defer 로 등록한다).
func startEdgeBounds(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, log *slog.Logger) (observeFloor, func()) {
	return launchEdgeBounds(ctx, cfg, edgeBoundsDeps{
		ledger:   index.NewEdgeBoundsLedger(pool),
		newStore: openKVSStore,
		opt:      edgeBoundsOptions(newWriterToken(), log, time.Now),
	})
}

// edgeBoundsOptions 는 Writer 의 설계값이다. writerID 는 해제 표지 값의 앞머리라 프로세스마다 새 토큰이다.
func edgeBoundsOptions(writerID string, log *slog.Logger, now func() time.Time) edgebounds.Options {
	return edgebounds.Options{
		FlushEvery:    edgeBoundsFlushEvery,
		ReleaseEvery:  edgeBoundsReleaseEvery,
		ReleaseSettle: edgeBoundsReleaseSettle,
		Margin:        edgeBoundsMargin,
		CallTimeout:   edgeBoundsCallTimeout,
		SizeWarnBytes: edgeBoundsSizeWarnBytes,
		WriterID:      writerID,
		Log:           log,
		Now:           now,
	}
}

// launchEdgeBounds 는 설정에 따라 셋 가운데 하나다(설계 8-1). 어느 갈래든 기동을 막지 않는다 — 하한은 KVS 사본이라
// Writer 가 없어도 인덱싱 · 업로드 · 발행은 돈다.
//
//	ARN 꼴 밖   ERROR edge_bounds_config_invalid(reason — 원문 없음) → 끔 갈래
//	ARN 빔      INFO edge_bounds_disabled → 미해제 감시(edge_bounds_unreleased_without_writer · 해제 행 정리)만
//	KVS 못 엶   ERROR edge_bounds_degraded → 미해제 감시만
//	그 밖       Writer.Run 고루틴 · INFO edge_bounds_started
func launchEdgeBounds(ctx context.Context, cfg config.Config, d edgeBoundsDeps) (observeFloor, func()) {
	log := d.opt.Log
	if cfg.EdgeBoundsConfigInvalid != "" {
		log.Error("edge_bounds_config_invalid", "reason", cfg.EdgeBoundsConfigInvalid)
	}
	if cfg.EdgeBoundsKVSARN == "" {
		log.Info("edge_bounds_disabled",
			"note", "REWIND_EDGE_BOUNDS_KVS_ARN 이 비었다 — /dvr 하한을 올리지도 풀지도 않는다. 닫힌 미해제 행은 감시만 하고 유예가 지난 해제 행은 지운다")
		return nil, startUnreleasedProbe(ctx, d)
	}
	w, err := newEdgeBoundsWriter(ctx, cfg.EdgeBoundsKVSARN, d)
	if err != nil {
		log.Error("edge_bounds_degraded", "err", err,
			"note", "/dvr 하한 Writer 만 강등된다 — 인덱싱 · 업로드 · 발행은 계속되고 회복은 재기동이다")
		return nil, startUnreleasedProbe(ctx, d)
	}
	log.Info("edge_bounds_started", "kvs_arn", cfg.EdgeBoundsKVSARN, "writer", d.opt.WriterID,
		"flush_every", d.opt.FlushEvery, "release_every", d.opt.ReleaseEvery, "margin", d.opt.Margin)
	stop := runInBackground(ctx, w.Run)
	now := d.opt.Now
	return func(streamID, roundID string, msn int64) { w.Observe(streamID, roundID, msn, now()) }, stop
}

func newEdgeBoundsWriter(ctx context.Context, kvsARN string, d edgeBoundsDeps) (*edgebounds.Writer, error) {
	s, err := d.newStore(ctx, kvsARN)
	if err != nil {
		return nil, err
	}
	return edgebounds.New(d.ledger, s, d.opt)
}

// runInBackground 는 run 을 고루틴으로 띄우고, ctx 를 끊고 run 이 돌아오기를 기다리는 정지 함수를 돌려준다.
func runInBackground(ctx context.Context, run func(context.Context)) func() {
	runCtx, cancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Go(func() { run(runCtx) })
	return func() {
		cancel()
		wg.Wait()
	}
}

// openKVSStore 는 운영 KVS 다. kvsARN 은 설정이 꼴을 본 값(cfg.EdgeBoundsKVSARN)만 받는다 — 끝점 호스트가 ARN 의 계정
// 번호로 정해지므로 검증 밖의 값이 들어오면 요청이 다른 호스트로 간다(Ⓒ-5 보안 L1).
func openKVSStore(ctx context.Context, kvsARN string) (edgebounds.Store, error) {
	client, err := newKVSClient(ctx)
	if err != nil {
		return nil, err
	}
	return kvsstore.New(client, kvsARN), nil
}

// newKVSClient 는 KVS 데이터 평면 클라이언트다. 자격 · 지역은 SDK 기본 체인이다(업로더와 같다).
//
//	재시도   시도 1 — SDK 재시도를 끈다. UpdateKeys 는 판(If-Match)에 묶인 쓰기라 SDK 가 다시 보내면 어느 시도가 닿았는지
//	         가려진다. 재시도는 Writer 하나가 맡는다 — 실패 뒤 Describe 판 대조(경합이면 원장을 다시 읽어 한 번 더 ·
//	         아니면 다음 틱 — Writer 계약 4 · Ⓒ-5 보안 L3). 발행 · 업로드의 S3 클라이언트와 같은 처분이다
//	끝점     SDK 표준 끝점 덮어쓰기(AWS_ENDPOINT_URL · AWS_ENDPOINT_URL_CLOUDFRONT_KEYVALUESTORE · 공유 설정 endpoint_url)를
//	         받지 않는다 — 끝점은 ARN 의 계정 번호로만 정해진다(<계정>.cloudfront-kvs.global.api.aws)
func newKVSClient(ctx context.Context) (*cloudfrontkeyvaluestore.Client, error) {
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		return nil, fmt.Errorf("AWS 설정 적재 실패: %w", err)
	}
	return cloudfrontkeyvaluestore.NewFromConfig(awsCfg, func(o *cloudfrontkeyvaluestore.Options) {
		o.RetryMaxAttempts = 1
		o.BaseEndpoint = nil
	}), nil
}

// startUnreleasedProbe 는 Writer 가 없을 때 원장의 닫힌 미해제 행을 감시한다(설계 3-3 Writer 계약 7 · E10) — 그런 행이
// 있으면 그 회차 VOD 가 끝남 대기에 묶인다(판정식 — 원장 행이 있으면 ending). 확인마다 유예(ReleaseSettle)가 지난 해제
// 행도 지운다(Ⓒ-6b) — 해제 뒤 유예 안에 Writer 가 멈추고 Writer 없는 상자로 넘어가면 그 행을 지울 주체가 없어 같은
// 판정식이 회차를 영구 ending 으로 둔다. 다른 상자 Writer 와 같은 조건의 멱등 DELETE 이고 KVS 는 부르지 않는다.
// 기동 때 한 번 동기로 확인(기동 신호), 그 뒤 ReleaseEvery 마다 다시 본다. 원장 호출 하나는 CallTimeout 안이고 인덱서
// 풀 연결 하나를 잠깐 쥔다.
func startUnreleasedProbe(ctx context.Context, d edgeBoundsDeps) func() {
	p := &unreleasedProbe{ledger: d.ledger, timeout: d.opt.CallTimeout, settle: d.opt.ReleaseSettle, log: d.opt.Log}
	p.check(ctx)
	return runInBackground(ctx, func(ctx context.Context) {
		t := time.NewTicker(d.opt.ReleaseEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
			}
			p.check(ctx)
		}
	})
}

// unreleasedProbe 는 미해제 감시의 상태다 — 한 고루틴(기동 뒤에는 감시 고루틴)만 쓴다.
type unreleasedProbe struct {
	ledger  edgebounds.Ledger
	timeout time.Duration
	settle  time.Duration // 해제 뒤 이만큼 지난 행만 지운다(Writer 의 ReleaseSettle)
	log     *slog.Logger
	set     bool // without_writer 가 섰다
	failing bool // check_failed WARN 을 냈다
}

// check 는 감시 한 번이다 — 유예가 지난 해제 행을 지우고(Writer 해제 틱 앞머리와 같은 순서) 닫힌 미해제 행을 센다.
// 행이 있으면 set(rows) 한 줄, 0 이 되면 clear 한 줄 — 상태가 이어지면 줄을 다시 내지 않는다. 삭제 · 조회 가운데 하나라도
// 실패하면 실패가 이어지는 동안 WARN 한 줄이다(둘 다 성공한 확인이 구간을 끝낸다 — Writer 가 삭제 실패를 해제 패스의
// write_failing 에 싣는 것과 같다). 조회가 실패하면 set 상태는 그대로 둔다(모르는 것을 clear 로 읽지 않는다) — 삭제
// 실패는 조회를 막지 않으므로 set · clear 를 바꾸지 않는다. ctx 가 끝나 실패한 것은 알리지 않는다(종료는 장애가 아니다).
func (p *unreleasedProbe) check(ctx context.Context) {
	delErr := p.deleteReleased(ctx)
	cctx, cancel := context.WithTimeout(ctx, p.timeout)
	rows, err := p.ledger.ClosedUnreleased(cctx, unreleasedProbeLimit)
	cancel()
	if err != nil {
		err = fmt.Errorf("원장 ClosedUnreleased: %w", err)
	}
	if ctx.Err() != nil {
		return
	}
	p.reportFailure(errors.Join(delErr, err))
	if err != nil {
		return
	}
	switch on := len(rows) > 0; {
	case on && !p.set:
		p.set = true
		p.log.Error(signalUnreleasedWithoutWriter, "value", 1, "rows", len(rows),
			"note", "/dvr 하한 Writer 없이 닫힌 미해제 행이 있다 — 그 회차 VOD 가 끝남 대기에 묶인다. REWIND_EDGE_BOUNDS_KVS_ARN 을 채워 재기동한다")
	case !on && p.set:
		p.set = false
		p.log.Error(signalUnreleasedWithoutWriter, "value", 0)
	}
}

// deleteReleased 는 해제됐고 유예가 지난 행을 지운다. 지운 행이 있으면 INFO 한 줄(rows)이다.
func (p *unreleasedProbe) deleteReleased(ctx context.Context) error {
	cctx, cancel := context.WithTimeout(ctx, p.timeout)
	n, err := p.ledger.DeleteReleased(cctx, p.settle)
	cancel()
	if err != nil {
		return fmt.Errorf("원장 DeleteReleased: %w", err)
	}
	if n > 0 {
		p.log.Info(signalReleasedRowsDeleted, "rows", n, "settle_ms", p.settle.Milliseconds())
	}
	return nil
}

// reportFailure 는 확인 한 번의 실패 err 로 check_failed 를 맞춘다 — 실패 구간의 첫 확인에만 WARN 한 줄이다.
func (p *unreleasedProbe) reportFailure(err error) {
	if err == nil {
		p.failing = false
		return
	}
	if !p.failing {
		p.failing = true
		p.log.Warn(signalUnreleasedCheckFailed, "err", err.Error())
	}
}

// observePublished 는 발행 틱 tick 을 감싸, 새 판을 발행한 틱마다 그 목록의 회차 하한 후보를 observe 에 알린다(설계
// 4-1 · E11). 차선 고루틴에서 돈다 — Writer.Observe 는 여러 고루틴에서 불러도 된다. tick 이나 observe 가 nil 이면
// tick 을 그대로 돌려준다(발행 몫 없음 · Writer 없음).
//
// 관측 시각은 틱이 돌아온 뒤 Writer 시계로 찍는다 — 실제 발행 성공보다 조금 늦어 하한 후보가 Margin 을 늦게 지난다(안전한
// 쪽 · 웹 계약 불변식).
func observePublished(tick tickFunc, observe observeFloor) tickFunc {
	if tick == nil || observe == nil {
		return tick
	}
	return func(ctx context.Context, st publish.State, in publish.TickInput) publish.Outcome {
		out := tick(ctx, st, in)
		if roundID, msn, ok := publishedRound(in, out); ok {
			observe(in.Playlist.StreamID, roundID, msn)
		}
		return out
	}
}

// publishedRound 는 틱 결과 out 이 새 판을 발행했을 때 그 목록의 회차와 MSN 이다. 회차는 목록 마지막 행 ③ 키를
// playback.ParseSegKey 로 푼 값이다 — 한 목록에 켬 전(옛 꼴) · 뒤(새 꼴) 행이 섞여도 시청자가 이어 받는 꼬리의 꼴을
// 따른다(E11). 마지막 행이 옛 꼴 · 문법 밖 · 다른 스트림 · 발행본 끝 행과 다른 seq 면 거짓이다(Observe 하지 않음 —
// 하한 키가 없으면 엣지는 통과다). msn 은 발행본의 EXT-X-MEDIA-SEQUENCE(첫 행)다 — 끝 행을 넘기면 하한이 최신 목록의
// 첫 조각을 넘는다.
func publishedRound(in publish.TickInput, out publish.Outcome) (roundID string, msn int64, ok bool) {
	rows := in.Playlist.Rows
	if !out.Published || out.State.Prev == nil || len(rows) == 0 {
		return "", 0, false
	}
	last, prev := rows[len(rows)-1], out.State.Prev
	if last.Seq != prev.PublishedSeq {
		return "", 0, false
	}
	ref, err := playback.ParseSegKey(last.PlaybackS3Key)
	if err != nil || ref.RoundID == "" || ref.StreamID != in.Playlist.StreamID || ref.Seq != last.Seq {
		return "", 0, false
	}
	return ref.RoundID, prev.MediaSequence, true
}
