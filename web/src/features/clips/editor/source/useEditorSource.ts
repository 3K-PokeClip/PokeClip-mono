'use client';

import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { mediaStreamId, type BroadcastRow } from '@/api/clipEditor';
import { mediaCredentials } from '@/api/mediaPlayback';
import {
  ensureMediaAccess,
  ensurePlaybackAccess,
  isPlaybackExpired,
  reportPlaybackForbidden,
} from '@/api/playbackAccess';
import {
  fetchRewindStatus,
  roundRewindUrl,
  streamRewindUrl,
  type RewindStatusResult,
} from '@/api/rewindStatus';
import { mediaControlBaseUrl } from '@/features/player/mediaSource';
import type { XAnswer } from '@/features/player/rewindSwitch';
import { REWIND_POLL_MS, useRewindStatus } from '@/features/player/useRewindStatus';
import { parseEditorPlaylist, type EditorPlaylist } from './editorPlaylist';
import {
  nextEditorSourceState,
  type EditorSourceEvent,
  type EditorSourceState,
  type SegmentFailure,
} from './editorSourceState';

// 편집기 영상 원천 — 되감기 제어면 폴링과 ③ 목록 받기(POK-473 설계 prw2 5절 1–2 · 14.2 D3 · 계획 5절 E4b-1).
//   회차 경로  늘 묻는다(끝난 방송 · 방송 중 ending 판정). vod 가 서거나 끝 상태면 멈춘다
//   스트림 경로 방송 중에만 묻는다(회차 경로는 live 에 url 이 없다 — playback.go:41). 답마다 지금 방송 번호를 묻는다(R58 —
//            resolveX 는 broadcastStreamer 의 editorXResolver 로 그 채널 번호로만 묻는다). 다른 방송이면 멈춘다
//   목록      상태의 원천 주소(sourceOfState)에서만 받는다. 조각 실패 사건은 그 조각을 받은 창의 목록 주소를 싣고, 지금 원천의
//            주소가 아니면(낡은 창) 받지도 싣지도 않는다. 방송 중 여부(live)는 다시 받은 그 목록의 원천 경로로 정한다.
//            원천이 바뀌면 앞 원천에 걸린 요청을 끊고, 끝난 답도 그때 원천이 같을 때만 둔다(r1 cx H2). 원천 목록을 아직 못
//            받았으면 폴링 주기(REWIND_POLL_MS)마다 다시 받는다 — vod 가 서면 회차 폴링이 멈춰 회복을 알릴 길이 없다(r1 cx H1)
//   base     timelineOriginAt → 끝난 방송이면 vod 목록 첫 PDT → startedAt. 한 번 정하면 세션 동안 바꾸지 않는다
// 판정 순서는 editorSourceState 가 맡는다. 미리 받기의 한 번 읽기(readEditorSourceOnce)는 같은 함수들로 같은 판정을 낸다.

/** 받은 목록과 그 원천 — 창은 이 url 을 들고 다녀 조각 실패 사건에 싣는다 */
export interface SourceList {
  mode: 'live' | 'vod';
  url: string;
  playlist: EditorPlaylist;
}

export interface EditorSourceSnapshot {
  state: EditorSourceState;
  list: SourceList | null;
  /** 편집기 시간축 0초의 절대 ms. 끝난 방송이 vod 를 기다리는 동안은 null */
  base: number | null;
}

/** 조각 받기의 종국 실패 — 403 → 출입증 갱신 → 또 403 · 404 */
export type SegmentFailureKind = 'forbiddenAfterRefresh' | 'gone';

export interface EditorSource extends EditorSourceSnapshot {
  /**
   * 지금 원천의 목록을 자동 받기(원천이 서면 받고 실패하면 폴링 주기마다 다시)로 받으려다 실패했고 아직 들고 있지 않다. 첫
   * 받기가 끝나기 전에는 거짓이고, 한 번 실패한 뒤에는 다시 받는 중에도 참이다 — 받아 들면 거짓이다
   */
  listFailed: boolean;
  /**
   * 목록을 지금 원천에서 한 번 다시 받아 돌려준다(저장 때 이웃 판정 — 계획 4절 P-4′). 끝난 방송(vod)은 목록이 바뀌지 않아
   * 들고 있는 것을 준다. 실패하면 null 이고 들고 있는 목록은 그대로다
   * @param signal 부른 쪽이 멈추면 걸린 요청을 끊고 null 이다(이미 멈췄으면 받지 않는다)
   */
  reloadList(signal?: AbortSignal): Promise<EditorPlaylist | null>;
  /**
   * 조각 받기 실패를 원천 상태에 싣는다(D3 6–9행). 지금 원천 목록을 다시 받아 그 seq 가 남았는지 보고, 403 이면 회차 경로를
   * 한 번 묻는다. 방송 중이면 404 · 403 모두 다시 받기가 실패할 때 싣지 않는다(다음 보고에서 다시 받기가 될 때 판정한다 —
   * vod 는 목록이 바뀌지 않아 들고 있는 목록으로 본다). listUrl 이 지금 원천 주소가 아니면 버린다
   * @param listUrl 그 조각을 받은 창의 목록 주소(SourceList.url)
   */
  reportSegmentFailure(failure: SegmentFailureKind, seq: number, listUrl: string): Promise<void>;
}

const CHECKING: EditorSourceState = { kind: 'checking' };
const UNCONFIGURED: EditorSourceState = { kind: 'unavailable' };

/** 지금 원천 — 섰거나 조각 실패로 정한 목록, 회차 덮개 아래의 원천. 원천 전 · R58 3행 · 끝 상태면 null */
export function sourceOfState(state: EditorSourceState): SegmentFailure | null {
  switch (state.kind) {
    case 'ready':
      return { mode: state.mode, listUrl: state.url };
    case 'finalizing':
      return state.cause === 'round' ? sourceOfState(state.under) : null;
    case 'outsideDvr':
    case 'goneOrNone':
    case 'access':
      return state.segment ?? null;
    default:
      return null;
  }
}

function isSameSource(a: SegmentFailure | null, b: SegmentFailure): boolean {
  return a?.mode === b.mode && a.listUrl === b.listUrl;
}

/** 뒤 답으로 바뀌지 않는 상태 — 만료 · 회차 경로로 정한 없음 */
function isSettled(state: EditorSourceState): boolean {
  return state.kind === 'expired' || (state.kind === 'goneOrNone' && state.segment === undefined);
}

/** 회차 경로를 더 물을까 — 끝 상태 · vod 원천이 섰으면 멈춘다 */
function wantsRoundPoll(state: EditorSourceState): boolean {
  return !isSettled(state) && !(state.kind === 'ready' && state.mode === 'vod');
}

/** 스트림 경로를 더 물을까 — 방송 중 · R58 다른 방송이 아님 · vod 원천이 아님 */
function wantsStreamPoll(state: EditorSourceState, broadcast: BroadcastRow): boolean {
  if (broadcast.endedAt !== null || isSettled(state)) return false;
  if (state.kind === 'finalizing' && state.cause === 'stream') return false;
  return sourceOfState(state)?.mode !== 'vod';
}

function parseMs(iso: string | null | undefined): number | null {
  const ms = iso ? Date.parse(iso) : NaN;
  return Number.isFinite(ms) ? ms : null;
}

/**
 * 편집기 base(설계 3.2 결정 1). 서버 기준점이 정본이고, 방송 중이면 방송 시작이다. 끝난 방송은 vod 목록 첫 PDT 를 기다리고,
 * vod 가 서지 않는 것이 정해졌거나(만료 · 없음) 원천을 물을 수 없으면 방송 시작으로 대신한다
 */
export function editorBaseMs(
  broadcast: BroadcastRow,
  state: EditorSourceState,
  list: SourceList | null,
  configured = true,
): number | null {
  const origin = parseMs(broadcast.timelineOriginAt);
  if (origin !== null) return origin;
  const startedAt = parseMs(broadcast.startedAt);
  if (broadcast.endedAt === null) return startedAt;
  if (list?.mode === 'vod') return list.playlist.rows[0]?.pdtMs ?? startedAt;
  return isSettled(state) || !configured ? startedAt : null;
}

function roundEventOf(
  result: RewindStatusResult,
  nowMs: number,
  unknownSinceMs: number,
): EditorSourceEvent {
  return { type: 'round', result, clock: { nowMs, unknownSinceMs } };
}

/**
 * 목록 글 받기. 403 이면 쿠키가 지워진 것이라 출입증을 버리고 다시 받은 뒤 한 번 더 부른다(fetchWindowBytes 와 같은 관례).
 * 쿠키를 싣는 받기라 3xx 를 따라가지 않는다(redirect 'error' — PR 단계 r1 L-2)
 */
async function fetchListText(url: string, mediaKey: string, signal?: AbortSignal) {
  const load = () => fetch(url, { signal, credentials: mediaCredentials(), redirect: 'error' });
  let res = await load();
  if (res.status === 403) {
    reportPlaybackForbidden(mediaKey);
    await ensureMediaAccess(mediaKey);
    res = await load();
  }
  if (!res.ok) throw new Error(`목록 ${res.status}`);
  return res.text();
}

/**
 * 원천 주소의 목록. 출입증 만료면 expired, 못 받거나 못 읽으면 null(경고 — 주소는 싣지 않는다: 경로에 스트림 키가 든다).
 * 끊은 요청은 AbortError 를 던진다
 */
async function loadSourceList(
  broadcast: BroadcastRow,
  url: string,
  signal?: AbortSignal,
): Promise<EditorPlaylist | 'expired' | null> {
  const mediaKey = mediaStreamId(broadcast);
  if (isPlaybackExpired(broadcast.streamId)) return 'expired';
  try {
    const access = await ensurePlaybackAccess(broadcast.streamId, mediaKey);
    if (access?.kind === 'expired') return 'expired';
    if (access?.kind === 'failed') throw new Error('출입증을 받지 못했어요');
    const playlist = parseEditorPlaylist(await fetchListText(url, mediaKey, signal), url);
    if (playlist === null) throw new Error('목록 꼴이 맞지 않아요');
    return playlist;
  } catch (e) {
    if (signal?.aborted) throw e;
    console.warn('[editor] 편집기 목록을 받지 못했어요:', (e as Error).message);
    return null;
  }
}

/** 회차 경로가 404 인가(D3 7행). 물을 수 없거나 그 밖의 답이면 거짓 */
async function isRoundGone(broadcast: BroadcastRow): Promise<boolean> {
  const controlBase = mediaControlBaseUrl();
  const url = roundRewindUrl(controlBase, mediaStreamId(broadcast), broadcast.streamId);
  if (url === null || controlBase === null) return false;
  return (await fetchRewindStatus(url, controlBase)).kind === 'none';
}

/**
 * 미리 받기용 한 번 읽기 — 회차 경로 · (방송 중이면) 스트림 경로를 한 번씩 묻고, 원천이 서면 목록을 한 번 받는다. 판정은
 * useEditorSource 와 같은 함수다. 404 유예의 원점은 명부 endedAt, 없으면 지금이다
 */
export async function readEditorSourceOnce(
  broadcast: BroadcastRow,
  resolveX: (fresh: boolean) => Promise<XAnswer>,
  signal?: AbortSignal,
): Promise<EditorSourceSnapshot> {
  const controlBase = mediaControlBaseUrl();
  const mediaKey = mediaStreamId(broadcast);
  const roundUrl = roundRewindUrl(controlBase, mediaKey, broadcast.streamId);
  if (controlBase === null || roundUrl === null) {
    return {
      state: UNCONFIGURED,
      list: null,
      base: editorBaseMs(broadcast, UNCONFIGURED, null, false),
    };
  }
  let state: EditorSourceState = isPlaybackExpired(broadcast.streamId)
    ? { kind: 'expired' }
    : CHECKING;
  if (wantsRoundPoll(state)) {
    const nowMs = Date.now();
    const result = await fetchRewindStatus(roundUrl, controlBase, signal);
    state = nextEditorSourceState(
      state,
      roundEventOf(result, nowMs, parseMs(broadcast.endedAt) ?? nowMs),
    );
  }
  const streamUrl = streamRewindUrl(controlBase, mediaKey);
  if (streamUrl !== null && wantsStreamPoll(state, broadcast)) {
    const result = await fetchRewindStatus(streamUrl, controlBase, signal);
    const currentX = await resolveX(false);
    state = nextEditorSourceState(state, {
      type: 'stream',
      result,
      currentX,
      roundId: broadcast.streamId,
    });
  }
  const source = sourceOfState(state);
  let list: SourceList | null = null;
  if (source !== null) {
    const playlist = await loadSourceList(broadcast, source.listUrl, signal);
    if (playlist === 'expired') state = { kind: 'expired' };
    else if (playlist !== null) list = { mode: source.mode, url: source.listUrl, playlist };
  }
  return { state, list, base: editorBaseMs(broadcast, state, list) };
}

/**
 * 회차 · 스트림 경로 폴링 — 답을 원천 상태 사건으로 싣는다. 회차 경로 404 유예의 원점은 명부 endedAt, 없으면 이 훅이 처음
 * 404 를 받은 때다. 스트림 답은 지금 방송 번호를 물은 뒤 싣고, 늦게 끝난 앞 물음이 새 답을 덮지 않게 마지막 답만 싣는다
 */
function useSourcePolls(
  broadcast: BroadcastRow,
  state: EditorSourceState,
  dispatch: (event: EditorSourceEvent) => void,
  resolveX: (fresh: boolean) => Promise<XAnswer>,
): void {
  const controlBase = mediaControlBaseUrl();
  const mediaKey = mediaStreamId(broadcast);
  const broadcastRef = useRef(broadcast);
  broadcastRef.current = broadcast;
  const resolveXRef = useRef(resolveX);
  resolveXRef.current = resolveX;

  const firstNoneRef = useRef<number | null>(null);
  const onRound = useCallback(
    (result: RewindStatusResult) => {
      const nowMs = Date.now();
      if (result.kind === 'none') firstNoneRef.current ??= nowMs;
      const since = parseMs(broadcastRef.current.endedAt) ?? firstNoneRef.current ?? nowMs;
      dispatch(roundEventOf(result, nowMs, since));
    },
    [dispatch],
  );
  const roundPath = roundRewindUrl(controlBase, mediaKey, broadcast.streamId);
  useRewindStatus(wantsRoundPoll(state) ? roundPath : null, controlBase, onRound);

  const streamAskRef = useRef(0);
  const onStream = useCallback(
    (result: RewindStatusResult) => {
      streamAskRef.current += 1;
      const mine = streamAskRef.current;
      void resolveXRef
        .current(false)
        .catch((): XAnswer => ({ kind: 'unknown' }))
        .then((currentX) => {
          if (mine !== streamAskRef.current) return;
          dispatch({ type: 'stream', result, currentX, roundId: broadcastRef.current.streamId });
        });
    },
    [dispatch],
  );
  const streamPath = wantsStreamPoll(state, broadcast)
    ? streamRewindUrl(controlBase, mediaKey)
    : null;
  useRewindStatus(streamPath, controlBase, onStream);
}

interface SourceListLoad {
  list: SourceList | null;
  /** 렌더를 기다리지 않는 들고 있는 목록(받은 즉시 바뀐다) */
  listRef: { readonly current: SourceList | null };
  listFailed: boolean;
  /**
   * 원천 목록을 받아 들고 있는 목록으로 둔다. 만료면 expired 를 싣는다. 못 받았거나, 끊겼거나, 받는 사이 원천이 바뀌었으면
   * null 이고 들고 있는 목록은 그대로다. signal 이 멈춰도 끊긴 것이라 null 이다
   */
  loadInto(source: SegmentFailure, signal?: AbortSignal): Promise<EditorPlaylist | null>;
}

/**
 * 원천 목록 받기. 원천 주소가 바뀌면(새 원천이 섬) 앞 원천에 걸린 요청을 끊고 그 주소에서 받는다. 같은 주소의 목록을 들고
 * 있으면 받지 않는다. 못 받으면 실패로 두고 폴링 주기마다 다시 받는다(원천이 바뀌거나 내려가면 멈춘다). 내려가면 걸린 요청을
 * 전부 끊는다
 */
function useSourceList(
  broadcast: BroadcastRow,
  state: EditorSourceState,
  dispatch: (event: EditorSourceEvent) => void,
): SourceListLoad {
  const [list, setList] = useState<SourceList | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;
  const listRef = useRef(list);
  listRef.current = list;
  const broadcastRef = useRef(broadcast);
  broadcastRef.current = broadcast;

  const pendingRef = useRef(new Map<AbortController, SegmentFailure>());
  useEffect(() => {
    const pending = pendingRef.current;
    return () => pending.forEach((_source, controller) => controller.abort());
  }, []);

  const loadInto = useCallback(
    async (source: SegmentFailure, signal?: AbortSignal) => {
      if (signal?.aborted) return null;
      const controller = new AbortController();
      const stop = () => controller.abort();
      signal?.addEventListener('abort', stop, { once: true });
      pendingRef.current.set(controller, source);
      let playlist: Awaited<ReturnType<typeof loadSourceList>>;
      try {
        playlist = await loadSourceList(broadcastRef.current, source.listUrl, controller.signal);
      } catch (e) {
        if (controller.signal.aborted) return null; // 원천이 바뀌었거나 내려갔거나 부른 쪽이 멈췄다
        throw e;
      } finally {
        pendingRef.current.delete(controller);
        signal?.removeEventListener('abort', stop);
      }
      // 끊긴 뒤에는 답이 왔어도 버린다(끊기와 답 도착이 엇갈릴 수 있다)
      if (controller.signal.aborted) return null;
      if (!isSameSource(sourceOfState(stateRef.current), source)) return null;
      if (playlist === 'expired') dispatch({ type: 'accessExpired' });
      if (playlist === 'expired' || playlist === null) return null;
      const loaded: SourceList = { mode: source.mode, url: source.listUrl, playlist };
      listRef.current = loaded;
      setList(loaded);
      return playlist;
    },
    [dispatch],
  );

  const source = sourceOfState(state);
  const sourceKey = source === null ? null : `${source.mode} ${source.listUrl}`;
  useEffect(() => {
    const current = sourceOfState(stateRef.current);
    pendingRef.current.forEach((held, controller) => {
      if (current === null || !isSameSource(current, held)) controller.abort();
    });
    if (current === null || listRef.current?.url === current.listUrl) return undefined;
    let active = true;
    let timer: number | undefined;
    const attempt = () => {
      // 그사이 다른 받기(reloadList · 조각 실패의 다시 받기)가 이 원천의 목록을 들었으면 더 받지 않는다
      if (listRef.current?.url === current.listUrl) return;
      void loadInto(current).then((playlist) => {
        if (!active || playlist !== null) return;
        setFailedUrl(current.listUrl);
        timer = window.setTimeout(attempt, REWIND_POLL_MS);
      });
    };
    attempt();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [sourceKey, loadInto]);

  const listFailed =
    source !== null && list?.url !== source.listUrl && failedUrl === source.listUrl;
  return { list, listRef, listFailed, loadInto };
}

/**
 * 편집기 원천을 폴링한다. 상태 · 받은 목록 · base 와, 저장 때 목록 다시 받기 · 조각 실패 싣기를 준다
 * @param broadcast 연 방송(세션 동안 같은 방송)
 * @param resolveX 지금 방송 번호 묻기(editorXResolver)
 */
export function useEditorSource(
  broadcast: BroadcastRow,
  resolveX: (fresh: boolean) => Promise<XAnswer>,
): EditorSource {
  const controlBase = mediaControlBaseUrl();
  const mediaKey = mediaStreamId(broadcast);
  const roundPath = roundRewindUrl(controlBase, mediaKey, broadcast.streamId);
  const [state, dispatch] = useReducer(
    nextEditorSourceState,
    roundPath === null ? UNCONFIGURED : CHECKING,
  );
  const stateRef = useRef(state);
  stateRef.current = state;
  const broadcastRef = useRef(broadcast);
  broadcastRef.current = broadcast;
  const { list, listRef, listFailed, loadInto } = useSourceList(broadcast, state, dispatch);

  // base — 처음 정해진 값을 세션 동안 둔다(렌더 중 갱신 — 다음 렌더 전에 반영된다)
  const [frozenBase, setFrozenBase] = useState<number | null>(null);
  const base = frozenBase ?? editorBaseMs(broadcast, state, list, roundPath !== null);
  if (frozenBase === null && base !== null) setFrozenBase(base);

  useEffect(() => {
    if (isPlaybackExpired(broadcast.streamId)) dispatch({ type: 'accessExpired' });
  }, [broadcast.streamId]);

  useSourcePolls(broadcast, state, dispatch, resolveX);

  const reloadList = useCallback(
    async (signal?: AbortSignal) => {
      const current = sourceOfState(stateRef.current);
      if (current === null) return null;
      const held = listRef.current;
      if (current.mode === 'vod' && held?.url === current.listUrl) return held.playlist;
      return loadInto(current, signal);
    },
    [listRef, loadInto],
  );

  const reportSegmentFailure = useCallback(
    async (failure: SegmentFailureKind, seq: number, listUrl: string) => {
      const current = sourceOfState(stateRef.current);
      if (current === null || current.listUrl !== listUrl) return; // 낡은 창 — 지금 원천의 목록이 아니다
      const held = listRef.current?.url === listUrl ? listRef.current.playlist : null;
      // 받는 사이 원천이 바뀌면 아래 사건은 원천 상태가 버린다(listUrl 이 지금 원천이 아니다)
      const reloaded = current.mode === 'vod' ? null : await loadInto(current);
      // 다시 받기가 실패하면 그 seq 가 목록에서 빠졌는지 알 근거가 없다 — 낡은 목록으로 보면 하한 아래로 빠진 조각이
      // goneOrNone · access 로 굳는다(맞는 판정은 outsideDvr · 없는 조각은 403 으로도 온다). 장부 85 kty 2026-11-14 (가) · 403 확장
      if (current.mode === 'live' && reloaded === null) return;
      const playlist = reloaded ?? held;
      const roundGone =
        failure === 'forbiddenAfterRefresh' && (await isRoundGone(broadcastRef.current));
      dispatch({
        type: 'segment',
        failure,
        listed: playlist === null || playlist.rows.some((row) => row.seq === seq),
        live: current.mode === 'live',
        roundGone,
        listUrl,
      });
    },
    [listRef, loadInto],
  );

  return { state, list, base, listFailed, reloadList, reportSegmentFailure };
}
