'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  prefetchCardEditor,
  type CardEditorPrefetch,
} from '@/features/clips/editor/prefetchCardEditor';

// 카드의 「편집」을 언제 열까(POK-499). 편집기가 쓰는 영상 · 소리 · 파형을 카드가 화면에 보일 때 미리 받고, 다 오면 연다.
// 보이지 않는 카드는 받지 않는다: 한 화면에 카드가 많으면 받기가 몰린다. 차례를 기다리다 화면을 벗어난 카드도 줄에서 뺀다: 맡겨 둘
// 자리는 넷뿐이라(editorMedia) 훑고 지나간 카드까지 받으면 받은 것을 버린다.

/**
 * - loading: 아직 다 못 받았다(화면에 보이기 전도 여기다)
 * - pending: 녹화가 덜 올라왔다. PENDING_RECHECK_MS 뒤 다시 묻고, PENDING_MAX_RECHECKS번 넘게 안 오면 failed다
 * - ready: 다 받았다
 * - failed: 받다가 실패했다. 다시 시도할 수 있고, 영원히 막히지 않게 「편집」은 연다(편집기가 제 화면에서 다시 받는다)
 */
export type CardEditorReadyState = 'loading' | 'pending' | 'ready' | 'failed';

/** 녹화가 덜 올라온 카드를 다시 묻는 간격. 녹화 조각이 4초마다 올라온다 */
export const PENDING_RECHECK_MS = 10_000;
/**
 * 녹화를 이만큼 다시 물어도(1분) 안 올라오면 실패로 넘긴다. 방송 중에 녹화가 멈추면(녹화 서버 장애 · 재생 서버 없음) 녹화를
 * 기다리는 카드가 방송이 끝날 때까지 막혔다(로컬 리뷰 1라운드)
 */
export const PENDING_MAX_RECHECKS = 6;

const prefetchForCard = (streamId: string, cardId: string, signal: AbortSignal) =>
  prefetchCardEditor(streamId, cardId, undefined, signal);

export function useCardEditorReady(
  streamId: string,
  cardId: string,
  enabled: boolean,
  prefetch: (
    streamId: string,
    cardId: string,
    signal: AbortSignal,
  ) => Promise<CardEditorPrefetch> = prefetchForCard,
) {
  const [element, setElement] = useState<HTMLElement | null>(null);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState<CardEditorReadyState>('loading');
  const [attempt, setAttempt] = useState(0);
  /** 녹화를 기다리며 다시 물은 횟수 */
  const [rechecks, setRechecks] = useState(0);
  // 받기를 마쳤다(ready · failed). 마친 카드는 화면을 드나들어도 다시 받지 않는다. 다시 시도만 되돌린다
  const settled = useRef(false);

  useEffect(() => {
    if (element === null) return undefined;
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return undefined;
    }
    const observer = new IntersectionObserver((entries) => {
      const latest = entries[entries.length - 1];
      if (latest !== undefined) setVisible(latest.isIntersecting);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [element]);

  useEffect(() => {
    if (!enabled || !visible || settled.current) return undefined;
    let cancelled = false;
    let recheck: ReturnType<typeof setTimeout> | undefined;
    const abandon = new AbortController();
    // 다시 시도하는 동안에도 상태를 그대로 둔다: 실패로 이미 연 「편집」을 다시 잠그지 않는다(로컬 리뷰 1라운드)
    prefetch(streamId, cardId, abandon.signal).then(
      (result) => {
        if (cancelled) return;
        if (result === 'ready') {
          settled.current = true;
          setState('ready');
          return;
        }
        if (rechecks >= PENDING_MAX_RECHECKS) {
          settled.current = true;
          setState('failed');
          return;
        }
        // 실패로 연 「편집」은 다시 시도가 녹화 기다림으로 돌아와도 잠그지 않는다(PR #458 codex). 확인은 그대로 이어 간다
        setState((previous) => (previous === 'failed' ? 'failed' : 'pending'));
        recheck = setTimeout(() => setRechecks((n) => n + 1), PENDING_RECHECK_MS);
      },
      () => {
        if (cancelled) return;
        settled.current = true;
        setState('failed');
      },
    );
    return () => {
      cancelled = true;
      clearTimeout(recheck);
      abandon.abort();
    };
  }, [enabled, visible, streamId, cardId, attempt, rechecks, prefetch]);

  // 다시 시도는 녹화 기다림 횟수도 처음부터 센다
  const retry = useCallback(() => {
    settled.current = false;
    setRechecks(0);
    setAttempt((n) => n + 1);
  }, []);
  return { ref: setElement, state, retry };
}
