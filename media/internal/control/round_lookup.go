package control

// 회차 조회 RoundLookup 의 구현(POK-196 M6-3b · 설계 M6 11절 종결 판). 처리기 · 1초 캐시 · 한도 · 자원 상한은 RoundRoute
// 하나이고(round.go), 여기는 DB 한 번 읽기와 응답 변환뿐이다.

import (
	"context"
	"errors"
	"log/slog"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

// roundReader 는 회차 하나의 국면을 읽는다 — 운영은 index.LookupRoundPlayback 이다.
type roundReader func(ctx context.Context, streamID, roundID string) (index.PlaybackState, error)

// NewRoundLookup 은 회차 경로의 lookup 이다. pool 은 인덱서 풀이다(따로 두지 않는다 — 동시 수는 RoundRoute 의 전역 예산
// 동시 2 가 묶는다). baseURL 은 REWIND_PUBLIC_BASE_URL 이다. 시한은 RoundRoute 가 ctx 로 건다.
//
//	읽기 오류(DB 오류 · ctx 시한 · 풀 고갈)  (_, Unavailable, err) — 처리기가 503 · 줄은 처리기가 묶어 쓴다
//	Unknown(회차 모름 · 만료)               (_, NotFound, nil) — 404
//	Live · Ending · VOD · Ended             FromPlaybackState 의 (Status, Found, nil) 그대로 — 회차 경로는 Live 를 거르지 않는다
//	변환 오류(vod_incomplete · out_of_range)  (_, Unavailable, err) — 503 · 장부 값이 뒤틀린 결함 표지라 ERROR 한 줄
func NewRoundLookup(pool *pgxpool.Pool, baseURL string, log *slog.Logger) RoundLookup {
	return newRoundLookup(func(ctx context.Context, streamID, roundID string) (index.PlaybackState, error) {
		return index.LookupRoundPlayback(ctx, pool, streamID, roundID)
	}, baseURL, log)
}

// newRoundLookup 은 읽기 자리를 받는 NewRoundLookup 이다(시험이 DB 없이 매핑표를 본다).
func newRoundLookup(read roundReader, baseURL string, log *slog.Logger) RoundLookup {
	return func(ctx context.Context, streamID, roundID string) (status.Status, status.Answer, error) {
		st, err := read(ctx, streamID, roundID)
		if err != nil {
			return status.Status{}, status.Unavailable, err
		}
		out, answer, err := status.FromPlaybackState(st, baseURL)
		if err != nil {
			reason := ""
			var inv *status.InvalidError
			if errors.As(err, &inv) {
				reason = inv.Reason
			}
			log.Error("control_round_lookup_invalid", "stream_id", streamID, "round_id", roundID, "reason", reason, "err", err)
			return status.Status{}, status.Unavailable, err
		}
		return out, answer, nil
	}
}
