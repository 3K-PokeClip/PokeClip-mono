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

// UploadState 는 업로드 진행 상태다.
//
// 계약-세그먼트인덱스 2절이 고정한 3값이 전부다. 네 번째 값도, uploaded → pending
// 역전이도 없다(그 전이는 3번 소비자 계약의 확장이라 승인 사안이다).
// POK-30 업로더가 pending 행을 PUT 하고 uploaded 또는 failed 로 확정한다.
type UploadState string

const (
	// UploadStatePending 은 아직 올리지 않은 상태다. INSERT 시점의 값이다.
	UploadStatePending UploadState = "pending"
	// UploadStateUploaded 는 PUT 200 을 받고 장부에 확정한 상태다.
	// 이 값이 되기 전에는 콜드 URL 을 광고하면 안 된다(인덱스 불변식 1).
	UploadStateUploaded UploadState = "uploaded"
	// UploadStateFailed 는 재시도를 소진하고 실패로 확정한 상태다.
	// 종국 상태가 아니다 — 재기동·주기 스위퍼가 다시 집는다(G11′).
	UploadStateFailed UploadState = "failed"
)

// Record 는 stream_segments 표의 한 줄이며 필드가 컬럼과 1:1 대응한다.
type Record struct {
	StreamID     string
	Seq          int64
	StartPTSMS   int64
	StartWallUTC time.Time
	// DurationMS 는 DB 컬럼이 int 라 32비트로 좁혀 담는다. 축소는 H7 과 correctTail 두 지점에서만 한다.
	DurationMS int32
	// S3Key 는 올릴 자리 예약 문자열이다. POK-30 업로더가 이 값을 그대로 PUT 대상 키로 쓴다.
	S3Key           string
	LocalPath       string
	UploadState     UploadState
	Bytes           int64
	IsDiscontinuity bool

	// --- carrier 3필드 (POK-168 M2 · 설계 5.4.2 NULL 운반 규약 · 게이트 n1a~d) ---
	//
	// 반드시 포인터다: nil ⇒ SQL NULL. 값 타입으로 두면 "" 가 NOT VALID FK 에 걸려
	// 23503 으로 INSERT 자체가 실패하고(세션 미확정 = 정상 상태인데), PlaybackPDT 는
	// zero time 을 SQL 로 구분할 방법이 없다. 값 타입 필드를 두지 않는 것 자체가
	// 구조 보장이다 — n1d 가 정적으로 단언한다.
	//
	// 미확정(nil)은 이후 생산자(세션 레지스트리·③ 추출 — M3)가 1회 채운다.
	// 불변 트리거(ddl.go)가 "NULL → 값 1회"만 허용하고 재변경을 막는다.

	// SessionID 는 세션(회차) 귀속이다. stream_sessions.session_id 를 참조한다(FK NOT VALID).
	SessionID *string
	// PlaybackPDT 는 되감기 목록에 실리는 단조 PDT 원천이다(start_wall_utc 는 역행 가능).
	PlaybackPDT *time.Time
	// PlaybackS3Key 는 ③(재생 렌디션) 키다. 계약 5-5 착지 컬럼에 INSERT 시점부터 실을 수 있게 한다.
	PlaybackS3Key *string
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
	// S3Key 는 INSERT 때 예약된 키 그대로다. 업로더는 이 값을 재계산하지 않고 그대로 PUT 한다
	// — 키 형상이 계약이므로 재계산은 포맷을 바꾸는 순간 옛 행을 전부 어긋나게 한다.
	S3Key string
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

// S3Key 는 S3 에 올릴 때 쓸 경로 문자열을 만든다. INSERT 시점에 한 번 계산해 장부에 적고,
// 업로더는 그 값을 그대로 읽어 쓴다(G6 폐기 → G6′).
//
// 포맷 출처: 계약-세그먼트인덱스.md 의 s3_key 예시
//
//	streams/demo-stream/2026-07-25/10/seg_000123.m4s
//
// 날짜와 시는 start_wall_utc 를 UTC 로 포맷한다(계약: UTC 강제).
// 로컬 파일이 .mp4 여도 키는 .m4s 로 정규화한다 — 로컬 파일명과 콜드 키는 서로 다른 계약이며,
// 키 형상은 계약 정본이 정한다. POK-30 업로더가 이 예약 키를 그대로 PUT 대상으로 쓴다.
// seq 는 %06d 다. seq 가 100만 이상이면 자릿수가 늘어 사전식 정렬은 깨지나 유일성은 유지된다
// (4s x 100만 = 46일 연속 방송). 자릿수 정책 변경은 컬럼 정책 리뷰(3번 승인) 사안.
func S3Key(streamID string, seq int64, startWallUTC time.Time) string {
	utc := startWallUTC.UTC()
	return fmt.Sprintf("streams/%s/%s/%02d/seg_%06d.m4s",
		streamID, utc.Format("2006-01-02"), utc.Hour(), seq)
}
