import type { HlsConfig } from 'hls.js';
import { MAX_RANGE_SECONDS } from './timelineMath';

// 편집기 미리보기(VOD)용 hls.js 설정 (POK-238).
//
// **라이브의 HLS_DVR_CONFIG 를 그대로 쓰면 안 된다.** 그쪽 backBufferLength 는 3700초 —
// 되감기 창 전체를 버리지 않겠다는 뜻이다. 편집기 소스는 10분이 ~460MB라 그 값이면 브라우저
// MSE 버퍼 예산을 넘겨 강제 플러시가 일어나고 재생이 튄다.
//
// 이 소스는 EXT-X-PLAYLIST-TYPE:VOD 라 liveSyncPosition 이 없다 — 계약3 4절(catch-up 끄기)의
// 대상이 아니고, useHlsPlayback 의 DVR 시크 경로도 여기서는 의미가 없다.
export const EDITOR_HLS_VOD_CONFIG = {
  // VOD 다 — 저지연 파트 요청·블로킹 리로드를 걸 이유가 없다
  lowLatencyMode: false,
  // POK-197: 우리 분리 조각과 궁합 결함이 확인돼 금지된 실험 기능. 기본값도 false지만
  // 라이브러리 기본값이 바뀌어도 흔들리지 않게 설정으로 못박는다.
  progressive: false,
  // 선택 구간 전체를 뒤 버퍼에 남긴다 — 구간 반복이 돌 때마다 시작 조각을 다시 받으면
  // 이음매에서 끊긴다. 상한(3분)에 여유 1분.
  backBufferLength: MAX_RANGE_SECONDS + 60,
  // 앞 버퍼는 짧게 — 편집 중에는 구간 근처만 보므로 소스 전체를 미리 받을 이유가 없다.
  maxBufferLength: 30,
  maxMaxBufferLength: 120,
} as const satisfies Partial<HlsConfig>;
