'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMediaBuffering } from '@/features/player/useMediaBuffering';
import { windowBlobs } from './editorMedia';
import {
  boundaryAction,
  editorSourceError,
  effectiveBounds,
  MEDIA_END_MARGIN_SECONDS,
  type PlaybackBounds,
  type PlaybackError,
} from './editorPlayback';
import { createPlaybackClock, type ClockedPlayback } from './playbackClock';
import { editorSourceView } from './source/editorSourceState';
import { sourceTimelineOf } from './source/editorSourceTimeline';
import {
  decideWindow,
  ensureWindowHeads,
  fetchEditorWindow,
  type WindowTarget,
} from './source/editorWindow';
import { SegmentForbiddenError, SegmentGoneError } from './source/fetchWindowBytes';
import { headCacheFor, type HeadCache } from './source/fileHeadProbe';
import type { RemuxedWindow } from './source/remuxWindow';
import type { EditorSource } from './source/useEditorSource';
import { placeWindow, type WindowPlacement } from './source/windowPlacement';

// 편집기의 실재생 어댑터(POK-251 · POK-473 설계 prw2 5절 4) — ③ 되감기 목록에서 「구간 앞뒤로 조금 넉넉한 창」의 조각을 받아
// mediabunny 로 일반 mp4 하나로 다시 싸고(remuxWindow) <video> 하나로 튼다. 녹화 재생 서버(/playback)는 부르지 않는다.
//
// 시간축은 편집기와 같다 — 편집기 표시 초 S = (목록 PDT 축 ms − base)/1000. 영상 노드의 시간(창 Blob 시각)과 S 는 창 배치
// (placeWindow)만 바꾼다: 재생 위치는 sAtBlob, 시크는 blobAtS, 찍기는 pickAtBlob · pickAtS. 판 NULL 조각은 ② 머리가 오면 배치를
// 다시 셈한다(그 전에는 ③ 영상 첫 DTS 로 근사해 먼저 보인다).
//
// 창은 런 하나 안에 둔다(다른 런은 한 영상으로 이어 쌀 수 없다 — segmentsForWindow). 구간이 창 밖으로 나가면 지금 목록으로 창을
// 다시 정해 새로 받는다. 핸들을 끄는 동안 매번 받지 않게 잠깐 기다렸다 바꾼다. 방송 중 목록이 창 끝(구간 끝 + 여유)에 못
// 미치면 4초마다 목록을 다시 받으며 30초까지 기다린 뒤, 그래도 모자라면 있는 조각까지만 받는다. 그렇게 목록 끝에서 멈춘 창도
// 구간 끝을 창 끝 너머로 늘리면 다시 정한다(목록은 다시 받아 봐야 자랐는지 안다). 카드 자리가 목록 끝 뒤(uploading)면 풀릴
// 때까지 상한 없이 4초마다 목록을 다시 받는다.
//
// 원천이 서지 않았거나 막히면(D3 — 14.2) 그 까닭을 재생 오류로 알린다. 원천이 막혀도(ending 등) 받은 창은 그대로 틀고 새 창만
// 받지 않는다. 조각이 403(갱신 뒤) · 404 면 그 조각과 창의 목록 주소를 원천에 싣는다(D3 6–9행).

// 창 정하기 · 받기는 카드의 미리 받기와 함께 쓰는 source/editorWindow 에 있다. PAD_SECONDS 는 전부터 이 모듈의 이름이라 다시 내보낸다
export { PAD_SECONDS } from './source/editorWindow';

const REWINDOW_DELAY_MS = 500;
/**
 * 방송 중 목록을 다시 받는 간격과, 창이 목록 끝 뒤를 바랄 때 기다리는 한도(설계 prw2 5절 2). uploading 은 한도가 없다. 안 변한
 * 목록을 TARGETDURATION/2(우리 목록 3–4초) 이상 간격으로 다시 받는 것은 RFC 8216 6.3.4 규칙 안이다
 */
const LIVE_LIST_RELOAD_MS = 4_000;
const LIVE_LIST_WAIT_MS = 30_000;
const LIVE_LIST_RELOADS = Math.floor(LIVE_LIST_WAIT_MS / LIVE_LIST_RELOAD_MS);

/** 창이 쓰는 원천 — useEditorSource 결과 중 상태 · 목록 · 다시 받기 · 조각 실패 싣기 */
export type EditorWindowSource = Pick<
  EditorSource,
  'state' | 'list' | 'listFailed' | 'reloadList' | 'reportSegmentFailure'
>;

export interface EditorVideoPlayback {
  playback: ClockedPlayback;
  /** 화면이 <video ref=…> 로 받는다. 노드는 보이지 않게 두고 프레임은 VideoSurface 가 그린다 */
  videoRef: (element: HTMLVideoElement | null) => void;
  video: HTMLVideoElement | null;
  src: string;
  /** 영상이 받은 창(편집기 초 — 조각 경계). 아직 못 받았거나 받을 조각이 없으면 null */
  window: { startSeconds: number; endSeconds: number } | null;
  /** 지금 받으려는 창(편집기 초). 영상을 다 받기 전에도 있다. 창을 아직 못 정했거나 받을 조각이 없으면 null */
  targetWindow: { startSeconds: number; endSeconds: number } | null;
  /** 받아 둔 영상의 0초가 편집기 몇 초인가(창 배치로 셈한 값). 아직 못 받았으면 받으려는 창의 시작 */
  windowStartSeconds: number;
  /** 받은 창의 배치(Blob 시각 ↔ 편집기 초). 아직 못 받았으면 null */
  placement: WindowPlacement | null;
  /** 영상이 끊겨 기다리는 중(POK-439). 첫 mp4를 받는 동안은 영상 노드에 소스가 없어 거짓이다 */
  buffering: boolean;
}

interface Range {
  startSeconds: number;
  endSeconds: number;
}

/** 받은 창 — 정한 창 · 다시 싼 결과 · 영상 주소 · 배치 */
interface Placed {
  target: WindowTarget;
  remuxed: RemuxedWindow;
  src: string;
  placement: WindowPlacement | null;
}

/** 구간 끝이 창 끝(반 초 안쪽)에 닿았다 — 창을 뒤로 늘려야 한다 */
function reachesEnd(bounds: Range, target: WindowTarget): boolean {
  return bounds.endSeconds > target.endSeconds - 0.5;
}

function placementOf(
  target: WindowTarget,
  remuxed: RemuxedWindow,
  base: number,
  heads: HeadCache,
): WindowPlacement | null {
  try {
    return placeWindow(remuxed, target.segs, base, heads);
  } catch (e) {
    console.error('[editor] 받은 창을 배치하지 못했어요:', (e as Error).message);
    return null;
  }
}

/**
 * 구간(want)을 덮을 창을 정한다. 원천이 서 있고(view ready) 목록이 있을 때만 정한다 — 원천이 막히면 새 창을 정하지 않는다.
 * 같은 구간에는 한 번만 정하고, 정한 창의 열쇠가 같으면 앞 창을 그대로 둔다(다시 받지 않는다). 방송 중 목록이 창 끝에 못
 * 미치면 4초마다 목록을 다시 받으며 30초까지 기다린다 — 구간(want)이 새로 올 때마다 다시 센다
 */
function useWindowTarget(
  source: EditorWindowSource,
  base: number,
  mediaKey: string,
  want: Range,
  viewReady: boolean,
): { target: WindowTarget | null; empty: boolean } {
  const [decided, setDecided] = useState<{ want: Range; target: WindowTarget | null } | null>(null);
  const [tick, setTick] = useState(0);
  const reloadRef = useRef(source.reloadList);
  reloadRef.current = source.reloadList;
  const waitRef = useRef({ want, reloads: 0 });
  const list = source.list;

  useEffect(() => {
    if (list === null || !viewReady || decided?.want === want) return undefined;
    if (waitRef.current.want !== want) waitRef.current = { want, reloads: 0 };
    const wait = waitRef.current;
    const growing = list.mode === 'live' && wait.reloads < LIVE_LIST_RELOADS;
    const decision = decideWindow(want, list, base, mediaKey, growing);
    if (decision.kind === 'wait') {
      let active = true;
      const timer = window.setTimeout(() => {
        wait.reloads += 1;
        void reloadRef.current().finally(() => {
          if (active) setTick((n) => n + 1);
        });
      }, LIVE_LIST_RELOAD_MS);
      return () => {
        active = false;
        window.clearTimeout(timer);
      };
    }
    const next = decision.kind === 'window' ? decision.target : null;
    setDecided((prev) => ({
      want,
      target: prev?.target != null && prev.target.key === next?.key ? prev.target : next,
    }));
    return undefined;
  }, [list, viewReady, want, decided, base, mediaKey, tick]);

  return { target: decided?.target ?? null, empty: decided !== null && decided.target === null };
}

/**
 * 방송 중 카드 자리가 목록 끝 뒤(uploading)인 동안 4초마다 목록을 다시 받는다 — 상한 없음(kty 2026-11-12 · 설계 14.2 D3 (ㄱ)
 * 자동 재시도). 업로드가 늦어도 목록이 자라면 풀려야 해서다. 목록이 자라 uploading 이 풀리거나, 원천이 바뀌거나(finalizing ·
 * 다른 목록) 화면을 떠나면 멈춘다. 다음 받기는 앞 받기가 끝난 뒤 4초다(겹쳐 받지 않는다)
 */
function useUploadingReload(source: EditorWindowSource, uploading: boolean): void {
  const reloadRef = useRef(source.reloadList);
  reloadRef.current = source.reloadList;
  const listUrl = uploading ? (source.list?.url ?? null) : null;

  useEffect(() => {
    if (listUrl === null) return undefined;
    let active = true;
    let timer: number | undefined;
    const schedule = () => {
      timer = window.setTimeout(() => {
        reloadRef
          .current()
          .catch((e: unknown) =>
            console.warn('[editor] 목록을 다시 받지 못했어요:', (e as Error).message),
          )
          .finally(() => {
            if (active) schedule();
          });
      }, LIVE_LIST_RELOAD_MS);
    };
    schedule();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [listUrl]);
}

/** 조각 받기 · 다시 싸기 실패 → 재생 오류. 403(갱신 뒤) · 404 는 그 조각과 창의 목록 주소를 원천에 싣는다 */
function windowFailureOf(
  e: unknown,
  target: WindowTarget,
  report: EditorWindowSource['reportSegmentFailure'],
): PlaybackError {
  if (e instanceof SegmentForbiddenError || e instanceof SegmentGoneError) {
    const kind = e instanceof SegmentForbiddenError ? 'forbiddenAfterRefresh' : 'gone';
    report(kind, e.seq ?? target.segs[0]!.seq, target.listUrl).catch((err: unknown) =>
      console.warn('[editor] 조각 실패를 원천에 싣지 못했어요:', (err as Error).message),
    );
    return kind === 'gone' ? 'gone' : 'access';
  }
  console.error('[editor] 창을 받아 다시 싸지 못했어요:', (e as Error).message);
  return 'fatal';
}

/**
 * 정한 창을 받아(미리 받아 둔 창이면 꺼내) 다시 싸고 배치한다. 판 NULL 이면 ② 머리를 함께 읽고, 머리가 오면 배치를 다시
 * 셈한다(onReplaced). 창이 바뀌거나 화면을 떠나면 걸린 요청 · 머리 판독을 끊는다
 */
function useWindowMedia(options: {
  target: WindowTarget | null;
  streamId: string;
  mediaKey: string;
  base: number;
  source: EditorWindowSource;
  setFailure: (failure: PlaybackError | null) => void;
  onReplaced: (placement: WindowPlacement | null) => void;
}): Placed | null {
  const { target, streamId, mediaKey, base, setFailure } = options;
  const [placed, setPlaced] = useState<Placed | null>(null);
  const reportRef = useRef(options.source.reportSegmentFailure);
  reportRef.current = options.source.reportSegmentFailure;
  const onReplacedRef = useRef(options.onReplaced);
  onReplacedRef.current = options.onReplaced;
  const heads = useMemo(() => headCacheFor(mediaKey), [mediaKey]);

  useEffect(() => {
    setFailure(null);
    if (target === null) {
      setPlaced(null);
      return undefined;
    }
    const abort = new AbortController();
    void ensureWindowHeads(streamId, mediaKey, target.segs, abort.signal);
    // 카드가 미리 받아 둔 창이면 다시 받지 않는다(POK-499)
    const cached = windowBlobs.get(target.key);
    (cached !== undefined
      ? Promise.resolve(cached)
      : fetchEditorWindow(mediaKey, target.segs, abort.signal)
    )
      .then((remuxed) => {
        if (abort.signal.aborted) return;
        const placement = placementOf(target, remuxed, base, heads);
        if (placement === null) setFailure('fatal');
        setPlaced({ target, remuxed, src: URL.createObjectURL(remuxed.blob), placement });
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setFailure(windowFailureOf(e, target, reportRef.current));
      });
    return () => abort.abort();
  }, [target, streamId, mediaKey, base, heads, setFailure]);

  // 머리가 늦게 오면 같은 창을 다시 배치한다(표시만 바뀐다). 캐시는 영상 키 하나에 하나라 다른 창(카드 미리 받기)의 머리에도
  // 알림이 온다 — 이 창 조각의 머리가 바뀌었을 때만 다시 배치한다
  useEffect(() => {
    if (placed === null) return undefined;
    const signature = () =>
      placed.target.segs.map((seg) => heads.get(seg.seq)?.state ?? '-').join(' ');
    let seen = signature();
    return heads.subscribe(() => {
      const now = signature();
      if (now === seen) return;
      seen = now;
      const placement = placementOf(placed.target, placed.remuxed, base, heads);
      setPlaced((prev) => (prev === placed ? { ...prev, placement } : prev));
      onReplacedRef.current(placement);
    });
  }, [placed, base, heads]);

  // 옛 영상 주소는 새 영상이 들어간 뒤에(또는 화면을 떠날 때) 지운다: 새 창을 받는 동안에도 <video>는 옛 주소로 틀고
  // 되감기 때문에, 받기를 시작할 때 지우면 그 사이 되감기가 브라우저에 따라 실패한다(PR #341 claude)
  const src = placed?.src ?? '';
  useEffect(() => {
    if (src === '') return undefined;
    return () => URL.revokeObjectURL(src);
  }, [src]);

  return placed;
}

/** 원천 상태(D3)와 받은 목록 — 보기 상태 · 목록 끝 · 개요 끝 */
function useSourceView(source: EditorWindowSource, base: number, want: Range) {
  const list = source.list;
  const bounds = useMemo(() => {
    const rows = list?.playlist.rows ?? [];
    const first = rows[0];
    const last = rows.at(-1);
    if (list === null || first === undefined || last === undefined) return null;
    return {
      firstPdtMs: first.pdtMs,
      endMs: last.pdtMs + last.durationMs,
      timeline: sourceTimelineOf(list.playlist, base),
    };
  }, [list, base]);
  const view = editorSourceView(source.state, bounds, base + want.startSeconds * 1000);
  return {
    view,
    sourceError: editorSourceError(view, list !== null, source.listFailed),
    timeline: bounds?.timeline ?? { pieces: [], endSeconds: 0 },
  };
}

export function useEditorVideoPlayback(options: {
  /** clip 방송 번호 — 창 소리 주소 요청(② 머리) */
  streamId: string;
  /** 영상 키 — 조각 · ② 머리 캐시 */
  mediaKey: string;
  /** 편집기 0초의 절대 ms — 세션 동안 바꾸지 않는다 */
  base: number;
  source: EditorWindowSource;
  initialRange: Range;
}): EditorVideoPlayback {
  const { streamId, mediaKey, base, source, initialRange } = options;
  const [video, setVideo] = useState<HTMLVideoElement | null>(null);
  const buffering = useMediaBuffering(useMemo(() => ({ current: video }), [video]));

  // 편집기가 내려가면 영상 노드가 쥔 디코더 · 버퍼를 놓는다. 노드를 문서에서 떼는 것만으로는 GC될 때까지 남는다: src 제거만으로도
  // 모자라 load()까지 해야 풀린다(useHlsPlayback과 같다). 영상 주소 지우기(useWindowMedia)보다 먼저 돌도록 그 앞에 둔다
  useEffect(() => {
    if (video === null) return undefined;
    return () => {
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
  }, [video]);

  const [want, setWant] = useState<Range>(initialRange);
  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(initialRange.startSeconds);
  // 재생 중 위치는 저장소에만 흐른다: 상태로 들면 이 훅을 부르는 화면부터 전부가 프레임마다 다시 돈다
  const [clock] = useState(() => createPlaybackClock(initialRange.startSeconds));
  const [failure, setFailure] = useState<PlaybackError | null>(null);
  /** 재생 위치를 끄는 중. 멈춘 영상도 seeked마다 허브를 깨우지 않게 끌기가 끝날 때 한 번만 올린다 */
  const scrubbingRef = useRef(false);

  const { view, sourceError, timeline } = useSourceView(source, base, want);
  const { target, empty } = useWindowTarget(source, base, mediaKey, want, view.kind === 'ready');
  useUploadingReload(source, view.kind === 'uploading');

  /** 위치를 허브까지 올린다: 멈춤 · 시크 · 구간 경계에서만. 재생 틱은 clock.publish만 부른다 */
  const settle = useCallback(
    (seconds: number) => {
      clock.publish(seconds);
      setCurrentSeconds(seconds);
    },
    [clock],
  );

  const videoNodeRef = useRef(video);
  videoNodeRef.current = video;
  /** 머리가 와 배치를 다시 셈했다: 멈춘 영상은 같은 편집기 초의 자리로 옮긴다(재생 중이면 위치 표시만 바뀐다) */
  const onReplaced = useCallback(
    (placement: WindowPlacement | null) => {
      const node = videoNodeRef.current;
      if (placement === null || node === null || !node.paused) return;
      node.currentTime = Math.max(0, placement.blobAtS(clock.getSeconds()));
    },
    [clock],
  );
  const placed = useWindowMedia({
    target,
    streamId,
    mediaKey,
    base,
    source,
    setFailure,
    onReplaced,
  });
  const placedRef = useRef(placed);
  placedRef.current = placed;

  const boundsRef = useRef<PlaybackBounds>({ ...initialRange, loop: true });
  /** 창을 갈아 끼운 뒤 돌아갈 자리(편집기 초) */
  const pendingSeekRef = useRef<number | null>(initialRange.startSeconds);
  const wantRef = useRef(want);
  wantRef.current = want;
  const committedKeyRef = useRef<string | null>(null);
  const targetKey = target?.key ?? null;
  useEffect(() => {
    if (targetKey === null) return;
    // 새 창을 정했다 — 영상이 들어오면 지금 구간 시작으로 간다(첫 창은 처음 구간 시작)
    if (committedKeyRef.current !== null && committedKeyRef.current !== targetKey) {
      pendingSeekRef.current = wantRef.current.startSeconds;
    }
    committedKeyRef.current = targetKey;
  }, [targetKey]);
  const rewindowTimer = useRef<number | null>(null);

  const transport = useVideoTransport({
    video,
    playing,
    placedRef,
    boundsRef,
    pendingSeekRef,
    scrubbingRef,
    clock,
    settle,
    setPlaying,
    setFailure,
  });

  const timelineRef = useRef(timeline);
  timelineRef.current = timeline;
  const listRef = useRef(source.list);
  listRef.current = source.list;
  const targetRef = useRef(target);
  targetRef.current = target;

  const setBounds = useCallback(
    (bounds: PlaybackBounds) => {
      boundsRef.current = bounds;
      const t = targetRef.current;
      if (t === null) {
        setWant((prev) =>
          prev.startSeconds === bounds.startSeconds && prev.endSeconds === bounds.endSeconds
            ? prev
            : { startSeconds: bounds.startSeconds, endSeconds: bounds.endSeconds },
        );
        return;
      }
      const { pieces, endSeconds } = timelineRef.current;
      const firstSeconds = pieces[0]?.fromSeconds ?? 0;
      const needsEarlier =
        bounds.startSeconds < t.startSeconds + 0.5 && t.startSeconds > firstSeconds;
      // 방송 중 목록은 끝이 더 자랄 수 있다 — 목록 끝에서 멈춘 창(30초 기다림을 포기한 창)도 다시 정한다
      const growing = listRef.current?.mode === 'live';
      const needsLater = reachesEnd(bounds, t) && (t.endSeconds < endSeconds || growing);
      if (!needsEarlier && !needsLater) return;
      if (rewindowTimer.current !== null) window.clearTimeout(rewindowTimer.current);
      rewindowTimer.current = window.setTimeout(() => {
        rewindowTimer.current = null;
        const current = boundsRef.current;
        const list = listRef.current;
        // 기다리는 사이 구간을 안쪽으로 되돌렸으면 창이 그대로다: 같은 영상을 다시 받고 구간 시작으로 튀지 않게 둔다. 방송 중
        // 목록 끝에서 멈춘 창은 지금 목록으로는 열쇠가 같아도 구간을 넘긴다 — 구간을 새로 줘 목록 기다리기(4초 · 30초 한도)를
        // 다시 돌린다. 다시 받기는 이렇게 구간을 바꿀 때만 일어나 한 번에 7회를 넘지 않는다
        const held = targetRef.current;
        const next = list === null ? null : decideWindow(current, list, base, mediaKey, false);
        const awaitsGrowth = list?.mode === 'live' && held !== null && reachesEnd(current, held);
        if (next?.kind === 'window' && next.target.key === held?.key && !awaitsGrowth) return;
        setWant({ startSeconds: current.startSeconds, endSeconds: current.endSeconds });
      }, REWINDOW_DELAY_MS);
    },
    [base, mediaKey],
  );

  useEffect(
    () => () => {
      if (rewindowTimer.current !== null) window.clearTimeout(rewindowTimer.current);
    },
    [],
  );

  const pickAtPlayhead = useCallback(() => {
    const placement = placedRef.current?.placement ?? null;
    const node = videoNodeRef.current;
    return placement === null || node === null ? null : placement.pickAtBlob(node.currentTime);
  }, []);
  const pickAtS = useCallback(
    (seconds: number) => placedRef.current?.placement?.pickAtS(seconds) ?? null,
    [],
  );

  const error = sourceError ?? (empty ? 'no-recording' : failure);
  const durationSeconds = timeline.endSeconds;
  const playback = useMemo<ClockedPlayback>(
    () => ({
      playing,
      currentSeconds,
      clock,
      durationSeconds,
      error,
      ...transport,
      setBounds,
      pickAtPlayhead,
      pickAtS,
    }),
    [
      playing,
      currentSeconds,
      clock,
      durationSeconds,
      error,
      transport,
      setBounds,
      pickAtPlayhead,
      pickAtS,
    ],
  );

  const loadedTarget = placed?.target ?? null;
  const loadedWindow = useMemo(
    () =>
      loadedTarget === null
        ? null
        : { startSeconds: loadedTarget.startSeconds, endSeconds: loadedTarget.endSeconds },
    [loadedTarget],
  );
  const targetWindow = useMemo(
    () =>
      target === null ? null : { startSeconds: target.startSeconds, endSeconds: target.endSeconds },
    [target],
  );
  const placement = placed?.placement ?? null;

  return {
    playback,
    videoRef: setVideo,
    video,
    src: placed?.src ?? '',
    window: loadedWindow,
    targetWindow,
    windowStartSeconds: placement?.sAtBlob(0) ?? target?.startSeconds ?? want.startSeconds,
    placement,
    buffering,
  };
}

type Transport = Pick<
  ClockedPlayback,
  'togglePlay' | 'seekTo' | 'seekBy' | 'scrubTo' | 'endScrub' | 'setRate'
>;

/** 영상 노드 배선이 함께 쓰는 것 */
interface VideoWiring {
  video: HTMLVideoElement | null;
  playing: boolean;
  placedRef: { readonly current: Placed | null };
  boundsRef: { current: PlaybackBounds };
  pendingSeekRef: { current: number | null };
  scrubbingRef: { current: boolean };
  clock: ReturnType<typeof createPlaybackClock>;
  settle: (seconds: number) => void;
  setPlaying: (playing: boolean) => void;
  setFailure: (failure: PlaybackError | null) => void;
}

/** 위치 바꾸기 — Blob 시각 ↔ 편집기 초 · 경계 */
interface Conversions {
  /** 그 Blob 시각의 편집기 초. 받은 창이 없으면 지금 위치 */
  sAt: (blobSeconds: number) => number;
  /** 경계 판정에 쓸 구간 — 받은 영상이 구간 끝보다 먼저 끝나면 영상 끝이 구간 끝이다(POK-490) */
  boundsNow: (node: HTMLVideoElement) => PlaybackBounds;
  /** 편집기 초를 받은 창 안으로 묶어 그 Blob 시각으로 옮긴다. 받은 창이 없으면 옮기지 않고 null */
  seekNode: (node: HTMLVideoElement, seconds: number) => { s: number } | null;
}

function useConversions(w: VideoWiring): Conversions {
  const { placedRef, boundsRef, clock } = w;
  const sAt = useCallback(
    (blobSeconds: number) =>
      placedRef.current?.placement?.sAtBlob(blobSeconds) ?? clock.getSeconds(),
    [placedRef, clock],
  );
  const boundsNow = useCallback(
    (node: HTMLVideoElement) =>
      effectiveBounds(
        boundsRef.current,
        Number.isFinite(node.duration) ? sAt(node.duration) : node.duration,
      ),
    [boundsRef, sAt],
  );
  const seekNode = useCallback(
    (node: HTMLVideoElement, seconds: number) => {
      const placed = placedRef.current;
      if (placed?.placement == null) return null;
      const { startSeconds, endSeconds } = placed.target;
      const s = Math.min(endSeconds - 0.05, Math.max(startSeconds, seconds));
      node.currentTime = Math.max(0, placed.placement.blobAtS(s));
      return { s };
    },
    [placedRef],
  );
  return { sAt, boundsNow, seekNode };
}

/** 영상 노드 이벤트 — 메타데이터(처음 자리) · 위치 · 재생 · 멈춤 · 끝 · 오류 */
function videoHandlers(node: HTMLVideoElement, w: VideoWiring, c: Conversions) {
  const { boundsRef, pendingSeekRef, scrubbingRef, clock, settle, setPlaying, setFailure } = w;
  const position = () => c.sAt(node.currentTime);
  const replay = () =>
    node.play().catch((e: unknown) => {
      if (!(e instanceof DOMException && e.name === 'AbortError')) setFailure('fatal');
    });
  return {
    loadedmetadata: () => {
      setFailure(null);
      const target = pendingSeekRef.current;
      pendingSeekRef.current = null;
      if (target !== null) c.seekNode(node, target);
      settle(position());
    },
    timeupdate: () => {
      const now = position();
      const action = boundaryAction(now, c.boundsNow(node));
      if (action.kind === 'seek') {
        c.seekNode(node, action.toSeconds);
        settle(position());
      } else if (action.kind === 'stop' && !node.paused) {
        node.pause();
        settle(position());
      } else if (node.paused && !scrubbingRef.current) {
        settle(now);
      } else {
        // 재생 중(초당 4번쯤 온다)이나 끄는 중에는 허브를 깨우지 않는다
        clock.publish(now);
      }
    },
    play: () => setPlaying(true),
    pause: () => {
      setPlaying(false);
      settle(position());
    },
    // 경계 틱보다 영상 끝이 먼저 왔다(영상 끝 바로 앞의 틱을 놓침): 「구간 반복」이면 구간 시작으로 되감아 이어 튼다(POK-490).
    // 받은 영상이 구간 시작에도 못 닿으면 되감을 자리가 영상 밖이라 멈춘다 — 「못 닿음」은 effectiveBounds와 같은 여유로
    // 본다(PR #454 1 · 2차 리뷰)
    ended: () => {
      const mediaEnd = c.sAt(node.duration);
      const loopable = mediaEnd - MEDIA_END_MARGIN_SECONDS > boundsRef.current.startSeconds;
      if (!boundsRef.current.loop || !loopable) {
        setPlaying(false);
        settle(position());
        return;
      }
      c.seekNode(node, boundsRef.current.startSeconds);
      settle(position());
      void replay();
    },
    error: () => setFailure('fatal'),
  };
}

function useVideoEvents(w: VideoWiring, c: Conversions): void {
  const { video, pendingSeekRef } = w;
  const wiringRef = useRef({ w, c });
  wiringRef.current = { w, c };
  useEffect(() => {
    if (video === null) return undefined;
    const handlers = videoHandlers(video, wiringRef.current.w, wiringRef.current.c);
    const names = Object.keys(handlers) as (keyof typeof handlers)[];
    for (const name of names) video.addEventListener(name, handlers[name]);
    // seeked 도 위치 틱과 같다(멈춘 영상의 시크)
    video.addEventListener('seeked', handlers.timeupdate);
    // 리스너를 달기 전에 이미 메타데이터가 와 있을 수 있다(캐시된 영상) — 그러면 처음 자리로 못 간다.
    if (video.readyState >= 1 && pendingSeekRef.current !== null) handlers.loadedmetadata();
    return () => {
      for (const name of names) video.removeEventListener(name, handlers[name]);
      video.removeEventListener('seeked', handlers.timeupdate);
    };
  }, [video, pendingSeekRef]);
}

/**
 * timeupdate 는 초당 4번쯤이라 구간 끝을 0.25초까지 넘긴다 — 재생 중에는 프레임마다 경계를 본다. 위치는 프레임마다 저장소로만
 * 간다. 허브까지 가는 것은 되감을 때뿐이고, 멈출 때는 pause 이벤트가 올린다
 */
function usePlayingFrames(w: VideoWiring, c: Conversions): void {
  const { video, playing, clock, settle } = w;
  const { sAt, boundsNow, seekNode } = c;
  useEffect(() => {
    if (video === null || !playing) return undefined;
    let raf = 0;
    const tick = () => {
      raf = requestAnimationFrame(tick);
      const now = sAt(video.currentTime);
      const action = boundaryAction(now, boundsNow(video));
      if (action.kind === 'seek') {
        seekNode(video, action.toSeconds);
        settle(sAt(video.currentTime));
        return;
      }
      if (action.kind === 'stop') video.pause();
      clock.publish(now);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [video, playing, clock, settle, sAt, boundsNow, seekNode]);
}

/** 영상 노드 배선과 재생 조작(재생 · 시크 · 끌기 · 배속) */
function useVideoTransport(w: VideoWiring): Transport {
  const { video, scrubbingRef, clock, settle, setFailure } = w;
  const c = useConversions(w);
  const { sAt, boundsNow, seekNode } = c;
  useVideoEvents(w, c);
  usePlayingFrames(w, c);

  const togglePlay = useCallback(() => {
    if (video === null) return;
    if (!video.paused) {
      video.pause();
      return;
    }
    const bounds = boundsNow(video);
    const now = sAt(video.currentTime);
    // 구간 끝에 서 있으면 처음부터 다시 — 끝에서 재생을 누르면 곧바로 멈춰 아무 일도 없는 것처럼 보인다
    if (now >= bounds.endSeconds - 0.05 || now < bounds.startSeconds) {
      seekNode(video, bounds.startSeconds);
      settle(sAt(video.currentTime));
    }
    // 누르자마자 멈추거나 새 영상으로 갈아 끼우면 play()가 AbortError로 거절된다: 보통 조작이라 오류로 알리지 않는다
    video.play().catch((e: unknown) => {
      if (!(e instanceof DOMException && e.name === 'AbortError')) setFailure('fatal');
    });
  }, [video, boundsNow, sAt, seekNode, settle, setFailure]);
  const seekTo = useCallback(
    (seconds: number) => {
      if (video === null) return;
      settle(seekNode(video, seconds)?.s ?? seconds);
    },
    [video, seekNode, settle],
  );
  const seekBy = useCallback(
    (delta: number) => {
      if (video !== null) seekTo(sAt(video.currentTime) + delta);
    },
    [video, seekTo, sAt],
  );
  const scrubTo = useCallback(
    (seconds: number) => {
      if (video === null) return;
      scrubbingRef.current = true;
      clock.publish(seekNode(video, seconds)?.s ?? seconds);
    },
    [video, seekNode, clock, scrubbingRef],
  );
  const endScrub = useCallback(() => {
    if (!scrubbingRef.current) return;
    scrubbingRef.current = false;
    settle(clock.getSeconds());
  }, [clock, settle, scrubbingRef]);
  const setRate = useCallback(
    (rate: number) => {
      if (video !== null) video.playbackRate = rate;
    },
    [video],
  );
  return useMemo(
    () => ({ togglePlay, seekTo, seekBy, scrubTo, endScrub, setRate }),
    [togglePlay, seekTo, seekBy, scrubTo, endScrub, setRate],
  );
}
