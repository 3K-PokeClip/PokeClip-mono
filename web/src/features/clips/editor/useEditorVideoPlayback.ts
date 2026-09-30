'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { recordingClipUrl } from '@/api/mediaPlayback';
import { playableFrom, type RecordingPiece } from '@/features/player/recordingTimeline';
import { boundaryAction, type EditorPlayback, type PlaybackBounds } from './editorPlayback';

// 편집기의 실재생 어댑터(POK-251) — 녹화 재생 서버에서 「구간 앞뒤로 조금 넉넉한 창」을 mp4 한 덩어리로 받아
// <video> 하나로 튼다. 방송 중이든 끝난 뒤든 같은 길이다(녹화 파일에서 읽는다).
//
// 시간축은 편집기와 같다 — 방송(녹화) 시작 기준 초. 영상 노드의 시간은 창의 시작을 0으로 세므로
// 「방송 기준 초 = 창 시작 + video.currentTime」으로 옮긴다.
//
// 구간이 창 밖으로 나가면 창을 다시 잡아 새로 받는다. 핸들을 끄는 동안 매번 받지 않게 잠깐 기다렸다 바꾼다.
//
// 🔴 창은 녹화 구간 하나 안에 둔다(POK-253). 재생 서버는 틈 안에서 시작하는 요청에 404, 틈을 건너는 요청은 틈 앞에서
// 끊는다(실측). 재접속 직후 카드는 앞 여유 10초가 틈에 걸려 미리보기가 통째로 실패했다. 구간이 틈을 건너면 틈 앞까지만 보인다.

const PAD_SECONDS = 10;
const REWINDOW_DELAY_MS = 500;

interface Window {
  startSeconds: number;
  endSeconds: number;
  /** 구간에 녹화가 하나도 없다(틈 안이거나 녹화 끝 뒤) — 받지 않고 미리보기 실패로 알린다 */
  empty?: true;
}

function windowFor(
  range: { startSeconds: number; endSeconds: number },
  totalSeconds: number,
  pieces: RecordingPiece[] | undefined,
): Window {
  const wide = {
    startSeconds: Math.max(0, range.startSeconds - PAD_SECONDS),
    endSeconds: Math.min(totalSeconds, range.endSeconds + PAD_SECONDS),
  };
  if (pieces === undefined) return wide;
  // 구간의 시작이 든 녹화 구간(틈이면 다음 구간) 안으로 자른다
  const piece = playableFrom({ pieces }, range.startSeconds);
  const containing = piece && pieces.find((p) => p.toSeconds === piece.toSeconds)!;
  const clipped = containing && {
    startSeconds: Math.max(wide.startSeconds, containing.fromSeconds),
    endSeconds: Math.min(wide.endSeconds, containing.toSeconds),
  };
  // 여유까지 넣어도 녹화가 안 걸리면 받지 않는다 — 두 자르기가 엇갈려 뒤집힌 창으로 엉뚱한 구간을 보였다(PR #205 codex)
  if (!clipped || clipped.startSeconds >= clipped.endSeconds) {
    return { startSeconds: range.startSeconds, endSeconds: range.startSeconds, empty: true };
  }
  return clipped;
}

export interface EditorVideoPlayback {
  playback: EditorPlayback;
  /** 화면이 <video ref=…> 로 받는다. 노드는 보이지 않게 두고 프레임은 VideoSurface 가 그린다 */
  videoRef: (element: HTMLVideoElement | null) => void;
  video: HTMLVideoElement | null;
  src: string;
}

export function useEditorVideoPlayback(options: {
  streamId: string;
  /** 녹화 시작(UTC epoch ms) — 방송 기준 0초의 절대 시각 */
  recordingStartMs: number;
  /** 지금까지 녹화된 길이(초) */
  recordingSeconds: number;
  /** 녹화 구간들(이 훅의 시간축 초). 없으면 한 덩어리로 본다 */
  pieces?: RecordingPiece[];
  initialRange: { startSeconds: number; endSeconds: number };
}): EditorVideoPlayback {
  const { streamId, recordingStartMs, recordingSeconds, pieces, initialRange } = options;
  const [video, setVideo] = useState<HTMLVideoElement | null>(null);
  const [win, setWin] = useState<Window>(() => windowFor(initialRange, recordingSeconds, pieces));
  const piecesRef = useRef(pieces);
  piecesRef.current = pieces;
  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(initialRange.startSeconds);
  const [failed, setFailed] = useState(false);

  const winRef = useRef(win);
  winRef.current = win;
  const boundsRef = useRef<PlaybackBounds>({ ...initialRange, loop: true });
  /** 창을 갈아 끼운 뒤 돌아갈 자리(방송 기준 초) */
  const pendingSeekRef = useRef<number | null>(initialRange.startSeconds);
  const rewindowTimer = useRef<number | null>(null);

  const remoteUrl = useMemo(
    () =>
      recordingClipUrl(
        streamId,
        recordingStartMs + Math.round(win.startSeconds * 1000),
        Math.max(1, win.endSeconds - win.startSeconds),
      ),
    [streamId, recordingStartMs, win],
  );

  // 🔴 재생 서버의 응답은 만들어 가며 흘려보내는 영상이라 그대로 틀면 **되감기·건너뛰기가 안 된다**(seekable 0~0, 실측).
  // 창 하나를 통째로 받아 브라우저 안의 파일로 만든 뒤 튼다 — 그러면 구간 반복·프레임 이동이 즉시 된다.
  const [src, setSrc] = useState('');
  useEffect(() => {
    if (recordingSeconds <= 0) return undefined;
    if (win.empty) {
      setFailed(true);
      setSrc('');
      return undefined;
    }
    const abort = new AbortController();
    let objectUrl: string | null = null;
    setFailed(false);
    fetch(remoteUrl, { signal: abort.signal })
      .then((res) => {
        if (!res.ok) throw new Error(String(res.status));
        return res.blob();
      })
      .then((blob) => {
        objectUrl = URL.createObjectURL(new Blob([blob], { type: 'video/mp4' }));
        setSrc(objectUrl);
      })
      .catch((e: unknown) => {
        if (!(e instanceof DOMException && e.name === 'AbortError')) setFailed(true);
      });
    return () => {
      abort.abort();
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl);
    };
  }, [remoteUrl, recordingSeconds, win.empty]);

  const clampToWindow = useCallback((seconds: number) => {
    const w = winRef.current;
    return Math.min(w.endSeconds - 0.05, Math.max(w.startSeconds, seconds));
  }, []);

  useEffect(() => {
    if (video === null) return undefined;
    const position = () => winRef.current.startSeconds + video.currentTime;
    const onLoaded = () => {
      setFailed(false);
      const target = pendingSeekRef.current;
      pendingSeekRef.current = null;
      if (target !== null)
        video.currentTime = Math.max(0, clampToWindow(target) - winRef.current.startSeconds);
      setCurrentSeconds(position());
    };
    const onTime = () => {
      const now = position();
      const action = boundaryAction(now, boundsRef.current);
      if (action.kind === 'seek') {
        video.currentTime = Math.max(
          0,
          clampToWindow(action.toSeconds) - winRef.current.startSeconds,
        );
      } else if (action.kind === 'stop' && !video.paused) {
        video.pause();
      }
      setCurrentSeconds(position());
    };
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onError = () => setFailed(true);
    video.addEventListener('loadedmetadata', onLoaded);
    // 리스너를 달기 전에 이미 메타데이터가 와 있을 수 있다(캐시된 영상) — 그러면 처음 자리로 못 간다.
    if (video.readyState >= 1 && pendingSeekRef.current !== null) onLoaded();
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('seeked', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onPause);
    video.addEventListener('error', onError);
    return () => {
      video.removeEventListener('loadedmetadata', onLoaded);
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('seeked', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onPause);
      video.removeEventListener('error', onError);
    };
  }, [video, clampToWindow]);

  // timeupdate 는 초당 4번쯤이라 구간 끝을 0.25초까지 넘긴다 — 재생 중에는 프레임마다 경계를 본다.
  useEffect(() => {
    if (video === null || !playing) return undefined;
    let raf = 0;
    const tick = () => {
      raf = requestAnimationFrame(tick);
      const now = winRef.current.startSeconds + video.currentTime;
      const action = boundaryAction(now, boundsRef.current);
      if (action.kind === 'seek') {
        video.currentTime = Math.max(
          0,
          clampToWindow(action.toSeconds) - winRef.current.startSeconds,
        );
      } else if (action.kind === 'stop') {
        video.pause();
      }
      setCurrentSeconds(now);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [video, playing, clampToWindow]);

  const togglePlay = useCallback(() => {
    if (video === null) return;
    if (video.paused) {
      const bounds = boundsRef.current;
      const now = winRef.current.startSeconds + video.currentTime;
      // 구간 끝에 서 있으면 처음부터 다시 — 끝에서 재생을 누르면 곧바로 멈춰 아무 일도 없는 것처럼 보인다
      if (now >= bounds.endSeconds - 0.05 || now < bounds.startSeconds) {
        video.currentTime = Math.max(
          0,
          clampToWindow(bounds.startSeconds) - winRef.current.startSeconds,
        );
      }
      void video.play().catch(() => setFailed(true));
    } else {
      video.pause();
    }
  }, [video, clampToWindow]);

  const seekTo = useCallback(
    (seconds: number) => {
      if (video === null) return;
      const target = clampToWindow(seconds);
      video.currentTime = Math.max(0, target - winRef.current.startSeconds);
      setCurrentSeconds(target);
    },
    [video, clampToWindow],
  );

  const seekBy = useCallback(
    (delta: number) => {
      if (video === null) return;
      seekTo(winRef.current.startSeconds + video.currentTime + delta);
    },
    [video, seekTo],
  );

  const setRate = useCallback(
    (rate: number) => {
      if (video !== null) video.playbackRate = rate;
    },
    [video],
  );

  const setBounds = useCallback(
    (bounds: PlaybackBounds) => {
      boundsRef.current = bounds;
      const w = winRef.current;
      const needsEarlier = bounds.startSeconds < w.startSeconds + 0.5 && w.startSeconds > 0;
      const needsLater = bounds.endSeconds > w.endSeconds - 0.5 && w.endSeconds < recordingSeconds;
      if (!needsEarlier && !needsLater) return;
      // 녹화 구간 끝에 막혀 더 넓힐 수 없으면 창을 다시 받지 않는다 — 같은 창을 매번 새로 받게 된다
      const next = windowFor(bounds, recordingSeconds, piecesRef.current);
      if (
        next.startSeconds === w.startSeconds &&
        next.endSeconds === w.endSeconds &&
        next.empty === w.empty
      )
        return;
      if (rewindowTimer.current !== null) window.clearTimeout(rewindowTimer.current);
      rewindowTimer.current = window.setTimeout(() => {
        rewindowTimer.current = null;
        const current = boundsRef.current;
        pendingSeekRef.current = current.startSeconds;
        setWin(windowFor(current, recordingSeconds, piecesRef.current));
      }, REWINDOW_DELAY_MS);
    },
    [recordingSeconds],
  );

  useEffect(
    () => () => {
      if (rewindowTimer.current !== null) window.clearTimeout(rewindowTimer.current);
    },
    [],
  );

  const playback = useMemo<EditorPlayback>(
    () => ({
      playing,
      currentSeconds,
      durationSeconds: recordingSeconds,
      error: failed ? 'fatal' : null,
      togglePlay,
      seekTo,
      seekBy,
      setRate,
      setBounds,
    }),
    [
      playing,
      currentSeconds,
      recordingSeconds,
      failed,
      togglePlay,
      seekTo,
      seekBy,
      setRate,
      setBounds,
    ],
  );

  return { playback, videoRef: setVideo, video, src };
}
