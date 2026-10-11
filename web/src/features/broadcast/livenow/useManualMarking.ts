'use client';

import { useCallback, useEffect } from 'react';
import { useToast } from '@/ui';
import { isMarkHotkey, isTypingTarget } from './markKey';

// 수동 마킹(시안 1b) — 누른 순간을 서버에 카드로 남기는 길이 아직 없다(POK-257, MVP 밖).
//
// 전에는 3초 뒤 화면에만 카드를 세웠다. 그 카드는 새로 고침에 사라지고 편집기도 못 찾는, 저장된 것처럼 보이는
// 가짜 성공이었다. 지난 방송에서는 방송 끝 시각에 찍혔다(QA 2026-10-11). 그래서 단추와 핫키는 두고 준비 중이라고만
// 말한다(없는 기능은 「준비 중」, ADR-077). 서버 길이 생기면 mark() 안이 「서버에 찍어 보내고 통로로 되받는다」가 되고,
// 되받기 전까지의 자리는 StreamInfoBar·HighlightCardPanel의 pendingLabel이 그린다.
//
// 리스너를 document에 다는 이유: 핫키의 쓸모가 "어디를 보고 있든 눌리는 것"이라
// 화면 컨테이너에 달면 포커스가 밖에 있을 때 죽는다. 대신 가드가 둘 붙는다 —
// 입력 중인 곳(isTypingTarget)과 이미 누군가 처리한 키(defaultPrevented).

export function useManualMarking(): { mark: () => void } {
  const { toast } = useToast();

  const mark = useCallback(() => {
    toast({
      tone: 'info',
      title: '준비 중인 기능이에요',
      description: '수동 마킹은 아직 준비 중이에요. 준비되면 알려드릴게요.',
    });
  }, [toast]);

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

  return { mark };
}
