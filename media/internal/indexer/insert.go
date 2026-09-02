// 이 파일은 H9(INSERT)의 실패 분류와 재시도만 담당한다.
//
// 판정(indexer.go)과 나눈 이유: "무엇을 기록할지"와 "기록이 실패했을 때 어떻게 할지"는
// 바뀌는 이유가 다르다. 전자는 재생 규약이, 후자는 DB 운영 사정이 바꾼다.
package indexer

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgconn"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// insertFate 는 INSERT 에러를 어떻게 다룰지 셋으로 가른다.
type insertFate int

const (
	// fateRetry — 시간이 지나면 나아질 수 있는 실패(연결 끊김, 자원 부족, 운영자 개입).
	fateRetry insertFate = iota
	// fatePoison — 몇 번을 다시 넣어도 같은 결과인 실패. 그 세그먼트만 격리하고 계속 간다.
	fatePoison
	// fateFatal — 설정이나 스키마가 잘못됐다는 뜻. 사람이 고쳐야 하므로 프로세스를 끝낸다.
	fateFatal
)

// classifyInsertError 는 SQLSTATE 앞 두 자리(클래스)로 대응을 정한다.
//
// 재시도해서 될 일과 안 될 일을 가르지 않으면, 잘못된 값 하나가 30초씩 파이프라인을
// 붙잡고 끝내 프로세스를 죽인다. 그 사이 멀쩡한 세그먼트들도 함께 밀린다.
func classifyInsertError(err error) insertFate {
	// 경합은 SQLSTATE 보다 먼저 본다. 세션 경합은 sentinel 과 23505 PgError 를 한 연쇄에
	// 함께 감싸고 오므로(index.sessionError), 클래스만 보면 무결성 위반=fateFatal 로 읽힌다.
	// 배타성을 호출부의 if/else 순서가 아니라 이 함수의 성질로 못 박는다.
	if _, _, ok := contentionSignal(err); ok {
		return fateRetry
	}

	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		// 네트워크 오류, 타임아웃 등 드라이버 계층 실패는 재시도 가치가 있다.
		return fateRetry
	}

	switch class := sqlStateClass(pgErr.Code); class {
	case "08", "40", "53", "55", "57", "58":
		// 08 연결 예외 / 40 직렬화 실패·데드락 / 53 자원 부족 / 55 락 획득 실패 /
		// 57 운영자 개입(셧다운 등) / 58 외부 IO 오류. 전부 시간이 지나면 나아질 수 있다.
		return fateRetry
	case "22":
		// 22 데이터 예외(범위 초과, 잘못된 바이트열, 길이 초과). 그 행의 값이 원인인 것이 확실하다.
		// 다음 세그먼트는 멀쩡할 수 있으므로 이 행만 건너뛴다.
		return fatePoison
	default:
		// 23 무결성 위반(23505 는 Store 가 InsertOutcome 으로 이미 걸러 낸다),
		// 54 전역 한도, 42 문법·권한, 28 인증 등.
		//
		// 이것들은 "이 행이 이상하다"가 아니라 "스키마나 설정이 어긋났다"는 신호다.
		// 예를 들어 23502(NOT NULL 위반)는 정본 마이그레이션이 우리 코드와 안 맞는다는 뜻인데,
		// 행 단위로 건너뛰며 계속 가면 인덱스가 조용히 비어 간다. 사람이 봐야 한다.
		return fateFatal
	}
}

// contentionSignal 은 **재시도가 스스로 푸는 경합** 두 갈래를 가려낸다.
// isContention=false 면 경합이 아니라 일반 실패이고 classifyInsertError 가 처분한다.
// (그 함수도 이 신호를 먼저 읽어 fateRetry 를 돌린다 — 처분이 호출 순서에 걸리지 않게.)
//
// 둘의 처분은 같다(H9 백오프)지만 신호 이름을 나누는 것은 원인이 다르기 때문이다:
// 락 경합은 단일 쓰기자 전제(D10)가 흔들린다는 신호이고, 세션 경합은 같은 스트림의
// 세션을 두 시도가 동시에 열려 한 정상 동시성이다(재시도하면 귀속 갈래로 접힌다).
//
// **세션 id 파생 규칙의 결함(stream_sessions_pkey)은 여기 오지 않는다** — 그 갈래는
// 재시도해도 같은 id 를 다시 만들어 크래시루프가 되므로 무결성 오류 경로로 가서
// 프로세스를 끝내고 사람을 부른다(계획 4.3 ⒏).
func contentionSignal(err error) (event, note string, isContention bool) {
	switch {
	case errors.Is(err, index.ErrLockContended):
		return "insert_lock_contended", "단일 쓰기자 전제 확인 필요. 백오프 후 재시도한다", true
	case errors.Is(err, index.ErrSessionContended):
		return "session_open_contended", "다른 시도가 세션을 먼저 열었다. 백오프 후 재시도하면 귀속으로 접힌다", true
	}
	return "", "", false
}

func sqlStateClass(code string) string {
	if len(code) < 2 {
		return ""
	}
	return code[:2]
}

// insertWithRetry 는 H9 의 지수 백오프 재시도다.
// 재시도 상한을 소진하면 에러를 올린다 -> main 이 exit 1 -> compose 재기동 -> Scan 복구(D8).
//
// poisoned 가 true 면 이 세그먼트 하나만 버리고 프로세스는 계속 간다.
// 에러·poison 반환의 outcome 값은 무의미한 채움이다 — 호출부는 err·poisoned 를 먼저 본다.
func (ix *Indexer) insertWithRetry(ctx context.Context, rec index.Record, seed index.Seed, src index.SessionSource) (outcome index.InsertOutcome, seedRes index.SeedResult, poisoned bool, err error) {
	var lastErr error
	backoff := ix.opt.InsertRetryBase

	for attempt := range ix.opt.InsertRetryMax {
		result, res, insertErr := ix.store.Insert(ctx, rec, seed, src)
		if insertErr == nil {
			// 한 번이라도 통과했다면 전역 이상은 아니다. 산발적 poison 이 누적돼
			// 언젠가 프로세스를 죽이는 일이 없도록 여기서 기록을 지운다.
			ix.poisonStreak[rec.StreamID] = 0
			return result, res, false, nil
		}
		lastErr = insertErr

		// 락 상한 이중 초과(55P03 — store 층 즉시 1회 재시도까지 실패)는 단일 쓰기자
		// 전제(D10)가 흔들린다는 신호라 별도 이름으로 기록한다. 처분은 일반 재시도와
		// 같다 — 이 고루틴이 이 조각을 물고 백오프하는 동안은 후속 조각이 seq 를 선점할
		// 수 없다(D10 단일 호출자 = 순서 보전이 구조적으로 보장되는 유일한 구간). 여기서
		// 즉시 프로세스를 끝내면 재기동 초기 수집(비동기 발사)과 실시간 유입이 같은
		// select 에서 경합해 이 조각이 H3 로 영구 유실될 수 있다(리뷰 r4 반증 — D8
		// 재기동은 회수 순서를 보장하지 않는다). 경합이 계속되면 아래 상한 소진 경로가
		// 기존 처분(D8)대로 프로세스를 끝낸다 — 조각 단위 건너뛰기·보류가 H3 와 충돌해
		// 반복 반증된 것(리뷰 r1~r3)과 달리, 이 경로는 기존 H9 정책의 재사용이다.
		if event, note, isContention := contentionSignal(insertErr); isContention {
			ix.log.Warn(event, "stream_id", rec.StreamID, "seq", rec.Seq,
				"attempt", attempt+1, "err", insertErr, "note", note)
		} else {
			switch classifyInsertError(insertErr) {
			case fatePoison:
				ix.poisonStreak[rec.StreamID]++
				streak := ix.poisonStreak[rec.StreamID]
				ix.log.Error("insert_poisoned",
					"stream_id", rec.StreamID, "seq", rec.Seq, "path", rec.LocalPath,
					"err", insertErr, "streak", streak,
					"note", "재시도해도 같은 결과다. 이 세그먼트만 건너뛴다")

				if streak >= ix.opt.PoisonStreakMax {
					// 연달아 난다는 것은 개별 행이 아니라 전역이 이상하다는 뜻이다.
					// 시간축으로 두 문제를 갈라내는 지점이 여기다.
					ix.log.Error("poison_streak_exceeded",
						"stream_id", rec.StreamID, "streak", streak, "limit", ix.opt.PoisonStreakMax)
					return index.InsertInserted, index.SeedResult{}, false, fmt.Errorf(
						"연속 poison INSERT %d회 stream_id=%q: %w", streak, rec.StreamID, insertErr)
				}
				return index.InsertInserted, index.SeedResult{}, true, nil
			case fateFatal:
				return index.InsertInserted, index.SeedResult{}, false, fmt.Errorf(
					"복구 불가한 INSERT 오류 stream_id=%q seq=%d: %w", rec.StreamID, rec.Seq, insertErr)
			}

			ix.log.Warn("insert_retry", "stream_id", rec.StreamID, "seq", rec.Seq,
				"attempt", attempt+1, "err", insertErr)
		}

		if attempt == ix.opt.InsertRetryMax-1 {
			break
		}
		if !sleepCtx(ctx, backoff) {
			return index.InsertInserted, index.SeedResult{}, false, ctx.Err()
		}
		backoff *= 2
	}
	return index.InsertInserted, index.SeedResult{}, false, fmt.Errorf("INSERT 재시도 상한 소진 stream_id=%q seq=%d: %w",
		rec.StreamID, rec.Seq, lastErr)
}
