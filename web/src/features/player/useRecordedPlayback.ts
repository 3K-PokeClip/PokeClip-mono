'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { recordingClipUrl } from '@/api/mediaPlayback';
import { AT_EDGE_THRESHOLD_SECONDS, behindFromSeekFraction } from './playerMath';
import { useControlsAutoHide } from './useControlsAutoHide';
import { PLAYER_QUALITIES, type PlayerQuality, type PlayerSimulation } from './usePlayerSimulation';

// 끝난 방송 다시보기(POK-251) — 녹화 재생 서버에서 「지금 자리부터 끝까지」를 한 줄기로 받아 튼다.
// 그 응답은 만들어 가며 흘려보내는 영상이라 브라우저가 건너뛰지 못한다(seekable 0~0, 실측). 그래서
// 「이동」은 주소를 새 시작점으로 갈아 끼우는 것이다 — 시크바·카드 클릭·화살표가 전부 그 한 길을 탄다.
//
// 라이브 플레이어와 같은 계약(PlayerSimulation)을 채운다: 끝(방송 종료 시점)이 「엣지」이고
// behindSeconds = 끝에서 얼마나 앞인가, windowSeconds = 방송 전체 길이.

export interface RecordedSource {
  streamId: string;
  /** 녹화 시작(UTC epoch ms) */
  startMs: number;
  durationSeconds: number;
}

const SEEK_DEBOUNCE_MS = 250;

export function useRecordedPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  recorded: RecordedSource,
): PlayerSimulation {
  const total = recorded.durationSeconds;
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  /** 지금 줄기가 시작한 자리(방송 기준 초) */
  const [offset, setOffset] = useState(0);
  const [position, setPosition] = useState(0);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);
  const offsetRef = useRef(0);
  offsetRef.current = offset;
  const seekTimer = useRef<number | null>(null);

  const src = useMemo(
    () =>
      recordingClipUrl(
        recorded.streamId,
        recorded.startMs + Math.round(offset * 1000),
        Math.max(1, total - offset),
      ),
    [recorded.streamId, recorded.startMs, offset, total],
  );

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return undefined;
    video.src = src;
    video.muted = muted;
    void video.play().catch(() => {
      /* 자동재생 거부 — 재생 버튼이 남는다 */
    });
    const onTime = () => setPosition(offsetRef.current + video.currentTime);
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onPause);
    return () => {
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onPause);
    };
    // muted 는 아래 토글이 노드에 직접 쓴다 — 여기 의존성에 넣으면 음소거를 풀 때마다 영상이 처음부터 다시 온다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, videoRef]);

  const jumpTo = useCallback(
    (seconds: number) => {
      const target = Math.min(Math.max(0, total - 1), Math.max(0, seconds));
      setPosition(target);
      if (seekTimer.current !== null) window.clearTimeout(seekTimer.current);
      // 시크바를 끄는 동안 매 좌표마다 새 줄기를 받지 않는다
      seekTimer.current = window.setTimeout(() => {
        seekTimer.current = null;
        setOffset(target);
      }, SEEK_DEBOUNCE_MS);
    },
    [total],
  );

  useEffect(
    () => () => {
      if (seekTimer.current !== null) window.clearTimeout(seekTimer.current);
    },
    [],
  );

  const behindSeconds = Math.max(0, Math.round(total - position));

  return {
    playing,
    muted,
    volume,
    behindSeconds,
    atEdge: behindSeconds < AT_EDGE_THRESHOLD_SECONDS,
    windowSeconds: total,
    uptimeSeconds: total,
    quality,
    lowLatency: false,
    clipMarked: false,
    controlsVisible,
    togglePlay: useCallback(() => {
      const video = videoRef.current;
      if (video === null) return;
      if (video.paused) void video.play().catch(() => undefined);
      else video.pause();
    }, [videoRef]),
    toggleMute: useCallback(() => {
      setMuted((prev) => {
        const next = !prev;
        if (videoRef.current) videoRef.current.muted = next;
        return next;
      });
    }, [videoRef]),
    setVolume: useCallback(
      (value: number) => {
        setVolumeState(value);
        if (videoRef.current) videoRef.current.volume = Math.min(1, Math.max(0, value / 100));
      },
      [videoRef],
    ),
    seekToFraction: useCallback(
      (fraction: number) => jumpTo(total - behindFromSeekFraction(fraction, total)),
      [jumpTo, total],
    ),
    // 음수는 엣지(끝) 방향 — 라이브 플레이어와 같은 부호
    seekBy: useCallback(
      (delta: number) => jumpTo(offsetRef.current + (videoRef.current?.currentTime ?? 0) - delta),
      [jumpTo, videoRef],
    ),
    // 지난 방송에는 「실시간」이 없다 — 처음으로 돌아간다
    returnToLive: useCallback(() => jumpTo(0), [jumpTo]),
    setQuality,
    toggleLowLatency: useCallback(() => undefined, []),
    markClip: useCallback(() => undefined, []),
    wake,
    sleep,
  };
}
