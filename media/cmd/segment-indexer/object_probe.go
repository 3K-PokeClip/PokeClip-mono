package main

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/publish"
)

// 객체 탐침(-rewind-object-probe · 계획 ①-9 · 설계 r7 8-2 판정 D)은 media 역할 자격으로 VOD 사본 경로의 객체 연산을 한 번씩
// 해 보고 종료 코드로 판정한다 — 원본 Put → 사본 Copy → 사본 Head → 둘 Delete → 둘 Head 없음. 판정 D 를 통과하기 전에는
// 이관으로 가지 않는다(지우지 못하는 상태로 사본을 쌓지 않는다).
//
// 탐침이 만들고 지우는 키는 시험 스트림 objectProbeStream 의 조각 0 원본(dvr/…/seg/000000.m4s)과 그 사본(vod/…) 둘뿐이다.
// 스트림 이름이 pcprobe- 로 시작하지 않으면 아무것도 보내지 않는다(E31) — 실제 스트림 ID 는 이 접두를 쓰지 않고, 저장소의
// Delete 도 dvr/ 쪽은 pcprobe- 원본만 받는다(publish.Store.Delete).

// objectProbeStream 은 탐침의 시험 스트림 이름이다(설계 8-2 의 키 예 그대로).
const objectProbeStream = "pcprobe-pok525"

// objectProbePrefix 는 탐침 스트림 이름의 접두다 — publish 의 탐침 원본 예외(checkDelete)와 같은 값이다.
const objectProbePrefix = "pcprobe-"

// 탐침의 종료 코드다(설계 r7 7절 신호 표 · E31 · E34). 1 은 쓰지 않는다 — 사이드카 기동 실패(main)와 섞이지 않게 한다.
const (
	exitProbeOK        = 0 // 다섯 단계 통과 · 남은 탐침 키 없음
	exitProbeConfig    = 2 // 설정 — 환경 변수 · 버킷 · S3 클라이언트
	exitProbeAuth      = 3 // 인증 · 권한 거부 — 로그의 stage 가 put · copy · head · delete 중 어느 연산인지 말한다
	exitProbeTransport = 4 // 망 · 시한 · 서버 오류(Put · Copy · Head) — 결과 모름
	exitProbeName      = 6 // 시험 스트림 이름 검사 실패 — 아무것도 보내지 않았다
	exitProbeMismatch  = 7 // Head 가 기대와 다름 — 사본이 없거나 메타가 원본과 다름 · 지운 뒤에도 남음
	exitProbeCleanup   = 8 // 끝 정리(Delete) 실패 — 탐침 키가 남았을 수 있다(다음 실행의 시작 정리가 지운다)
	exitProbeStale     = 9 // 시작 정리 실패 — 앞 실행이 남긴 탐침 키를 못 지웠다
)

// 탐침의 구간이다 — 실패 줄의 phase 값이다.
const (
	phaseStale   = "stale"   // 시작 정리 — 앞 실행이 남긴 탐침 키를 지운다
	phaseProbe   = "probe"   // 원본 Put → 사본 Copy → 사본 Head
	phaseCleanup = "cleanup" // 끝 정리 — 둘 Delete → 둘 Head 없음
)

// objectProbeStepTimeout 은 저장소 요청 한 번의 시한이다 — Store 는 스스로 시간을 재지 않는다(publish.Store 설명).
const objectProbeStepTimeout = 10 * time.Second

// objectProbeRunMeta 는 원본에 싣는 사용자 메타 키다. 값은 실행마다 새 난수라, 사본 Head 가 같은 값을 돌려주면 이번 실행의
// 원본을 메타째 복사했다는 뜻이다.
const objectProbeRunMeta = "pcprobe-run"

// runObjectProbe 는 -rewind-object-probe 1회 실행이다 — 설정을 읽고 발행과 같은 S3 클라이언트로 저장소를 만들어
// probeObjects 를 돌린다. 로그는 stdout 에 JSON 한 줄씩 쓴다.
func runObjectProbe(getenv func(string) string, stdout io.Writer) int {
	log := slog.New(slog.NewJSONHandler(stdout, nil))
	cfg, err := config.Load(getenv)
	if err != nil {
		return probeConfigFailed(log, err)
	}
	if cfg.S3.Bucket == "" {
		return probeConfigFailed(log, errors.New("S3 버킷이 비었다 — 탐침할 저장소가 없다"))
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	_, client, err := newPublishS3Client(ctx, cfg)
	if err != nil {
		return probeConfigFailed(log, err)
	}
	if err := requireHTTPS(aws.ToString(client.Options().BaseEndpoint)); err != nil {
		return probeConfigFailed(log, err)
	}
	log.Info("object_probe_started", "bucket", cfg.S3.Bucket, "region", cfg.S3.Region, "stream", objectProbeStream)
	return probeObjects(ctx, publish.NewS3Store(client, cfg.S3.Bucket), objectProbeStream, log)
}

func probeConfigFailed(log *slog.Logger, err error) int {
	log.Error("object_probe_failed", "stage", "config", "exit", exitProbeConfig, "err", err)
	return exitProbeConfig
}

// objectProber 는 탐침 한 번의 상태다 — 저장소와 탐침 키 둘(원본 src · 사본 dst).
type objectProber struct {
	store    publish.Store
	log      *slog.Logger
	src, dst string
}

// probeObjects 는 stream 의 탐침 키로 객체 탐침을 돌리고 종료 코드(exitProbe…)를 돌려준다. 원본을 올린 뒤 실패하면 지울 수
// 있는 한 탐침 키를 지우고 나간다 — 남으면 다음 실행의 시작 정리가 지운다.
func probeObjects(ctx context.Context, store publish.Store, stream string, log *slog.Logger) int {
	src, dst, err := objectProbeKeys(stream)
	if err != nil {
		log.Error("object_probe_failed", "stage", "name", "exit", exitProbeName, "err", err)
		return exitProbeName
	}
	p := objectProber{store: store, log: log, src: src, dst: dst}
	if code := p.cleanStale(ctx); code != exitProbeOK {
		return code
	}
	code := p.copyRoundTrip(ctx)
	if code == exitProbeOK {
		code = p.deleteAndConfirm(ctx)
	}
	if code == exitProbeOK {
		log.Info("object_probe_passed", "src", src, "dst", dst, "exit", exitProbeOK)
	}
	return code
}

// objectProbeKeys 는 시험 스트림 stream 의 탐침 키 둘 — 조각 0 의 ③ 원본과 그 VOD 사본 — 을 만든다. stream 이
// objectProbePrefix 뒤에 이름이 붙은 키 안전 이름이 아니면 오류다.
func objectProbeKeys(stream string) (src, dst string, err error) {
	if len(stream) <= len(objectProbePrefix) || !strings.HasPrefix(stream, objectProbePrefix) {
		return "", "", fmt.Errorf("탐침 스트림 이름 %.64q 는 %s 로 시작하는 시험 이름이 아니다", stream, objectProbePrefix)
	}
	if src, err = playback.SegKey(stream, 0); err != nil {
		return "", "", err
	}
	if dst, err = playback.VODPath(src); err != nil {
		return "", "", err
	}
	return src, dst, nil
}

// cleanStale 은 시작 정리다(E34) — 앞 실행이 강제 종료돼 남긴 탐침 키가 있으면 지우고 없어졌는지 다시 본다.
func (p objectProber) cleanStale(ctx context.Context) int {
	stale, code := p.existing(ctx, phaseStale)
	if code != exitProbeOK || len(stale) == 0 {
		return code
	}
	if err := p.delete(ctx, stale); err != nil {
		return p.fail(phaseStale, "delete", classifyProbeError(err, exitProbeStale), err)
	}
	left, code := p.existing(ctx, phaseStale)
	if code != exitProbeOK {
		return code
	}
	if len(left) > 0 {
		return p.fail(phaseStale, "head", exitProbeStale, fmt.Errorf("지운 뒤에도 남았다: %s", strings.Join(left, " ")))
	}
	p.log.Info("object_probe_stale_cleaned", "keys", strings.Join(stale, " "))
	return exitProbeOK
}

// copyRoundTrip 은 원본 Put → 사본 Copy → 사본 Head 다. 실패하면 탐침 키를 지우려 해 보고 그 실패의 종료 코드를 돌려준다.
func (p objectProber) copyRoundTrip(ctx context.Context) int {
	run := rand.Text()
	code := p.putCopyHead(ctx, run)
	if code != exitProbeOK {
		if err := p.delete(ctx, []string{p.dst, p.src}); err != nil {
			p.log.Error("object_probe_cleanup_failed", "keys", p.dst+" "+p.src, "err", err)
		}
	}
	return code
}

func (p objectProber) putCopyHead(ctx context.Context, run string) int {
	put, cancel := context.WithTimeout(ctx, objectProbeStepTimeout)
	_, err := p.store.Put(put, p.src, publish.IfAbsent(), []byte("p"), map[string]string{objectProbeRunMeta: run})
	cancel()
	if err != nil {
		return p.fail(phaseProbe, "put", classifyProbeError(err, exitProbeTransport), err)
	}
	p.log.Info("object_probe_put", "key", p.src)

	cp, cancel := context.WithTimeout(ctx, objectProbeStepTimeout)
	err = p.store.Copy(cp, p.src, p.dst)
	cancel()
	if err != nil {
		return p.fail(phaseProbe, "copy", classifyProbeError(err, exitProbeTransport), err)
	}
	p.log.Info("object_probe_copied", "src", p.src, "dst", p.dst)

	st, code := p.head(ctx, phaseProbe, p.dst)
	if code != exitProbeOK {
		return code
	}
	if !st.Exists || st.Meta[objectProbeRunMeta] != run {
		return p.fail(phaseProbe, "head", exitProbeMismatch, fmt.Errorf("사본 %s 가 없거나 메타가 원본과 다르다(exists=%t)", p.dst, st.Exists))
	}
	p.log.Info("object_probe_copy_checked", "key", p.dst)
	return exitProbeOK
}

// deleteAndConfirm 은 끝 정리다 — 사본과 원본을 지우고 둘 다 없어졌는지 Head 로 본다.
func (p objectProber) deleteAndConfirm(ctx context.Context) int {
	keys := []string{p.dst, p.src}
	if err := p.delete(ctx, keys); err != nil {
		return p.fail(phaseCleanup, "delete", classifyProbeError(err, exitProbeCleanup), err)
	}
	p.log.Info("object_probe_deleted", "keys", strings.Join(keys, " "))
	left, code := p.existing(ctx, phaseCleanup)
	if code != exitProbeOK {
		return code
	}
	if len(left) > 0 {
		return p.fail(phaseCleanup, "head", exitProbeMismatch, fmt.Errorf("지운 뒤에도 남았다: %s", strings.Join(left, " ")))
	}
	p.log.Info("object_probe_absent", "keys", strings.Join(keys, " "))
	return exitProbeOK
}

// existing 은 탐침 키 둘 중 저장소에 있는 것을 돌려준다.
func (p objectProber) existing(ctx context.Context, phase string) ([]string, int) {
	var found []string
	for _, k := range []string{p.dst, p.src} {
		st, code := p.head(ctx, phase, k)
		if code != exitProbeOK {
			return nil, code
		}
		if st.Exists {
			found = append(found, k)
		}
	}
	return found, exitProbeOK
}

func (p objectProber) head(ctx context.Context, phase, key string) (publish.Stat, int) {
	hctx, cancel := context.WithTimeout(ctx, objectProbeStepTimeout)
	defer cancel()
	st, err := p.store.Head(hctx, key)
	if err != nil {
		return publish.Stat{}, p.fail(phase, "head", classifyProbeError(err, exitProbeTransport), err)
	}
	return st, exitProbeOK
}

// delete 는 탐침 키를 지운다. 정리는 탐침이 SIGTERM 으로 취소된 뒤에도 시도한다 — 취소를 떼고 시한만 건다.
func (p objectProber) delete(ctx context.Context, keys []string) error {
	dctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), objectProbeStepTimeout)
	defer cancel()
	return p.store.Delete(dctx, keys)
}

// fail 은 실패 한 줄을 남기고 code 를 돌려준다. stage 는 실패한 저장소 연산(put · copy · head · delete)이고 phase 는 그
// 연산이 속한 구간이다.
func (p objectProber) fail(phase, stage string, code int, err error) int {
	p.log.Error("object_probe_failed", "phase", phase, "stage", stage, "exit", code, "err", err)
	return code
}

// probeAuthStatuses · probeAuthCodes 는 인증 · 권한 거부로 읽는 HTTP 상태와 S3 오류 코드다(업로더의 hardStatuses ·
// hardCodes 중 인증 몫).
var (
	probeAuthStatuses = map[int]bool{401: true, 403: true}
	probeAuthCodes    = map[string]bool{"AccessDenied": true, "InvalidAccessKeyId": true, "SignatureDoesNotMatch": true, "ExpiredToken": true}
)

// classifyProbeError 는 err 가 인증 · 권한 거부면 exitProbeAuth, 아니면 fallback 이다. SDK 형(awshttp.ResponseError ·
// smithy.APIError)이 가진 메서드 모양으로 본다 — 저장소 오류는 원 오류를 감싸 돌려준다(publish storeError).
//
// DeleteObjects 는 키별 거부를 상태 200 응답 본문으로 돌려주고, publish.S3Store.Delete 는 그것을 형 없는 오류 문장으로
// 낸다 — 그 경우는 여기서 fallback(끝 정리면 8)이다. 로그의 err 에 S3 코드(AccessDenied)가 실린다.
func classifyProbeError(err error, fallback int) int {
	var withStatus interface{ HTTPStatusCode() int }
	if errors.As(err, &withStatus) && probeAuthStatuses[withStatus.HTTPStatusCode()] {
		return exitProbeAuth
	}
	var withCode interface{ ErrorCode() string }
	if errors.As(err, &withCode) && probeAuthCodes[withCode.ErrorCode()] {
		return exitProbeAuth
	}
	return fallback
}
