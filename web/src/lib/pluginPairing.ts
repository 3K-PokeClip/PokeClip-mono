'use client';

import type { StreamKeyStatus } from '@/api/streamKeys';
import { track } from '@/lib/analytics';
import { useOnboardingStore } from '@/stores/onboarding';

// 플러그인 연결 사실(POK-608) — 서버가 주는 연결 시각(lastPairedAt) 하나로 판정한다.
// 홈(준비 단계·연결 상태 카드)과 설정 › 플러그인이 같은 상태 캐시를 읽고, 읽을 때마다 여기로 올린다.
// 코드 발급(issued)은 연결이 아니다: 2026-11-18~19 새 회원 넷이 코드를 받았지만 OBS에 넣은 사람은 없었다.

/** 이 브라우저가 마지막으로 센 연결 시각 — 같은 연결을 화면마다·폴링마다 다시 세지 않는다 */
const REPORTED_KEY = 'pc-plugin-paired-reported';
/** 저장소를 못 쓰는 브라우저(프라이빗 모드 등)에서만 쓰는 기억 */
let memoryFallback: string | null = null;

function lastReported(): string | null {
  try {
    return window.localStorage.getItem(REPORTED_KEY);
  } catch {
    return memoryFallback;
  }
}

function rememberReported(lastPairedAt: string): void {
  try {
    window.localStorage.setItem(REPORTED_KEY, lastPairedAt);
  } catch {
    memoryFallback = lastPairedAt;
  }
}

/** 연결했나 — 상태를 아직 못 읽었으면 null(「안 함」으로 단정하지 않는다) */
export function isPluginPaired(status: StreamKeyStatus | undefined): boolean | null {
  return status === undefined ? null : status.lastPairedAt !== undefined;
}

/**
 * 서버에서 읽은 연결 사실을 올린다.
 *  - 둘러보기 2단계 체크(pluginLinked)를 서버 값에 맞춘다. 내리기도 한다: 예전에는 코드 발급만으로 켰고
 *    그 값이 브라우저에 남아 있다.
 *  - 처음 보는 연결 시각이면 `plugin_paired`를 한 번 보낸다. 계정을 바꿔 가며 쓰는 브라우저는 다시 셀 수 있다
 *    (시각이 계정마다 달라서) — 사람 수로 보는 퍼널에는 영향이 없다.
 */
export function syncPluginPaired(status: StreamKeyStatus, seen: 'code_dialog' | 'later'): void {
  const paired = status.lastPairedAt !== undefined;
  const store = useOnboardingStore.getState();
  if (store.pluginLinked !== paired) store.setPluginLinked(paired);
  if (status.lastPairedAt === undefined || lastReported() === status.lastPairedAt) return;
  rememberReported(status.lastPairedAt);
  track('plugin_paired', { seen });
}
