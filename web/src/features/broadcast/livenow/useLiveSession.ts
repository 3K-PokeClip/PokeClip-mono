'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { resolveLiveStreamId, vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { useChannelStreamerId } from '@/features/channel/ChannelScope';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import type { XAnswer } from '@/features/player/rewindSwitch';
import type { RewindRange } from '@/features/player/sessionShared';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionClosed,
} from '@/features/player/usePlaybackSession';
import { useToast } from '@/ui';
import { publishLiveData } from './liveDataStore';
import type { BroadcastStatus } from './useBroadcastClock';
import type { Reselect } from './useLiveStreamSelection';

// 라이브 화면의 재생 세션 배선(설계 5.5절, POK-200 M5) — 세션을 여는 조건 · 지난 방송 판정 화면 · 재생 보유 · 「새 방송」 띠를 정한다.
// 세션은 이 층(GlassPlayer key 바깥)에 살아 재생기가 다시 마운트돼도 남는다. 명부 ended 는 세션을 닫지 않는다(재조회 신호다).

/** 지금 방송 번호를 묻는 한도 — 넘으면 「모름」이다(설계 5.3절) */
const X_TIMEOUT_MS = 2_000;
/** 고정 화면이 버튼 규칙으로 지금 방송 번호를 다시 묻는 최소 간격 */
const PINNED_X_REUSE_MS = 10_000;
/** 라이브 대시보드 경로 — 고정 화면의 「새 방송 보기」가 이 경로의 ?stream= 으로 간다 */
const LIVE_DASHBOARD_PATH = '/broadcast/livenow';

export interface LiveSessionInput {
  /** 방송 번호(= 회차 ID) */
  liveStreamId: string;
  /** 영상 경로의 키 — 모르면 방송 번호 */
  mediaKey: string | null;
  /** 방송|영상 키 */
  accessKey: string | null;
  /** 출입증을 받았거나 필요 없다는 답이 왔다 */
  accessReady: boolean;
  broadcastStatus: BroadcastStatus;
  /** 라이브 목록 주소 */
  liveUrl: string | null;
  /** 보유 중의 다시 고르기(useLiveStreamSelection) — deferred 는 띠, latest(실패면 null)는 지금 방송 번호 X 의 재료 */
  reselect: Reselect;
  /**
   * 「다시보기 보기」 로 past 닫힘 — 닫는 같은 동기 호출에서 불러, 단추 전에 받아 둔 카드를 버리게 한다(누른 것이 마지막
   * 의도다 · POK-547)
   */
  onChosenPastClose?: () => void;
}

export interface LiveSession {
  session: PlaybackSession | null;
  /**
   * 지난 방송 판정으로 그리는 화면인가 — 끝난 뒤 들어온 화면(시청자 A: 이 방송|영상 키로 세션을 연 적 없이 명부 ended ·
   * POK-546 설계 2-4) 또는 세션이 past 로 닫힌 화면(시청자 B · POK-547). 라이브 · 열린 세션(명부 ended 포함) · go-live ·
   * left 로 닫힌 화면 · 명부 상태 모름은 거짓이다
   */
  showsPast: boolean;
  /** 세션이 past 로 닫혔다 — 라이브 주소를 다시 붙이지 않고, 재생기 표시는 끝난 방송이다 */
  closedToPast: boolean;
  /** past 로 닫힌 화면의 404 유예 원점 폴백(끝남 대기를 연 시각 — SessionClosed.endingSinceMs). 그 밖은 null */
  pastSinceMs: number | null;
  /**
   * past 로 닫힌 화면이 이어 틀 자리(PDT epoch ms — POK-547). 끝에 닿아 닫혔거나 소모했으면 null. past 로 닫힌 그
   * 방송|영상 키에서만 낸다
   */
  pastResumeAtMs: number | null;
  /** past 로 닫힐 때 세션이 확인한 다시보기(ready:true). past 로 닫힌 그 방송|영상 키에서만 낸다 */
  pastSeenVod: RewindRange | null;
  /** 이어 틀 자리를 소모한다 — 닫힘 기록 · 확인한 다시보기는 둔다. 다시보기가 시작을 적용하거나 옮길 때 부른다 */
  consumePastResume: () => void;
  /** 「새 방송이 시작됐어요」 띠의 방송 번호 */
  band: string | null;
  onBand: () => void;
}

/** past 로 닫힌 기록 — 키 · 끝남 대기를 연 시각 · 넘긴 자리 · 확인한 다시보기(POK-547) */
interface ClosedPast {
  key: string;
  sinceMs: number | null;
  resumeAtMs: number | null;
  seenVod: RewindRange | null;
}

/**
 * 고정 안 한 화면의 지금 방송 번호 X — 마지막 다시 고르기(10초)의 답이다. 그 물음이 실패했으면 모름이다: 「보던 방송 그대로」로
 * 단정하면 같은 키의 새 방송 B 가 A 세션 재료에 섞인다(cx H4)
 */
export function unpinnedX(latest: string | null): XAnswer {
  return latest === null ? { kind: 'unknown' } : { kind: 'known', id: latest };
}

/**
 * 고정 화면의 지금 방송 번호 묻기 — 10초 안의 폴링은 같은 방송|영상 키의 마지막 답을 다시 쓴다. 기억에는 가장 늦게 시작한 물음의
 * 답만 쓴다: 늦게 온 옛 물음(A)이 새 답(B)을 덮으면 다음 폴링이 A 를 다시 써 다른 방송 띠가 굳는다(cx H3). `query` 의 답이 null 이면 모름.
 * 물음은 서로 기다리지 않는다 — 각 물음은 자기 답을 돌려준다(합류 없음 · kty 결정 3)
 */
export function createPinnedXResolver(
  query: () => Promise<string | null>,
): (fresh: boolean, key: string | null) => Promise<XAnswer> {
  let memory: { at: number; key: string | null; answer: XAnswer } | null = null;
  let latestAsk = 0;
  return async (fresh, key) => {
    if (
      !fresh &&
      memory !== null &&
      memory.key === key &&
      Date.now() - memory.at < PINNED_X_REUSE_MS
    ) {
      return memory.answer;
    }
    latestAsk += 1;
    const ask = latestAsk;
    const id = await query();
    const answer: XAnswer = id === null ? { kind: 'unknown' } : { kind: 'known', id };
    if (ask === latestAsk) memory = { at: Date.now(), key, answer };
    return answer;
  };
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(null), ms);
    void promise.then((value) => {
      window.clearTimeout(timer);
      resolve(value);
    });
  });
}

export function useLiveSession(input: LiveSessionInput): LiveSession {
  const { liveStreamId, mediaKey, accessKey, broadcastStatus } = input;
  const { deferred } = input.reselect;
  const router = useRouter();
  const pathname = usePathname();
  const streamParam = useSearchParams().get('stream');
  const pinned = vodStreamIdFromPathname(pathname ?? '') !== null || Boolean(streamParam);
  const streamerId = useChannelStreamerId();
  const { toast, dismiss } = useToast();
  // past 로 닫힌 방송|영상 키와 그 끝남 대기를 연 시각 · 넘긴 자리 · 확인한 다시보기 — 같은 키로는 세션을 다시 열지 않는다
  // (cc H1 ②). 키 비교라 지울 필요가 없다
  const [closedPast, setClosedPast] = useState<ClosedPast | null>(null);
  const onChosenRef = useRef(input.onChosenPastClose);
  onChosenRef.current = input.onChosenPastClose;
  // 세션을 연 적 있는 키 — 이 키로 연 적 없이 명부가 ended 면 끝난 뒤 들어온 화면(A)이다(지난 방송 화면 포함 · POK-546)
  const [openedFor, setOpenedFor] = useState<string | null>(null);
  // 띠를 눌러 보유를 푼 키
  const [releasedFor, setReleasedFor] = useState<string | null>(null);

  const latestRef = useRef(input.reselect.latest);
  latestRef.current = input.reselect.latest;
  const accessKeyRef = useRef(accessKey);
  accessKeyRef.current = accessKey;
  const pinnedX = useMemo(
    () =>
      createPinnedXResolver(() => withTimeout(resolveLiveStreamId(streamerId, true), X_TIMEOUT_MS)),
    [streamerId],
  );
  const resolveX = useCallback(
    async (fresh: boolean): Promise<XAnswer> => {
      if (!fresh && !pinned) return unpinnedX(latestRef.current);
      return pinnedX(fresh, accessKeyRef.current);
    },
    [pinned, pinnedX],
  );

  const onClose = useCallback(
    (closed: SessionClosed) => {
      if (closed.reason === 'past') {
        setClosedPast({
          key: closed.accessKey,
          sinceMs: closed.endingSinceMs,
          resumeAtMs: closed.handoff?.resumeAtMs ?? null,
          seenVod: closed.handoff?.seenVod ?? null,
        });
        // 같은 동기 호출이라 닫힘 · 카드 비움이 한 그리기로 묶인다
        if (closed.handoff?.chosen === true) onChosenRef.current?.();
        return;
      }
      // 고정 화면의 「새 방송 보기」는 라이브 대시보드의 그 방송으로 간다. 고정 안 한 화면은 보유가 풀려 다시 고르기가 X 를 넣는다
      if (closed.reason === 'left' && closed.goTo !== undefined && pinned) {
        router.push(`${LIVE_DASHBOARD_PATH}?stream=${encodeURIComponent(closed.goTo)}`);
      }
    },
    [pinned, router],
  );
  // 안내 문구마다 마지막으로 띄운 토스트 — 거두기(넘김 되돌림)가 그 토스트를 닫는다
  const noticeToasts = useRef(new Map<string, string>());
  const onNotice = useCallback(
    (text: string) => {
      noticeToasts.current.set(text, toast({ tone: 'info', title: text }));
    },
    [toast],
  );
  const onWithdrawNotice = useCallback(
    (text: string) => {
      const id = noticeToasts.current.get(text);
      if (id === undefined) return;
      noticeToasts.current.delete(text);
      dismiss(id);
    },
    [dismiss],
  );

  const session = usePlaybackSession({
    canOpen:
      broadcastStatus === 'live' &&
      input.accessReady &&
      accessKey !== null &&
      closedPast?.key !== accessKey,
    roundId: liveStreamId || null,
    accessKey,
    liveUrl: input.liveUrl,
    // 제어면 키는 출입증 쿠키와 무관하다 — 쿠키를 못 받은 곳(로컬 · dev)에서도 폴링이 돈다(R30)
    controlKey: mediaKey ?? (liveStreamId || null),
    controlBase: mediaControlBaseUrl(),
    resolveX,
    onClose,
    onNotice,
    onWithdrawNotice,
  });

  const opened = session !== null;
  useEffect(() => {
    if (opened && accessKey !== null) setOpenedFor(accessKey);
  }, [opened, accessKey]);

  // 명부 ended 는 재조회 신호 하나다(정산 15분 뒤라 끝 증거가 아니다)
  const dispatch = session?.dispatch;
  useEffect(() => {
    if (broadcastStatus === 'ended') dispatch?.({ type: 'roster-ended' });
  }, [broadcastStatus, dispatch]);

  const pastClose = closedPast !== null && closedPast.key === accessKey ? closedPast : null;
  const closedToPast = pastClose !== null;
  const consumePastResume = useCallback(
    () =>
      setClosedPast((p) => (p === null || p.resumeAtMs === null ? p : { ...p, resumeAtMs: null })),
    [],
  );
  // 보유 — 세션이 열려 있거나 past 로 닫혀 그 자리에서 지난 방송 판정을 보이는 동안. 띠를 누르면 푼다(5.5절)
  const holding = (opened || closedToPast) && accessKey !== releasedFor && liveStreamId !== '';
  useEffect(() => {
    publishLiveData({ playbackHold: holding ? liveStreamId : null });
  }, [holding, liveStreamId]);

  const pastOnly = openedFor !== accessKey && broadcastStatus === 'ended';

  // 세션 덮개가 다른 방송 띠를 세웠으면 화면 띠는 세우지 않는다 — 같은 뜻의 띠가 둘 서지 않게(cc L)
  const coverBanner = session?.view.banner ?? null;
  const band =
    holding &&
    coverBanner === null &&
    deferred !== null &&
    deferred !== '' &&
    deferred !== liveStreamId
      ? deferred
      : null;
  const onBand = useCallback(() => {
    setReleasedFor(accessKey);
    dispatch?.({ type: 'leave' });
  }, [accessKey, dispatch]);

  return {
    session,
    showsPast: pastOnly || closedToPast,
    closedToPast,
    pastSinceMs: pastClose?.sinceMs ?? null,
    pastResumeAtMs: pastClose?.resumeAtMs ?? null,
    pastSeenVod: pastClose?.seenVod ?? null,
    consumePastResume,
    band,
    onBand,
  };
}
