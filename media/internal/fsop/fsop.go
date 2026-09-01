// Package fsop 은 취소를 받지 않는 개별 파일시스템 호출(os.Stat, 미디어 프로브 등)을
// 짧은 워커로 감싸 시간 상한을 주는 층이다. 멈춘 파일시스템이 이벤트 루프를
// 무징후로 세우는 경로를 여기서 끊는다(POK-168 r15a · ADR-063).
//
// 계약 4항(설계 3.2 "fsop 의 계약 4" — 형상은 M1 확정, F-41):
//  1. 취소를 받지 않는 개별 FS 호출을 짧은 워커로 감싸 상한을 준다.
//     상한 = FS_OP_TIMEOUT(권고 5초). 상한 초과 시 워커는 버려지고 늦은 결과는 폐기된다 —
//     버려진 워커의 종료 시각은 무보장이며(F-38), 상한은 비율 상한이지 총량 상한이 아니다.
//  2. 자원 핸들을 시간 경계 너머로 돌려주지 않는다 — 파일을 여는 작업(ProbeT)은
//     열고·읽고·닫기를 워커 안에서 통째로 끝낸다. 늦은 성공의 FD 는 워커 자신이 닫는다.
//  3. 타임아웃은 정상 실패와 구분돼 호출자에게 도달한다: errors.Is(err, ErrStalled).
//     호출자(indexer)가 그 구분으로 fs_op_stalled 신호와 Latch.Trip 을 잇는다(수용 기준 f6m ⓓ).
//  4. Latch 는 loop 단일 goroutine 이 소유한다 — 고루틴 안전하지 않다(락 없음이 계약이다).
package fsop

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"
)

// DefaultOpTimeout 은 개별 FS 호출 상한의 기본값이다(계약 1항 권고).
const DefaultOpTimeout = 5 * time.Second

// ErrStalled 는 개별 FS 호출이 상한을 넘겨 버려졌음을 뜻한다.
// 정상 실패(파일 없음 등)와 반드시 구분돼 호출자에게 도달해야 한다(계약 3항).
var ErrStalled = errors.New("fsop: FS 호출이 상한을 넘겼다")

// StatT 는 os.Stat 을 워커로 감싸 timeout 상한을 준다.
// 타임아웃이면 (nil, ErrStalled 래핑)을 돌려주고 워커는 버려진다.
func StatT(path string, timeout time.Duration) (os.FileInfo, error) {
	type result struct {
		fi  os.FileInfo
		err error
	}
	// 버퍼 1 이라 버려진 워커의 늦은 송신이 막히지 않는다 — 워커는 반드시 끝까지 간다.
	ch := make(chan result, 1)
	go func() {
		fi, err := os.Stat(path)
		ch <- result{fi, err}
	}()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r.fi, r.err
	case <-t.C:
		return nil, fmt.Errorf("%w: op=stat path=%q timeout=%s", ErrStalled, path, timeout)
	}
}

// ProbeT 는 "열고·읽고·닫는" 프로브 함수 전체를 워커로 감싼다(계약 2항).
// probe 는 자기 파일을 자기가 닫아야 한다 — 그래야 늦은 성공의 FD 를 워커가 회수한다.
func ProbeT(path string, timeout time.Duration, probe func(string) (int64, error)) (int64, error) {
	type result struct {
		d   int64
		err error
	}
	ch := make(chan result, 1)
	go func() {
		d, err := probe(path)
		ch <- result{d, err}
	}()

	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-ch:
		return r.d, r.err
	case <-t.C:
		return 0, fmt.Errorf("%w: op=probe path=%q timeout=%s", ErrStalled, path, timeout)
	}
}

// Latch 는 FS 열화 래치다(계약 4항). loop 단일 goroutine 만 만진다.
//
// 트립되면 처리 경로가 새 FS 워커를 만들지 않게 하는 것이 목적이다 —
// "한 주기 안의 hang op 개수"를 비율로 묶는 장치이며, 해제는 주기 머리의
// root 프로브 1회(Reset)가 한다.
type Latch struct {
	log     *slog.Logger
	tripped bool
	path    string
	site    string
}

// NewLatch 는 래치를 만든다. log 는 fs_degraded 게이지 신호를 싣는다.
func NewLatch(log *slog.Logger) *Latch {
	return &Latch{log: log}
}

// Trip 은 래치를 세운다. 멱등이다 — 이미 트립된 상태의 재트립은 최초 좌표를 유지한다.
func (l *Latch) Trip(path, site string) {
	if l.tripped {
		return
	}
	l.tripped, l.path, l.site = true, path, site
	l.log.Error("fs_degraded", "value", 1, "path", path, "site", site)
}

// Tripped 는 래치 상태다.
func (l *Latch) Tripped() bool {
	return l.tripped
}

// Reset 은 주기 머리에서 root 를 1회 프로브해 해제를 시도한다.
// 프로브가 다시 상한을 넘기면 트립을 유지한다(false). root 부재 같은 정상 실패는
// hang 이 아니므로 해제한다 — 그 실패는 정상 경로의 에러 처리가 받는다.
func (l *Latch) Reset(root string, timeout time.Duration) bool {
	if !l.tripped {
		return true
	}
	if _, err := StatT(root, timeout); errors.Is(err, ErrStalled) {
		l.log.Error("fs_op_stalled", "op", "stat", "site", "latch_reset_probe", "path", root)
		return false
	}
	l.log.Info("fs_degraded", "value", 0, "path", l.path, "site", l.site)
	l.tripped, l.path, l.site = false, "", ""
	return true
}
