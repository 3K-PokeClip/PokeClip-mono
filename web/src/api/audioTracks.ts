'use client';

import { apiFetch } from './client';

// 오디오 트랙 이름(POK-240) — auth 서버. 여섯 칸 고정, 빈 칸은 null.
//   GET/PUT /api/auth/me/audio-tracks              내 트랙 이름(설정)
//   GET     /api/streamers/{id}/audio-tracks       그 스트리머의 트랙 이름(본인·위임 편집자만, 아니면 404)
// 칸 i = trackId i (계약6·ADR-017: 0 = 최종 믹스, 1~5 = 소스별).

export const TRACK_COUNT = 6;
export const TRACK_LABEL_MAX = 32;
export type TrackLabels = (string | null)[];

/** 이름이 없는 칸의 표기 — 서버 계약의 「빈 칸은 트랙 n」 */
export function trackDisplayName(index: number, label: string | null | undefined): string {
  if (label) return label;
  return index === 0 ? '최종 믹스(트랙 1)' : `트랙 ${index + 1}`;
}

export async function fetchMyTrackLabels(): Promise<TrackLabels> {
  const res = await apiFetch('/api/auth/me/audio-tracks');
  return ((await res.json()) as { labels: TrackLabels }).labels;
}

export async function saveMyTrackLabels(labels: TrackLabels): Promise<TrackLabels> {
  const res = await apiFetch('/api/auth/me/audio-tracks', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ labels }),
  });
  return ((await res.json()) as { labels: TrackLabels }).labels;
}

export async function fetchStreamerTrackLabels(
  streamerUserId: number | string,
): Promise<TrackLabels> {
  const res = await apiFetch(
    `/api/streamers/${encodeURIComponent(String(streamerUserId))}/audio-tracks`,
  );
  return ((await res.json()) as { labels: TrackLabels }).labels;
}
