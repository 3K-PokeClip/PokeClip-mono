'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { SegmentAudioAccess } from '@/api/clipEditor';
import { holdEditorWindowFetch, segmentTrackKey, segmentTracks } from '../editorMedia';
import type { EditorPlaylist, PlaylistSegment } from '../source/editorPlaylist';
import { ensureWindowHeads } from '../source/editorWindow';
import {
  failMissingHeads,
  headCacheFor,
  readFragmentVideoDts,
  type HeadCache,
} from '../source/fileHeadProbe';
import type { RemuxedWindow } from '../source/remuxWindow';
import type { WindowPlacement } from '../source/windowPlacement';
import { windowAudioAccess } from '../source/windowAudioAccess';
import {
  captureAnchorOf,
  captureRowOf,
  loadTrackAudio,
  SegmentUrlExpiredError,
  windowAnchors,
  type SegmentAnchors,
  type TrackAudioLoad,
  type TrackPiece,
} from './loadTrackAudio';
import { LEVEL_STEP_SECONDS, measureCells, type TrackLevels } from './trackLevels';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
//
// 소리는 창 Blob 시각(영상 노드의 currentTime)에 놓는다(설계 prw2 3.3절 — loadTrackAudio). 그래서 재생기는 창 하나에 묶이고,
// 창이 바뀌면 바로 버린다: 앞 창 재생기가 새 창 Blob 시각을 따라가면 다른 자리 소리를 낸다. 앞 창 풀린 소리(PCM)도 새 창
// 소리를 기다리지 않고 놓는다 — 새 창 소리가 풀릴 때까지 쥐면 두 창 소리가 함께 산다(M3 ④). 놓는다는 것은 이 훅이 쥔 참조를
// 끊는 것이다: 상태에서 빼고, 오래 사는 함수(받기 · 고리 · 효과 정리 · 영상 노드에 붙은 듣기)는 훅 바깥에서 지어 렌더를 붙들지
// 않는다. 실제로 거두는 것은 GC 이고, React 는 앞 렌더의 상태를 다음 렌더까지 한 벌 더 쥐므로 창을 바꾼 뒤 한 번 더 그려져야
// 다 풀린다(창 바꿈은 재생기 · 상태를 바꿔 늘 다시 그린다). 새 창 소리가 오기까지는 음량을 안 바꾼 편집본이면
// 영상 소리, 바꿔 둔 편집본이면 무음이다(뺀 트랙이 섞인 최종 믹스를 내지 않는다 — 아래 영상 소리를 끄는 때).
//
// 못 부르면(소리 문맥 없음·주소·받기·풀기 실패) 「unavailable」이고 영상 소리가 그대로 난다: 화면이 그 사실을 알린다.
//
// ② 기다림 고리(설계 prw2 7절 c10b′): ② 가 아직 없어서 생기는 기다림 셋 — 창 seq 의 ② 행이 응답에 없음 · 판 NULL 창 seq 의
// 머리가 missing · 끝 소리 확보가 기다림(not_ready · 확보 seq 의 ③ 이 목록에 아직 없음) — 을 고리 하나로 돈다. AUDIO_WAIT_MS
// 마다 소리 주소를 다시 묻고(② 머리 판독과 응답 하나를 나눠 쓴다 — windowAudioAccess) 빠진 머리를 다시 읽는다. 행이 같으면
// 내려받기 0 · 바뀐 덩어리만 다시 푼다(loadTrackAudio previous). 창 바뀜 · 화면 떠남이면 멈추고, AUDIO_WAIT_LIMIT 에 닿으면
// 경고 한 번을 남기고 멈춘다 — 행이 끝내 빠지면 영상 소리로 물러나고, 머리가 끝내 없으면 failed(그 조각 고름은 저장 막음),
// 확보는 포기한다. skipped · 칸 없음은 확보 없이 진행이라 다시 묻지 않는다(kty R28 — clip 이 막음). 창 조각 행이 하나도 없는
// 동안은 못 부름이 아니라 waiting 이다(불러오는 동안과 같은 소리 규칙 — kty 결정 (나) c10b′ r1 5번). 머리는 missing 만 다시
// 읽는다: failed 를 차례마다 다시 읽으면 판독 상한만큼 되풀이된다(보안 검수 c10b′ M-1).

/**
 * - idle: 받을 창이 없다(녹화가 없는 구간 · 녹화 없음). 알릴 것이 없다
 * - waiting: 창 조각의 ② 가 아직 하나도 없어 기다림 고리가 다시 묻는 중이다. 불러오는 중과 같은 소리 규칙(음량을 바꿔 둔
 *   편집본은 무음)이고 화면은 기다린다고 알린다 — 상한에 닿으면 unavailable(kty 결정 (나) c10b′ r1 5번)
 * - unavailable: 못 불렀다. 영상 소리로 들린다고 화면이 알린다
 */
export type TrackPreviewStatus = 'idle' | 'loading' | 'waiting' | 'ready' | 'unavailable';

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

/** ② 기다림 고리가 다시 묻는 간격(설계 prw2 7절 c10b′) */
export const AUDIO_WAIT_MS = 10_000;
/** ② 기다림 고리 상한(약 5분). 닿으면 멈추고 창마다 경고 한 번을 남긴다 */
export const AUDIO_WAIT_LIMIT = 30;

/** 다시 물을 사유 — 빠진 ② 행 · 판 NULL 머리 missing · 확보 기다림 */
type WaitReason = 'rows' | 'heads' | 'capture';
const REASON_LABEL: Record<WaitReason, string> = {
  rows: '② 행',
  heads: '② 머리',
  capture: '끝 소리 확보',
};

/** 이 창을 다시 물어야 하는 사유. 머리는 판 NULL 조각만 본다(판 2 는 읽지 않는다) */
function waitReasons(
  load: TrackAudioLoad,
  segs: readonly Pick<PlaylistSegment, 'seq' | 'timing'>[],
  heads: HeadCache | null,
): WaitReason[] {
  const reasons: WaitReason[] = [];
  if (load.missingSeqs.length > 0) reasons.push('rows');
  const headMissing = (seg: Pick<PlaylistSegment, 'seq' | 'timing'>) =>
    seg.timing === null && heads?.get(seg.seq)?.state === 'missing';
  if (segs.some(headMissing)) reasons.push('heads');
  if (load.capture === 'waiting') reasons.push('capture');
  return reasons;
}

/**
 * ② 기다림 고리 하나. 처음 받은 소리를 놓고, 사유가 남으면 AUDIO_WAIT_MS 마다 다시 받아 트랙이 바뀌면 다시 놓는다. 다시 받기가
 * 실패하면 들고 있는 소리로 다음 차례를 기다린다(차례는 센다). AUDIO_WAIT_LIMIT 번 다시 받고도 사유가 남으면 giveUp 이다.
 * 돌려준 함수가 멈춘다
 */
function runAudioWait(steps: {
  first: Promise<TrackAudioLoad>;
  refresh: (previous: TrackAudioLoad) => Promise<TrackAudioLoad>;
  reasons: (load: TrackAudioLoad) => WaitReason[];
  show: (load: TrackAudioLoad) => void;
  fail: () => void;
  giveUp: (reasons: readonly WaitReason[]) => void;
}): () => void {
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let rounds = 0;
  const wait = (load: TrackAudioLoad) => {
    const reasons = steps.reasons(load);
    if (stopped || reasons.length === 0) return;
    if (rounds >= AUDIO_WAIT_LIMIT) {
      steps.giveUp(reasons);
      return;
    }
    timer = setTimeout(() => {
      rounds += 1;
      steps.refresh(load).then(
        (next) => {
          if (stopped) return;
          if (next.tracks !== load.tracks) steps.show(next);
          wait(next);
        },
        () => {
          if (!stopped) wait(load);
        },
      );
    }, AUDIO_WAIT_MS);
  };
  steps.first.then(
    (load) => {
      if (stopped) return;
      steps.show(load);
      wait(load);
    },
    () => {
      if (!stopped) steps.fail();
    },
  );
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}

/** 트랙 소리를 받을 창 — 영상이 받으려는 창(useEditorVideoPlayback 의 target)의 열쇠 · 조각 · S 범위 */
export interface TrackWindow {
  /** 창 열쇠(editorWindowKey). 재생기는 이 창 하나에 묶인다 */
  key: string;
  segs: readonly PlaylistSegment[];
  startSeconds: number;
  endSeconds: number;
}

/** 놓아 둔 소리와 그 창(열쇠 · S 범위) — 파형을 그 창 배치로 잰다 */
interface PlacedSound {
  key: string;
  range: { startSeconds: number; endSeconds: number };
  tracks: TrackPiece[][];
}

/** 창이 바뀌어 소리(PCM)를 놓은 앞 창 — 잰 파형 값만 남는다(새 창을 받는 동안 그대로 그린다) */
interface ReleasedSound {
  key: string;
  levels: TrackLevels | null;
}

/** 영상이 받은 창 — 다시 싼 결과(소리를 놓을 Blob 닻)와 배치(파형을 잴 Blob 범위) */
export interface LoadedTrackWindow {
  key: string;
  remuxed: RemuxedWindow;
  placement: WindowPlacement | null;
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
  /** 앞서 받은 결과(② 기다림 고리) — 받은 조각 · 푼 덩어리를 다시 쓴다 */
  previous?: TrackAudioLoad;
  /** 확보 조각의 Blob 닻(captureAnchorOf). 없으면 끝 소리를 확보하지 않는다 */
  captureAnchor?: (seq: number) => Promise<number | 'wait' | null>;
}): Promise<TrackAudioLoad> {
  const { streamId, segs, decode, signal, precheck, previous, captureAnchor } = options;
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
  /** 바깥(닻 · 확보 닻) 기다림도 멈춤에 끊는다: 안쪽이 그 기다림에 묶여 끝나지 않으면 아래에서 안쪽 끝을 기다리다 멈춘다 */
  const untilStopped = <T>(waiting: Promise<T>) => Promise.race([waiting, stopped]);
  const work = loadTrackAudio(
    {
      // 창 조각이 덮는 [첫 PDT, 마지막 PDT + EXTINF) — 확보 후보만큼 넓히지 않는다. 판 NULL 창의 ② 머리 판독이 같은 범위를
      // 묻는 중이면 그 응답을 같이 쓴다(windowAudioAccess). 멈춤 시한 · 바깥 멈춤이면 그 요청을 더는 같이 쓰지 않는다
      fetchAccess: async () => {
        const access = await windowAudioAccess(streamId, segs, abort.signal);
        progressed();
        return access;
      },
      fetchBytes: async (url) => {
        // 멈추면 fetch 도 끊기지만, 붙잡힌 연결이 거부를 늦게 알려도 안쪽이 바로 끝나게 멈춤에 묶는다
        const res = await untilStopped(fetch(url, { signal: abort.signal }));
        // 서명 주소 만료(S3는 403): 주소를 새로 받아 다시 받는다(loadTrackAudio)
        if (res.status === 403) throw new SegmentUrlExpiredError();
        if (!res.ok) throw new Error(`segment ${res.status}`);
        const bytes = await untilStopped(res.arrayBuffer());
        progressed();
        return bytes;
      },
      anchors: async () => {
        // 영상 창(③ 받기 · 다시 싸기)을 기다리는 동안은 소리 받기가 멈춘 것이 아니다
        clearTimeout(timer);
        const anchors = await untilStopped(options.anchors());
        progressed();
        return anchors;
      },
      captureAnchor:
        captureAnchor === undefined
          ? undefined
          : async (seq) => {
              const anchor = await untilStopped(captureAnchor(seq));
              progressed();
              return anchor;
            },
      decode,
    },
    segs.map((seg) => seg.seq),
    { precheck, previous, signal: abort.signal },
  );
  return Promise.race([work, stopped])
    .catch(async (error: unknown) => {
      // 받는 중인 다른 조각도 멈춘다: 하나가 실패하면 결과를 버린다(PR #341 claude). 안쪽이 실제로 끝난 뒤에 던진다 — 먼저
      // 던지면 쥔 쪽(카드 미리 받기의 차례)이 자리를 넘긴 뒤에도 안에서 풀던 덩어리가 이어져 옛 · 새 작업이 겹친다
      abort.abort();
      await work.catch(() => undefined);
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

/** 확보 seq 의 ③ 을 찾을 목록 — 편집기 원천(useEditorSource)의 지금 목록과 다시 받기 */
interface TrackListSource {
  list?: EditorPlaylist | null;
  reloadList?: () => Promise<EditorPlaylist | null>;
}

/** 다시 싼 창 중 소리 닻에 쓰는 것 — 조각 표와 Δ */
type WindowTiming = Pick<RemuxedWindow, 'shiftSeconds' | 'segments'>;

/**
 * 창 확보 조각의 Blob 닻(설계 prw2 7절 c10b′ 확보 배치). 들고 있는 목록에 확보 seq 의 ③ 이 없으면 목록을 한 번 다시 받아
 * 찾는다(방송 중 창 끝 바로 뒤 조각은 목록 끝 너머일 수 있다). ③ 머리 tfdt 는 쿠키 자격으로 Range 판독한다
 */
async function windowCaptureAnchor(
  segs: readonly PlaylistSegment[],
  seq: number,
  remuxed: Promise<WindowTiming>,
  source: TrackListSource,
  signal: AbortSignal,
): Promise<number | 'wait' | null> {
  const { shiftSeconds } = await remuxed;
  let row = captureRowOf(source.list ?? null, segs, seq);
  if (row === 'wait' && source.reloadList !== undefined) {
    row = captureRowOf(await source.reloadList(), segs, seq);
  }
  return captureAnchorOf(row, shiftSeconds, (found) => readFragmentVideoDts(found, signal));
}

/** 카드가 미리 받아 맡긴 그 창의 소리(POK-499) */
function prefetchedLoad(
  streamId: string,
  segs: readonly PlaylistSegment[],
): TrackAudioLoad | undefined {
  const first = segs[0];
  const last = segs.at(-1);
  if (first === undefined || last === undefined) return undefined;
  return segmentTracks.get(segmentTrackKey(streamId, first.seq, last.seq));
}

/** 받는 중인 창의 닻 기다림 — 영상이 같은 열쇠의 창을 받으면 풀고, 그 창을 받다 실패하면 실패로 끝낸다 */
interface PendingAnchors {
  key: string;
  resolve: (remuxed: WindowTiming) => void;
  reject: (error: Error) => void;
}

/** 창 하나의 소리 받기에 쓰는 것(startWindowAudio) */
interface WindowAudioRun {
  streamId: string;
  win: TrackWindow;
  decoder: DecodingContext;
  /** 목록 · 다시 받기 · 영상 키 — 고리가 그때그때 지금 값을 읽는다 */
  source: { readonly current: TrackListSource & { mediaKey?: string } };
  anchors: { current: PendingAnchors | null };
  show: (win: TrackWindow, decoder: DecodingContext, load: TrackAudioLoad) => void;
  setStatus: (status: TrackPreviewStatus) => void;
  setPlaced: (placed: null) => void;
  setPlayer: (player: null) => void;
}

/**
 * 창 하나의 소리를 받아 놓고 ② 기다림 고리를 돈다. 돌려준 함수가 멈춘다. 훅 바깥(모듈)에서 짓는다: 훅 안에서 지은 함수는 그
 * 렌더의 지역 전부(앞 창 재생기와 그 소리 포함)를 함께 붙든다 — 새 창 받기가 도는 동안(멈춤 시한 타이머) 앞 창 소리가 살아
 * 있었다(M3 ④ · 2026-11-13 힙 스냅숏)
 */
function startWindowAudio(run: WindowAudioRun): () => void {
  const { streamId, win, decoder, source, anchors, show, setStatus, setPlaced, setPlayer } = run;
  // 창이 바뀌거나 화면이 떠나면 앞 창의 받기 · 고리를 멈춘다: 안 멈추면 버릴 조각 수십 개를 끝까지 받는다(로컬 리뷰 2라운드)
  const abort = new AbortController();
  const remuxed = new Promise<WindowTiming>((resolve, reject) => {
    anchors.current = { key: win.key, resolve, reject };
  });
  // 받기가 닻을 묻기 전에 실패로 끝나도(주소 · 받기 실패) 영상 실패가 처리되지 않은 거부로 남지 않게 한다
  remuxed.catch(() => undefined);
  setStatus('loading');
  const { mediaKey } = source.current;
  const load = (previous?: TrackAudioLoad) =>
    loadSegmentTracks({
      streamId,
      segs: win.segs,
      anchors: () => remuxed.then(windowAnchors),
      decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
      signal: abort.signal,
      previous,
      captureAnchor: (seq) =>
        windowCaptureAnchor(win.segs, seq, remuxed, source.current, abort.signal),
    });
  // 카드가 미리 받아 풀어 둔 창이면 다시 받지 않는다(POK-499). 그래도 그 창을 받은 영상의 닻이 올 때까지 놓지 않는다:
  // 영상 · 소리 캐시는 따로 버려져 영상이 아직 앞 창을 틀 수 있다(받는 갈래와 같은 묶음 — 닻이 와야 재생기를 만든다)
  const prefetched = prefetchedLoad(streamId, win.segs);
  // 처음 받는 동안(닻 기다림 · 풀기까지)은 카드 미리 받기를 쉬게 한다: 둘이 겹칠 때 메모리가 가장 높았다(M3). 고리의 다시
  // 받기는 잡지 않는다 — 빠진 조각만 받는데, 10초마다 잡으면 카드 미리 받기가 그때마다 끊긴다
  const release = prefetched !== undefined ? () => undefined : holdEditorWindowFetch();
  const first = prefetched !== undefined ? remuxed.then(() => prefetched) : load();
  first.then(release, release);
  const stop = runAudioWait({
    first,
    // 판 NULL 창의 빠진 머리도 같은 차례에 다시 읽는다 — 소리 주소 응답 하나를 나눠 쓴다(windowAudioAccess). 고리가
    // 기다리는 것은 missing 이라 failed 는 다시 읽지 않는다(보안 검수 c10b′ M-1 — 다시 시도는 저장 쪽 몫)
    refresh: (previous) => {
      if (mediaKey !== undefined)
        void ensureWindowHeads(streamId, mediaKey, win.segs, abort.signal, {
          retryFailed: false,
        });
      return load(previous);
    },
    reasons: (current) =>
      waitReasons(current, win.segs, mediaKey === undefined ? null : headCacheFor(mediaKey)),
    show: (current) => show(win, decoder, current),
    fail: () => {
      setPlaced(null);
      setStatus('unavailable');
    },
    giveUp: (reasons) => {
      const labels = reasons.map((reason) => REASON_LABEL[reason]).join(' · ');
      console.warn(`[editor] ② 를 기다리다 그만뒀어요(창 ${win.key} · ${labels})`);
      if (mediaKey !== undefined)
        failMissingHeads(
          mediaKey,
          win.segs.map((seg) => seg.seq),
        );
      // 창 seq 의 ② 행이 끝내 빠졌다: 그 자리가 무음으로 남지 않게 ③ 섞인 소리(영상 소리)로 물러난다
      if (reasons.includes('rows')) {
        setPlayer(null);
        setStatus('unavailable');
      }
    },
  });
  return () => {
    stop();
    abort.abort();
    release();
  };
}

/**
 * 영상 소리를 muted 로 두고, 돌려준 함수가 앞 값으로 되돌린다. 훅 바깥에서 짓는 까닭은 startWindowAudio 와 같다: 이 효과는
 * 창이 바뀌어도 muted 가 같으면 다시 돌지 않아, 훅 안에서 지은 정리 함수가 지은 렌더(앞 창 재생기와 그 소리)를 붙들었다
 */
function muteVideoWhile(video: HTMLVideoElement, muted: boolean): () => void {
  const wasMuted = video.muted;
  video.muted = muted;
  return () => {
    video.muted = wasMuted;
  };
}

/**
 * 영상 배속을 지금 한 번 · 바뀔 때마다 알린다. 돌려준 함수가 멈춘다. 듣는 함수는 영상 노드에 붙어 노드가 바뀔 때까지 살므로
 * 훅 바깥에서 짓는다(muteVideoWhile 과 같은 까닭)
 */
function watchRate(video: HTMLVideoElement, onRate: (rate: number) => void): () => void {
  const report = () => onRate(video.playbackRate);
  report();
  video.addEventListener('ratechange', report);
  return () => video.removeEventListener('ratechange', report);
}

export function useTrackPreview(options: {
  /** clip 방송 번호(편집본과 같은 번호) */
  streamId: string;
  /** 영상 키 — 판 NULL 창의 ② 머리 캐시(기다림 고리가 빠진 머리를 다시 읽는다). 없으면 머리는 보지 않는다 */
  mediaKey?: string;
  /** 영상이 받으려는 창. 영상 받기와 함께 조각을 받기 시작한다. 받을 창이 없으면(녹화 없음 · 아직 못 정함) null */
  window: TrackWindow | null;
  /** 영상이 받은 창. 받은 조각은 열쇠가 같은 이 창의 닻으로 놓는다. 아직 못 받았으면 null */
  loaded: LoadedTrackWindow | null;
  /** 영상이 받다 실패한 창의 열쇠(③ 받기 · 다시 싸기). 그 창의 닻은 오지 않으므로 소리도 「못 부름」으로 끝낸다 */
  failedKey?: string | null;
  /** 지금 ③ 목록 — 끝 소리 확보 seq 의 ③ 을 찾는다. 없으면 확보는 목록을 기다린다 */
  list?: EditorPlaylist | null;
  /** 목록을 한 번 다시 받는다(useEditorSource reloadList) — 확보 seq 의 ③ 이 들고 있는 목록에 없을 때 */
  reloadList?: () => Promise<EditorPlaylist | null>;
  video: HTMLVideoElement | null;
  createContext?: () => DecodingContext;
}): TrackPreview {
  const { streamId, loaded, video, createContext = defaultContext } = options;
  const failedKey = options.failedKey ?? null;
  const windowKey = options.window?.key ?? null;
  const [status, setStatus] = useState<TrackPreviewStatus>('idle');
  const [player, setPlayer] = useState<TrackAudioPlayer | null>(null);
  const [placed, setPlaced] = useState<PlacedSound | ReleasedSound | null>(null);
  /** 지금 그린 파형 — 창이 바뀌어 앞 창 소리를 놓을 때 잰 값만 남긴다 */
  const levelsRef = useRef<TrackLevels | null>(null);
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
  /** 목록 · 다시 받기 · 영상 키 — 고리가 그때그때 지금 값을 읽는다 */
  const sourceRef = useRef({
    list: options.list,
    reloadList: options.reloadList,
    mediaKey: options.mediaKey,
  });
  sourceRef.current = {
    list: options.list,
    reloadList: options.reloadList,
    mediaKey: options.mediaKey,
  };
  const anchorsRef = useRef<PendingAnchors | null>(null);

  /** 받은 소리를 놓는다 — 파형 자리 · 재생기 · 상태. 소리 트랙을 하나도 못 꺼냈으면 못 부름이다 */
  const showLoad = useCallback(
    (win: TrackWindow, decoder: DecodingContext, load: TrackAudioLoad) => {
      // 파형은 트랙이 하나뿐이어도 그린다(소리 크기는 보여 줄 수 있다)
      const range = { startSeconds: win.startSeconds, endSeconds: win.endSeconds };
      setPlaced({ key: win.key, range, tracks: load.tracks });
      if (load.tracks.length === 0) {
        setPlayer(null);
        // 창 조각의 ② 행이 아직 없어 놓을 소리가 없다: 고리가 기다리는 동안은 못 부름이 아니다(상한이면 giveUp 이 물러난다)
        setStatus(load.missingSeqs.length > 0 ? 'waiting' : 'unavailable');
        return;
      }
      // 트랙이 하나뿐이어도(최종 믹스만) 재생기로 튼다: 영상 소리로 두면 그 트랙의 음량·넣기가 미리보기에 안 들린다(PR #341 2차).
      // 기다림 고리가 새 소리를 놓으면 새 재생기다 — 앞 재생기는 아래 이펙트가 버린다
      const made = new TrackAudioPlayer(decoder, load.tracks, () => {
        // 소리 문맥을 못 깨웠다: 이 재생기가 아직 쓰이고 있으면 접고 영상 소리로 물러난다
        if (playerRef.current !== made) return;
        setPlayer(null);
        setStatus('unavailable');
      });
      // 음량은 아래 이펙트가 건다(만들자마자는 모두 0이다)
      setPlayer(made);
      setStatus('ready');
    },
    [],
  );

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
    // 앞 창 소리(PCM)는 새 창 소리를 기다리지 않고 상태에서 뺀다: 새 창 소리가 풀려 놓일 때까지 쥐면 창 둘의 소리가 함께
    // 산다(M3 ④ — 창 A → B 바꿈에 옛 A PCM 216 MiB 가 B PCM 233 MiB 와 겹쳤다). 파형은 잰 값만 남긴다(카드 캐시의 같은 소리는
    // 예산이 다룬다). 이것만으로 풀리지는 않는다 — 훅 안에서 지은 함수가 남아 있으면 그 렌더째 붙든다(머리 주석 · startWindowAudio)
    setPlaced((current) =>
      current === null || !('tracks' in current)
        ? current
        : { key: current.key, levels: levelsRef.current },
    );
    let ctx = contextRef.current;
    if (ctx === null) {
      try {
        ctx = contextRef.current = createRef.current();
      } catch {
        setStatus('unavailable');
        return undefined;
      }
    }
    return startWindowAudio({
      streamId,
      win,
      decoder: ctx,
      source: sourceRef,
      anchors: anchorsRef,
      show: showLoad,
      setStatus,
      setPlaced,
      setPlayer,
    });
  }, [streamId, windowKey, showLoad]);

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
    pending.resolve(loaded.remuxed);
  }, [loaded, failedKey, windowKey]);

  // 파형 — 놓아 둔 소리를 같은 창의 배치로 잰다(판 NULL 머리가 늦게 와 배치가 바뀌면 다시 잰다). 새 창을 받는 동안은 영상이
  // 앞 창을 트는 동안 앞 창에서 잰 값이 남는다(편집기 초로 그려 자리가 맞다 — 소리는 이미 놓았다)
  const placement = loaded !== null && loaded.key === placed?.key ? loaded.placement : null;
  const levels = useMemo(() => {
    if (placed === null || placement === null) return null;
    return 'tracks' in placed
      ? windowLevels(placed.tracks, placement, placed.range)
      : placed.levels;
  }, [placed, placement]);
  levelsRef.current = levels;

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
  useEffect(() => (video === null ? undefined : watchRate(video, setVideoRate)), [video]);
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
  // 때까지 조용히 기다리지 않는다(PR #341 claude). ② 행을 기다리는 동안(waiting)도 같은 규칙이다(kty 결정 (나) c10b′ r1 5번)
  const pending = status === 'loading' || status === 'waiting';
  const muteVideo = player !== null ? !videoCarries : pending && !videoMixMatches;
  useEffect(
    () => (video === null ? undefined : muteVideoWhile(video, muteVideo)),
    [video, muteVideo],
  );

  // 소리는 창 Blob 시각에 놓였다 — 영상 노드의 시각(그려진 프레임의 미디어 시각)을 그대로 따른다. 재생기는 그 창을 받은 영상에서만
  // 산다(창이 바뀌면 버림)
  useEffect(() => {
    if (player === null || video === null) return undefined;
    return followVideo(player, video);
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
