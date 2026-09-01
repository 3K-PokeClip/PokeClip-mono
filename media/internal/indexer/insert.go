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
func (ix *Indexer) insertWithRetry(ctx context.Context, rec index.Record, seed index.Seed) (outcome index.InsertOutcome, seedRes index.SeedResult, poisoned bool, err error) {
	var lastErr error
	backoff := ix.opt.InsertRetryBase

	for attempt := range ix.opt.InsertRetryMax {
		result, res, insertErr := ix.store.Insert(ctx, rec, seed)
		if insertErr == nil {
			// 한 번이라도 통과했다면 전역 이상은 아니다. 산발적 poison 이 누적돼
			// 언젠가 프로세스를 죽이는 일이 없도록 여기서 기록을 지운다.
			ix.poisonStreak[rec.StreamID] = 0
			return result, res, false, nil
		}
		lastErr = insertErr

		// 컷오프 경합의 락 상한(55P03, 즉시 재시도 포함 — m1b)은 poison 도 fatal 도 아니다.
		// 이 조각 하나만 건너뛴다 — 파일은 다음 Scan 이 회수한다(유실 0). 백오프로 붙잡으면
		// D10 루프가 락 경합에 30초씩 볼모가 된다.
		if errors.Is(insertErr, index.ErrLockContended) {
			ix.log.Warn("insert_lock_contended",
				"stream_id", rec.StreamID, "seq", rec.Seq, "path", rec.LocalPath,
				"note", "락 경합으로 이 조각만 건너뛴다. 다음 Scan 이 회수한다")
			return index.InsertInserted, index.SeedResult{}, true, nil
		}

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
