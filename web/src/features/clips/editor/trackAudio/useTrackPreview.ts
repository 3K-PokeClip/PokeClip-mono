'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio } from './loadTrackAudio';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상이 받은 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
//
// 못 부르면(소리 문맥 없음·주소 실패·소리 트랙이 하나뿐) 「unavailable」이고 영상 소리가 그대로 난다: 화면이 그 사실을 알린다.

export type TrackPreviewStatus = 'loading' | 'ready' | 'unavailable';

export interface TrackPreview {
  status: TrackPreviewStatus;
  /** 트랙 번호 순 음량(1 = 100%, 끈 트랙 0) */
  setGains(gains: readonly number[]): void;
}

type DecodingContext = AudioContextLike & Pick<AudioContext, 'decodeAudioData' | 'close'>;

/**
 * 풀어 둔 소리를 24kHz로 둔다: 구간 3분 20초 × 트랙 여섯이면 48kHz는 수백 MB다. 미리 듣기에는 24kHz(12kHz까지의 소리)로 충분하다.
 * 브라우저에 소리 문맥이 없으면(시험 환경 등) 던지고, 훅은 그것을 「못 부름」으로 받는다.
 */
const defaultContext = (): DecodingContext => new AudioContext({ sampleRate: 24_000 });

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
    setStatus('loading');
    setPlayer(null);
    const fromMs = baseMs + Math.round(startSeconds * 1000);
    const toMs = baseMs + Math.round(endSeconds * 1000);
    loadTrackAudio(
      {
        fetchAccess: () => requestSegmentAudioAccess(streamId, fromMs, toMs),
        fetchBytes: async (url) => {
          const res = await fetch(url);
          if (!res.ok) throw new Error(`segment ${res.status}`);
          return res.arrayBuffer();
        },
        decode: (adts) => decoder.decodeAudioData(adts.buffer as ArrayBuffer),
      },
      baseMs,
    )
      .then((tracks) => {
        if (cancelled) return;
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
      });
    return () => {
      cancelled = true;
      made?.dispose();
    };
  }, [streamId, baseMs, startSeconds, endSeconds]);

  useEffect(
    () => () => {
      void contextRef.current?.close();
    },
    [],
  );

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

  return useMemo(() => ({ status, setGains }), [status, setGains]);
}
