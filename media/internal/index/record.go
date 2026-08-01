// Package index 는 DB 담당이다. stream_segments 표 한 줄을 Go 값으로 표현하고(Record),
// 어디까지 기록했는지 기억하며(Cursor), 실제 읽기·쓰기를 수행한다(Store).
//
// 이 패키지의 핵심 발상은 파생값을 저장하지 않고 그때그때 계산한다는 것이다.
// 값이 세 군데에 복사돼 있으면 언젠가 서로 어긋나기 때문이다.
package index

import (
	"fmt"
	"time"
)

// UploadState 는 업로드 진행 상태다. 이번 범위에서는 pending 하나만 쓴다(G6, S3 미접촉).
type UploadState string

// UploadStatePending 은 아직 올리지 않은 상태다. 이번 범위에서 쓰는 유일한 값.
const UploadStatePending UploadState = "pending"

// Record 는 stream_segments 표의 한 줄이며 필드가 컬럼과 1:1 대응한다.
type Record struct {
	StreamID     string
	Seq          int64
	StartPTSMS   int64
	StartWallUTC time.Time
	// DurationMS 는 DB 컬럼이 int 라 32비트로 좁혀 담는다. 축소는 H7 과 correctTail 두 지점에서만 한다.
	DurationMS int32
	// S3Key 는 나중에 올릴 자리 예약 문자열이다. 이번 범위에서는 만들기만 하고 쓰지 않는다.
	S3Key           string
	LocalPath       string
	UploadState     UploadState
	Bytes           int64
	IsDiscontinuity bool
}

// TailRow 는 스트림의 마지막 행이다. 파생값의 단일 출처이며,
// 값이 세 곳에서 어긋나는 사고를 구조적으로 막는다.
type TailRow struct {
	Seq          int64
	StartPTSMS   int64
	StartWallUTC time.Time
	DurationMS   int32
	Bytes        int64
	LocalPath    string
	UploadState  UploadState
}

// Cursor 는 "이 방송을 어디까지 기록했나"를 기억하는 책갈피다.
type Cursor struct {
	NextSeq int64
	// Tail 이 nil 이면 이 스트림에 행이 없다(= 첫 세그먼트다).
	Tail *TailRow
}

// NextPTSMS 는 다음 세그먼트가 시작할 재생 위치다 = 마지막 조각의 시작 + 그 길이.
func (c Cursor) NextPTSMS() int64 {
	if c.Tail == nil {
		return 0
	}
	return c.Tail.StartPTSMS + int64(c.Tail.DurationMS)
}

// LastStartWall 은 마지막으로 기록한 세그먼트의 벽시계 시작 시각이다.
func (c Cursor) LastStartWall() time.Time {
	if c.Tail == nil {
		return time.Time{}
	}
	return c.Tail.StartWallUTC
}

// ExpectedNextWall 은 "다음 파일은 이 시각쯤 시작할 것"이라는 기대치다.
// 실제와의 차이가 drift 이며 H8 이 이 값으로 불연속을 판정한다.
func (c Cursor) ExpectedNextWall() time.Time {
	if c.Tail == nil {
		return time.Time{}
	}
	return c.Tail.StartWallUTC.Add(time.Duration(c.Tail.DurationMS) * time.Millisecond)
}

// InsertOutcome 은 INSERT 시도 결과를 3가지로 구분한다.
// "실패"를 하나로 뭉뚱그리지 않는 이유는 중복은 정상이고 번호 충돌은 비상이기 때문이다(D5).
type InsertOutcome int

const (
	// InsertInserted 는 새 행이 들어간 경우다. 커서를 전진시킨다.
	InsertInserted InsertOutcome = iota
	// InsertDuplicatePath 는 같은 파일이 이미 있는 경우다. UNIQUE(stream_id, local_path) 가 막아 준다.
	// 사고가 아니라 설계상 허용된 정상 상황이며(멱등이 지켜졌다는 증거) 커서를 전진시키면 안 된다.
	InsertDuplicatePath
	// InsertSeqConflict 는 PK(stream_id, seq) 충돌이다. 커서가 DB 보다 뒤처졌다는 뜻이고,
	// 이는 "사이드카가 단일 쓰기자"라는 전제(D10) 위반 신호다. 정상 운영에서는 발생하지 않는다.
	// LoadCursor 재적재 후 1회만 재시도한다 — 반복 재시도는 다른 쓰기자와 seq 경합을 벌이는 것이며
	// G3(seq 재사용·재정렬 금지)를 위협한다. 2회째 실패 시 ERROR 후 프로세스를 종료해 사람이 본다.
	InsertSeqConflict
)

// String 은 로그와 테스트 실패 메시지에서 숫자 대신 이름이 보이게 한다.
func (o InsertOutcome) String() string {
	switch o {
	case InsertInserted:
		return "inserted"
	case InsertDuplicatePath:
		return "duplicate_path"
	case InsertSeqConflict:
		return "seq_conflict"
	default:
		return fmt.Sprintf("unknown(%d)", int(o))
	}
}

// S3Key 는 나중에 S3 에 올릴 때 쓸 경로 문자열을 만든다. 이번 범위에서는 적어 두기만 한다(G6).
//
// 포맷 출처: 계약-세그먼트인덱스.md 의 s3_key 예시
//
//	streams/demo-stream/2026-07-25/10/seg_000123.m4s
//
// 날짜와 시는 start_wall_utc 를 UTC 로 포맷한다(계약: UTC 강제).
// 로컬 파일이 .mp4 여도 키는 .m4s 로 정규화한다 — 로컬 파일명과 콜드 키는 서로 다른 계약이며,
// 키 형상은 계약 정본이 정한다. POK-30 업로더가 이 예약 키를 그대로 PUT 대상으로 쓴다.
// seq 는 %06d 다. seq 가 100만 이상이면 자릿수가 늘어 사전식 정렬은 깨지나 유일성은 유지된다
// (4s x 100만 = 46일 연속 방송). 자릿수 정책은 POK-35 문의 항목.
func S3Key(streamID string, seq int64, startWallUTC time.Time) string {
	utc := startWallUTC.UTC()
	return fmt.Sprintf("streams/%s/%s/%02d/seg_%06d.m4s",
		streamID, utc.Format("2006-01-02"), utc.Hour(), seq)
}
