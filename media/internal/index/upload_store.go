package index

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// UploadTarget 은 "올릴 대상 1건"이다. 실시간 경로(인덱서)와 재개 경로(스위퍼)가
// 같은 타입을 쓴다 — 두 경로에 서로 다른 어휘를 두면 크기 재확인 같은 공통 규칙이
// 한쪽에서만 지켜지는 사고가 난다.
type UploadTarget struct {
	StreamID string
	// Axis 는 이 대상이 어느 업로드 축인가다(설계 5.5.4 #4). 영값은 미판정이며
	// 업로더가 그 자리에서 거부한다 — 축을 빠뜨린 대상이 조용히 ② 로 접히면 안 된다.
	Axis Axis
	// Seq 는 ②·③ 축의 대상 식별자다. **init 축에서는 쓰지 않는다**(설계 5.5.2) —
	// 그 축의 유일성은 SessionID 가 준다. 키 검사의 seq 대조도 축으로 갈린다.
	Seq int64
	// SessionID 는 init 축의 대상 식별자다. ③ 축에서는 그 조각이 귀속된 세션이며(기대 init
	// 대조와 init_mismatch 결속의 앵커), ② 축에서는 비어 있다.
	SessionID string
	// S3Key 는 INSERT 때 예약된 키 그대로다. 재계산하지 않는다(키 형상 = 계약). 예외는 init 축
	// 하나다 — 조회가 빈 값을 싣고 워커가 playback.InitKey 로 만든다(계획 2.1 「명시적 예외」).
	S3Key     string
	LocalPath string
	// Bytes 는 장부에 적힌 크기다. 꼬리 대조·CAS 기대값·failed 확정 판정·PUT 후 비교에 쓴다.
	// NULL 인 행은 애초에 조회되지 않는다(결정 14).
	Bytes int64
	// IsTail 은 조회 시점에 그 스트림의 마지막 seq 였는가다.
	IsTail bool
	// ExpectedInitSHA 는 이 조각의 세션이 확정한 init 바이트 해시다. **스위퍼의 ③ 작업만
	// 채운다** — ③ 조회가 조각 세션의 sess.init_sha256 을 함께 싣는다. 실시간 ③ 작업은 비워
	// 보내고 워커가 인메모리 sessionInit[sessionID] 를 본다(계획 2.1 — 개시 시점엔 장부 값이
	// NULL 이라 실을 것이 없다).
	//
	// 작업에 동봉하는 이유는 **조회 메서드를 신설하지 않기 위해서다**: 워커가 PUT 직전에
	// "이 조각이 어느 MAP 을 기대하는가"를 알아야 5.3ⓒ(init 불일치 fail-closed)가 성립하는데,
	// 그때마다 DB 를 물으면 조각당 왕복이 하나 더 는다.
	// **이 값과 sessionInit 이 둘 다 없을 때만 대조 보류다** — 기대 init 을 아직 모른다는
	// 뜻이며(세션 init 확정 전이거나 재기동으로 캐시가 빈 뒤), 그 조각은 PUT 하지 않고 워커의
	// 보류 목록에서 기다린다.
	ExpectedInitSHA []byte

	// 아래 셋은 ③ 조각의 시간 도장 메타다(계획 4.2-R R3). **메모리 필드이며 DB 통로가 아니다**
	// — 장부에 컬럼이 없고, 스위퍼 조회는 채우지 않는다.

	// PlaybackPos 는 이 조각의 시간 도장 위치다 — pos = mtxi + offset. 실시간 작업은 루프가
	// 정해 싣고, 스위퍼 작업은 워커가 회차별 보정값 표로 정한다.
	PlaybackPos time.Duration
	// StitchOffset 은 이 조각에 적용된 보정값(offset)이다. 실시간 ③ 요청이 이 값으로 회차의
	// 보정값 표에 offset 줄을 적는다(평소 0 — 리셋·구멍을 이어 붙인 뒤에만 0 이 아니다).
	StitchOffset time.Duration
	// PosPinned 는 mtxi 판독에 실패해 PlaybackPos 를 기대값(직전 pos + 장부 길이)으로 고정했는가다.
	// 참이면 보정값 표에 고정 줄 {seq → pos} 을 적어, 스위퍼가 판독 없이 같은 값을 쓰게 한다.
	PosPinned bool
}

// SweepCursor 는 스위퍼 키셋 페이징의 위치이며, 뜻은 정확히 "여기까지 검사를 마쳤다"이다.
// 검사 완료의 기준은 접수 결과가 정한다 — 접수됐거나 거부된 행은 판정이 끝난 것이고,
// 큐가 차서 판정조차 못 한 행은 미검사다(결정 5⁵).
//
// 필드 순서가 곧 정렬 키 순서다. 축별 조회의 ORDER BY 와 반드시 같아야 한다.
//
// **축마다 마지막 키가 다르다**(설계 5.5.2): ②·③ 는 Seq 로, init 은 SessionID 로 전순서를
// 만든다. 한 축의 커서를 다른 축에 흘려 넣으면 안 되며, 스위퍼가 축별로 3벌을 든다.
type SweepCursor struct {
	IsFailed  bool
	StartWall time.Time
	StreamID  string
	Seq       int64
	// SessionID 는 init 축의 마지막 정렬 키다. ③ 축 커서에도 마지막 행의 세션이 실리지만
	// (PendingUploads 가 행의 SessionID 를 그대로 옮긴다) 정렬 키가 아니다 — ③ 조회는 이 값을
	// 인자로 쓰지 않는다. ② 축에서는 비어 있다.
	SessionID string
}

// IsZero 는 "처음부터"를 뜻한다. 순환 판정과 1단계 결과 재사용이 이 값에 달렸다.
func (c SweepCursor) IsZero() bool {
	return !c.IsFailed && c.StartWall.IsZero() && c.StreamID == "" && c.Seq == 0 && c.SessionID == ""
}

// UploadStore 는 업로드 결과를 장부에 반영하는 통로다.
//
// 계약: 모든 메서드는 ctx 취소 시 즉시 반환해야 한다 — 조회뿐 아니라 마킹도 그렇다.
// 이 계약이 깨지면 Shutdown 6단계의 무조건 join 이 무기한 대기가 된다(결정 17″).
// pgx 는 이 계약을 지킨다.
//
// 기존 Store 에 메서드를 얹지 않은 이유: Store 의 가짜 구현이 안 쓰는 메서드를 떠안게 되고
// Store 의 변경 이유가 둘이 된다(결정 2).
type UploadStore interface {
	MarkUploaded(ctx context.Context, streamID string, seq int64, expectBytes int64) (marked bool, err error)
	// MarkFailed 도 같은 CAS 를 쓴다. 호출자는 반드시 err 를 먼저 보고, 그 다음 marked 를 본다 —
	// (false, err) 를 CAS 거부로 오분류하면 DB 오류가 조용히 삼켜진다(CX-2 ⑥).
	MarkFailed(ctx context.Context, streamID string, seq int64, expectBytes int64) (marked bool, err error)
	// MarkPlaybackUploaded 는 ③(재생 렌디션) 축의 성공 CAS 다(설계 5.5.5).
	//
	// **bytes 가 앵커가 아니라 SET 인 것이 ② 와의 계약 차이다**(계약 ⓒ): ③ 의 바이트는
	// 장부에 미리 적힌 값이 아니라 방금 만든 산출물의 길이다. ② 처럼 WHERE 에 두면
	// 그 CAS 는 영원히 0행이다.
	MarkPlaybackUploaded(ctx context.Context, streamID string, seq int64, bytes int64) (marked bool, err error)
	// MarkPlaybackFailed 는 ③ 축의 실패 CAS 이며, **사유가 ReasonInitMismatch 일 때만**
	// 같은 트랜잭션에서 그 조각의 세션을 ending(init_mismatch)으로 전이시킨다(설계 5.3ⓒ).
	//
	// 사유를 인자로 받는 이유(메서드를 나누지 않는 이유): 두 갈래가 같은 조각 CAS 를 쓰고
	// 다른 것은 "세션 절이 붙는가" 하나뿐이다. 일시 실패(PUT·브레이커·타임아웃)로 세션을
	// 끝내면 순단마다 방송이 쪼개진다.
	//
	// sessionID 는 결속 갈래의 앵커다 — 조각이 그 세션의 것일 때만 조각과 세션을 함께 바꾸며,
	// 비어 있으면 오류다. 일시 실패 갈래는 설계 5.5.5 둘째 문장 그대로라 쓰지 않는다.
	// marked 는 조각 CAS 가 1행을 바꿨는가다(세션이 이미 live 가 아니면 세션은 그대로 둔다).
	MarkPlaybackFailed(ctx context.Context, streamID string, seq int64, sessionID, reason string) (marked bool, err error)
	// MarkInitUploaded 는 세션 init(MAP)의 **첫 업로드 CAS** 다(설계 5.5.5 · 5.3ⓑ·ⓓ).
	//
	// 세 조건이 전부 맞을 때만 4열(키·해시·크기·확정 시각)을 쓴다: 그 세션이 있고, 아직
	// 확정되지 않았고(init_uploaded_at IS NULL), **장부의 해시가 비어 있거나 올린 바이트의
	// 해시와 같다**. 셋째 조건의 "비어 있음"이 정상 경로다 — 개시 시점의 init 3열은 NULL 이고,
	// 그 값을 기록하는 유일한 생산자가 첫 업로드인 이 호출이다. sha256 은 반드시 **올린 쪽이
	// 계산한 값**이어야 한다: 행에서 읽어 되넘기면 자기 비교라 5.3ⓑ(init 동일성 = 바이트
	// 동등성) 보증이 통째로 사라진다.
	//
	// incompatible 은 워커가 계산한 **계승 비호환 여부**(ADR-044 `stsd` 지문)다. 참이면서
	// 그 세션이 계승 후보(inherits_session IS NOT NULL)일 때만 계승을 **영속 해제**한다 —
	// TD 분할 세션(inherits_session NULL·base 승계)은 무접촉이다. 해제는 첫 확정 1회뿐이라
	// 이미 확정된 세션에는 참을 넘겨도 바뀌지 않는다. 계승 후보가 아닌 개시
	// (SeedResult.PrevFirstLocalPath 가 빈 값)에서는 호출자가 거짓을 넘긴다 — 게이트 비적용.
	//
	// 실패 쪽 CAS 는 없다. 5.5.5 가 init 에 성공 문장 하나만 정의하며, 실패 상태 컬럼을
	// 새로 만들지 않는다(회수는 재추출·재요청으로 한다).
	MarkInitUploaded(ctx context.Context, sessionID string, sha256 []byte, s3Key string, bytes int64, incompatible bool) (InitMark, error)
	// PendingUploads 는 그 **축의** after 다음 페이지를 집어 온다. 정렬 첫 키가 가변이므로
	// at-least-once 다. 둘째 반환값은 이 페이지의 마지막 행을 가리키는 커서이며, 행이 0건이면
	// after 를 그대로 돌려준다 — 되감김을 만들지 않기 위해서다.
	//
	// 축이 인자인 것이 계약이다(설계 5.5.4 #2): 축마다 상태 열도 자격 술어도 식별자도 다르다.
	// 미판정 축은 오류다 — 조용히 ② 로 접히면 ③ 의 결과가 아카이브 열을 바꾼다.
	PendingUploads(ctx context.Context, axis Axis, tailGraceSecs float64, limit int, after SweepCursor) ([]UploadTarget, SweepCursor, error)
	// CountBacklog 의 세 값 관계: pending 과 failed 는 서로 배타이고,
	// bytesNull 은 그 둘의 부분집합이다(= 처리 불가로 조회에서 제외된 개수). 중복 계수다.
	//
	// 축 라벨이 인자인 이유(#7): 한 수치를 세 라벨로 나눠 찍으면 ③ 의 잔량이 ② 것으로 읽힌다.
	CountBacklog(ctx context.Context, axis Axis) (pending, failed, bytesNull int64, err error)
}

// markUploadedSQL ($3 = 기대 bytes)
//
// bytes 를 WHERE 에서 읽기만 한다. SET 목록에 bytes 가 없다 —
// 결정 9‴(bytes 의 쓰기 소유자는 UpdateTail 하나)와 모순되지 않는 이유가 이것이다.
// upload_state <> 'uploaded' 가드는 확정된 행을 되돌리지 못하게 한다.
const markUploadedSQL = `
UPDATE stream_segments SET upload_state = 'uploaded', uploaded_at = now()
 WHERE stream_id = $1 AND seq = $2 AND upload_state <> 'uploaded' AND bytes = $3`

// markFailedSQL — 같은 CAS 다. uploaded_at 은 건드리지 않는다.
const markFailedSQL = `
UPDATE stream_segments SET upload_state = 'failed'
 WHERE stream_id = $1 AND seq = $2 AND upload_state <> 'uploaded' AND bytes = $3`

// markPlaybackUploadedSQL ($3 = 산출물 길이) — 설계 5.5.5 의 ③ 성공 문장이다.
//
// bytes 가 SET 목록에 있고 WHERE 에 없다. ② 와 정반대이며 그것이 계약 ⓒ 다 —
// ③ 의 길이는 장부에 예고된 값이 아니라 방금 만든 산출물의 실측값이다.
// IN ('pending','failed') 가드는 확정된 행을 되돌리지 못하게 한다.
const markPlaybackUploadedSQL = `
UPDATE stream_segments
   SET playback_upload_state = 'uploaded', playback_uploaded_at = now(), playback_bytes = $3
 WHERE stream_id = $1 AND seq = $2 AND playback_upload_state IN ('pending','failed')`

// markPlaybackFailedSQL 은 일시 실패용 한 문장이다 — 설계 5.5.5 둘째 문장 그대로다.
// 앵커는 ③ 성공 문장과 같다(PK + 상태 가드). 세션은 건드리지 않는다.
const markPlaybackFailedSQL = `
UPDATE stream_segments SET playback_upload_state = 'failed'
 WHERE stream_id = $1 AND seq = $2 AND playback_upload_state IN ('pending','failed')`

// markPlaybackInitMismatchSQL 은 설계 5.3ⓒ 를 **DB 에 즉시 영속**시키는 한 문장이다.
//
// 결속이 요점이다: 세션 전이는 조각 CAS 가 **실제로 1행을 바꿨을 때만** 일어난다
// (`FROM seg` 가 그 결속이다). 두 문장으로 나누면 조각 CAS 가 0행인데도 세션이 끝나
// 이미 정산된 방송이 불일치 하나로 쪼개진다.
//
// 메모리 이벤트에 의존하지 않으므로 크래시·ⓐ 단독 배포에서도 상태가 남는다.
//
// 세션 종료 절(sess)은 session 패키지 endSessionSQL 과 같은 형상이다(state='live' 술어·SET
// 목록). index 는 session 을 임포트하지 않아 그 문장을 쓸 수 없으므로 자기 벌을 두고, 두 문장이
// 어긋나지 않는지는 session 패키지의 PG 테스트가 대조한다(계획 부기 21).
const markPlaybackInitMismatchSQL = `
WITH seg AS (
    UPDATE stream_segments SET playback_upload_state = 'failed'
     WHERE stream_id = $1 AND seq = $2 AND session_id = $3
       AND playback_upload_state IN ('pending','failed')
    RETURNING session_id
), sess AS (
    UPDATE stream_sessions s
       SET state = 'ending', ending_at = now(), end_reason = $4
      FROM seg
     WHERE s.session_id = seg.session_id AND s.state = 'live'
    RETURNING 1
)
SELECT (SELECT count(*) FROM seg)`

// ReasonInitMismatch 는 MarkPlaybackFailed 의 사유 중 세션을 끝내는 유일한 값이며, 그대로
// stream_sessions.end_reason 에 적힌다(계획 5절 새 어휘 2 중 하나). M3 의 'td_exceeded' 와
// 같은 층의 값이고 DDL CHECK 는 없다(ddl.go 의 end_reason text).
const ReasonInitMismatch = "init_mismatch"

// InitMark 는 첫 init CAS 의 결과 4분기다. bool 하나로는 "이미 같은 바이트로 확정됐다"(정상
// 재시도)와 "다른 바이트로 확정됐다"(세션을 쪼개야 하는 사건)를 구분할 수 없다.
//
// 영값은 판정이 아니다 — 오류와 함께만 나간다. 값이 1 부터인 이유는 안 채운 결과가 조용히
// 성공으로 읽히지 않게 하기 위해서다(SessionOp 와 같은 이디엄).
type InitMark uint8

const (
	// InitMarkSuccess — 이번 호출이 4열을 썼다.
	InitMarkSuccess InitMark = iota + 1
	// InitMarkAlreadySame — 이미 **같은** 바이트로 확정돼 있다. 멱등 재시도이며 정상이다.
	InitMarkAlreadySame
	// InitMarkMismatch — 이미 **다른** 바이트로 확정돼 있다. 그 세션의 MAP 은 바꿀 수 없으므로
	// 호출자는 ERROR 를 남기고 작업을 끝낸다(재수거 사다리 — init 작업에는 조각이 없다).
	InitMarkMismatch
	// InitMarkMissing — 그 세션 행이 없다.
	InitMarkMissing
)

// String 은 로그와 테스트 실패 메시지에서 숫자 대신 이름이 보이게 한다.
func (m InitMark) String() string {
	switch m {
	case InitMarkSuccess:
		return "success"
	case InitMarkAlreadySame:
		return "already_same"
	case InitMarkMismatch:
		return "mismatch"
	case InitMarkMissing:
		return "missing"
	default:
		return "unknown"
	}
}

// markInitUploadedSQL — 첫 init 업로드의 정본 SQL 한 자리다(설계 5.5.5 · 5.3ⓑ·ⓓ · ADR-044).
//
//	$1 session_id · $2 sha256 · $3 init_s3_key · $4 init_bytes · $5 계승 비호환 여부
//
// 한 문장에 넷을 담는 이유: 4열 기록 · 결과 4분기 · `stsd` 비호환에 의한 계승 해제 ·
// 그 해제의 영속이 **같은 사건의 네 면**이다. 나누면 "init 은 확정됐는데 계승은 살아 있는"
// 중간 상태가 실재하고, 그 상태로 발행되면 옛 MAP 으로 새 조각을 디코드하게 된다.
//
// 앵커가 bytes 가 아니라 sha256 인 것이 ②·③ 와의 차이다: init 은 "같은 MAP 인가"가
// 세션 전체의 재생 가능성을 가르므로 크기가 아니라 내용으로 대조한다(5.3ⓑ).
// `init_sha256 IS NULL OR = $2` 는 개시 시점 NULL(첫 업로드)과 같은 바이트 재시도를 함께 받는다.
//
// 해제 술어의 `inherits_session IS NOT NULL` 한정이 계약이다 — TD 분할 세션은
// inherits_session 이 NULL 인 채 base 를 승계하므로, 한정을 빼면 그 base 가 0 으로
// 되돌아가 DISC-SEQ 가 역행한다.
//
// cur 의 FOR UPDATE 는 같은 세션에 두 워커가 동시에 들어와도 판정이 갈리지 않게 한다.
const markInitUploadedSQL = `
WITH cur AS (
    SELECT s.init_sha256, s.init_uploaded_at, s.inherits_session
      FROM stream_sessions s WHERE s.session_id = $1 FOR UPDATE
), upd AS (
    UPDATE stream_sessions s SET
        init_s3_key = $3, init_bytes = $4, init_uploaded_at = now(), init_sha256 = $2,
        inherits_session   = CASE WHEN s.inherits_session IS NOT NULL AND $5 THEN NULL ELSE s.inherits_session END,
        discontinuity_base = CASE WHEN s.inherits_session IS NOT NULL AND $5 THEN 0    ELSE s.discontinuity_base END
      FROM cur
     WHERE s.session_id = $1 AND s.init_uploaded_at IS NULL
       AND (s.init_sha256 IS NULL OR s.init_sha256 = $2)
    RETURNING 1
)
SELECT (SELECT count(*) FROM upd), cur.init_sha256, cur.init_uploaded_at FROM cur`

// pendingArchiveUploadsSQL ($1 = tailGrace 초, $2 = limit, $3..$6 = 키셋 커서)
//
// 꼬리 예외 OR 조건이 핵심이다 — 후속 행이 없는 마지막 조각은 아직 자라는 중일 수 있어
// tailGrace 를 넘긴 뒤에야 집는다. 무조건 제외하면 재기동으로 보류가 증발한 꼬리가
// 영구 pending 으로 남는다(결정 5⁵).
//
// ORDER BY 의 첫 항이 클래스 간 기아를 막고(pending 우선), 뒤 세 항은 키셋 페이징의
// 전순서 요건이다. WHERE 의 행 비교는 이 ORDER BY 와 반드시 같은 순서여야 한다.
//
// 정렬 키 두 개(start_wall_utc·클래스)를 결과에 함께 실어 온다. 커서를 나중에 되읽으면
// 그 사이에 행의 상태가 전이했을 때 커서가 엉뚱한 클래스로 건너뛰어 남은 pending 을
// 통째로 건너뛴다. 같은 스냅샷에서 읽는 것이 유일하게 안전한 방법이다.
const pendingArchiveUploadsSQL = `
SELECT t.stream_id, t.seq, t.s3_key, t.local_path, t.bytes,
       NOT EXISTS (SELECT 1 FROM stream_segments s
                    WHERE s.stream_id = t.stream_id AND s.seq > t.seq) AS is_tail,
       (t.upload_state <> 'pending') AS is_failed, t.start_wall_utc,
       '', NULL::bytea
  FROM stream_segments t
 WHERE t.upload_state IN ('pending','failed')
   AND t.local_path IS NOT NULL
   AND t.bytes IS NOT NULL
   AND ( EXISTS (SELECT 1 FROM stream_segments s
                  WHERE s.stream_id = t.stream_id AND s.seq > t.seq)
         OR t.start_wall_utc < now() - make_interval(secs => $1::double precision) )
   AND ( (t.upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq )
       > ( $3::boolean, $4::timestamptz, $5::text, $6::bigint )
 ORDER BY (t.upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq
 LIMIT $2`

// pendingPlaybackUploadsSQL — ③ 축의 조회다. 인자 자리는 ② 와 같다.
//
// ② 와 갈리는 지점 여섯, 전부 "그 조각이 되감기 목록에 실릴 수 있는가"의 조건이다:
//
//  1. 상태 열이 playback_upload_state 다(② 의 열을 보면 ③ 이 영원히 안 돌거나 두 번 돈다).
//  2. **세션 INNER JOIN** — session_id 가 NULL 인 조각은 `settled` 가 거짓이라 목록에 실릴 수
//     없다(f19). 그런 조각의 ③ 를 만들면 아무도 안 읽는 객체만 쌓인다.
//  3. **컷오프 INNER JOIN + seq >= cutoff_seq**(T13) — 컷오프 미만은 되감기 창 밖이고,
//     컷오프가 아예 없는 스트림은 창 자체가 없다. 둘 다 조인 하나로 면제된다.
//     countPlaybackBacklogSQL 도 같은 두 줄을 쓴다(계약 5-5 6항 — 4축 소비자의 공통 술어).
//  4. **state <> 'ended'**(T7) — ending 은 아직 정산 창 안이라 계속 집는다. 거기서 멈추면
//     마지막 조각들이 빠진 채 목록이 굳는다.
//  5. **playback_s3_key IS NOT NULL** — 목록이 가리키는 객체 키다. 파생은 INSERT 때 한 번뿐이고
//     실패는 NULL 로 남는다(설계 5.2·계약 5-5 ⓑ) — 그 조각은 올릴 자리가 없다.
//  6. **playback_pdt IS NOT NULL** — PDT 가 없는 조각은 `settled` 가 거짓이다. "세션이 있으면
//     PDT 도 있다"는 쓰기 경로의 성질일 뿐 DB 제약이 아니라서 따로 거른다.
//
// 조각의 session_id 와 그 세션의 init_sha256 을 함께 실어 온다 — 워커가 init 대조를 하는 데
// 필요한 전부이며, 이것이 실리지 않으면 조각마다 DB 왕복이 하나씩 는다(조회 메서드 신설 0).
// 시간 도장 위치(PlaybackPos)는 싣지 않는다: 장부에 그 값이 없고, 워커가 회차별 보정값 표로
// 정한다(계획 4.2-R R3).
const pendingPlaybackUploadsSQL = `
SELECT t.stream_id, t.seq, t.playback_s3_key, t.local_path, t.bytes,
       NOT EXISTS (SELECT 1 FROM stream_segments s
                    WHERE s.stream_id = t.stream_id AND s.seq > t.seq) AS is_tail,
       (t.playback_upload_state <> 'pending') AS is_failed, t.start_wall_utc,
       t.session_id, sess.init_sha256
  FROM stream_segments t
  JOIN stream_sessions sess ON sess.session_id = t.session_id
  JOIN stream_cutoffs  cut  ON cut.stream_id   = t.stream_id
 WHERE t.playback_upload_state IN ('pending','failed')
   AND t.local_path IS NOT NULL
   AND t.bytes IS NOT NULL
   AND t.playback_s3_key IS NOT NULL
   AND t.playback_pdt IS NOT NULL
   AND sess.state <> 'ended'
   AND t.seq >= cut.cutoff_seq
   AND ( EXISTS (SELECT 1 FROM stream_segments s
                  WHERE s.stream_id = t.stream_id AND s.seq > t.seq)
         OR t.start_wall_utc < now() - make_interval(secs => $1::double precision) )
   AND ( (t.playback_upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq )
       > ( $3::boolean, $4::timestamptz, $5::text, $6::bigint )
 ORDER BY (t.playback_upload_state <> 'pending'), t.start_wall_utc, t.stream_id, t.seq
 LIMIT $2`

// pendingInitUploadsSQL — init 축의 조회다($1 은 쓰지 않는다: 꼬리 예외가 없다).
//
// 대상이 조각이 아니라 **세션**이라 형상이 둘과 다르다. 커서의 마지막 키도 seq 가 아니라
// session_id 다(설계 5.5.2 — 그 축의 유일성은 세션이 준다).
//
// LATERAL 로 세션의 **최신 조각**을 붙이는 이유: init 은 그 조각에서 **다시 만들어** 올리는 것이다
// (재수거 형상). 재포장 init 은 같은 송출 설정이면 회차 안 어느 조각에서 만들어도 같은 바이트라(설계 5.3ⓑ) 원천은 어느
// 조각이어도 되지만, 첫 조각에 묶으면 그 조각만 결정적으로 실패하는 회차(소리가 늦게 시작한 첫 조각
// 등)는 같은 실패를 영영 되풀이한다 — 최신 조각이면 첫 조각이 결정적으로 실패해도 다음 조각으로
// 회복한다(Phase 3 r4 cc #2). 붙일 조각이 없는 세션은 아직 만들 재료가 없으므로 이 조회에 나오지 않는다.
//
// S3Key 를 비워 내보내는 것이 계약이다 — init 키는 장부에 예약된 값이 아니라
// playback.InitKey 파생이고, index 는 그 패키지를 임포트하지 않는다(순환 방지).
//
// 인자 자리도 둘과 다르다($1 = limit, $2..$4 = 커서): 꼬리 예외가 없어 tailGrace 가 없고,
// 실패 클래스가 없어 정렬 첫 항도 없다(5.5.5 는 init 에 성공 문장 하나만 정의한다).
const pendingInitUploadsSQL = `
SELECT s.stream_id, 0::bigint, '', seg.local_path, seg.bytes,
       false AS is_tail, false AS is_failed, s.started_at,
       s.session_id, NULL::bytea
  FROM stream_sessions s
  JOIN LATERAL (
       SELECT g.local_path, g.bytes FROM stream_segments g
        WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
          AND g.local_path IS NOT NULL AND g.bytes IS NOT NULL
        ORDER BY g.seq DESC LIMIT 1) seg ON true
 WHERE s.init_uploaded_at IS NULL
   AND s.state <> 'ended'
   AND ( s.started_at, s.stream_id, s.session_id )
       > ( $2::timestamptz, $3::text, $4::text )
 ORDER BY s.started_at, s.stream_id, s.session_id
 LIMIT $1`

// countBacklogSQL 3벌 — 축 라벨은 수치와 함께 갈려야 한다(설계 5.5.4 #7).
//
// bytesNull 필터를 IN ('pending','failed') 로 못 박는다. 상태 열에 예상 밖 값이 들어오면
// "<> 'uploaded'" 만으로는 부분집합 관계가 깨진다.
const countArchiveBacklogSQL = `
SELECT count(*) FILTER (WHERE upload_state = 'pending'),
       count(*) FILTER (WHERE upload_state = 'failed'),
       count(*) FILTER (WHERE upload_state IN ('pending','failed') AND bytes IS NULL)
  FROM stream_segments WHERE local_path IS NOT NULL`

// countPlaybackBacklogSQL — ③ 는 세션 귀속 조각만 센다(세션 없는 조각은 대상이 아니다).
//
// **컷오프가 적용되는 행만 센다**(계약 5-5 6항 — 적용 = 컷오프 기록 있음 AND seq >= 컷오프).
// 결합과 seq 술어는 pendingPlaybackUploadsSQL 의 두 줄 그대로다. 둘이 갈라지면 재수거가 집지
// 않는 면제 행을 이 집계가 영구 적체로 보고한다. stream_cutoffs 는 stream_id 가 PK 라 조인이
// 행을 불리지 않는다.
const countPlaybackBacklogSQL = `
SELECT count(*) FILTER (WHERE t.playback_upload_state = 'pending'),
       count(*) FILTER (WHERE t.playback_upload_state = 'failed'),
       count(*) FILTER (WHERE t.playback_upload_state IN ('pending','failed') AND t.bytes IS NULL)
  FROM stream_segments t
  JOIN stream_cutoffs  cut  ON cut.stream_id   = t.stream_id
 WHERE t.local_path IS NOT NULL AND t.session_id IS NOT NULL
   AND t.seq >= cut.cutoff_seq`

// countInitBacklogSQL — init 은 조각이 아니라 세션을 센다. failed 열이 없으므로 둘째 값은
// 언제나 0 이다(5.5.5 는 init 에 성공 문장 하나만 정의한다). bytesNull 은 만들 재료가 없는
// 세션 수다 — 조각이 하나도 없어 재추출 자체가 불가능한 국면의 관측이다.
const countInitBacklogSQL = `
SELECT count(*), 0::bigint,
       count(*) FILTER (WHERE NOT EXISTS (
           SELECT 1 FROM stream_segments g
            WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id
              AND g.local_path IS NOT NULL AND g.bytes IS NOT NULL))
  FROM stream_sessions s
 WHERE s.init_uploaded_at IS NULL AND s.state <> 'ended'`

type pgUploadStore struct {
	pool *pgxpool.Pool
}

// NewUploadStore 는 PostgreSQL 구현을 돌려준다.
func NewUploadStore(pool *pgxpool.Pool) UploadStore {
	return &pgUploadStore{pool: pool}
}

func (s *pgUploadStore) MarkUploaded(ctx context.Context, streamID string, seq int64, expectBytes int64) (bool, error) {
	return s.mark(ctx, markUploadedSQL, "uploaded", streamID, seq, expectBytes)
}

func (s *pgUploadStore) MarkFailed(ctx context.Context, streamID string, seq int64, expectBytes int64) (bool, error) {
	return s.mark(ctx, markFailedSQL, "failed", streamID, seq, expectBytes)
}

func (s *pgUploadStore) MarkPlaybackUploaded(ctx context.Context, streamID string, seq, bytes int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, markPlaybackUploadedSQL, streamID, seq, bytes)
	if err != nil {
		return false, fmt.Errorf("③ 업로드 확정 실패 stream_id=%q seq=%d: %w", streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}

// MarkPlaybackFailed 는 사유에 따라 문장이 갈린다 — 갈래는 여기 하나뿐이다.
func (s *pgUploadStore) MarkPlaybackFailed(ctx context.Context, streamID string, seq int64, sessionID, reason string) (bool, error) {
	if reason != ReasonInitMismatch {
		tag, err := s.pool.Exec(ctx, markPlaybackFailedSQL, streamID, seq)
		if err != nil {
			return false, fmt.Errorf("③ 실패 확정 실패 stream_id=%q seq=%d reason=%q: %w",
				streamID, seq, reason, err)
		}
		return tag.RowsAffected() == 1, nil
	}

	// 결속 갈래의 앵커다 — 빈 값으로는 끝낼 세션을 특정할 수 없다.
	if sessionID == "" {
		return false, fmt.Errorf("init_mismatch 확정에 session_id 가 없다 stream_id=%q seq=%d", streamID, seq)
	}
	var marked int
	err := s.pool.QueryRow(ctx, markPlaybackInitMismatchSQL, streamID, seq, sessionID, reason).Scan(&marked)
	if err != nil {
		return false, fmt.Errorf("③ 실패·세션 분리 실패 stream_id=%q seq=%d session_id=%q: %w",
			streamID, seq, sessionID, err)
	}
	return marked == 1, nil
}

// MarkInitUploaded 는 세션 표를 겨눈다 — 공통 몸통(mark)을 쓰지 않는 이유가 그것이다.
// 표도 다르고 식별자도 (stream_id, seq) 가 아니라 session_id 하나다.
func (s *pgUploadStore) MarkInitUploaded(ctx context.Context, sessionID string, sha []byte, s3Key string, size int64, incompatible bool) (InitMark, error) {
	// 빈 해시는 앵커가 아니다. 써 넣으면 이후 모든 대조의 기준이 사라져 바이트를 한 번도
	// 만들지 않은 세션의 발행 게이트가 열린다(5.3ⓑ 보증 소멸).
	if len(sha) == 0 {
		return 0, fmt.Errorf("init 확정에 sha256 이 없다 session_id=%q", sessionID)
	}

	var (
		updated    int
		curSHA     []byte
		uploadedAt *time.Time
	)
	err := s.pool.QueryRow(ctx, markInitUploadedSQL, sessionID, sha, s3Key, size, incompatible).
		Scan(&updated, &curSHA, &uploadedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		// cur 가 비었다 = 그 세션 행이 없다. 불일치와는 다른 사건이다.
		return InitMarkMissing, nil
	}
	if err != nil {
		return 0, fmt.Errorf("init 업로드 확정 실패 session_id=%q: %w", sessionID, err)
	}

	switch {
	case updated == 1:
		return InitMarkSuccess, nil
	case uploadedAt != nil && bytes.Equal(curSHA, sha):
		return InitMarkAlreadySame, nil
	default:
		// 확정된 해시가 다르거나, 미확정인데 장부의 해시가 다르다 — 어느 쪽이든 그 세션의
		// MAP 은 이 바이트가 아니다.
		return InitMarkMismatch, nil
	}
}

// mark 는 두 CAS 의 공통 몸통이다. 오류일 때 marked 를 false 로 확정해 돌려주지만,
// 그 false 는 "거부"가 아니라 "판정 못 함"이다 — 호출자가 err 를 먼저 보는 것이 계약이다.
func (s *pgUploadStore) mark(ctx context.Context, query, target, streamID string, seq, expectBytes int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, query, streamID, seq, expectBytes)
	if err != nil {
		return false, fmt.Errorf("업로드 상태 확정 실패 target=%s stream_id=%q seq=%d: %w",
			target, streamID, seq, err)
	}
	return tag.RowsAffected() == 1, nil
}

// pendingQueryFor 는 축의 조회 문장과 인자를 함께 고른다.
//
// 둘을 한 자리에서 고르는 것이 계약이다 — 문장과 인자 목록은 축마다 다르고(init 은 자리
// 개수부터 다르다) 따로 고르면 자리 수가 어긋난 채 실행 시점에야 터진다.
func pendingQueryFor(axis Axis, tailGraceSecs float64, limit int, after SweepCursor) (string, []any, error) {
	switch axis {
	case AxisArchive:
		return pendingArchiveUploadsSQL,
			[]any{tailGraceSecs, limit, after.IsFailed, after.StartWall.UTC(), after.StreamID, after.Seq}, nil
	case AxisPlayback:
		return pendingPlaybackUploadsSQL,
			[]any{tailGraceSecs, limit, after.IsFailed, after.StartWall.UTC(), after.StreamID, after.Seq}, nil
	case AxisInit:
		return pendingInitUploadsSQL,
			[]any{limit, after.StartWall.UTC(), after.StreamID, after.SessionID}, nil
	default:
		return "", nil, fmt.Errorf("업로드 대상 조회의 축이 미판정이다(axis=%s)", axis)
	}
}

func (s *pgUploadStore) PendingUploads(ctx context.Context, axis Axis, tailGraceSecs float64, limit int, after SweepCursor) ([]UploadTarget, SweepCursor, error) {
	query, args, err := pendingQueryFor(axis, tailGraceSecs, limit, after)
	if err != nil {
		return nil, after, err
	}
	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, after, fmt.Errorf("업로드 대상 조회 실패 axis=%s: %w", axis, err)
	}
	defer rows.Close()

	// 커서는 마지막으로 읽은 행을 가리킨다. 행이 없으면 after 그대로 — 되감기지 않는다.
	next := after
	targets := make([]UploadTarget, 0, limit)
	for rows.Next() {
		var (
			t   UploadTarget
			key SweepCursor
		)
		if err := rows.Scan(&t.StreamID, &t.Seq, &t.S3Key, &t.LocalPath, &t.Bytes, &t.IsTail,
			&key.IsFailed, &key.StartWall, &t.SessionID, &t.ExpectedInitSHA); err != nil {
			return nil, after, fmt.Errorf("업로드 대상 스캔 실패 axis=%s: %w", axis, err)
		}
		// 축은 조회가 정한다 — 조회가 곧 그 축의 자격 술어이므로 산출도 정의상 그 축이다.
		// 축을 비워 내보내면 업로더가 미판정으로 보고 전부 거부한다(fail-closed).
		t.Axis = axis

		key.StreamID, key.Seq, key.SessionID = t.StreamID, t.Seq, t.SessionID
		// 저장은 UTC 강제지만 드라이버가 로컬 위치로 실어 올 수 있어 여기서 못 박는다(D6).
		key.StartWall = key.StartWall.UTC()

		targets = append(targets, t)
		next = key
	}
	if err := rows.Err(); err != nil {
		return nil, after, fmt.Errorf("업로드 대상 조회 중 오류 axis=%s: %w", axis, err)
	}
	return targets, next, nil
}

// backlogQueryFor 는 축의 잔량 집계 문장이다.
func backlogQueryFor(axis Axis) (string, error) {
	switch axis {
	case AxisArchive:
		return countArchiveBacklogSQL, nil
	case AxisPlayback:
		return countPlaybackBacklogSQL, nil
	case AxisInit:
		return countInitBacklogSQL, nil
	default:
		return "", fmt.Errorf("잔량 집계의 축이 미판정이다(axis=%s)", axis)
	}
}

func (s *pgUploadStore) CountBacklog(ctx context.Context, axis Axis) (int64, int64, int64, error) {
	query, err := backlogQueryFor(axis)
	if err != nil {
		return 0, 0, 0, err
	}
	var pending, failed, bytesNull int64
	if err := s.pool.QueryRow(ctx, query).Scan(&pending, &failed, &bytesNull); err != nil {
		return 0, 0, 0, fmt.Errorf("업로드 잔량 집계 실패 axis=%s: %w", axis, err)
	}
	return pending, failed, bytesNull, nil
}
