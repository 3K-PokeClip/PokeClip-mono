'use client';

import { useCallback, useEffect, useRef } from 'react';
import { fetchRewindStatus, type RewindStatusResult } from '@/api/rewindStatus';

// 되감기 제어면 경로 하나를 폴링한다(설계 5.1절, POK-200 M5). 켜고 끄는 것은 재생 세션이 경로별로 정한다: 경로가 null 이면
// 묻지 않는다. 답은 받을 때마다 onResult 로 올린다(잠시 불가도 올린다 — 직전 상태를 둘지는 받는 쪽이 정한다).
// 순번으로 늦게 온 답을 버리고(R14), 경로가 바뀌거나 내려가면 걸린 요청을 끊는다. 요청마다 시한을 두고, 답 · 시한 · 오류 어느
// 갈래에서도 다음 조회를 건다 — 답이 끝나지 않는 요청 하나가 폴링을 멈추지 못한다(cx H1). 주기는 요청을 시작한 때부터 센다.

/** 폴링 주기. 10초는 D17 결정(치지직 실물)이다 */
export const REWIND_POLL_MS = 10_000;
/** 같은 url 이 다시 올 때의 재조회 간격 · 횟수 — CDN · 브라우저 1초 캐시를 넘긴다(R16) */
export const REWIND_RETRY_MS = 2_000;
const REWIND_RETRY_COUNT = 3;
/** 요청 하나의 시한 — 폴링 주기보다 짧아 다음 주기를 밀지 않는다. 넘으면 끊고 「잠시 불가」로 올린다 */
export const REWIND_REQUEST_TIMEOUT_MS = 8_000;

export interface RewindStatusPoll {
  /**
   * 지금 곧바로 다시 묻는다(숨김 복귀 · 명부 ended · 끝남 대기 진입). retryWhileUrl 을 주면 답의 url 이 그 값과 같은 동안
   * 2초 간격으로 세 번까지 더 묻고 10초로 돌아간다(R16 — ENDLIST 뒤 같은 url)
   */
  refresh: (retryWhileUrl?: string) => void;
}

/**
 * @param url 물을 제어면 경로. null 이면 끈다
 * @param controlBase 제어면 주소(답의 url 출처 대조). null 이면 끈다
 * @param onResult 답 하나마다 부른다. 늦게 온 답 · 끊은 요청은 부르지 않는다
 */
export function useRewindStatus(
  url: string | null,
  controlBase: string | null,
  onResult: (result: RewindStatusResult) => void,
): RewindStatusPoll {
  const onResultRef = useRef(onResult);
  onResultRef.current = onResult;
  const refreshRef = useRef<(retryWhileUrl?: string) => void>(() => {});

  useEffect(() => {
    if (url === null || controlBase === null) {
      refreshRef.current = () => {};
      return undefined;
    }
    let seq = 0;
    let inFlight: AbortController | null = null;
    let timer: number | undefined;
    let retryWhileUrl: string | null = null;
    let retriesLeft = 0;
    let deadline: number | undefined;

    /** 답 하나를 올리고 다음 조회를 건다. 받는 쪽이 던져도 다음 조회는 건다(cc L1) */
    const settle = (result: RewindStatusResult, startedAt: number) => {
      try {
        onResultRef.current(result);
      } catch (e) {
        console.error('[rewind] 제어면 답 처리 실패:', e);
      }
      const sameUrl =
        retryWhileUrl !== null && result.kind === 'ok' && result.status.url === retryWhileUrl;
      if (sameUrl && retriesLeft > 0) {
        retriesLeft -= 1;
        timer = window.setTimeout(ask, REWIND_RETRY_MS);
        return;
      }
      retryWhileUrl = null;
      timer = window.setTimeout(ask, Math.max(0, startedAt + REWIND_POLL_MS - Date.now()));
    };

    const ask = () => {
      window.clearTimeout(timer);
      window.clearTimeout(deadline);
      inFlight?.abort();
      const controller = new AbortController();
      inFlight = controller;
      seq += 1;
      const mine = seq;
      const startedAt = Date.now();
      let timedOut = false;
      deadline = window.setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, REWIND_REQUEST_TIMEOUT_MS);
      fetchRewindStatus(url, controlBase, controller.signal).then(
        (result) => {
          if (mine !== seq) return;
          window.clearTimeout(deadline);
          inFlight = null;
          settle(result, startedAt);
        },
        () => {
          // 끊은 요청만 여기로 온다(fetchRewindStatus 는 그 밖의 실패를 결과로 접는다). 새 요청 · 내려감이 끊었으면 버리고,
          // 시한이 끊었으면 「잠시 불가」로 올린 뒤 다음 조회를 건다
          if (mine !== seq || !timedOut) return;
          inFlight = null;
          settle({ kind: 'unavailable' }, startedAt);
        },
      );
    };

    refreshRef.current = (sameAs?: string) => {
      retryWhileUrl = sameAs ?? null;
      retriesLeft = sameAs === undefined ? 0 : REWIND_RETRY_COUNT;
      ask();
    };
    // 숨김 복귀는 곧바로 묻기만 한다 — 진행 중인 같은 url 재조회(2초 × 3)는 이어 간다(cc L1)
    const onVisibility = () => {
      if (document.visibilityState === 'visible') ask();
    };
    document.addEventListener('visibilitychange', onVisibility);
    ask();
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.clearTimeout(timer);
      window.clearTimeout(deadline);
      seq += 1;
      inFlight?.abort();
      refreshRef.current = () => {};
    };
  }, [url, controlBase]);

  const refresh = useCallback((retryWhileUrl?: string) => refreshRef.current(retryWhileUrl), []);
  return { refresh };
}
