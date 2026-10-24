'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio } from './loadTrackAudio';
import { measureLevels, type TrackLevels } from './trackLevels';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상이 받은 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
//
// 못 부르면(소리 문맥 없음·주소·받기·풀기 실패) 「unavailable」이고 영상 소리가 그대로 난다: 화면이 그 사실을 알린다.

/**
 * - idle: 받을 창이 없다(첫 영상 창을 받는 중 · 녹화가 없는 구간). 알릴 것이 없다
 * - single: 소리 트랙이 하나뿐이다(최종 믹스만). 영상 소리와 같아 고를 소리가 없을 뿐 실패가 아니다
 * - unavailable: 못 불렀다. 영상 소리로 들린다고 화면이 알린다
 */
export type TrackPreviewStatus = 'idle' | 'loading' | 'ready' | 'single' | 'unavailable';

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
const defaultContext = (): DecodingContext => new AudioContext({ sampleRate: 24_000 });

/**
 * 조각 받기·풀기가 이만큼 안에 안 끝나면 「못 부름」이다. 불러오는 동안 영상 소리를 꺼 둘 수 있어(음량을 바꿔 둔 편집본),
 * 받기가 멈춘 채면 화면이 아무 소리도 안 낸다(로컬 리뷰 2라운드). 3분 20초 창(조각 약 50개)을 보통 망에서 받는 데 넉넉하다
 */
export const TRACK_LOAD_TIMEOUT_MS = 60_000;

/**
 * 이 음량이 영상 소리와 같은가: 재생 서버 영상은 첫 소리 트랙만 남기므로 첫 트랙만 100%이고 나머지를 끈 음량이다.
 * 새로 연 편집본이 이렇다(저장된 트랙 없음 = 첫 트랙만)
 */
export function sameAsVideo(gains: readonly number[]): boolean {
  return gains.every((gain, i) => gain === (i === 0 ? 1 : 0));
}

export function useTrackPreview(options: {
  /** clip 방송 번호(편집본과 같은 번호) */
  streamId: string;
  /** 편집기 시간축 0초의 절대 ms */
  baseMs: number;
  /** 영상이 받은 창(편집기 초). 녹화가 없으면 null */
  window: { startSeconds: number; endSeconds: number } | null;
  video: HTMLVideoElement | null;
  createContext?: () => DecodingContext;
}): TrackPreview {
  const { streamId, baseMs, video, createContext = defaultContext } = options;
  const startSeconds = options.window?.startSeconds ?? null;
  const endSeconds = options.window?.endSeconds ?? null;
  const [status, setStatus] = useState<TrackPreviewStatus>('idle');
  const [player, setPlayer] = useState<TrackAudioPlayer | null>(null);
  const [levels, setLevels] = useState<TrackLevels | null>(null);
  const [videoMixMatches, setVideoMixMatches] = useState(true);
  const contextRef = useRef<DecodingContext | null>(null);
  const gainsRef = useRef<readonly number[]>([]);
  const createRef = useRef(createContext);
  const playerRef = useRef(player);
  playerRef.current = player;

  useEffect(() => {
    // 영상이 아직 창을 못 받았거나(첫 창을 받는 중) 녹화가 없는 구간이다: 실패가 아니라 들을 것이 없다(PR #341 claude)
    if (startSeconds === null || endSeconds === null) {
      setStatus('idle');
      setPlayer(null);
      setLevels(null);
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
    const timer = setTimeout(() => abort.abort(), TRACK_LOAD_TIMEOUT_MS);
    const stopped = new Promise<never>((_, reject) => {
      abort.signal.addEventListener('abort', () => reject(new Error('track audio load stopped')));
    });
    // 앞 창의 재생기·파형은 새 것이 준비될 때까지 둔다. 조각 받기는 영상까지 든 파일이라 수십 초가 걸리는데(3분 구간 ≈ 150MB),
    // 그동안 앞 창과 겹치는 자리는 그대로 들린다. 재생기는 편집기 초로 소리를 들고 있어 새 창 영상을 따라가도 제자리다(PR #341 claude)
    setStatus('loading');
    const fromMs = baseMs + Math.round(startSeconds * 1000);
    const toMs = baseMs + Math.round(endSeconds * 1000);
    Promise.race([
      loadTrackAudio(
        {
          fetchAccess: () => requestSegmentAudioAccess(streamId, fromMs, toMs),
          fetchBytes: async (url) => {
            const res = await fetch(url, { signal: abort.signal });
            if (!res.ok) throw new Error(`segment ${res.status}`);
            return res.arrayBuffer();
          },
          decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
        },
        baseMs,
      ),
      stopped,
    ])
      .then((tracks) => {
        if (cancelled) return;
        // 파형은 트랙이 하나뿐이어도 그린다(소리 크기는 보여 줄 수 있다)
        setLevels(measureLevels(tracks, startSeconds, endSeconds));
        // 소리 트랙을 하나도 못 꺼냈으면 못 부름이다
        if (tracks.length === 0) {
          setPlayer(null);
          setStatus('unavailable');
          return;
        }
        // 트랙이 하나뿐이면(최종 믹스만) 영상 소리와 같다: 트랙을 꺼도 고를 소리가 없을 뿐 실패가 아니다(PR #341 claude)
        if (tracks.length === 1) {
          setPlayer(null);
          setStatus('single');
          return;
        }
        const made = new TrackAudioPlayer(decoder, tracks, () => {
          // 소리 문맥을 못 깨웠다: 이 재생기가 아직 쓰이고 있으면 접고 영상 소리로 물러난다
          if (playerRef.current !== made) return;
          setPlayer(null);
          setStatus('unavailable');
        });
        made.setGains(gainsRef.current);
        setPlayer(made);
        setStatus('ready');
      })
      .catch(() => {
        if (cancelled) return;
        setPlayer(null);
        setLevels(null);
        setStatus('unavailable');
      })
      .finally(() => clearTimeout(timer));
    return () => {
      cancelled = true;
      abort.abort();
      clearTimeout(timer);
    };
  }, [streamId, baseMs, startSeconds, endSeconds]);

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

  // 영상 소리를 끄는 때: 재생기가 있으면(트는 중 · 새 창을 받는 동안 앞 창 재생기가 이어 튼다) 늘 끈다. 재생기 없이 불러오는
  // 중이면 음량이 영상 소리와 다를 때만 끈다: 다르면 뺀 트랙까지 섞인 최종 믹스가 들려서다(로컬 리뷰 1라운드). 같으면(새로 연
  // 편집본) 영상 소리가 곧 미리 듣기 소리라 조각을 다 받을 때까지 조용히 기다리지 않는다(PR #341 claude)
  const muteVideo = player !== null || (status === 'loading' && !videoMixMatches);
  useEffect(() => {
    if (video === null) return undefined;
    const wasMuted = video.muted;
    video.muted = muteVideo;
    return () => {
      video.muted = wasMuted;
    };
  }, [video, muteVideo]);

  useEffect(() => {
    if (player === null || video === null || startSeconds === null) return undefined;
    return followVideo(player, video, () => startSeconds + video.currentTime);
  }, [player, video, startSeconds]);

  const setGains = useCallback(
    (gains: readonly number[]) => {
      gainsRef.current = gains;
      player?.setGains(gains);
      setVideoMixMatches(sameAsVideo(gains));
    },
    [player],
  );

  return useMemo(() => ({ status, levels, setGains }), [status, levels, setGains]);
}
