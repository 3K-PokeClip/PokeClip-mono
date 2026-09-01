package recording

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"
)

// ErrSettleTimeout 은 MaxSettle 안에 크기가 안정되지 않았음을 뜻한다.
// 이때도 마지막 관측값(FileInfo)은 함께 돌려준다 — 호출자가 크기 변화를 판정해야 하기 때문이다.
var ErrSettleTimeout = errors.New("파일 크기가 안정되지 않았다")

// SettleOptions 는 "파일이 다 써졌는지" 판정하는 대기 규칙의 설정값이다.
type SettleOptions struct {
	// PollInterval 은 파일 크기를 다시 보는 주기다.
	PollInterval time.Duration
	// SettleWait 은 크기가 이만큼 불변이면 완성으로 보는 시간이다.
	//
	// 기본 2s 의 근거: MediaMTX 는 영상을 모았다가 recordPartDuration 주기로 디스크에 쓴다.
	// 쓰기와 쓰기 사이의 공백을 "다 썼다"로 오해하면 절반짜리 파일을 완성으로 판정하므로
	// 공백 길이의 2배는 기다려야 한다(설계 2.1절 "쓰기 주기의 2배 이상").
	// v1.19.3·v1.20.1 업스트림 기본값 recordPartDuration = 1s(conf/path.go 확인)이므로 2 x 1s = 2s.
	SettleWait time.Duration
	// MaxSettle 을 넘기면 포기하고 ErrSettleTimeout 을 돌려준다.
	MaxSettle time.Duration
	// Stat 은 stat 주입점이다(POK-168 M1 — 처리 FS 격리). 비어 있으면 os.Stat 를 쓴다.
	// 채우는 소유자는 indexer.New 의 단일 생성점(newSettleOptions)뿐이며,
	// 워처 호출점은 이 필드를 쓰지 않는다 — 미지정 = 현행 그대로(무변경).
	Stat func(string) (os.FileInfo, error)
}

// stat 은 주입점이 비었을 때 os.Stat 로 되돌리는 접근자다.
func (o SettleOptions) stat(path string) (os.FileInfo, error) {
	if o.Stat != nil {
		return o.Stat(path)
	}
	return os.Stat(path)
}

// DefaultSettleOptions 는 설계 2.1절의 기본값이다.
func DefaultSettleOptions() SettleOptions {
	return SettleOptions{
		PollInterval: 500 * time.Millisecond,
		SettleWait:   2 * time.Second,
		MaxSettle:    30 * time.Second,
	}
}

// Settle 은 크기가 SettleWait 동안 불변일 때까지 폴링한다.
//
// 즉시 반환 조건: (mtime 나이 >= SettleWait) AND (연속 두 stat 크기 동일).
// 이미 한참 전에 마지막으로 쓰였고 크기도 그대로면 더 기다릴 이유가 없다(정상 경로 지연 0).
//
// 단 mtime 나이가 음수(파일 mtime 이 미래 — 시계 스큐)면 즉시 반환하지 않고 폴링 경로를 탄다.
// 미래 시각이면 나이 계산 자체를 믿을 수 없으므로 안전한 폴링으로 되돌린다.
func Settle(ctx context.Context, path string, opt SettleOptions) (os.FileInfo, error) {
	first, err := opt.stat(path)
	if err != nil {
		return nil, fmt.Errorf("세그먼트 stat 실패 %q: %w", path, err)
	}

	if fi, ok := settledImmediately(path, first, opt); ok {
		return fi, nil
	}

	deadline := time.Now().Add(opt.MaxSettle)
	last := first
	lastChange := time.Now()

	ticker := time.NewTicker(opt.PollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return last, ctx.Err()
		case <-ticker.C:
		}

		fi, err := opt.stat(path)
		if err != nil {
			return last, fmt.Errorf("세그먼트 stat 실패 %q: %w", path, err)
		}
		if fi.Size() != last.Size() {
			lastChange = time.Now()
		}
		last = fi

		if time.Since(lastChange) >= opt.SettleWait {
			return last, nil
		}
		if time.Now().After(deadline) {
			return last, fmt.Errorf("%w: path=%q size=%d", ErrSettleTimeout, path, last.Size())
		}
	}
}

// settledImmediately 는 대기 없이 통과시켜도 되는지 판정한다.
func settledImmediately(path string, first os.FileInfo, opt SettleOptions) (os.FileInfo, bool) {
	age := time.Since(first.ModTime())
	// 음수 = mtime 이 미래(시계 스큐). 나이를 믿을 수 없으니 폴링으로 되돌린다.
	if age < 0 || age < opt.SettleWait {
		return nil, false
	}
	second, err := opt.stat(path)
	if err != nil || second.Size() != first.Size() {
		return nil, false
	}
	return second, true
}
