'use client';

import { useCallback, useEffect, useReducer, useRef } from 'react';
import { roundRewindUrl, type RewindStatusResult } from '@/api/rewindStatus';
import {
  foldPastVodAnswer,
  pastVodNeedsPoll,
  type PastVodHold,
  type PastVodState,
} from '@/features/player/pastVod';
import { useRewindStatus } from '@/features/player/useRewindStatus';

// 끝난 뒤 들어온 화면(A — 세션을 연 적 없이 명부가 ended 인 화면)의 회차 경로 폴링(POK-546 설계 2-2). 폴링은
// useRewindStatus(10초 · 시한 8초 · 늦은 답 버림 · 숨김 복귀 때 묻기)를 그대로 쓰고, 답을 다시보기 상태로 접는 일은 순수
// 모듈(pastVod.ts)이 맡는다. 이 훅은 경로를 켜고 끄는 조건과 404 유예의 원점만 정한다. 웹은 회차 경로 답만 믿는다.

/** usePastVod 가 회차 경로를 묻는 데 쓰는 입력 — 물을지 여부 · 경로를 만들 주소와 키 · 404 유예의 원점 */
export interface PastVodInput {
  /** 물을 화면인가 — 끝난 뒤 들어온 화면이고 보관 기간이 지나지 않음 */
  enabled: boolean;
  /** 제어면 주소(mediaControlBaseUrl). 없으면 물을 수 없다 */
  controlBase: string | null;
  /** 영상 경로의 키 */
  controlKey: string | null;
  /** 방송 번호(= 회차 ID) */
  roundId: string | null;
  /** 명부 endedAt — 404 유예의 원점. 없으면 이 화면이 처음 404 를 받은 시각이 원점이다 */
  endedAtMs: number | null;
}

/** 경로를 만들 수 없을 때 — 묻지 않는다 */
const UNCONFIGURED: PastVodState = { kind: 'unconfigured' };
/** 이 경로로 아직 아무 답도 받지 않았을 때 */
const UNANSWERED: PastVodState = { kind: 'checking', answered: false };

/**
 * 한 회차 경로로 쌓은 것 — 상태와 첫 404 시각을 한 묶음으로 둔다. 경로(방송 · 키)가 바뀌면 묶음째 새로 세워, 다른 경로로 갔다
 * 돌아와도 옛 상태 · 옛 원점이 되살아나지 않는다
 */
type HeldState = PastVodHold & { forUrl: string | null };

/** 경로 하나의 새 묶음 — 아직 아무 답도 받지 않았다 */
function freshHold(url: string | null): HeldState {
  return { forUrl: url, state: UNANSWERED, firstUnknownMs: null };
}

/** 묶음을 바꾸는 일 — 경로가 바뀜 · 폴러가 묻는 경로의 답 하나 */
type HoldAction =
  | { type: 'route'; url: string | null }
  | {
      type: 'answer';
      /** 답을 받은 때 폴러가 묻고 있던 경로(커밋된 값) */
      askingUrl: string;
      result: RewindStatusResult;
      nowMs: number;
      /** 답을 받은 때의 명부 endedAt(커밋된 값) */
      endedAtMs: number | null;
    };

/** 경로가 바뀌면 묶음째 새로 세우고, 답은 묶음의 경로와 묻던 경로가 같을 때만 접는다(앞 경로의 답은 버린다) */
function holdReducer(held: HeldState, action: HoldAction): HeldState {
  if (action.type === 'route') return held.forUrl === action.url ? held : freshHold(action.url);
  if (held.forUrl !== action.askingUrl) return held;
  return {
    ...held,
    ...foldPastVodAnswer(held, action.result, action.nowMs, action.endedAtMs),
  };
}

/**
 * 회차 경로를 물어 다시보기 상태를 낸다.
 * - 경로를 만들 수 없으면(제어면 주소 · 키 · 방송 번호가 없거나 경로 조각 꼴이 아님) 켜짐과 무관하게 unconfigured 이고 묻지 않는다.
 * - 꺼져 있으면 묻지 않고, 그 경로로 쌓은 상태를 그대로 낸다.
 * - 켜져 있으면 다시 물을 상태(checking · preparing · unavailable)인 동안만 폴링한다. ready · none 이면 멈춘다.
 * - 방송이나 키가 바뀌면 checking(답 없음)으로 새로 세우고 첫 404 원점도 함께 버린다(돌아와도 처음부터).
 * - 답은 커밋된 값(폴러가 묻는 경로 · endedAt)으로만 접는다 — 경로를 바꾼 렌더가 커밋된 뒤 · 폴러가 옮기기 전에 온 앞 경로의
 *   답은 버리고, 버려진 전이 렌더 뒤에도 지금 경로의 답은 받는다.
 */
export function usePastVod(input: PastVodInput): PastVodState {
  const { enabled, controlBase, controlKey, roundId, endedAtMs } = input;
  const routeUrl =
    controlKey !== null && roundId !== null
      ? roundRewindUrl(controlBase, controlKey, roundId)
      : null;
  // useState 가 아니라 useReducer 다. useState 의 갱신은 마지막으로 렌더한 상태로 결과를 미리 셈해 같으면 렌더를 건너뛰는데,
  // 그 상태가 버려진 렌더(전이 중 Suspense 로 멈춘 렌더)의 것일 수 있다 — 그러면 지금 경로의 답을 「다른 경로」로 셈해 버리고
  // 다시 렌더하지 않아 멈춘다. useReducer 는 미리 셈하지 않고 렌더에서 커밋된 상태로 줄인다
  const [held, dispatchHold] = useReducer(holdReducer, routeUrl, freshHold);
  // 경로가 바뀐 렌더에서 곧바로 새 묶음으로 바꾼다(렌더 중 갱신 — 다음 렌더 전에 반영되고, 렌더가 버려지면 함께 버려진다)
  let current = held;
  if (held.forUrl !== routeUrl) {
    current = freshHold(routeUrl);
    dispatchHold({ type: 'route', url: routeUrl });
  }

  const state = routeUrl === null ? UNCONFIGURED : current.state;
  const pollUrl = enabled && pastVodNeedsPoll(state) ? routeUrl : null;

  // 답 처리는 커밋된 값만 읽는다. useRewindStatus 는 답 처리 함수를 렌더 중에 갈아 끼우므로, 렌더의 값(경로 · endedAt)을 닫아
  // 잡으면 두 갈래에서 틀린다.
  // - 경로를 바꾼 렌더가 커밋된 직후 · passive 전: 앞 경로의 답이 새 경로 렌더의 함수로 들어온다.
  // - 전이 렌더가 버려진 뒤(Suspense 로 멈춘 렌더 · 건너뛴 복귀 렌더): 커밋되지 않은 렌더의 함수가 남는다.
  // 그래서 폴러가 지금 묻는 경로와 endedAt 을 폴러가 요청을 다시 거는 passive 단계에서 적고, 답은 그 경로의 묶음에만 접는다.
  // 앞 경로의 답은 묶음이 이미 새 경로로 바뀌어(렌더 중 갱신) 버려진다
  const askingUrlRef = useRef<string | null>(null);
  const endedAtRef = useRef(endedAtMs);
  useEffect(() => {
    askingUrlRef.current = pollUrl;
  }, [pollUrl]);
  useEffect(() => {
    endedAtRef.current = endedAtMs;
  }, [endedAtMs]);

  const onResult = useCallback((result: RewindStatusResult) => {
    const askingUrl = askingUrlRef.current;
    if (askingUrl === null) return;
    dispatchHold({
      type: 'answer',
      askingUrl,
      result,
      nowMs: Date.now(),
      endedAtMs: endedAtRef.current,
    });
  }, []);
  useRewindStatus(pollUrl, controlBase, onResult);
  return state;
}
