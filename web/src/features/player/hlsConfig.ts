import type { HlsConfig } from 'hls.js';
import { mediaCredentials } from '@/api/mediaPlayback';

// 계약3 4절 1번 "catch-up 끄기" — POK-31 스파이크에서 확정한 설정 (hls.js 1.7 기준 실측).
// catch-up이 살아 있으면 되감기 중 플레이어가 스스로 라이브로 점프해 "DVR이 안 되는
// 것처럼" 보인다 — 과거 서버 탓 오판정의 원인.
//
// hls.js의 catch-up은 두 갈래이고, 1.7 기본값은 둘 다 꺼져 있다:
//   1) 강제 시크 — liveMaxLatencyDurationCount 초과 시. 기본값 Infinity = 발동 안 함.
//   2) 배속 추격 — maxLiveSyncPlaybackRate. 기본값 1 = 비활성.
//
// 티켓 후보였던 `liveMaxLatencyDurationCount: 1e9` 명시는 쓰지 않는다 — 명시하면
// hls.js 검증이 liveSyncDurationCount 명시까지 요구하고(미명시 시 생성자 throw),
// 그 명시는 LL-HLS의 PART-HOLD-BACK 저지연 타겟을 무시하게 만들어 LIVE 복귀
// 지점이 엣지에서 ~17초 뒤로 밀린다 (둘 다 POK-31 실행으로 확인).
//
// 라이브 · 되감기 재생기는 이 바탕을 펼치고 뒤 버퍼만 덮는다(POK-200 M5 · 설계 5.4절). 되감기 목록도 ENDLIST 없는 미닫이 live
// 목록이라 catch-up 끔은 두 설정 모두의 계약이다(hlsConfig.test.ts 가 두 설정을 다 본다).
export const HLS_BASE_CONFIG = {
  // LL-HLS(EXT-X-PART·blocking reload) 활성 — LIVE 복귀 지점(liveSyncPosition)이
  // PART-HOLD-BACK 기준으로 잡힌다
  lowLatencyMode: true,
  // 배속 추격 비활성 고정 — 기본값(1)과 같지만, 라이브러리 기본값 변경에 흔들리지
  // 않도록 "catch-up 끄기"의 의도를 설정으로 못박는다 (1 = 재생 속도 조절 없음)
  maxLiveSyncPlaybackRate: 1,
  // 출입증 쿠키(POK-317). CDN 뒤 조각은 쿠키가 없으면 403이다. 요청마다 읽으므로 출입증을 받은 뒤 요청부터 실린다.
  // 받기 전(로컬·dev)에는 안 싣는다: 로컬 영상 서버는 Allow-Origin * 라 켜면 브라우저가 조각을 버린다
  xhrSetup: (xhr: XMLHttpRequest) => {
    xhr.withCredentials = mediaCredentials() === 'include';
  },
} as const satisfies Partial<HlsConfig>;

/**
 * 라이브 재생기. 1시간 전으로의 시크는 되감기 재생기로 갈아 끼우므로(설계 5.3절) 뒤 버퍼는 짧게 둔다(메모리 실측 대상)
 */
export const HLS_LIVE_CONFIG = {
  ...HLS_BASE_CONFIG,
  backBufferLength: 90,
} as const satisfies Partial<HlsConfig>;

/** 되감기 · 다시보기 재생기. 목록 안 뒤로 가기를 받으려고 라이브보다 조금 길게 둔다(메모리 실측 대상) */
export const HLS_REWIND_CONFIG = {
  ...HLS_BASE_CONFIG,
  backBufferLength: 120,
} as const satisfies Partial<HlsConfig>;
