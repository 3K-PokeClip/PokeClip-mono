'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { recordingSearchFrom } from '@/features/broadcast/endedWindow';
import { resolveLiveStreamId, vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { useChannelStreamerId } from '@/features/channel/ChannelScope';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import type { XAnswer } from '@/features/player/rewindSwitch';
import {
  usePlaybackSession,
  type PlaybackSession,
  type SessionClosed,
} from '@/features/player/usePlaybackSession';
import { useToast } from '@/ui';
import { publishLiveData } from './liveDataStore';
import type { BroadcastStatus } from './useBroadcastClock';
import type { Reselect } from './useLiveStreamSelection';

// 라이브 화면의 재생 세션 배선(설계 5.5절, POK-200 M5) — 세션을 여는 조건 · 녹화 조회 요청 · 재생 보유 · 「새 방송」 띠를 정한다.
// 세션은 이 층(GlassPlayer key 바깥)에 살아 재생기가 다시 마운트돼도 남는다. 명부 ended 는 세션을 닫지 않는다(재조회 신호다).

/** 지금 방송 번호를 묻는 한도 — 넘으면 「모름」이다(설계 5.3절) */
const X_TIMEOUT_MS = 2_000;
/** 고정 화면이 버튼 규칙으로 지금 방송 번호를 다시 묻는 최소 간격 */
const PINNED_X_REUSE_MS = 10_000;
/** 라이브 대시보드 경로 — 고정 화면의 「새 방송 보기」가 이 경로의 ?stream= 으로 간다 */
const LIVE_DASHBOARD_PATH = '/broadcast/livenow';

/** 녹화 조회 요청 — 그 자리에서 고정한다. 끝 시각이 있어야 같은 키의 다음 방송 녹화가 섞이지 않는다(cc H1) */
export interface RecordedRequest {
  /** 찾는 하한(recordingSearchFrom). 시작을 모르는 방송은 끝 12시간 안이고, 그것도 모르면 null */
  fromMs: number | null;
  untilMs: number | null;
}

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
  startedAt: number | null;
  endedAt: number | null;
  /** 라이브 목록 주소 */
  liveUrl: string | null;
  /** 보유 중의 다시 고르기(useLiveStreamSelection) — deferred 는 띠, latest(실패면 null)는 지금 방송 번호 X 의 재료 */
  reselect: Reselect;
}

export interface LiveSession {
  session: PlaybackSession | null;
  /** 녹화 조회 요청. null 이면 묻지 않는다 */
  recordedRequest: RecordedRequest | null;
  /** 세션이 녹화로 닫혔다 — 라이브 주소를 다시 붙이지 않고, 재생기 표시는 끝난 방송이다 */
  closedRecorded: boolean;
  /** 「새 방송이 시작됐어요」 띠의 방송 번호 */
  band: string | null;
  onBand: () => void;
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
 * 답만 쓴다: 늦게 온 옛 물음(A)이 새 답(B)을 덮으면 다음 폴링이 A 를 다시 써 다른 방송 띠가 굳는다(cx H3). `query` 의 답이 null 이면 모름
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
  const { liveStreamId, mediaKey, accessKey, broadcastStatus, startedAt, endedAt } = input;
  const { deferred } = input.reselect;
  const router = useRouter();
  const pathname = usePathname();
  const streamParam = useSearchParams().get('stream');
  const pinned = vodStreamIdFromPathname(pathname ?? '') !== null || Boolean(streamParam);
  const streamerId = useChannelStreamerId();
  const { toast } = useToast();
  // recorded 로 닫힌 방송|영상 키 — 같은 키로는 세션을 다시 열지 않는다(cc H1 ②). 키 비교라 지울 필요가 없다
  const [closedRecordedFor, setClosedRecordedFor] = useState<string | null>(null);
  const [recordedReq, setRecordedReq] = useState<(RecordedRequest & { key: string }) | null>(null);
  // 세션을 연 적 있는 키 — 연 적 없는 화면만 「명부 ended」 로 녹화를 묻는다(지난 방송 화면 · 끝난 뒤 들어온 화면)
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
      if (closed.reason === 'recorded') {
        setClosedRecordedFor(closed.accessKey);
        setRecordedReq({
          key: closed.accessKey,
          fromMs: recordingSearchFrom(startedAt, endedAt),
          untilMs: endedAt ?? closed.observedEndMs ?? Date.now(),
        });
        return;
      }
      // 고정 화면의 「새 방송 보기」는 라이브 대시보드의 그 방송으로 간다. 고정 안 한 화면은 보유가 풀려 다시 고르기가 X 를 넣는다
      if (closed.reason === 'left' && closed.goTo !== undefined && pinned) {
        router.push(`${LIVE_DASHBOARD_PATH}?stream=${encodeURIComponent(closed.goTo)}`);
      }
    },
    [startedAt, endedAt, pinned, router],
  );
  const onNotice = useCallback((text: string) => toast({ tone: 'info', title: text }), [toast]);

  const session = usePlaybackSession({
    canOpen:
      broadcastStatus === 'live' &&
      input.accessReady &&
      accessKey !== null &&
      closedRecordedFor !== accessKey,
    roundId: liveStreamId || null,
    accessKey,
    liveUrl: input.liveUrl,
    // 제어면 키는 출입증 쿠키와 무관하다 — 쿠키를 못 받은 곳(로컬 · dev)에서도 폴링이 돈다(R30)
    controlKey: mediaKey ?? (liveStreamId || null),
    controlBase: mediaControlBaseUrl(),
    resolveX,
    onClose,
    onNotice,
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

  const closedRecorded = accessKey !== null && closedRecordedFor === accessKey;
  // 보유 — 세션이 열려 있거나 녹화로 닫혀 그 녹화를 보이는 동안. 띠를 누르면 푼다(5.5절)
  const holding = (opened || closedRecorded) && accessKey !== releasedFor && liveStreamId !== '';
  useEffect(() => {
    publishLiveData({ playbackHold: holding ? liveStreamId : null });
  }, [holding, liveStreamId]);

  const pastOnly = openedFor !== accessKey && broadcastStatus === 'ended';
  const fromMs = recordingSearchFrom(startedAt, endedAt);
  const recordedRequest = useMemo<RecordedRequest | null>(() => {
    if (recordedReq !== null && recordedReq.key === accessKey) {
      return { fromMs: recordedReq.fromMs, untilMs: recordedReq.untilMs };
    }
    return pastOnly ? { fromMs, untilMs: endedAt } : null;
  }, [recordedReq, accessKey, pastOnly, fromMs, endedAt]);

  const band =
    holding && deferred !== null && deferred !== '' && deferred !== liveStreamId ? deferred : null;
  const onBand = useCallback(() => {
    setReleasedFor(accessKey);
    dispatch?.({ type: 'leave' });
  }, [accessKey, dispatch]);

  return { session, recordedRequest, closedRecorded, band, onBand };
}
