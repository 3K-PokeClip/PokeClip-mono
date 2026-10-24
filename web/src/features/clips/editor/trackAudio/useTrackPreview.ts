'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { loadTrackAudio, SegmentUrlExpiredError } from './loadTrackAudio';
import { measureLevels, type TrackLevels } from './trackLevels';
import { followVideo, TrackAudioPlayer, type AudioContextLike } from './trackAudioPlayer';

// 트랙별 미리 듣기(POK-389): 영상이 받은 창(구간 ± 여유)의 녹화 조각을 받아 트랙마다 풀고, 영상 노드를 따라 튼다.
// 트랙을 끄거나 음량을 바꾸면 미리보기 소리에도 바로 들린다(저장·렌더와 같은 트랙 번호).
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
const defaultContext = (): DecodingContext => new AudioContext({ sampleRate: 24_000 });

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

export function useTrackPreview(options: {
  /** clip 방송 번호(편집본과 같은 번호) */
  streamId: string;
  /** 편집기 시간축 0초의 절대 ms */
  baseMs: number;
  /** 조각을 받을 창(편집기 초): 영상이 받으려는 창이라 영상 받기와 함께 시작한다. 녹화가 없으면 null */
  window: { startSeconds: number; endSeconds: number } | null;
  video: HTMLVideoElement | null;
  /**
   * 영상 노드의 0초가 편집기 몇 초인가(영상이 실제로 받아 튼 창의 시작). 영상이 아직 없으면 null이고 따라 틀기를 기다린다.
   * 생략하면 받을 창의 시작이다(영상과 소리가 늘 같은 창일 때)
   */
  playingFromSeconds?: number | null;
  createContext?: () => DecodingContext;
}): TrackPreview {
  const { streamId, baseMs, video, createContext = defaultContext } = options;
  const startSeconds = options.window?.startSeconds ?? null;
  const endSeconds = options.window?.endSeconds ?? null;
  const playingFrom =
    options.playingFromSeconds === undefined ? startSeconds : options.playingFromSeconds;
  const [status, setStatus] = useState<TrackPreviewStatus>('idle');
  const [player, setPlayer] = useState<TrackAudioPlayer | null>(null);
  const [levels, setLevels] = useState<TrackLevels | null>(null);
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

  useEffect(() => {
    // 녹화가 없는 구간(받을 창 없음)이다: 실패가 아니라 들을 것이 없다(PR #341 claude)
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
    // 앞 창의 재생기·파형은 새 것이 준비될 때까지 둔다. 조각 받기는 영상까지 든 파일이라 수십 초가 걸리는데(3분 구간 ≈ 150MB),
    // 그동안 앞 창과 겹치는 자리는 그대로 들린다. 재생기는 편집기 초로 소리를 들고 있어 새 창 영상을 따라가도 제자리다(PR #341 claude)
    setStatus('loading');
    const fromMs = baseMs + Math.round(startSeconds * 1000);
    const toMs = baseMs + Math.round(endSeconds * 1000);
    Promise.race([
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
        // 받는 중인 다른 조각도 멈춘다: 하나가 실패하면 결과를 버린다(PR #341 claude)
        abort.abort();
        if (cancelled) return;
        setPlayer(null);
        setLevels(null);
        setStatus('unavailable');
      })
      .finally(() => {
        settled = true;
        clearTimeout(timer);
      });
    return () => {
      cancelled = true;
      settled = true;
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

  // 영상 소리를 끄는 때: 재생기가 소리를 내면(트는 중 · 새 창을 받는 동안 앞 창 재생기가 이어 튼다) 끈다. 재생기 없이 불러오는
  // 중이면 음량이 영상 소리와 다를 때만 끈다: 다르면 뺀 트랙까지 섞인 최종 믹스가 들려서다(로컬 리뷰 1라운드). 같으면(새로 연
  // 편집본) 영상 소리가 곧 미리 듣기 소리라 조각을 다 받을 때까지 조용히 기다리지 않는다(PR #341 claude)
  const muteVideo = player !== null ? !videoCarries : status === 'loading' && !videoMixMatches;
  useEffect(() => {
    if (video === null) return undefined;
    const wasMuted = video.muted;
    video.muted = muteVideo;
    return () => {
      video.muted = wasMuted;
    };
  }, [video, muteVideo]);

  // 소리는 편집기 초로 들고 있어, 받을 창보다 영상이 늦게(또는 앞 창 그대로) 돌아도 영상의 창으로 옮기면 제자리다
  useEffect(() => {
    if (player === null || video === null || playingFrom === null) return undefined;
    return followVideo(player, video, () => playingFrom + video.currentTime);
  }, [player, video, playingFrom]);

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
