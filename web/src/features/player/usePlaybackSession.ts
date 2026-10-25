'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { roundRewindUrl, streamRewindUrl, type RewindStatusResult } from '@/api/rewindStatus';
import {
  ENDING_WAIT_MS,
  RESUME_WAIT_MS,
  openSession,
  pollTargets,
  sessionView,
  transition,
  type PlaybackSessionState,
  type SessionClose,
  type SessionEffect,
  type SessionEvent,
  type SessionView,
  type XAnswer,
} from './rewindSwitch';
import { useRewindStatus } from './useRewindStatus';

// 재생 세션의 수명 소유자(설계 5.5절, POK-200 M5). LiveScreen 층 — GlassPlayer 의 key 바깥 — 에 산다: 출입증 회복 · 시계 key 로
// 재생기가 다시 마운트돼도 세션은 남아 같은 자리에서 잇는다(R36). 명부 상태 · 재생기 마운트 · 자동 방송 선택과 따로 산다.
// 상태기계(rewindSwitch)에 사건을 먹이고, 나온 효과를 건다: 재조회 · 재생기 시크 · 알림 · 닫기 · 지금 방송 번호 다시 묻기.

/** LIVE 의 「지금 방송 번호」 다시 묻기 간격(설계 5.3절) */
const X_RECHECK_MS = 2_000;

/** 세션이 재생기에 거는 명령 — 재생기가 마운트될 때 묶고 내려갈 때 푼다 */
export interface SessionPlayer {
  seekToMs: (atMs: number) => void;
  seekToLiveEdge: () => void;
}

export interface SessionClosed {
  reason: SessionClose;
  /** 「새 방송 보기」의 방송 번호 */
  goTo?: string;
  /** 녹화 조회의 끝 시각 대체 — 이 세션 재생기가 로드한 목록의 조각 끝 최대(R42) */
  observedEndMs: number | null;
  accessKey: string;
}

export interface SessionOptions {
  /** 여는 조건(명부 live ∧ 출입증 준비 ∧ recorded 로 닫힌 적 없음 — LiveScreen 이 정한다). 열린 뒤에는 닫는 조건이 아니다 */
  canOpen: boolean;
  /** 방송 번호(= 회차 ID) */
  roundId: string | null;
  /** 방송|영상 키 */
  accessKey: string | null;
  /** 라이브 목록 주소 */
  liveUrl: string | null;
  /** 제어면 키(영상 키 — 출입증 쿠키와 무관, R30) */
  controlKey: string | null;
  /** 제어면 주소(NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL). 비면 세션을 열지 않는다 */
  controlBase: string | null;
  /** 지금 방송 번호 X. fresh = 기억을 버리고 새로 묻는다(LIVE 누름) — 2초 안에 모르면 unknown */
  resolveX: (fresh: boolean) => Promise<XAnswer>;
  onClose: (closed: SessionClosed) => void;
  onNotice?: (text: string) => void;
}

export interface PlaybackSession {
  state: PlaybackSessionState;
  view: SessionView;
  dispatch: (event: SessionEvent) => void;
  /** LIVE(실시간으로) — 지금 방송 번호를 새로 물어 상태기계에 넘긴다 */
  liveButton: () => void;
  bindPlayer: (player: SessionPlayer | null) => void;
}

/** 이 국면의 스트림 폴링 답에 지금 방송 번호가 필요한가(세션에 묶인 live · 끝남 대기 버튼 규칙) */
function needsX(s: PlaybackSessionState): boolean {
  return (s.phase.kind === 'live' && s.boundLive) || s.phase.kind === 'ending-wait';
}

function canStart(o: SessionOptions): boolean {
  return (
    o.canOpen &&
    o.roundId !== null &&
    o.accessKey !== null &&
    o.liveUrl !== null &&
    o.controlKey !== null &&
    o.controlBase !== null
  );
}

export function usePlaybackSession(options: SessionOptions): PlaybackSession | null {
  const optionsRef = useRef(options);
  optionsRef.current = options;
  const [state, setState] = useState<PlaybackSessionState | null>(null);
  const stateRef = useRef(state);
  const playerRef = useRef<SessionPlayer | null>(null);
  const boundOnceRef = useRef(false);
  /** left 로 닫은 방송|영상 키 — 화면이 다른 방송으로 넘어가기 전에 같은 방송으로 다시 열지 않는다 */
  const leftForRef = useRef<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const commit = useCallback((next: PlaybackSessionState | null) => {
    stateRef.current = next;
    setState(next);
  }, []);

  // 여는 때 — 세션이 없거나 닫혔고 열 수 있으면 연다(go-live 로 닫히면 같은 방송을 새로 연다 — 5.5절)
  const startable = canStart(options);
  const { roundId, accessKey, liveUrl } = options;
  useEffect(() => {
    const current = stateRef.current;
    const sameBroadcast = current !== null && current.accessKey === accessKey;
    if (current !== null && current.closed === null && sameBroadcast) return;
    if (current !== null && current.closed === null && !sameBroadcast) {
      // 다른 방송을 고름 — left 로 닫는다(5.3절 아무 국면)
      commit(null);
    }
    if (!startable || roundId === null || accessKey === null || liveUrl === null) return;
    if (leftForRef.current === accessKey) return;
    leftForRef.current = null;
    boundOnceRef.current = playerRef.current !== null;
    commit(openSession({ roundId, accessKey, liveUrl }));
  }, [startable, roundId, accessKey, liveUrl, commit, state?.closed]);

  const refreshRef = useRef<{ stream: (u?: string) => void; round: () => void }>({
    stream: () => {},
    round: () => {},
  });
  const recheckTimer = useRef<number | undefined>(undefined);

  const dispatchRef = useRef<(event: SessionEvent) => void>(() => {});
  const applyEffects = useCallback((effects: SessionEffect[], s: PlaybackSessionState) => {
    for (const effect of effects) {
      switch (effect.kind) {
        case 'refresh':
          refreshRef.current.stream(effect.retryWhileUrl);
          refreshRef.current.round();
          break;
        case 'seek-within':
          playerRef.current?.seekToMs(effect.atMs);
          break;
        case 'seek-live-edge':
          playerRef.current?.seekToLiveEdge();
          break;
        case 'notice':
          optionsRef.current.onNotice?.(effect.text);
          break;
        case 'recheck-x':
          window.clearTimeout(recheckTimer.current);
          recheckTimer.current = window.setTimeout(() => {
            void optionsRef.current
              .resolveX(true)
              .then((x) =>
                dispatchRef.current({ type: 'live-button', x, attempt: effect.attempt }),
              );
          }, X_RECHECK_MS);
          break;
        case 'close':
          if (effect.reason === 'left') leftForRef.current = s.accessKey;
          optionsRef.current.onClose({
            reason: effect.reason,
            goTo: effect.goTo,
            observedEndMs: s.observedEndMs,
            accessKey: s.accessKey,
          });
          break;
      }
    }
  }, []);

  const dispatch = useCallback(
    (event: SessionEvent) => {
      const current = stateRef.current;
      if (current === null) return;
      const next = transition(current, event, Date.now());
      if (next.state !== current) commit(next.state);
      applyEffects(next.effects, next.state);
    },
    [commit, applyEffects],
  );
  dispatchRef.current = dispatch;

  const liveButton = useCallback(() => {
    const current = stateRef.current;
    if (current === null) return;
    // 라이브 국면의 LIVE 는 라이브 끝으로 옮길 뿐이라 묻지 않는다
    if (current.phase.kind === 'live') {
      dispatch({ type: 'live-button', x: { kind: 'unknown' }, attempt: 0 });
      return;
    }
    void optionsRef.current
      .resolveX(true)
      .then((x) => dispatch({ type: 'live-button', x, attempt: 0 }));
  }, [dispatch]);

  const bindPlayer = useCallback(
    (player: SessionPlayer | null) => {
      playerRef.current = player;
      if (player === null) return;
      // 처음 묶을 때는 그대로 · 내려갔다 다시 묶이면 같은 소스를 마지막 자리에서 잇는다(리마운트 — R36)
      if (boundOnceRef.current) dispatch({ type: 'remount' });
      boundOnceRef.current = true;
    },
    [dispatch],
  );

  // 폴링 — 국면이 정한 경로만 켠다(5.1절 표)
  const targets = state === null ? { stream: false, round: false } : pollTargets(state);
  const { controlBase, controlKey } = options;
  const streamUrl =
    targets.stream && controlKey !== null ? streamRewindUrl(controlBase, controlKey) : null;
  const roundUrl =
    targets.round && controlKey !== null && state !== null
      ? roundRewindUrl(controlBase, controlKey, state.roundId)
      : null;
  const onStream = useCallback(
    (result: RewindStatusResult) => {
      const current = stateRef.current;
      if (current === null) return;
      if (!needsX(current)) {
        dispatch({ type: 'stream-poll', result });
        return;
      }
      void optionsRef.current
        .resolveX(false)
        .then((x) => dispatch({ type: 'stream-poll', result, x }));
    },
    [dispatch],
  );
  const onRound = useCallback(
    (result: RewindStatusResult) => dispatch({ type: 'round-poll', result }),
    [dispatch],
  );
  const streamPoll = useRewindStatus(streamUrl, controlBase, onStream);
  const roundPoll = useRewindStatus(roundUrl, controlBase, onRound);
  refreshRef.current = {
    stream: (u) => streamPoll.refresh(u),
    round: () => roundPoll.refresh(),
  };

  // 시한 — 재접속 대기가 끝나는 때(덮개 문구가 바뀐다)와 20분 시한(tick)에 깨운다
  const endingSince = state?.endingSince ?? null;
  useEffect(() => {
    if (endingSince === null) return undefined;
    const timers = [RESUME_WAIT_MS, ENDING_WAIT_MS].map((after) =>
      window.setTimeout(
        () => {
          setNow(Date.now());
          dispatchRef.current({ type: 'tick' });
        },
        Math.max(0, endingSince + after - Date.now()),
      ),
    );
    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [endingSince]);

  useEffect(() => () => window.clearTimeout(recheckTimer.current), []);

  const view = useMemo(
    () => (state === null ? null : sessionView(state, Math.max(now, Date.now()))),
    [state, now],
  );
  if (state === null || view === null || state.closed !== null) return null;
  return { state, view, dispatch, liveButton, bindPlayer };
}
