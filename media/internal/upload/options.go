// Package upload 는 세그먼트 파일 하나를 S3 에 올리고 그 결과를 장부에 확정한다.
//
// 이 패키지가 소유하는 변경 이유는 하나다 — "재시도·게이트·브레이커·스위퍼·수명 정책이
// 어떻게 바뀌는가". "이 조각이 지금 올릴 대상인가"의 판정은 indexer 가 소유하며,
// 그래서 indexer 는 이 패키지를 임포트하지 않는다(설계 3절).
package upload

import (
	"os"
	"time"
)

// 상수 17개. 값의 소유자는 이 블록 하나다 — env 로 여는 3개(RetryMax·SweepEvery·CircuitMax)만
// Options 필드로 덮어쓴다.
const (
	// defaultRetryMax 는 PUT 재시도 상한이다. AC3-a 의 기대 수치(조각당 upload_retry 3건)와 직결된다
	// — 마지막 시도는 재시도 로그를 남기지 않으므로 RetryMax-1 건이 나온다.
	defaultRetryMax = 4
	// defaultSweepEvery 는 재개 스위프 주기다.
	defaultSweepEvery = 30 * time.Second
	// defaultCircuitMax 는 하드 실패가 연속으로 몇 행 나면 전역 차단할지다. 0 = 브레이커 무효화.
	defaultCircuitMax = 3

	// defaultRetryBase 는 재시도 지수 백오프의 첫 간격이다. 1s+2s+4s = 총 약 7초로,
	// 세그먼트가 4초마다 오는 생성 주기와 균형을 이룬다(결정 8‴).
	defaultRetryBase = time.Second
	// defaultPutTimeout 은 PUT 1회의 상한이다.
	defaultPutTimeout = 30 * time.Second
	// defaultQueueLen 은 작업 큐 용량이다. SweepLimit 의 2배인 것이 G16⁗ 전제 (ii)의 수치 근거다
	// — 한 회차가 1단계와 2단계 페이지를 모두 접수하려면 회차 시작 시 큐가 사실상 비어 있어야 한다.
	defaultQueueLen = 256
	// defaultQueueWarnLen 을 넘으면 회차 말미 요약이 경고로 나온다.
	defaultQueueWarnLen = 64
	// defaultSweepLimit 은 스위프 1페이지 크기다.
	defaultSweepLimit = 128
	// defaultSweepMaxPages 는 한 회차가 도는 최대 페이지 수다.
	defaultSweepMaxPages = 8
	// defaultCursorStallWarn 은 커서가 연속으로 몇 회차 정체하면 따로 경고할지다(L21).
	defaultCursorStallWarn = 5
	// defaultBacklogWarn 은 잔량 경고 임계다.
	defaultBacklogWarn = 30
	// defaultDeferredStreakWarn 은 mark_failed_deferred 가 연속 몇 회를 넘으면 사람이 봐야 하는가다.
	// 임계를 넘어도 새 로그를 만들지 않는다 — deferred_streak 필드 하나로만 드러난다(N-3 관측안).
	defaultDeferredStreakWarn = 5
	// defaultResultBufLen 은 Results 채널 버퍼다. 상한 보장이 아니라 정책값이다 —
	// 워커가 큐를 비우는 동안 생산자가 다시 채우므로 누적 결과는 이 값을 넘을 수 있다(L7).
	defaultResultBufLen = 256

	// defaultTailGrace 는 꼬리를 집기 전에 기다리는 시간이다. indexer 와 같은 값을 쓴다.
	defaultTailGrace = 2 * time.Minute
	// defaultBackoffMax 는 지수 백오프의 상한이다.
	defaultBackoffMax = 30 * time.Minute
	// defaultCircuitCooldown 은 Open 에서 HalfOpen 으로 넘어가는 데 걸리는 시간이다.
	defaultCircuitCooldown = 5 * time.Minute
	// defaultCircuitBlockLog 는 Open 구간의 차단 요약 주기다.
	defaultCircuitBlockLog = time.Minute
	// defaultMarkTimeout 은 마킹 1회의 상한이다. 정상 운영용이며 종료 상한과 무관하다 —
	// 종료 상한은 markCancel 이 보장한다(결정 17″).
	defaultMarkTimeout = 10 * time.Second
	// defaultShutdownGrace 는 진행 중 PUT 의 완주를 봐주는 시간이다.
	defaultShutdownGrace = 4 * time.Second
	// defaultShutdownMarkGrace 는 PUT 취소 후 마킹 완주를 봐주는 시간이다.
	// 4s + 2s = 최악 6초로 docker compose stop 기본 10초 안에 든다.
	defaultShutdownMarkGrace = 2 * time.Second
)

// Options 는 업로더의 판단 기준이다. 앞의 셋은 env 로 열려 있고 나머지는 상수다.
type Options struct {
	// SegmentRoot 는 경로 검증용 문자열이다. 파일을 여는 데는 쓰지 않는다.
	SegmentRoot string
	// Root 는 파일을 여는 유일한 손잡이다. os.Root 는 루트 밖 이탈만 막는다(결정 15″).
	Root *os.Root

	RetryMax   int           // env
	SweepEvery time.Duration // env
	CircuitMax int           // env — 0 이면 브레이커 전체 무효화

	RetryBase          time.Duration
	PutTimeout         time.Duration
	QueueLen           int
	QueueWarnLen       int
	SweepLimit         int
	SweepMaxPages      int
	CursorStallWarn    int
	DeferredStreakWarn int
	ResultBufLen       int
	TailGrace          time.Duration
	BacklogWarn        int64
	BackoffMax         time.Duration
	CircuitCooldown    time.Duration
	CircuitBlockLog    time.Duration
	MarkTimeout        time.Duration
	ShutdownGrace      time.Duration
	ShutdownMarkGrace  time.Duration
}

// DefaultOptions 는 설계 8절의 기본값이다.
func DefaultOptions(root *os.Root, segmentRoot string) Options {
	return Options{
		SegmentRoot:        segmentRoot,
		Root:               root,
		RetryMax:           defaultRetryMax,
		SweepEvery:         defaultSweepEvery,
		CircuitMax:         defaultCircuitMax,
		RetryBase:          defaultRetryBase,
		PutTimeout:         defaultPutTimeout,
		QueueLen:           defaultQueueLen,
		QueueWarnLen:       defaultQueueWarnLen,
		SweepLimit:         defaultSweepLimit,
		SweepMaxPages:      defaultSweepMaxPages,
		CursorStallWarn:    defaultCursorStallWarn,
		DeferredStreakWarn: defaultDeferredStreakWarn,
		ResultBufLen:       defaultResultBufLen,
		TailGrace:          defaultTailGrace,
		BacklogWarn:        defaultBacklogWarn,
		BackoffMax:         defaultBackoffMax,
		CircuitCooldown:    defaultCircuitCooldown,
		CircuitBlockLog:    defaultCircuitBlockLog,
		MarkTimeout:        defaultMarkTimeout,
		ShutdownGrace:      defaultShutdownGrace,
		ShutdownMarkGrace:  defaultShutdownMarkGrace,
	}
}
