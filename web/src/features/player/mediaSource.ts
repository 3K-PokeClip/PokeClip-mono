'use client';

// 재생 소스 결정 — 서버 주소는 env에만 둔다 (web/README.md 하드코딩 금지 규칙).
// 영상 경로의 키가 있으면 진짜 LL-HLS({base}/{키}/index.m3u8), 없으면 스텁,
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
    // base의 트레일링 슬래시 정규화 — 배포 env에 슬래시를 붙여 넣으면 이중 슬래시 URL이
    // 되고, 302 세션 리다이렉트 엣지가 그 경로를 다른 리소스로 라우팅할 수 있다.
    return `${env.liveBaseUrl.replace(/\/+$/, '')}/${streamParam}/index.m3u8`;
  }
  return env.stubUrl || null;
}

/**
 * @param mediaKey 영상 경로의 키(POK-233) — 라이브 화면이 고른 방송의 `ingestStreamId`, 모르면 방송 번호. 주소의 `?stream=`은
 *   방송 번호라 여기서 영상 경로로 바로 쓰지 않는다(회차 번호면 그 이름의 경로가 없다). 방송 선택이 그 값을 받아 이 키를 만든다
 */
export function useMediaSource(mediaKey?: string | null): string | null {
  // process.env는 리터럴 접근만 빌드 타임에 인라이닝된다 (googleOAuth.ts 선례)
  return buildMediaSourceUrl(mediaKey || null, {
    stubUrl: process.env.NEXT_PUBLIC_MEDIA_STUB_URL,
    liveBaseUrl: process.env.NEXT_PUBLIC_MEDIA_LIVE_BASE_URL,
  });
}
