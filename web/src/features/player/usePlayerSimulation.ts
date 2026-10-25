'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { REWIND_UI_MAX_SECONDS, behindFromSeekFraction, isAtEdge } from './playerMath';
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
   * 상한은 REWIND_UI_MAX_SECONDS이고 방송이 짧으면 그만큼만이다. 실재생은 seekable에서,
   * 시뮬레이션은 방송 경과에서 파생한다.
   */
  windowSeconds: number;
  uptimeSeconds: number;
  quality: PlayerQuality;
  lowLatency: boolean;
  /**
   * 화질·저지연 설정 메뉴를 보일까. 실재생(라이브·다시보기)은 렌디션이 하나뿐이고 저지연도 반영하지 않아 고를 것이 없는
   * 거짓 선택지라 끈다(POK-253, 계약3 2절). 목업 재생(스토리북·시안)만 보인다
   */
  settingsMenu: boolean;
  /**
   * 끝난 방송의 녹화 다시보기인가. 오른쪽 끝이 「실시간」이 아니라 녹화 끝이고 그 끝으로 가는 동작도 녹화 재생이 정한다
   * (ADR-081 8). 버튼·시크바 문구가 이 값으로 갈린다(QA 2026-10-11)
   */
  replay: boolean;
  /**
   * 실재생이 멎은 까닭을 영상 위에 한 줄로 알린다(POK-337). 없으면(null·목업) 덮개가 없다. 영상 서버를 아직 못 부른
   * 자리(출입증·녹화 목록)는 이 칸이 아니라 영상 자리 아랫줄이 알린다
   */
  playbackError?: string | null;
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
  /**
   * 녹화 다시보기만: 그 자리(방송 기준 초)로 바로 옮긴다. 카드처럼 정확한 시각으로 갈 때 쓴다: 시크바 비율을 거치면 시차를 초
   * 단위로 반올림해 1초 안쪽 앞에 섰다(POK-324 리뷰 1라운드). 없으면 비율로 옮긴다
   */
  seekToPosition?: (seconds: number) => void;
  /**
   * 녹화 다시보기만: 지금 자리(방송 기준 초)를 내리지 않은 값으로 읽는다. 옮기는 중이면 도착점이다. 없으면 그 길이 없는 재생기다
   */
  currentPositionSeconds?: () => number;
  /**
   * 라이브 실재생만: 그 실제 시각(epoch ms)으로 옮긴다. 조각마다 그 조각의 시각 표시(PDT)로 영상 시각을 센다(계약3, POK-326).
   * 목록에 시각 표시가 없으면 false다(부르는 쪽이 방송 경과로 옮긴다). 없으면 그 길이 없는 재생기다
   */
  seekToDate?: (epochMs: number) => boolean;
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
  const windowSeconds = Math.min(REWIND_UI_MAX_SECONDS, uptimeSeconds);

  // 방송 시간은 항상 흐르고, 일시정지 중엔 라이브 엣지에서 뒤처진다 (DVR).
  useEffect(() => {
    const tick = window.setInterval(() => {
      setUptime((s) => s + 1);
      // 상한이 windowSeconds가 아니라 계약 상한인 건 의도다 — 일시정지 중엔 시차와 방송
      // 경과가 같이 1초씩 늘어 시차가 창을 앞지르지 않는다. windowSeconds를 여기 넣으면
      // deps에 걸려 인터벌이 매초 재설정될 뿐이다.
      if (!playing) setBehind((b) => Math.min(REWIND_UI_MAX_SECONDS, b + 1));
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
    settingsMenu: true,
    replay: false,
    setQuality,
    toggleLowLatency: useCallback(() => setLowLatency((v) => !v), []),
    markClip,
    wake,
    sleep,
  };
}
