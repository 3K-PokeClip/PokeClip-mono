'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { LIVE_WINDOW_SECONDS, behindFromSeekFraction, isAtEdge } from './playerMath';
import { useControlsAutoHide } from './useControlsAutoHide';

// 라이브 재생 시뮬레이션 — 실제 미디어 없이 시안(영상 플레이어 글래스)의 동작을 재현한다.
// 실재생 경로는 useHlsPlayback — GlassPlayer가 src 유무로 고르며, 이 훅은 src 없는
// 목업 전용이다. PlayerSimulation 반환 형태가 두 훅이 지키는 계약이다.

/** 클립 구간 마커가 시크바에 남는 시간 (시안 값) */
export const CLIP_MARK_MS = 3000;

export const PLAYER_QUALITIES = ['자동 (1080p)', '1080p60', '720p', '480p'] as const;
export type PlayerQuality = (typeof PLAYER_QUALITIES)[number];

export interface PlayerSimulationOptions {
  /** 테스트용 초기 시차(초) — 시킹·복귀 분기를 결정적으로 만든다 */
  initialBehindSeconds?: number;
  /** 테스트용 초기 방송 경과(초) */
  initialUptimeSeconds?: number;
}

export interface PlayerSimulation {
  playing: boolean;
  muted: boolean;
  /** 0..100 */
  volume: number;
  behindSeconds: number;
  atEdge: boolean;
  /**
   * 되감을 수 있는 폭(초) — 시크바의 좌측 끝이다 (계약3 4절 4번, POK-32).
   * 상한은 LIVE_WINDOW_SECONDS이고 방송이 짧으면 그만큼만이다. 실재생은 seekable에서,
   * 시뮬레이션은 방송 경과에서 파생한다.
   */
  windowSeconds: number;
  uptimeSeconds: number;
  quality: PlayerQuality;
  lowLatency: boolean;
  clipMarked: boolean;
  /** 자동 숨김 상태 — 일시정지 중엔 이 값과 무관하게 컨트롤을 보여준다 */
  controlsVisible: boolean;
  togglePlay: () => void;
  toggleMute: () => void;
  setVolume: (value: number) => void;
  /** 시크바 클릭 위치(0..1) → 시차 환산 (계약3 4절: 엣지 3초 스냅) */
  seekToFraction: (fraction: number) => void;
  /** 키보드 시킹 — 음수는 엣지 방향 */
  seekBy: (deltaSeconds: number) => void;
  /** 명시적 LIVE 복귀 (계약3 4절) */
  returnToLive: () => void;
  setQuality: (quality: PlayerQuality) => void;
  toggleLowLatency: () => void;
  markClip: () => void;
  wake: () => void;
  sleep: () => void;
}

export function usePlayerSimulation(options: PlayerSimulationOptions = {}): PlayerSimulation {
  const [playing, setPlaying] = useState(true);
  const [muted, setMuted] = useState(false);
  const [volume, setVolumeState] = useState(70);
  const [behindSeconds, setBehind] = useState(options.initialBehindSeconds ?? 0);
  const [uptimeSeconds, setUptime] = useState(options.initialUptimeSeconds ?? 5043);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const [lowLatency, setLowLatency] = useState(true);
  const [clipMarked, setClipMarked] = useState(false);
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);

  const clipTimer = useRef<number>(undefined);

  // 방송 시작 전으로는 되감을 수 없다 — 실재생이 seekable에서 창을 얻는 것과 같은 물리다.
  // 고정 1시간으로 두면 짧은 방송 경로를 목업·스토리북에서 영영 볼 수 없다 (POK-32).
  const windowSeconds = Math.min(LIVE_WINDOW_SECONDS, uptimeSeconds);

  // 방송 시간은 항상 흐르고, 일시정지 중엔 라이브 엣지에서 뒤처진다 (DVR).
  useEffect(() => {
    const tick = window.setInterval(() => {
      setUptime((s) => s + 1);
      // 상한이 windowSeconds가 아니라 계약 상한인 건 의도다 — 일시정지 중엔 시차와 방송
      // 경과가 같이 1초씩 늘어 시차가 창을 앞지르지 않는다. windowSeconds를 여기 넣으면
      // deps에 걸려 인터벌이 매초 재설정될 뿐이다.
      if (!playing) setBehind((b) => Math.min(LIVE_WINDOW_SECONDS, b + 1));
    }, 1000);
    return () => window.clearInterval(tick);
  }, [playing]);

  useEffect(() => () => window.clearTimeout(clipTimer.current), []);

  const togglePlay = useCallback(() => {
    setPlaying((p) => !p);
    wake();
  }, [wake]);

  const seekToFraction = useCallback(
    (fraction: number) => {
      setBehind(behindFromSeekFraction(fraction, windowSeconds));
    },
    [windowSeconds],
  );

  const seekBy = useCallback(
    (deltaSeconds: number) => {
      setBehind((b) => {
        const next = Math.min(windowSeconds, Math.max(0, b + deltaSeconds));
        return isAtEdge(next) ? 0 : next;
      });
    },
    [windowSeconds],
  );

  const markClip = useCallback(() => {
    setClipMarked(true);
    window.clearTimeout(clipTimer.current);
    clipTimer.current = window.setTimeout(() => setClipMarked(false), CLIP_MARK_MS);
  }, []);

  const setVolume = useCallback((value: number) => {
    const v = Math.min(100, Math.max(0, Math.round(value)));
    setVolumeState(v);
    if (v > 0) setMuted(false);
  }, []);

  return {
    playing,
    muted,
    volume,
    behindSeconds,
    atEdge: isAtEdge(behindSeconds),
    windowSeconds,
    uptimeSeconds,
    quality,
    lowLatency,
    clipMarked,
    controlsVisible,
    togglePlay,
    toggleMute: useCallback(() => setMuted((m) => !m), []),
    setVolume,
    seekToFraction,
    seekBy,
    returnToLive: useCallback(() => setBehind(0), []),
    setQuality,
    toggleLowLatency: useCallback(() => setLowLatency((v) => !v), []),
    markClip,
    wake,
    sleep,
  };
}
