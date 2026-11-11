package upload

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 이 파일은 복원이 행 하나의 ② 원천을 읽는 자리다(설계 r6 2.10 「구멍 행의 ② 원천」 — 계약 8.1 ⓓ 「로컬, 없으면 S3」).

// sourceState 는 원천을 읽은 결과의 갈래다.
type sourceState uint8

const (
	// sourceRead — 장부 크기만큼(로컬이 더 짧으면 그만큼) 읽었다.
	sourceRead sourceState = iota
	// sourceMissing — 로컬도 ② 객체도 없다(원천 부재 — 외부 삭제 + ② 미업로드).
	sourceMissing
	// sourceTooLarge — 장부 크기가 입력 상한을 넘는다. 파일은 자라기만 하므로 결정적이다(실시간의 input_too_large 와 같다).
	sourceTooLarge
	// sourceShort — ② 객체가 장부 크기보다 짧다(잘린 업로드 · 다른 객체). 그 바이트를 원천으로 쓰지 않는다.
	sourceShort
)

// rowSource 는 행 하나의 ② 원천이다. body 는 sourceRead 일 때만 있다.
type rowSource struct {
	state sourceState
	body  []byte
}

// loadSource 는 행 r 의 ② 를 읽는다 — 로컬 파일(장부 크기만큼 — 꼬리에서 자라는 중이어도 장부가 아는 확정 몫), 로컬이 없으면 ②
// 객체(② uploaded 일 때만 — 로컬 정리는 ② uploaded 를 요구한다). 일시 실패(열기 · 읽기 · GET 의 부재 밖 오류)는 오류다 — 행을
// 지나가지 않고 다시 본다.
func (u *Uploader) loadSource(streamID string, r index.PlaybackChainRow) (rowSource, error) {
	if r.Bytes == nil {
		return rowSource{state: sourceMissing}, nil
	}
	if *r.Bytes > getLimit {
		return rowSource{state: sourceTooLarge}, nil
	}
	body, found, err := u.readLocal(streamID, r.LocalPath, *r.Bytes)
	if err != nil {
		return rowSource{}, failRestore("local_read", fmt.Errorf("seq %d: %w", r.Seq, err))
	}
	if found {
		return rowSource{state: sourceRead, body: body}, nil
	}
	return u.readArchiveObject(r)
}

// readLocal 은 로컬 ② 를 size 바이트까지 읽는다. 파일이 없으면 found 가 거짓이다. 루트 이탈 · 디렉터리 어긋남은 오류다(대상 검증과
// 같은 판정 — streamRel).
func (u *Uploader) readLocal(streamID, path string, size int64) (body []byte, found bool, err error) {
	if path == "" {
		return nil, false, nil
	}
	rel, reason := u.streamRel(streamID, path)
	if reason != "" {
		return nil, false, fmt.Errorf("경로 %q 거부: %s", path, reason)
	}
	f, err := u.opt.Root.Open(rel)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	defer f.Close()
	buf := make([]byte, size)
	n, err := io.ReadFull(f, buf)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return nil, false, err
	}
	return buf[:n], true, nil
}

// readArchiveObject 는 ② 객체를 원천으로 읽는다. ② 가 안 올라갔거나 객체가 없으면 원천 부재다. 장부 크기만큼만 쓴다 — 로컬
// 원천과 같은 규칙이다(c10b cc 보충 M · 보안 L): 객체가 더 길면 장부 크기로 자르고, 더 짧으면 sourceShort 다. 짧은 객체를
// 그대로 쓰면 파트 경계에서 잘린 바이트가 읽혀 틀린 트랙 끝 · 객체 대조 재료가 된다.
func (u *Uploader) readArchiveObject(r index.PlaybackChainRow) (rowSource, error) {
	if r.UploadState != index.UploadStateUploaded || r.S3Key == "" {
		return rowSource{state: sourceMissing}, nil
	}
	_, getter, _ := u.restoreDeps()
	ctx, cancel := u.s3Ctx()
	defer cancel()
	body, err := getter.Get(ctx, r.S3Key)
	switch {
	case errors.Is(err, ErrObjectNotFound):
		return rowSource{state: sourceMissing}, nil
	case err != nil:
		return rowSource{}, failRestore("s3_get", err)
	case int64(len(body)) < *r.Bytes:
		return rowSource{state: sourceShort}, nil
	}
	return rowSource{state: sourceRead, body: body[:*r.Bytes]}, nil
}

// sourceTiming 은 행의 사슬 재료다 — 원래 사실 칸 · 길이는 장부 값(실시간 사슬과 같은 재료 — indexer sourceOf), mtxi 는
// ② 판독 값이다. unreadable 이 비지 않으면 그 행은 읽히지 않는다(본문 해석 실패 · 원천 부재) — 부르는 쪽이 꼬리면 기다리고,
// 아니면 지나간다. mtxi 없음과 입력 상한 초과는 결정적이라 읽힌 것으로 친다(Mtxi nil — 기대값 고정, 실시간과 같다).
func sourceTiming(r index.PlaybackChainRow, src rowSource) (st playback.SourceTiming, unreadable string) {
	st = playback.SourceTiming{Seq: r.Seq, SessionID: r.SessionID, Recorder: r.Timing.Recorder, Number: r.Timing.Number,
		VideoFirst: r.Timing.VideoFirst, VideoEnd: r.Timing.VideoEnd, Dur: time.Duration(r.DurationMS) * time.Millisecond}
	switch src.state {
	case sourceMissing:
		return st, "source_missing"
	case sourceShort:
		return st, "source_size_mismatch"
	case sourceTooLarge:
		return st, ""
	}
	timing, err := playback.ReadTiming(bytes.NewReader(src.body))
	switch {
	case err == nil:
		st.Mtxi = timing.Mtxi
		return st, ""
	case errors.Is(err, playback.ErrInputTooLarge):
		return st, ""
	}
	return st, "body_unreadable"
}
