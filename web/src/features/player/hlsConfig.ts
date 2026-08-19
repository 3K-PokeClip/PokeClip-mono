import type { HlsConfig } from 'hls.js';
import { LIVE_WINDOW_SECONDS } from './playerMath';

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
export const HLS_DVR_CONFIG = {
  // LL-HLS(EXT-X-PART·blocking reload) 활성 — LIVE 복귀 지점(liveSyncPosition)이
  // PART-HOLD-BACK 기준으로 잡힌다
  lowLatencyMode: true,
  // 배속 추격 비활성 고정 — 기본값(1)과 같지만, 라이브러리 기본값 변경에 흔들리지
  // 않도록 "catch-up 끄기"의 의도를 설정으로 못박는다 (1 = 재생 속도 조절 없음)
  maxLiveSyncPlaybackRate: 1,
  // 되감기 창(3600s) + 여유 100s — 재생점 뒤 버퍼를 창 경계에서 잘라내지 않는다.
  // 정확히 3600이면 1시간 전으로 시크한 지점의 세그먼트가 이미 축출됐을 수 있다.
  backBufferLength: LIVE_WINDOW_SECONDS + 100,
} as const satisfies Partial<HlsConfig>;
