'use client';

// 편집기 미리보기의 실재생 — hls.js VOD 어댑터 (POK-238).
//
// useHlsPlayback(라이브)과 붙이는 방식·에러 복구·정리 순서는 같지만 별도 파일이다.
// 그쪽은 DVR 좌표(liveSyncPosition·되감기 창)에 얽혀 여러 티켓에 걸쳐 다듬어졌고, VOD 에는
// 그 좌표가 아예 없다. 억지로 하나로 묶으면 양쪽 모두에 안 맞는 분기가 생긴다 — 의도된 중복이다.
//
// jsdom 에는 미디어 구현이 없어 이 훅은 테스트하지 않는다(useHlsPlayback 과 같은 이유).
// 검증은 순수 함수(boundaryAction)와 주입 경로가 맡는다.

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type Hls from 'hls.js';
import { EDITOR_HLS_VOD_CONFIG } from './editorHlsConfig';
import {
  boundaryAction,
  type EditorPlayback,
  type EditorPlaybackOptions,
  type PlaybackBounds,
  type PlaybackError,
} from './editorPlayback';

/** 화면에 위치를 알리는 주기. 더 잦게 알려도 사람 눈에 안 보이고 리렌더만 늘어난다 */
const PUBLISH_INTERVAL_MS = 100;
/** 위치를 이만큼으로 반올림해 같은 값이면 리렌더를 건너뛴다 */
const PUBLISH_PRECISION = 100;

function roundPosition(seconds: number): number {
  return Math.round(seconds * PUBLISH_PRECISION) / PUBLISH_PRECISION;
}

export function useEditorHlsPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  playlistUrl: string,
  options: EditorPlaybackOptions,
): EditorPlayback {
  const { durationSeconds: fallbackDuration, initialSeconds } = options;

  // playing 은 video 이벤트로만 바꾼다 — 재생이 거부되면 false 로 남아 재생 버튼이 노출된다
  const [playing, setPlaying] = useState(false);
  // 매니페스트가 오기 전에도 트랜스포트가 구간 시작을 보여야 한다 — 0에서 시작하면 한 번 튄다
  const [currentSeconds, setCurrentSeconds] = useState(initialSeconds);
  const [durationSeconds, setDurationSeconds] = useState(fallbackDuration);
  const [error, setError] = useState<PlaybackError | null>(null);

  const hlsRef = useRef<Hls | null>(null);
  const boundsRef = useRef<PlaybackBounds | null>(null);
  const rateRef = useRef(1);
  const publishedAtRef = useRef(0);
  const initialRef = useRef(initialSeconds);

  const publish = useCallback((seconds: number, force = false) => {
    const now = Date.now();
    if (!force && now - publishedAtRef.current < PUBLISH_INTERVAL_MS) return;
    publishedAtRef.current = now;
    setCurrentSeconds(roundPosition(seconds));
  }, []);

  /**
   * 구간 밖으로 나갔으면 되돌린다. 재생 중에만 부른다 —
   * 정지 중에도 돌면 구간 핸들을 끄는 것만으로 재생 위치가 끌려간다.
   */
  const enforceBounds = useCallback(
    (video: HTMLVideoElement) => {
      const bounds = boundsRef.current;
      if (bounds === null) return false;
      const action = boundaryAction(video.currentTime, bounds);
      if (action.kind === 'seek') {
        video.currentTime = action.toSeconds;
        publish(action.toSeconds, true);
        return true;
      }
      if (action.kind === 'stop') {
        video.pause();
        video.currentTime = action.atSeconds;
        publish(action.atSeconds, true);
        return true;
      }
      return false;
    },
    [publish],
  );

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return undefined;

    // StrictMode 이중 이펙트·동적 import 경합 방어 — 정리 뒤 도착한 인스턴스를 버린다
    let disposed = false;
    let recoveredNetwork = false;
    let recoveredMedia = false;
    let raf = 0;

    setPlaying(false);
    setError(null);

    // 경계 판정을 프레임마다 본다. 100ms 발행 주기로만 보면 2배속에서 0.2초 넘게 지나친다.
    const frame = () => {
      raf = requestAnimationFrame(frame);
      if (!enforceBounds(video)) publish(video.currentTime);
    };

    const onPlay = () => {
      setPlaying(true);
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(frame);
    };
    const onPause = () => {
      setPlaying(false);
      cancelAnimationFrame(raf);
      publish(video.currentTime, true);
    };
    // 백그라운드 탭에서는 rAF 가 멎는다 — 그동안의 경계 판정을 이쪽이 받는다.
    // 정밀도는 ~250ms 로 떨어지지만 구간 밖을 무한히 재생하지는 않는다.
    const onTimeUpdate = () => {
      if (!video.paused && enforceBounds(video)) return;
      publish(video.currentTime);
    };
    const onSeeked = () => publish(video.currentTime, true);
    const onLoadedMetadata = () => {
      if (Number.isFinite(video.duration) && video.duration > 0) setDurationSeconds(video.duration);
      // attachMedia 의 load 알고리즘이 playbackRate 를 defaultPlaybackRate 로 되돌린다.
      // 허브의 setRate effect 는 attach 보다 먼저 도므로 여기서 다시 심어야 배속이 살아남는다.
      video.playbackRate = rateRef.current;
      video.defaultPlaybackRate = rateRef.current;
    };

    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onPause);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('seeked', onSeeked);
    video.addEventListener('loadedmetadata', onLoadedMetadata);

    void (async () => {
      // hls.js 는 클라이언트 전용(월드 오브젝트 참조) — 동적 import 로 번들 분리까지 겸한다
      const { default: HlsCtor } = await import('hls.js');
      if (disposed) return;

      if (HlsCtor.isSupported()) {
        const hls = new HlsCtor({ ...EDITOR_HLS_VOD_CONFIG, startPosition: initialRef.current });
        hlsRef.current = hls;
        hls.on(HlsCtor.Events.ERROR, (_event, data) => {
          if (!data.fatal) return;
          if (data.type === HlsCtor.ErrorTypes.NETWORK_ERROR && !recoveredNetwork) {
            recoveredNetwork = true;
            hls.startLoad();
            return;
          }
          if (data.type === HlsCtor.ErrorTypes.MEDIA_ERROR && !recoveredMedia) {
            recoveredMedia = true;
            hls.recoverMediaError();
            return;
          }
          console.error('[editor] 복구 불가 hls.js 오류:', data.type, data.details);
          hls.destroy();
          hlsRef.current = null;
          setError('fatal');
        });
        hls.attachMedia(video);
        // 자동재생하지 않는다 — 편집기는 사용자가 구간을 정한 뒤 직접 재생을 누르는 화면이고,
        // 자동재생 정책 때문에 음소거로 시작하면 소리로 구간을 고르는 일이 안 된다.
        hls.loadSource(playlistUrl);
      } else if (video.canPlayType('application/vnd.apple.mpegurl') !== '') {
        video.src = playlistUrl;
        video.currentTime = initialRef.current;
      } else {
        console.warn('[editor] HLS 재생 불가 — hls.js 미지원 + 네이티브 HLS 없음 (Chrome 권장)');
        setError('unsupported');
      }
    })();

    return () => {
      disposed = true;
      cancelAnimationFrame(raf);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onPause);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('seeked', onSeeked);
      video.removeEventListener('loadedmetadata', onLoadedMetadata);
      hlsRef.current?.destroy();
      hlsRef.current = null;
      // src 제거만으로는 네트워크·디코더가 붙어 있다 — load()까지 해야 리소스가 해제된다
      video.removeAttribute('src');
      video.load();
    };
  }, [videoRef, playlistUrl, enforceBounds, publish]);

  const seekTo = useCallback(
    (seconds: number) => {
      const video = videoRef.current;
      if (video === null) return;
      const limit =
        Number.isFinite(video.duration) && video.duration > 0
          ? video.duration
          : Number.POSITIVE_INFINITY;
      const next = Math.min(limit, Math.max(0, seconds));
      video.currentTime = next;
      publish(next, true);
    },
    [videoRef, publish],
  );

  return {
    playing,
    currentSeconds,
    durationSeconds,
    error,
    togglePlay: useCallback(() => {
      const video = videoRef.current;
      if (video === null) return;
      if (video.paused) void video.play().catch(() => {});
      else video.pause();
    }, [videoRef]),
    seekTo,
    // 스로틀된 상태가 아니라 실제 재생 위치에 더한다 — 상태 기준이면 연타가 같은 자리에서 겹친다
    seekBy: useCallback(
      (delta: number) => {
        const video = videoRef.current;
        if (video !== null) seekTo(video.currentTime + delta);
      },
      [videoRef, seekTo],
    ),
    setRate: useCallback(
      (rate: number) => {
        rateRef.current = rate;
        const video = videoRef.current;
        if (video === null) return;
        video.playbackRate = rate;
        // 다음 load 에서 되돌아가지 않게 기본값도 같이 옮긴다
        video.defaultPlaybackRate = rate;
      },
      [videoRef],
    ),
    setBounds: useCallback((bounds: PlaybackBounds) => {
      // 여기서 바로 시크하지 않는다 — 다음 재생 틱이 처리한다(시뮬레이션과 같은 시점).
      // 즉시 옮기면 구간 핸들을 끄는 동안 영상이 손을 따라 튄다.
      boundsRef.current = bounds;
    }, []),
  };
}
