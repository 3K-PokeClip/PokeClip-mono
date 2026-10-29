'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  prefetchCardEditor,
  type CardEditorPrefetch,
} from '@/features/clips/editor/prefetchCardEditor';

// 카드의 「편집」을 언제 열까(POK-499). 편집기가 쓰는 영상 · 소리 · 파형을 카드가 화면에 보일 때 미리 받고, 다 오면 연다.
// 보이지 않는 카드는 받지 않는다: 한 화면에 카드가 많으면 받기가 몰린다.

/**
 * - loading: 아직 다 못 받았다(화면에 보이기 전도 여기다)
 * - pending: 녹화가 덜 올라왔다. PENDING_RECHECK_MS 뒤 다시 묻는다
 * - ready: 다 받았다
 * - failed: 받다가 실패했다. 다시 시도할 수 있고, 영원히 막히지 않게 「편집」은 연다(편집기가 제 화면에서 다시 받는다)
 */
export type CardEditorReadyState = 'loading' | 'pending' | 'ready' | 'failed';

/** 녹화가 덜 올라온 카드를 다시 묻는 간격. 녹화 조각이 4초마다 올라온다 */
export const PENDING_RECHECK_MS = 10_000;

export function useCardEditorReady(
  streamId: string,
  cardId: string,
  enabled: boolean,
  prefetch: (streamId: string, cardId: string) => Promise<CardEditorPrefetch> = prefetchCardEditor,
) {
  const [element, setElement] = useState<HTMLElement | null>(null);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState<CardEditorReadyState>('loading');
  const [attempt, setAttempt] = useState(0);

  // 한 번 보이면 보인 것으로 둔다: 가로로 넘기다 지나간 카드의 받기를 멈추면 돌아왔을 때 처음부터 다시 받는다
  useEffect(() => {
    if (element === null || visible) return undefined;
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return undefined;
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) setVisible(true);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [element, visible]);

  useEffect(() => {
    if (!enabled || !visible) return undefined;
    let cancelled = false;
    let recheck: ReturnType<typeof setTimeout> | undefined;
    // 다시 시도하면 다시 「불러오는 중」이다. 녹화를 기다리는 중의 재확인은 「기다림」 그대로 둔다
    setState((previous) => (previous === 'failed' ? 'loading' : previous));
    prefetch(streamId, cardId).then(
      (result) => {
        if (cancelled) return;
        setState(result);
        if (result === 'pending')
          recheck = setTimeout(() => setAttempt((n) => n + 1), PENDING_RECHECK_MS);
      },
      () => {
        if (!cancelled) setState('failed');
      },
    );
    return () => {
      cancelled = true;
      clearTimeout(recheck);
    };
  }, [enabled, visible, streamId, cardId, attempt, prefetch]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { ref: setElement, state, retry };
}
