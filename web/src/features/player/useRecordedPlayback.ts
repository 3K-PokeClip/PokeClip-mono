'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { recordingClipUrl } from '@/api/mediaPlayback';
import { AT_EDGE_THRESHOLD_SECONDS, behindFromSeekFraction } from './playerMath';
import { useControlsAutoHide } from './useControlsAutoHide';
import { PLAYER_QUALITIES, type PlayerQuality, type PlayerSimulation } from './usePlayerSimulation';
import { playableBackFrom, playableFrom, type RecordingTimeline } from './recordingTimeline';

// 끝난 방송 다시보기(POK-251) — 녹화 재생 서버에서 「지금 자리부터 끝까지」를 한 줄기로 받아 튼다.
// 그 응답은 만들어 가며 흘려보내는 영상이라 브라우저가 건너뛰지 못한다(seekable 0~0, 실측). 그래서
// 「이동」은 주소를 새 시작점으로 갈아 끼우는 것이다 — 시크바·카드 클릭·화살표가 전부 그 한 길을 탄다.
//
// 라이브 플레이어와 같은 계약(PlayerSimulation)을 채운다: 끝(방송 종료 시점)이 「엣지」이고
// behindSeconds = 끝에서 얼마나 앞인가, windowSeconds = 방송 전체 길이.
//
// 녹화가 구간 여럿이면(송출 끊김·녹화기 재시작) 한 번에 한 구간만 달라 한다 — 재생 서버는 틈을 건너 주지 않는다
// (recordingTimeline). 한 구간이 끝나면 다음 구간을 이어 틀고, 틈 안을 누르면 다음 구간 시작으로 간다(POK-253).

export type RecordedSource = RecordingTimeline & { streamId: string };

const SEEK_DEBOUNCE_MS = 250;

export function useRecordedPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  recorded: RecordedSource,
): PlayerSimulation {
  const total = recorded.durationSeconds;
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  /** 가고 싶은 자리(방송 기준 초). 실제 줄기는 여기서 틀 수 있는 구간부터다 */
  const [offset, setOffset] = useState(0);
  const [position, setPosition] = useState(0);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);
  // 틈 안이면 다음 구간 시작, 녹화 끝을 지났으면 마지막 구간 끝 1초 앞(끝까지 본 뒤 다시 누른 경우)
  const piece = useMemo(() => {
    const last = recorded.pieces[recorded.pieces.length - 1]!;
    return (
      playableFrom(recorded, offset) ?? {
        fromSeconds: Math.max(last.fromSeconds, last.toSeconds - 1),
        toSeconds: last.toSeconds,
      }
    );
  }, [recorded, offset]);
  /** 지금 줄기가 시작한 자리(방송 기준 초) — 영상 노드의 0초 */
  const offsetRef = useRef(0);
  offsetRef.current = piece.fromSeconds;
  const pieceEndRef = useRef(0);
  pieceEndRef.current = piece.toSeconds;
  const seekTimer = useRef<number | null>(null);
  /**
   * 사용자가 재생을 원하는 상태인가. 이동은 주소를 갈아 끼우는 것이라 새 줄기를 틀지 말지를 이것으로 정한다 —
   * 무조건 틀면 멈춰 둔 영상이 시크바·카드·화살표 한 번에 다시 재생된다(PR #200 codex). 처음 열 때는 튼다.
   */
  const wantPlayRef = useRef(true);
  const recordedRef = useRef(recorded);
  recordedRef.current = recorded;

  const src = useMemo(
    () =>
      recordingClipUrl(
        recorded.streamId,
        recorded.startMs + Math.round(piece.fromSeconds * 1000),
        Math.max(1, piece.toSeconds - piece.fromSeconds),
      ),
    [recorded.streamId, recorded.startMs, piece],
  );

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return undefined;
    video.src = src;
    video.muted = muted;
    if (wantPlayRef.current) {
      void video.play().catch(() => {
        /* 자동재생 거부 — 재생 버튼이 남는다 */
      });
    }
    const onTime = () => setPosition(offsetRef.current + video.currentTime);
    const onPlay = () => {
      wantPlayRef.current = true;
      setPlaying(true);
    };
    const onPause = () => {
      // 끝나서 멈춘 것은 사용자가 멈춘 것이 아니다 — 다음 구간을 이어 틀지는 ended가 정한다
      if (!video.ended) wantPlayRef.current = false;
      setPlaying(false);
    };
    const onEnded = () => {
      const next = playableFrom(recordedRef.current, pieceEndRef.current);
      if (next === null) {
        wantPlayRef.current = false;
        return;
      }
      setPosition(next.fromSeconds);
      setOffset(next.fromSeconds);
    };
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onEnded);
    return () => {
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onEnded);
    };
    // muted 는 아래 토글이 노드에 직접 쓴다 — 여기 의존성에 넣으면 음소거를 풀 때마다 영상이 처음부터 다시 온다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, videoRef]);

  const jumpTo = useCallback(
    (seconds: number, backward = false) => {
      const wanted = Math.min(Math.max(0, total - 1), Math.max(0, seconds));
      // 틈 안이면 다음 구간 시작으로(뒤로 가기면 앞 구간 끝으로) — 시크바도 실제로 틀 자리를 보인다
      const target = backward
        ? playableBackFrom(recordedRef.current, wanted)
        : (playableFrom(recordedRef.current, wanted)?.fromSeconds ?? wanted);
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
      // 다 본 뒤 다시 누르면 처음부터다 — 그대로 틀면 브라우저가 지금 줄기(마지막 구간)만 다시 튼다(PR #205 codex)
      if (video.ended && offsetRef.current > 0) {
        wantPlayRef.current = true;
        setPosition(0);
        setOffset(0);
        return;
      }
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
      (delta: number) =>
        jumpTo(offsetRef.current + (videoRef.current?.currentTime ?? 0) - delta, delta > 0),
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
