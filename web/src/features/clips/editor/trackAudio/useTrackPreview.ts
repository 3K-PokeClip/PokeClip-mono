'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { requestSegmentAudioAccess, type SegmentAudioAccess } from '@/api/clipEditor';
import { segmentTrackKey, segmentTracks } from '../editorMedia';
import type { PlaylistSegment } from '../source/editorPlaylist';
import type { RemuxedWindow } from '../source/remuxWindow';
import type { WindowPlacement } from '../source/windowPlacement';
import {
  loadTrackAudio,
  SegmentUrlExpiredError,
  windowAnchors,
  type SegmentAnchors,
  type TrackPiece,
} from './loadTrackAudio';
import { LEVEL_STEP_SECONDS, measureCells, type TrackLevels } from './trackLevels';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
//
// 소리는 창 Blob 시각(영상 노드의 currentTime)에 놓는다(설계 prw2 3.3절 — loadTrackAudio). 그래서 재생기는 창 하나에 묶이고,
// 창이 바뀌면 바로 버린다: 앞 창 재생기가 새 창 Blob 시각을 따라가면 다른 자리 소리를 낸다. 새 창 소리가 오기까지는 음량을 안
// 바꾼 편집본이면 영상 소리, 바꿔 둔 편집본이면 무음이다(뺀 트랙이 섞인 최종 믹스를 내지 않는다 — 아래 영상 소리를 끄는 때).
//
// 못 부르면(소리 문맥 없음·주소·받기·풀기 실패) 「unavailable」이고 영상 소리가 그대로 난다: 화면이 그 사실을 알린다.

/**
 * - idle: 받을 창이 없다(녹화가 없는 구간 · 녹화 없음). 알릴 것이 없다
 * - unavailable: 못 불렀다. 영상 소리로 들린다고 화면이 알린다
 */
export type TrackPreviewStatus = 'idle' | 'loading' | 'ready' | 'unavailable';

export interface TrackPreview {
  status: TrackPreviewStatus;
  /** 트랙별 소리 크기(타임라인 파형). 못 불렀거나 아직이면 null */
  levels: TrackLevels | null;
  /** 트랙 번호 순 음량(1 = 100%, 끈 트랙 0) */
  setGains(gains: readonly number[]): void;
}

type DecodingContext = AudioContextLike & Pick<AudioContext, 'decodeAudioData' | 'close'>;

/**
 * 풀어 둔 소리를 24kHz로 둔다: 구간 3분 20초 × 트랙 여섯이면 48kHz는 수백 MB다. 미리 듣기에는 24kHz(12kHz까지의 소리)로 충분하다.
 * 브라우저에 소리 문맥이 없으면(시험 환경 등) 던지고, 훅은 그것을 「못 부름」으로 받는다.
 */
export const PREVIEW_SAMPLE_RATE = 24_000;
const defaultContext = (): DecodingContext => new AudioContext({ sampleRate: PREVIEW_SAMPLE_RATE });

/**
 * 받기가 이만큼 아무 진척 없이 멈추면 「못 부름」이다. 불러오는 동안 영상 소리를 꺼 둘 수 있어(음량을 바꿔 둔 편집본),
 * 받기가 멈춘 채면 화면이 아무 소리도 안 낸다(로컬 리뷰 2라운드). 조각(≈3MB) 하나가 올 때마다 다시 잰다: 전체에 시한을
 * 걸면 느린 망에서 3분 구간(≈150MB)을 받다 끊고 받은 것을 다 버렸다(PR #341 2차)
 */
export const TRACK_LOAD_TIMEOUT_MS = 60_000;

/**
 * 이 음량이 영상 소리와 같은가: 재생 서버 영상은 첫 소리 트랙만 남기므로 첫 트랙만 100%이고 나머지를 끈 음량이다.
 * 새로 연 편집본이 이렇다(저장된 트랙 없음 = 첫 트랙만)
 */
export function sameAsVideo(gains: readonly number[]): boolean {
  return gains.every((gain, i) => gain === (i === 0 ? 1 : 0));
}

/** 트랙 소리를 받을 창 — 영상이 받으려는 창(useEditorVideoPlayback 의 target)의 열쇠 · 조각 · S 범위 */
export interface TrackWindow {
  /** 창 열쇠(editorWindowKey). 재생기는 이 창 하나에 묶인다 */
  key: string;
  segs: readonly PlaylistSegment[];
  startSeconds: number;
  endSeconds: number;
}

/** 영상이 받은 창 — 다시 싼 결과(소리를 놓을 Blob 닻)와 배치(파형을 잴 Blob 범위) */
export interface LoadedTrackWindow {
  key: string;
  remuxed: RemuxedWindow;
  placement: WindowPlacement | null;
}

/** 소리 주소를 물을 범위 — 창 조각이 덮는 [첫 PDT, 마지막 PDT + EXTINF)(절대 ms). 확보 후보만큼 넓히지 않는다 */
function audioRangeOf(segs: readonly Pick<PlaylistSegment, 'pdtMs' | 'durationMs'>[]) {
  const first = segs[0];
  const last = segs.at(-1);
  if (first === undefined || last === undefined) throw new Error('창 조각이 없다');
  return { fromMs: first.pdtMs, toMs: last.pdtMs + last.durationMs };
}

/**
 * 창 조각의 ② 녹화 소리를 받아 트랙마다 풀고 창 Blob 시각에 놓는다. 편집기의 미리 듣기와 카드의 미리 받기(POK-499)가 함께
 * 쓴다. 받기가 TRACK_LOAD_TIMEOUT_MS 동안 아무 진척이 없거나 signal이 멈추면 던진다. 하나가 실패하면 받는 중인 나머지도 멈춘다
 */
export function loadSegmentTracks(options: {
  /** clip 방송 번호(편집본과 같은 번호) */
  streamId: string;
  /** 창 조각(seq · PDT · EXTINF) */
  segs: readonly Pick<PlaylistSegment, 'seq' | 'pdtMs' | 'durationMs'>[];
  /** 그 창의 닻. 받기를 다 마친 뒤 부른다 — 기다리는 동안은 받기 멈춤으로 세지 않는다. 거부하면(영상이 그 창을 못 받음) 던진다 */
  anchors: () => Promise<SegmentAnchors>;
  decode: (adts: Uint8Array) => Promise<AudioBuffer>;
  signal?: AbortSignal;
  /** 조각 목록만 보고 본문을 받을지 정한다. false면 하나도 받지 않고 SegmentsNotReadyError로 던진다(loadTrackAudio) */
  precheck?: (segments: SegmentAudioAccess['segments']) => boolean;
}): Promise<TrackPiece[][]> {
  const { streamId, segs, decode, signal, precheck } = options;
  const { fromMs, toMs } = audioRangeOf(segs);
  const abort = new AbortController();
  const stopOuter = () => abort.abort();
  signal?.addEventListener('abort', stopOuter);
  let settled = false;
  let timer = setTimeout(() => abort.abort(), TRACK_LOAD_TIMEOUT_MS);
  /** 받기가 한 걸음 나아갔다(주소 · 조각 하나): 멈춤 시한을 처음부터 다시 잰다 */
  const progressed = () => {
    if (settled) return;
    clearTimeout(timer);
    timer = setTimeout(() => abort.abort(), TRACK_LOAD_TIMEOUT_MS);
  };
  const stopped = new Promise<never>((_, reject) => {
    abort.signal.addEventListener('abort', () => reject(new Error('track audio load stopped')));
  });
  return Promise.race([
    loadTrackAudio(
      {
        fetchAccess: async () => {
          const access = await requestSegmentAudioAccess(streamId, fromMs, toMs);
          progressed();
          return access;
        },
        fetchBytes: async (url) => {
          const res = await fetch(url, { signal: abort.signal });
          // 서명 주소 만료(S3는 403): 주소를 새로 받아 다시 받는다(loadTrackAudio)
          if (res.status === 403) throw new SegmentUrlExpiredError();
          if (!res.ok) throw new Error(`segment ${res.status}`);
          const bytes = await res.arrayBuffer();
          progressed();
          return bytes;
        },
        anchors: async () => {
          // 영상 창(③ 받기 · 다시 싸기)을 기다리는 동안은 소리 받기가 멈춘 것이 아니다
          clearTimeout(timer);
          const anchors = await options.anchors();
          progressed();
          return anchors;
        },
        decode,
      },
      segs.map((seg) => seg.seq),
      precheck,
    ),
    stopped,
  ])
    .catch((error: unknown) => {
      // 받는 중인 다른 조각도 멈춘다: 하나가 실패하면 결과를 버린다(PR #341 claude)
      abort.abort();
      throw error;
    })
    .finally(() => {
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', stopOuter);
    });
}

/**
 * 창의 파형. 그리기는 편집기 초 S 의 고른 칸(LEVEL_STEP_SECONDS)이고, 소리는 Blob 시각에 놓였으므로 S 칸 하나하나를 배치로
 * Blob 구간 [blobAtS(s_i), blobAtS(s_i + 칸)]으로 바꿔 잰다. 창 전체를 한 번에 재 S 에 고르게 펴면 회차 안 순단(PDT 7초 ·
 * Blob 4초)에서 봉우리가 1–3초 어긋난다. 죽은 자리(조각 사이 PDT 빈 곳)는 Blob 구간 길이가 0 이라 빈 칸이다
 */
export function windowLevels(
  tracks: readonly (readonly TrackPiece[])[],
  placement: Pick<WindowPlacement, 'blobAtS'>,
  range: { startSeconds: number; endSeconds: number },
): TrackLevels {
  const { startSeconds, endSeconds } = range;
  const count = Math.max(0, Math.ceil((endSeconds - startSeconds) / LEVEL_STEP_SECONDS - 1e-9));
  const edges = Array.from({ length: count + 1 }, (_, i) =>
    placement.blobAtS(Math.min(endSeconds, startSeconds + i * LEVEL_STEP_SECONDS)),
  );
  return { startSeconds, stepSeconds: LEVEL_STEP_SECONDS, tracks: measureCells(tracks, edges) };
}

export function useTrackPreview(options: {
  /** clip 방송 번호(편집본과 같은 번호) */
  streamId: string;
  /** 영상이 받으려는 창. 영상 받기와 함께 조각을 받기 시작한다. 받을 창이 없으면(녹화 없음 · 아직 못 정함) null */
  window: TrackWindow | null;
  /** 영상이 받은 창. 받은 조각은 열쇠가 같은 이 창의 닻으로 놓는다. 아직 못 받았으면 null */
  loaded: LoadedTrackWindow | null;
  /** 영상이 받다 실패한 창의 열쇠(③ 받기 · 다시 싸기). 그 창의 닻은 오지 않으므로 소리도 「못 부름」으로 끝낸다 */
  failedKey?: string | null;
  video: HTMLVideoElement | null;
  createContext?: () => DecodingContext;
}): TrackPreview {
  const { streamId, loaded, video, createContext = defaultContext } = options;
  const failedKey = options.failedKey ?? null;
  const windowKey = options.window?.key ?? null;
  const [status, setStatus] = useState<TrackPreviewStatus>('idle');
  const [player, setPlayer] = useState<TrackAudioPlayer | null>(null);
  /** 놓아 둔 소리와 그 창(열쇠 · S 범위) — 파형을 그 창 배치로 잰다 */
  const [placed, setPlaced] = useState<{
    key: string;
    range: { startSeconds: number; endSeconds: number };
    tracks: TrackPiece[][];
  } | null>(null);
  // 트랙 번호 순 음량은 ref로 든다: 음량 숫자를 끄는 동안 값이 초당 수십 번 오는데, 상태로 들면 그때마다 이 훅을 쥔 화면
  // 전체가 한 번 더 그려졌다(PR #341 3차). 화면이 알아야 하는 「영상 소리와 같은가」만 상태다(값이 같으면 다시 안 그린다)
  const gainsRef = useRef<readonly number[]>([]);
  const [videoMixMatches, setVideoMixMatches] = useState(true);
  const [videoRate, setVideoRate] = useState(1);
  const videoRateRef = useRef(videoRate);
  videoRateRef.current = videoRate;
  const contextRef = useRef<DecodingContext | null>(null);
  const createRef = useRef(createContext);
  const playerRef = useRef(player);
  playerRef.current = player;
  const windowRef = useRef(options.window);
  windowRef.current = options.window;
  /** 받는 중인 창의 닻 기다림 — 영상이 같은 열쇠의 창을 받으면 풀고, 그 창을 받다 실패하면 실패로 끝낸다 */
  const anchorsRef = useRef<{
    key: string;
    resolve: (anchors: SegmentAnchors) => void;
    reject: (error: Error) => void;
  } | null>(null);

  useEffect(() => {
    const win = windowRef.current;
    // 창이 바뀌었다: 앞 창 재생기는 그 창 Blob 시각에 묶여 있어 바로 버린다(새 창 소리가 오기까지는 불러오는 동안과 같다)
    setPlayer(null);
    anchorsRef.current = null;
    // 녹화가 없는 구간(받을 창 없음)이다: 실패가 아니라 들을 것이 없다(PR #341 claude)
    if (win === null || win.key !== windowKey) {
      setStatus('idle');
      setPlaced(null);
      return undefined;
    }
    let ctx = contextRef.current;
    if (ctx === null) {
      try {
        ctx = contextRef.current = createRef.current();
      } catch {
        setStatus('unavailable');
        return undefined;
      }
    }
    const decoder = ctx;
    let cancelled = false;
    // 창이 바뀌거나 화면이 떠나면 앞 창의 받기를 멈춘다: 안 멈추면 버릴 조각 수십 개를 끝까지 받는다(로컬 리뷰 2라운드)
    const abort = new AbortController();
    const anchors = new Promise<SegmentAnchors>((resolve, reject) => {
      anchorsRef.current = { key: win.key, resolve, reject };
    });
    // 받기가 닻을 묻기 전에 실패로 끝나도(주소 · 받기 실패) 영상 실패가 처리되지 않은 거부로 남지 않게 한다
    anchors.catch(() => undefined);
    setStatus('loading');
    const first = win.segs[0];
    const last = win.segs.at(-1);
    // 카드가 미리 받아 풀어 둔 창이면 다시 받지 않는다(POK-499). 그래도 그 창을 받은 영상의 닻이 올 때까지 놓지 않는다:
    // 영상 · 소리 캐시는 따로 버려져 영상이 아직 앞 창을 틀 수 있다(받는 갈래와 같은 묶음 — 닻이 와야 재생기를 만든다)
    const prefetched =
      first === undefined || last === undefined
        ? undefined
        : segmentTracks.get(segmentTrackKey(streamId, first.seq, last.seq));
    (prefetched !== undefined
      ? anchors.then(() => prefetched)
      : loadSegmentTracks({
          streamId,
          segs: win.segs,
          anchors: () => anchors,
          decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
          signal: abort.signal,
        })
    )
      .then((tracks) => {
        if (cancelled) return;
        // 파형은 트랙이 하나뿐이어도 그린다(소리 크기는 보여 줄 수 있다)
        setPlaced({
          key: win.key,
          range: { startSeconds: win.startSeconds, endSeconds: win.endSeconds },
          tracks,
        });
        // 소리 트랙을 하나도 못 꺼냈으면 못 부름이다
        if (tracks.length === 0) {
          setStatus('unavailable');
          return;
        }
        // 트랙이 하나뿐이어도(최종 믹스만) 재생기로 튼다: 영상 소리로 두면 그 트랙의 음량·넣기가 미리보기에 안 들린다(PR #341 2차)
        const made = new TrackAudioPlayer(decoder, tracks, () => {
          // 소리 문맥을 못 깨웠다: 이 재생기가 아직 쓰이고 있으면 접고 영상 소리로 물러난다
          if (playerRef.current !== made) return;
          setPlayer(null);
          setStatus('unavailable');
        });
        // 음량은 아래 이펙트가 건다(만들자마자는 모두 0이다)
        setPlayer(made);
        setStatus('ready');
      })
      .catch(() => {
        if (cancelled) return;
        setPlaced(null);
        setStatus('unavailable');
      });
    return () => {
      cancelled = true;
      abort.abort();
    };
  }, [streamId, windowKey]);

  // 영상이 받는 창의 닻을 푼다: 받는 중인 창과 열쇠가 같을 때만(앞 창을 받은 영상의 닻으로 놓지 않는다). 영상이 그 창을 받다
  // 실패하면 닻은 오지 않는다 — 실패로 끝내 「못 부름」으로 영상 소리를 되살리고 받아 둔 판독 결과를 놓는다. 닻을 기다리는
  // 동안은 받기 멈춤 시한을 세지 않으므로(loadSegmentTracks) 이 길이 없으면 loading 에 머문다
  useEffect(() => {
    const pending = anchorsRef.current;
    if (pending === null) return;
    if (failedKey === pending.key) {
      pending.reject(new Error('video window failed'));
      return;
    }
    if (loaded === null || loaded.key !== pending.key) return;
    pending.resolve(windowAnchors(loaded.remuxed));
  }, [loaded, failedKey, windowKey]);

  // 파형 — 놓아 둔 소리를 같은 창의 배치로 잰다(판 NULL 머리가 늦게 와 배치가 바뀌면 다시 잰다). 새 창을 받는 동안은 앞 창
  // 소리 · 배치가 그대로라 앞 파형이 남는다(편집기 초로 그려 자리가 맞다)
  const placement = loaded !== null && loaded.key === placed?.key ? loaded.placement : null;
  const levels = useMemo(
    () =>
      placed === null || placement === null
        ? null
        : windowLevels(placed.tracks, placement, placed.range),
    [placed, placement],
  );

  // 재생기는 바뀔 때(새 창 · 못 부름) 앞 것을, 화면을 떠날 때 마지막 것을 버린다. 소리 문맥을 닫기 전에 버려야 해서 그 이펙트보다 앞에 둔다
  useEffect(() => {
    if (player === null) return undefined;
    return () => player.dispose();
  }, [player]);

  useEffect(
    () => () => {
      // 닫은 문맥은 다시 못 쓴다(시계가 0에 멈춰 소리가 안 난다). 비워 둬야 다시 붙을 때 새로 만든다:
      // 개발 모드(StrictMode)는 붙였다 떼었다 다시 붙인다
      void contextRef.current?.close();
      contextRef.current = null;
    },
    [],
  );

  // 배속: 소리 노드의 배속은 음높이까지 바꾼다(영상은 음높이를 지킨다). 배속 중이고 음량이 영상 소리와 같으면 영상 소리가
  // 같은 소리를 제 음높이로 내므로 재생기를 쉬게 한다(PR #341 2차). 음량을 바꿔 두었으면 그 음량이 맞게 들리는 쪽(재생기)을
  // 둔다 — 그때는 음높이가 배속만큼 바뀐다(소리 노드에는 음높이를 지키는 배속이 없다)
  useEffect(() => {
    if (video === null) return undefined;
    const onRate = () => setVideoRate(video.playbackRate);
    onRate();
    video.addEventListener('ratechange', onRate);
    return () => video.removeEventListener('ratechange', onRate);
  }, [video]);
  const videoCarries = videoRate !== 1 && videoMixMatches;

  /** 재생기에 음량을 건다. 영상 소리가 대신 내는 동안(배속 · 같은 음량)은 재생기를 0으로 둔다 */
  const applyGains = useCallback((target: TrackAudioPlayer, carries: boolean) => {
    const gains = gainsRef.current;
    target.setGains(carries ? gains.map(() => 0) : gains);
  }, []);

  // 재생기가 바뀌거나(만들자마자는 모두 0) 판단 재료(배속 · 같은 음량)가 바뀌면 다시 건다. 음량만 바뀌면 setGains가 바로
  // 건다. 재료를 다 걸어 두는 까닭: 같은 렌더 묶음에서 음량과 배속이 함께 바뀌면 setGains는 옛 배속으로 걸고, 「맡는가」
  // 결과는 그대로라 그것만 보면 다시 안 건다
  useEffect(() => {
    if (player !== null) applyGains(player, videoCarries);
  }, [player, videoCarries, videoRate, videoMixMatches, applyGains]);

  // 영상 소리를 끄는 때: 재생기가 소리를 내면 끈다. 재생기 없이 불러오는 중이면(새 창을 받는 동안도 — 앞 창 재생기는 이미
  // 버렸다) 음량이 영상 소리와 다를 때만 끈다: 다르면 뺀 트랙까지 섞인 최종 믹스가 들려서다(로컬 리뷰 1라운드). 그래서 음량을
  // 바꿔 둔 편집본은 새 창 소리가 올 때까지 무음이다. 같으면(새로 연 편집본) 영상 소리가 곧 미리 듣기 소리라 조각을 다 받을
  // 때까지 조용히 기다리지 않는다(PR #341 claude)
  const muteVideo = player !== null ? !videoCarries : status === 'loading' && !videoMixMatches;
  useEffect(() => {
    if (video === null) return undefined;
    const wasMuted = video.muted;
    video.muted = muteVideo;
    return () => {
      video.muted = wasMuted;
    };
  }, [video, muteVideo]);

  // 소리는 창 Blob 시각에 놓였다 — 영상 노드의 시각을 그대로 따른다. 재생기는 그 창을 받은 영상에서만 산다(창이 바뀌면 버림)
  useEffect(() => {
    if (player === null || video === null) return undefined;
    return followVideo(player, video, () => video.currentTime);
  }, [player, video]);

  const setGains = useCallback(
    (next: readonly number[]) => {
      gainsRef.current = next;
      const matches = sameAsVideo(next);
      setVideoMixMatches(matches);
      // 새 음량으로 바로 건다(영상 소리가 맡을지는 새 음량으로 판단한다). 맡는 쪽이 바뀌면 다음 렌더의 이펙트가 한 번 더 건다
      const player = playerRef.current;
      if (player !== null) applyGains(player, videoRateRef.current !== 1 && matches);
    },
    [applyGains],
  );

  return useMemo(() => ({ status, levels, setGains }), [status, levels, setGains]);
}
