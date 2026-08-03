package mtxhook

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"time"
)

// eventBufSize 는 Events 채널의 버퍼다.
//
// 근거: 스풀은 파일이므로 소비가 느려 Reader 가 블록해도 **이벤트 유실이 없다**
// (다음 폴에서 이어 읽는다). 판정도 도착 순서가 아니라 시각 비교이므로 지연이 정확성에
// 무해하다. 256 은 초기 Scan·5분 주기 재스캔이 main 루프를 점유하는 동안의 유입을 흡수하는
// 여유이며 그 이상은 의미가 없다. 환경변수로 노출하지 않는다(YAGNI).
//
// **금지**: 버퍼가 찼을 때 드롭하는 non-blocking send 를 쓰지 않는다.
// 드롭은 무징후 미탐이고 블록은 무해하다 — 두 실패의 무게가 다르다.
const eventBufSize = 256

// 기본값들. 스풀 경로에는 기본값을 두지 않는다 — 빈 값이 "훅 어댑터 끔"이기 때문이다.
const (
	defaultPollInterval = 200 * time.Millisecond
	// defaultMaxLineBytes 는 **손상된 줄을 어디까지 참고 버릴지**의 안전 상한이다.
	// 정상 줄의 상한은 writer 쪽 계약(1줄 < 4096B)이며 층이 다르다.
	// 8192 = writer 상한의 2배 — 인터리브 흔적을 한 줄 분량은 참아 준다.
	defaultMaxLineBytes = 8192
	// readChunkBytes 는 한 번에 읽어 들이는 크기다. 스풀은 append-only 라 순차 읽기뿐이다.
	readChunkBytes = 32 * 1024
)

// ReaderOptions 는 스풀 tail Reader 의 설정이다.
type ReaderOptions struct {
	// SpoolPath 는 훅이 한 줄씩 덧붙이는 공유 파일이다. 빈 값이면 Reader 를 만들지 않는다
	// (호출자 책임 — NewReader 는 에러로 거부한다). 이것이 즉시 롤백 스위치다.
	SpoolPath string
	// PollInterval 은 tail 주기다. 0 이면 200ms.
	PollInterval time.Duration
	// MaxLineBytes 는 손상 줄 폐기 상한이다. 0 이면 8192.
	MaxLineBytes int
	Log          *slog.Logger
}

// Reader 는 스풀을 개행 단위로 tail 해 Events 채널로 흘린다.
//
// tail 계약:
//   - 소비 단위는 **개행으로 끝난 줄**뿐이다. 개행이 없는 꼬리는 carry 에 남겨 다음 폴에서
//     이어 붙인다 — 훅 write 와 폴링이 겹치는 순간의 부분 줄을 버리지 않기 위해서다.
//   - carry 가 MaxLineBytes 를 넘을 때만 폐기하고 다음 개행까지 건너뛴다.
//   - 크기가 오프셋보다 작아지면 트렁케이트로 보고 오프셋 0 리셋 + carry 비움.
//     검출 범위는 축소뿐이다 — 크기가 오프셋 이상으로 재성장한 파일 교체는 검출하지 못한다
//     (회전 없음 전제와 일관. inode 추적은 비목표).
//   - 파싱 실패 줄은 그 줄만 버린다. 스트림 전체를 멈추지 않는다.
//   - **flock 을 잡지 않는다.** 개행·carry 가 reader-writer 동기화를 담당하며,
//     Reader 가 잠그면 writer 의 200ms 잠금 상한만 축낸다.
type Reader struct {
	opt    ReaderOptions
	events chan Event
	done   chan struct{}

	// 아래는 내부 고루틴 전용이다. Wait 은 done 이 닫힌 뒤에만 err 를 읽는다.
	offset int64
	carry  []byte
	// skipToNewline 은 상한 초과로 버린 줄의 잔여를 다음 개행까지 흘려보내는 표시다.
	skipToNewline bool
	missingWarned bool
	err           error
}

// NewReader 는 Reader 를 만든다. 설정이 잘못됐으면 기동 시점에 실패시킨다.
func NewReader(opt ReaderOptions) (*Reader, error) {
	if opt.SpoolPath == "" {
		return nil, errors.New("훅 스풀 경로가 비었다 — 빈 값이면 Reader 를 아예 만들지 않아야 한다")
	}
	if opt.PollInterval <= 0 {
		opt.PollInterval = defaultPollInterval
	}
	if opt.MaxLineBytes <= 0 {
		opt.MaxLineBytes = defaultMaxLineBytes
	}
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	return &Reader{
		opt:    opt,
		events: make(chan Event, eventBufSize),
		done:   make(chan struct{}),
	}, nil
}

// Start 는 동기다. 반환 시점에 시작 오프셋이 확정돼 있다 —
// 파일이 있으면 EOF, 없으면 "생기면 처음부터"(오프셋 0)다.
//
// 과거 이벤트를 재생하지 않는 이유: 이미 INSERT 된 행의 is_discontinuity 를 고치는 경로가
// 없으므로 지나간 세션 경계는 소급 적용 대상이 아니다. 대가는 "사이드카 재기동 창에 걸친
// 재접속은 훅으로 검출되지 않는다"이며, 그 구간은 기존 벽시계 드리프트 판정이 맡는다.
func (r *Reader) Start(ctx context.Context) error {
	if fi, err := os.Stat(r.opt.SpoolPath); err == nil {
		r.offset = fi.Size()
	} else if os.IsNotExist(err) {
		r.offset = 0
		r.warnSpoolMissing()
	} else {
		return fmt.Errorf("훅 스풀 확인 실패 %q: %w", r.opt.SpoolPath, err)
	}

	go r.run(ctx)
	return nil
}

// Events 는 정규화된 훅 이벤트 채널이다. 소비자는 main 루프 하나뿐이다(D10).
func (r *Reader) Events() <-chan Event { return r.events }

// Done 은 내부 고루틴이 끝나면 닫힌다.
func (r *Reader) Done() <-chan struct{} { return r.done }

// Wait 는 종료 사유를 돌려준다. nil 이면 ctx 취소에 의한 정상 종료다.
// Done 이 닫힌 뒤에 부른다.
func (r *Reader) Wait() error { return r.err }

func (r *Reader) run(ctx context.Context) {
	defer close(r.done)
	defer close(r.events)

	t := time.NewTicker(r.opt.PollInterval)
	defer t.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := r.poll(ctx); err != nil {
				if ctx.Err() != nil {
					return // 취소 중 발생한 실패는 정상 종료로 본다
				}
				r.err = err
				return
			}
		}
	}
}

// poll 은 오프셋 이후에 늘어난 만큼을 읽어 개행 단위로 흘린다.
func (r *Reader) poll(ctx context.Context) error {
	f, err := os.Open(r.opt.SpoolPath)
	if os.IsNotExist(err) {
		// 새 볼륨에서 첫 송출 전까지는 스풀이 없다. 정상 상태이므로 계속 기다린다.
		r.warnSpoolMissing()
		return nil
	}
	if err != nil {
		// 권한 오류 등. 프로세스를 죽이지 않고 자연 강등한다(안전망이 남아 있다).
		r.opt.Log.Warn("hook_spool_open_failed", "path", r.opt.SpoolPath, "err", err)
		return nil
	}
	defer f.Close()

	fi, err := f.Stat()
	if err != nil {
		r.opt.Log.Warn("hook_spool_stat_failed", "path", r.opt.SpoolPath, "err", err)
		return nil
	}
	if fi.Size() < r.offset {
		r.opt.Log.Warn("hook_spool_truncated",
			"path", r.opt.SpoolPath, "old_offset", r.offset, "size", fi.Size(),
			"note", "오프셋을 0 으로 되돌린다")
		r.offset = 0
		r.carry = r.carry[:0]
		r.skipToNewline = false
	}
	if fi.Size() == r.offset {
		return nil
	}

	if _, err := f.Seek(r.offset, io.SeekStart); err != nil {
		return fmt.Errorf("훅 스풀 탐색 실패 offset=%d: %w", r.offset, err)
	}

	buf := make([]byte, readChunkBytes)
	for {
		n, readErr := f.Read(buf)
		if n > 0 {
			r.consume(ctx, buf[:n])
			r.offset += int64(n)
		}
		if errors.Is(readErr, io.EOF) {
			return nil
		}
		if readErr != nil {
			return fmt.Errorf("훅 스풀 읽기 실패 offset=%d: %w", r.offset, readErr)
		}
		if ctx.Err() != nil {
			return nil
		}
	}
}

// consume 은 읽어 온 바이트를 개행 단위로 갈라 흘린다. 개행 없는 꼬리는 carry 로 남는다.
func (r *Reader) consume(ctx context.Context, chunk []byte) {
	for len(chunk) > 0 {
		i := bytes.IndexByte(chunk, '\n')
		if i < 0 {
			r.appendCarry(chunk)
			return
		}

		segment := chunk[:i]
		chunk = chunk[i+1:]

		if r.skipToNewline {
			// 상한 초과로 버린 줄의 잔여다. 개행을 만났으니 여기서 정상 재개한다.
			r.skipToNewline = false
			continue
		}
		r.appendCarry(segment)
		if r.skipToNewline {
			// 이 줄 자체가 상한을 넘었다. 개행까지 이미 소비했으므로 바로 재개한다.
			r.skipToNewline = false
			continue
		}

		r.emit(ctx, r.carry)
		r.carry = r.carry[:0]
	}
}

// appendCarry 는 부분 줄을 이어 붙인다. 상한을 넘으면 그 줄을 버린다.
func (r *Reader) appendCarry(b []byte) {
	if len(r.carry)+len(b) > r.opt.MaxLineBytes {
		r.opt.Log.Warn("hook_line_overflow",
			"path", r.opt.SpoolPath, "max_line_bytes", r.opt.MaxLineBytes,
			"dropped_bytes", len(r.carry)+len(b),
			"note", "손상 줄로 보고 다음 개행까지 건너뛴다")
		r.carry = r.carry[:0]
		r.skipToNewline = true
		return
	}
	r.carry = append(r.carry, b...)
}

// emit 은 줄 하나를 Event 로 만들어 채널에 넣는다.
//
// 블로킹 전송이다(결정 ⑤). 소비자가 초기 Scan 으로 바빠도 버퍼가 차면 그냥 기다린다 —
// 스풀은 파일이라 기다리는 동안 유실이 없다.
func (r *Reader) emit(ctx context.Context, line []byte) {
	if len(bytes.TrimSpace(line)) == 0 {
		return
	}
	ev, err := ParseLine(line)
	if err != nil {
		r.opt.Log.Warn("hook_line_invalid", "path", r.opt.SpoolPath, "err", err)
		return
	}
	select {
	case r.events <- ev:
	case <-ctx.Done():
	}
}

// warnSpoolMissing 은 스풀 부재를 한 번만 경고한다.
// 폴 주기마다 경고하면 첫 송출 전까지 로그가 초당 5줄씩 쌓인다.
func (r *Reader) warnSpoolMissing() {
	if r.missingWarned {
		return
	}
	r.missingWarned = true
	r.opt.Log.Warn("hook_spool_missing",
		"path", r.opt.SpoolPath,
		"note", "아직 훅이 한 번도 발화하지 않았다면 정상이다. 생기면 처음부터 읽는다")
}
