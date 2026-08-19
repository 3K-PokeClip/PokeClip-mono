'use client';

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type Hls from 'hls.js';
import { behindFromCurrentTime, dvrRange, liveEdgePosition } from './dvrWindow';
import { HLS_DVR_CONFIG } from './hlsConfig';
import { LIVE_WINDOW_SECONDS, behindFromSeekFraction, isAtEdge } from './playerMath';
import { useControlsAutoHide } from './useControlsAutoHide';
import {
  CLIP_MARK_MS,
  PLAYER_QUALITIES,
  type PlayerQuality,
  type PlayerSimulation,
  type PlayerSimulationOptions,
} from './usePlayerSimulation';

// 실재생 훅 (POK-31) — usePlayerSimulation과 같은 PlayerSimulation 계약을 반환한다.
// hls.js 인스턴스·<video> 바인딩은 전부 여기 있고, UI(GlassPlayer 이하)는 어느 훅인지 모른다.
// jsdom엔 미디어 구현이 없어 이 훅은 테스트하지 않는다 — src 없는 테스트는 시뮬레이션 경로를 탄다.

export function useHlsPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  src: string,
  options: PlayerSimulationOptions = {},
): PlayerSimulation {
  // playing은 video 이벤트로만 바꾼다 — 자동재생이 거부되면 false로 남아 재생 버튼이 노출된다
  const [playing, setPlaying] = useState(false);
  // 자동재생 정책상 muted로 시작한다 (시뮬레이션의 muted=false와 다른 점)
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  const [behindSeconds, setBehind] = useState(0);
  const [uptimeSeconds, setUptime] = useState(options.initialUptimeSeconds ?? 0);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const [lowLatency, setLowLatency] = useState(true);
  const [clipMarked, setClipMarked] = useState(false);
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);

  const hlsRef = useRef<Hls | null>(null);
  const clipTimer = useRef<number>(undefined);

  // 방송 경과는 스트림 메타데이터에 없다 — 시뮬레이션처럼 시드+티커로 표시만 유지한다.
  // 방송 상태 API가 생기면 그 티켓에서 교체.
  useEffect(() => {
    const tick = window.setInterval(() => setUptime((s) => s + 1), 1000);
    return () => window.clearInterval(tick);
  }, []);

  useEffect(() => () => window.clearTimeout(clipTimer.current), []);

  // 초기 볼륨은 마운트 1회만 — src 전환(?stream= 변경) 이펙트에 두면 사용자가 만진
  // 볼륨이 스트림을 바꿀 때마다 70으로 되돌아간다.
  useEffect(() => {
    const video = videoRef.current;
    if (video) video.volume = 0.7;
  }, [videoRef]);

  // 유일한 시크 경로 (계약3 4절 2번) — 마우스·키보드·LIVE 복귀가 전부 이 함수를 탄다.
  // 라이브 재동기화를 호출하지 않는다.
  const seekToBehind = useCallback(
    (behind: number) => {
      const video = videoRef.current;
      if (!video) return;
      const range = dvrRange(video.seekable);
      if (!range) return;
      // 되감기 기준점은 엣지와 같은 지점이어야 한다 — range.end 기준으로 시크하고
      // liveEdgePosition 기준으로 시차를 재면 둘이 그 차이만큼 어긋난다.
      const live = liveEdgePosition(range, hlsRef.current?.liveSyncPosition ?? null);
      const clamped = Math.min(
        Math.min(LIVE_WINDOW_SECONDS, live - range.start),
        Math.max(0, behind),
      );
      if (isAtEdge(clamped)) {
        // 창이 라이브 지연폭보다 짧으면(방송 시작 직후) live가 range.start로 붕괴한다 —
        // 스냅할 지점이 없는데 시크하면 재생 중인 위치에서 뒤로 끌려간다. 그땐 두고 본다.
        if (live > range.start) video.currentTime = live;
        setBehind(0);
      } else {
        video.currentTime = Math.max(range.start, live - clamped);
        setBehind(Math.round(clamped));
      }
    },
    [videoRef],
  );

  const seekToFraction = useCallback(
    (fraction: number) => seekToBehind(behindFromSeekFraction(fraction)),
    [seekToBehind],
  );

  const seekBy = useCallback(
    (deltaSeconds: number) => {
      const video = videoRef.current;
      if (!video) return;
      const range = dvrRange(video.seekable);
      if (!range) return;
      seekToBehind(
        behindFromCurrentTime(range, video.currentTime, hlsRef.current?.liveSyncPosition ?? null) +
          deltaSeconds,
      );
    },
    [videoRef, seekToBehind],
  );

  // 라이브 복귀는 이 액션(LIVE 버튼)뿐이다 — 플레이어가 스스로 복귀하는 경로는 없다 (계약3 4절 3번)
  const returnToLive = useCallback(() => {
    seekToBehind(0);
    videoRef.current?.play().catch(() => {});
  }, [seekToBehind, videoRef]);

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    // 재개 시 currentTime을 만지지 않는다 — 되감은 위치를 그대로 이어서 튼다 (계약3 4절 1번)
    if (video.paused) video.play().catch(() => {});
    else video.pause();
    wake();
  }, [videoRef, wake]);

  const toggleMute = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !video.muted;
  }, [videoRef]);

  const setVolume = useCallback(
    (value: number) => {
      const video = videoRef.current;
      if (!video) return;
      const v = Math.min(100, Math.max(0, Math.round(value)));
      video.volume = v / 100;
      if (v > 0) video.muted = false;
    },
    [videoRef],
  );

  const markClip = useCallback(() => {
    setClipMarked(true);
    window.clearTimeout(clipTimer.current);
    clipTimer.current = window.setTimeout(() => setClipMarked(false), CLIP_MARK_MS);
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    // StrictMode 이중 이펙트·동적 import 경합 방어 — 클린업 후 도착한 인스턴스를 버린다
    let disposed = false;
    let recoveredNetwork = false;
    let recoveredMedia = false;

    // src 전환(?stream= 변경) 시 이전 스트림의 상태가 남지 않게 초기화 — 첫 마운트에선
    // 같은 값이라 no-op. 새 소스의 자동재생이 거부되면 playing=false로 남아 재생 버튼이 노출된다.
    setPlaying(false);
    setBehind(0);

    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onVolumeChange = () => {
      setMuted(video.muted);
      setVolumeState(Math.round(video.volume * 100));
    };
    // 시차는 정수 초로 반올림돼 있어 같은 값이면 setState가 리렌더를 건너뛴다
    const paint = () => {
      const range = dvrRange(video.seekable);
      if (!range) return;
      setBehind(
        behindFromCurrentTime(range, video.currentTime, hlsRef.current?.liveSyncPosition ?? null),
      );
    };
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('volumechange', onVolumeChange);
    video.addEventListener('timeupdate', paint);
    // 일시정지 중엔 timeupdate가 멎지만 라이브 엣지는 계속 흐른다 — 시차 표시용 보조 틱
    const paintTimer = window.setInterval(paint, 1000);

    void (async () => {
      // hls.js는 클라이언트 전용(월드 오브젝트 참조) — 동적 import로 번들 분리까지 겸한다
      const { default: HlsCtor } = await import('hls.js');
      if (disposed) return;

      if (HlsCtor.isSupported()) {
        const hls = new HlsCtor(HLS_DVR_CONFIG);
        hlsRef.current = hls;
        hls.on(HlsCtor.Events.ERROR, (_event, data) => {
          if (!data.fatal) return;
          // 스파이크 최소 복구 — 각 1회만 시도, 재발 시 정리하고 크게 남긴다
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
          // 화면은 마지막 프레임에서 멎고 로그만 남는다 — 사용자에게 보이는 에러 상태는
          // PlayerSimulation 계약을 넓혀야 해 POK-23에서 다룬다
          console.error('[player] 복구 불가 hls.js 오류:', data.type, data.details);
          hls.destroy();
          hlsRef.current = null;
        });
        hls.on(HlsCtor.Events.MANIFEST_PARSED, () => {
          // 자동재생 거부는 reject로 온다 — playing=false로 남아 재생 버튼이 노출된다
          video.play().catch(() => {});
        });
        hls.attachMedia(video);
        // index.m3u8의 302(세션 파라미터)는 기본 로더가 따라간다 — 커스텀 loader 금지 (.env.example 참조)
        hls.loadSource(src);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari 네이티브 HLS — liveSyncPosition이 없어 엣지 스냅은 백오프 경로를 탄다
        video.src = src;
        video.play().catch(() => {});
      } else {
        console.warn('[player] HLS 재생 불가 — hls.js 미지원 + 네이티브 HLS 없음 (Chrome 권장)');
      }
    })();

    return () => {
      disposed = true;
      window.clearInterval(paintTimer);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('volumechange', onVolumeChange);
      video.removeEventListener('timeupdate', paint);
      hlsRef.current?.destroy();
      hlsRef.current = null;
      // src 제거만으로는 네트워크·디코더가 붙어 있다 — load()까지 해야 리소스가 해제된다
      video.removeAttribute('src');
      video.load();
    };
  }, [videoRef, src]);

  return {
    playing,
    muted,
    volume,
    behindSeconds,
    atEdge: isAtEdge(behindSeconds),
    uptimeSeconds,
    quality,
    lowLatency,
    clipMarked,
    controlsVisible,
    togglePlay,
    toggleMute,
    setVolume,
    seekToFraction,
    seekBy,
    returnToLive,
    // 화질은 표시용 — hls.js 레벨 배선은 스파이크 범위 밖 (POK-23 이후)
    setQuality,
    // 표시 전용 — lowLatencyMode는 Hls 생성자 전용 옵션이라 실반영엔 인스턴스 재생성이
    // 필요하다. 시킹 위치 복원 등 엣지가 커서 스파이크 범위 밖 (POK-23 이후).
    toggleLowLatency: useCallback(() => setLowLatency((v) => !v), []),
    markClip,
    wake,
    sleep,
  };
}
