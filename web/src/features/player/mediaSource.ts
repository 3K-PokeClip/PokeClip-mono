'use client';

import { useSearchParams } from 'next/navigation';

// 재생 소스 결정 — 서버 주소는 env에만 둔다 (web/README.md 하드코딩 금지 규칙).
// ?stream=<id>가 있으면 진짜 LL-HLS({base}/{id}/index.m3u8), 없으면 스텁,
// env가 비어 있으면 null → GlassPlayer가 시뮬레이션으로 폴백한다.

/** 스트림 경로 가드 — infra/dev-media/player.html과 같은 규칙 */
const STREAM_ID_RE = /^[A-Za-z0-9_-]+$/;

export interface MediaSourceEnv {
  stubUrl?: string;
  liveBaseUrl?: string;
}

export function buildMediaSourceUrl(
  streamParam: string | null,
  env: MediaSourceEnv,
): string | null {
  if (streamParam && STREAM_ID_RE.test(streamParam) && env.liveBaseUrl) {
    return `${env.liveBaseUrl}/${streamParam}/index.m3u8`;
  }
  return env.stubUrl || null;
}

export function useMediaSource(): string | null {
  const params = useSearchParams();
  // process.env는 리터럴 접근만 빌드 타임에 인라이닝된다 (googleOAuth.ts 선례)
  return buildMediaSourceUrl(params.get('stream'), {
    stubUrl: process.env.NEXT_PUBLIC_MEDIA_STUB_URL,
    liveBaseUrl: process.env.NEXT_PUBLIC_MEDIA_LIVE_BASE_URL,
  });
}
