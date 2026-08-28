'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { isMarkHotkey, isTypingTarget } from './markKey';
import type { LiveHighlight } from './useLiveMockState';

// 수동 마킹(시안 1b) — 목업 데이터가 아니라 동작 시뮬레이션이다(usePlayerSimulation 계열).
//
// 실연동 때는 mark() 안이 「서버에 찍어 보내고 SSE로 되받는다」로 바뀌고, 되받기 전까지
// 자리를 지키는 pending만 남는다. 만들어진 카드가 useLiveMockState.highlights를 타고
// 들어오면 manualCards는 자연히 비고, 화면의 병합식은 그대로 성립한다.
//
// 리스너를 document에 다는 이유: 핫키의 쓸모가 "어디를 보고 있든 눌리는 것"이라
// 화면 컨테이너에 달면 포커스가 밖에 있을 때 죽는다. 대신 가드가 둘 붙는다 —
// 입력 중인 곳(isTypingTarget)과 이미 누군가 처리한 키(defaultPrevented).

/** 마킹부터 카드가 서기까지 — 시안의 「카드 만드는 중…」이 머무는 시간 */
export const CARD_CREATE_MS = 3000;

export interface ManualMarkingState {
  /** 카드를 만드는 중 — 버튼 아래 피드백과 목록 맨 앞 자리 표시가 이 값 하나로 함께 선다 */
  pending: boolean;
  /** 만드는 중인 카드의 시각 표기 — 쉬는 중이면 null */
  pendingLabel: string | null;
  /** 이 화면에서 찍어 만든 카드 — 최신이 앞 */
  manualCards: LiveHighlight[];
  mark: () => void;
}

function createManualCard(sequence: number, timestamp: string): LiveHighlight {
  return {
    id: `marked-${sequence}`,
    timestamp,
    title: `${timestamp} 수동 마킹`,
    meta: '수동 마킹 · 방금',
    status: 'manual',
    source: 'manual',
    // 방금 생긴 카드라는 표시 — 계약이 이미 가진 자리다
    emphasized: true,
  };
}

/**
 * @param timestamp 마킹으로 남길 시각 표기. 목업 단계에선 방송 경과 표기(stream.uptimeLabel)를
 *   그대로 쓴다 — 흐르는 시각은 플레이어 시뮬레이션 안에 있어 여기서 읽을 수 없고, 어차피
 *   시안 표기값과 같은 값이다.
 */
export function useManualMarking(timestamp: string): ManualMarkingState {
  const [pendingLabel, setPendingLabel] = useState<string | null>(null);
  const [manualCards, setManualCards] = useState<LiveHighlight[]>([]);
  const timer = useRef<number>(undefined);
  const sequence = useRef(0);
  // 상태가 아니라 ref로 막는 이유 — mark의 정체성이 pending마다 바뀌면 아래 리스너가 매번 재등록된다
  const busy = useRef(false);

  const mark = useCallback(() => {
    // 만드는 중에 또 누르면 앞 카드가 자리를 잃는다 — 시안에도 자리는 하나뿐이다
    if (busy.current) return;
    busy.current = true;
    setPendingLabel(timestamp);
    timer.current = window.setTimeout(() => {
      sequence.current += 1;
      setManualCards((prev) => [createManualCard(sequence.current, timestamp), ...prev]);
      setPendingLabel(null);
      busy.current = false;
    }, CARD_CREATE_MS);
  }, [timestamp]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // 플레이어 단축키 등 이미 누군가 처리한 키는 건드리지 않는다
      if (event.defaultPrevented) return;
      if (!isMarkHotkey(event)) return;
      if (isTypingTarget(event.target)) return;
      event.preventDefault();
      mark();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [mark]);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return { pending: pendingLabel !== null, pendingLabel, manualCards, mark };
}
