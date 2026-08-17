'use client';

import { useCallback } from 'react';
import { useOnboardingHydration, useOnboardingStore } from '@/stores/onboarding';

// 디자인 1k 채널 연동 화면의 목업 상태 (POK-112 스텁).
// 연동 여부는 온보딩 상태(POK-113)와 공유한다 — 시작 가이드 1단계 완료 체크가 이 값에서 나온다.
// POK-112 본 구현에서 이 훅 내부만 실제 동의 화면 왕복으로 갈아끼우면 화면은 그대로 쓴다.

/** 연동됨 상태의 표기값 (디자인 1k 값 그대로) */
export const CHZZK_LINKED_META = '게임하는너구리 · 팔로워 4.2만 · 마지막 방송 오늘';

export interface ChannelMockState {
  chzzkLinked: boolean;
  linkChzzk: () => void;
  unlinkChzzk: () => void;
}

export function useChannelMockState(): ChannelMockState {
  useOnboardingHydration();
  // 신규 계정 기본값은 미연동 — 스토어 초기값(false)이 그 서사를 든다.
  const chzzkLinked = useOnboardingStore((s) => s.channelLinked);
  const setChannelLinked = useOnboardingStore((s) => s.setChannelLinked);

  const linkChzzk = useCallback(() => setChannelLinked(true), [setChannelLinked]);
  const unlinkChzzk = useCallback(() => setChannelLinked(false), [setChannelLinked]);

  return { chzzkLinked, linkChzzk, unlinkChzzk };
}
