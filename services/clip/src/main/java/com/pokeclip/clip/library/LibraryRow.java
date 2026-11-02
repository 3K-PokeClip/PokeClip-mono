package com.pokeclip.clip.library;

import java.time.Instant;

/**
 * {@link LibraryQuery}가 표에서 뽑은 줄 하나 — 편집본 번호·원본 방송 요약·최신 영상 번호·파생 상태.
 * 편집본 본문과 영상 본문은 안 담는다: 그것은 엔티티로 다시 읽어 {@link LibraryEntry}로 조립한다.
 *
 * @param clipId 지금까지 만든 영상 중 가장 최근 것. 없으면 {@code null}
 * @param status SQL이 파생한 {@link LibraryStatus#param()} 값
 */
record LibraryRow(long recipeId,
                  String streamId,
                  String broadcastStatus,
                  Instant startedAt,
                  Instant endedAt,
                  Instant vodExpiresAt,
                  Long clipId,
                  String status) {
}
