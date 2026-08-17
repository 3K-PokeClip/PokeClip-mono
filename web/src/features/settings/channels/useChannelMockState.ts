'use client';

import { useCallback, useState } from 'react';

// 디자인 1k 채널 연동 화면의 목업 상태 (POK-112 스텁).
// 연동 API가 아직 없어 치지직 연동 여부만 로컬 상태로 든다 —
// POK-112 본 구현에서 이 훅 내부만 실제 동의 화면 왕복으로 갈아끼우면 화면은 그대로 쓴다.

/** 연동됨 상태의 표기값 (디자인 1k 값 그대로) */
export const CHZZK_LINKED_META = '게임하는너구리 · 팔로워 4.2만 · 마지막 방송 오늘';

export interface ChannelMockState {
  chzzkLinked: boolean;
  linkChzzk: () => void;
  unlinkChzzk: () => void;
}

export function useChannelMockState(): ChannelMockState {
  // 신규 계정 서사(M2 온보딩)에 맞춰 미연동으로 시작한다.
  const [chzzkLinked, setChzzkLinked] = useState(false);

  const linkChzzk = useCallback(() => setChzzkLinked(true), []);
  const unlinkChzzk = useCallback(() => setChzzkLinked(false), []);

  return { chzzkLinked, linkChzzk, unlinkChzzk };
}
