'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio } from './loadTrackAudio';
import { measureLevels, type TrackLevels } from './trackLevels';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상이 받은 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
//
// 못 부르면(소리 문맥 없음·주소 실패·소리 트랙이 하나뿐) 「unavailable」이고 영상 소리가 그대로 난다: 화면이 그 사실을 알린다.

export type TrackPreviewStatus = 'loading' | 'ready' | 'unavailable';

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
 * 조각 받기·풀기가 이만큼 안에 안 끝나면 「못 부름」이다. 불러오는 동안 영상 소리를 꺼 두므로, 받기가 멈춘 채면 화면이 아무
 * 소리도 안 낸다(로컬 리뷰 2라운드). 3분 20초 창(조각 약 50개)을 보통 망에서 받는 데 넉넉하다
 */
export const TRACK_LOAD_TIMEOUT_MS = 60_000;

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
  const [status, setStatus] = useState<TrackPreviewStatus>('loading');
  const [player, setPlayer] = useState<TrackAudioPlayer | null>(null);
  const [levels, setLevels] = useState<TrackLevels | null>(null);
  const contextRef = useRef<DecodingContext | null>(null);
  const gainsRef = useRef<readonly number[]>([]);
  const createRef = useRef(createContext);

  useEffect(() => {
    if (startSeconds === null || endSeconds === null) {
      setStatus('unavailable');
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
    let made: TrackAudioPlayer | null = null;
    // 창이 바뀌거나 화면이 떠나면 앞 창의 받기를 멈춘다: 안 멈추면 버릴 조각 수십 개를 끝까지 받는다(로컬 리뷰 2라운드)
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), TRACK_LOAD_TIMEOUT_MS);
    const stopped = new Promise<never>((_, reject) => {
      abort.signal.addEventListener('abort', () => reject(new Error('track audio load stopped')));
    });
    setStatus('loading');
    setPlayer(null);
    setLevels(null);
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
        // 트랙이 하나뿐이면(최종 믹스만) 영상 소리와 같다: 트랙을 꺼도 고를 소리가 없다
        if (tracks.length < 2) {
          setStatus('unavailable');
          return;
        }
        made = new TrackAudioPlayer(decoder, tracks);
        made.setGains(gainsRef.current);
        setPlayer(made);
        setStatus('ready');
      })
      .catch(() => {
        if (!cancelled) setStatus('unavailable');
      })
      .finally(() => clearTimeout(timer));
    return () => {
      cancelled = true;
      abort.abort();
      clearTimeout(timer);
      made?.dispose();
    };
  }, [streamId, baseMs, startSeconds, endSeconds]);

  useEffect(
    () => () => {
      // 닫은 문맥은 다시 못 쓴다(시계가 0에 멈춰 소리가 안 난다). 비워 둬야 다시 붙을 때 새로 만든다:
      // 개발 모드(StrictMode)는 붙였다 떼었다 다시 붙인다
      void contextRef.current?.close();
      contextRef.current = null;
    },
    [],
  );

  // 영상 소리는 「못 부름」일 때만 켠다. 불러오는 동안에도 끈다: 창을 다시 받는 사이 영상 소리(최종 믹스)를 켜면 뺀 트랙까지
  // 섞여 들렸다(로컬 리뷰 1라운드). 소리 문맥이 늦게 뜨는 1~2초는 조용하다
  useEffect(() => {
    if (video === null) return undefined;
    const wasMuted = video.muted;
    video.muted = status !== 'unavailable';
    return () => {
      video.muted = wasMuted;
    };
  }, [video, status]);

  useEffect(() => {
    if (player === null || video === null || startSeconds === null) return undefined;
    return followVideo(player, video, () => startSeconds + video.currentTime);
  }, [player, video, startSeconds]);

  const setGains = useCallback(
    (gains: readonly number[]) => {
      gainsRef.current = gains;
      player?.setGains(gains);
    },
    [player],
  );

  return useMemo(() => ({ status, levels, setGains }), [status, levels, setGains]);
}
