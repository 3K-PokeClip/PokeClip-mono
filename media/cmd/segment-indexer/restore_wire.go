package main

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// restoreStore 는 업로더에 넘기는 장부다 — 업로드 결과 CAS(index.UploadStore)에 ③ 사슬 복원이 읽는 조회 둘(upload.ChainLedger
// — POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)을 붙인다. 조회 둘은 index 의 패키지 함수라 UploadStore 의 가짜 구현들이 떠안지
// 않는다. 업로더는 이 장부와 S3 Putter(Getter 도 구현)를 받을 때만 복원한다.
type restoreStore struct {
	index.UploadStore
	pool *pgxpool.Pool
}

// newUploadStore 는 인덱서 풀 위의 업로더 장부다.
func newUploadStore(pool *pgxpool.Pool) index.UploadStore {
	return restoreStore{UploadStore: index.NewUploadStore(pool), pool: pool}
}

// PlaybackHoles 는 회차의 ③ 구멍(pending · failed)과 행마다의 닻이다.
func (s restoreStore) PlaybackHoles(ctx context.Context, sessionID string) ([]index.PlaybackHole, error) {
	return index.LoadPlaybackHoles(ctx, s.pool, sessionID)
}

// PlaybackChainRows 는 스트림의 seq 구간 [from, to] 장부 행이다(비귀속 행 포함).
func (s restoreStore) PlaybackChainRows(ctx context.Context, streamID string, from, to int64) ([]index.PlaybackChainRow, error) {
	return index.LoadPlaybackChainRows(ctx, s.pool, streamID, from, to)
}
