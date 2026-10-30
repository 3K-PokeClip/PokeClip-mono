'use client';

import { useEffect, useRef, type RefObject } from 'react';
import { isForeignKeyTarget, playerShortcutForKey, type PlayerShortcut } from './playerKeys';

/**
 * 재생 막대 말풍선에 적힌 단축키(POK-518). 시킹 키와 달리 페이지 전체에서 듣는다: 버튼에 마우스를 올린 채 말풍선을 보고 누르면
 * 포커스는 플레이어 밖에 있다. 입력창 · 대화상자처럼 그 키가 다른 곳의 것이면 건드리지 않는다(isForeignKeyTarget).
 * 지시문은 렌더마다 새로 오므로 ref로 읽는다: 재생 상태가 바뀔 때마다 듣기를 다시 걸지 않는다
 */
export function usePlayerShortcuts(
  playerRef: RefObject<HTMLElement | null>,
  actions: Record<PlayerShortcut, () => void>,
) {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return;
      const shortcut = playerShortcutForKey(event);
      if (shortcut === null) return;
      if (isForeignKeyTarget(event.target, event.code, playerRef.current)) return;
      event.preventDefault();
      actionsRef.current[shortcut]();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [playerRef]);
}
