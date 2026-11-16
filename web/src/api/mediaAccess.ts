'use client';

// 영상 요청의 출입 자격(POK-317). 출입증 쿠키를 실을지(mediaCredentials)와 출입증을 받을 구성인지(playbackConfigured)를
// 읽는다. 출입증을 받아 두는 곳은 playbackAccess.ts 이고, 이 파일은 그 결과(쿠키를 받았는가)만 기억한다.
// NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL 은 주소로 쓰지 않고 비었는지만 본다: 출입증 요청을 가르고(playbackAccess) 지난 방송 채팅
// 기다림을 가른다(LiveScreen). 값은 출입증 쿠키 경로 기준이다(web README 「컨테이너 이미지」 절). 서버 주소는 env 에만 둔다.

const BASE = (process.env.NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL ?? '').replace(/\/+$/, '');

export function playbackConfigured(): boolean {
  return BASE !== '';
}

/**
 * 영상 요청에 출입증 쿠키를 실을지(POK-317). clip이 출입증을 실제로 내준 뒤에만 싣는다: CDN 뒤(prod)는 쿠키가 없으면 403이고,
 * 로컬·dev 영상 서버(MediaMTX)는 Allow-Origin * 로만 답해 쿠키를 실은 요청의 응답을 브라우저가 버린다(2026-10-23 로컬 실측).
 * 서명 재료가 없는 clip(로컬·dev)은 출입증을 안 내주므로 따로 맞출 설정이 없다
 */
let playbackCookiesIssued = false;

export function markPlaybackCookiesIssued(): void {
  playbackCookiesIssued = true;
}

/** fetch의 credentials 값. 출입증을 받기 전에는 fetch 기본값(same-origin) 그대로다 */
export function mediaCredentials(): RequestCredentials {
  return playbackCookiesIssued ? 'include' : 'same-origin';
}
