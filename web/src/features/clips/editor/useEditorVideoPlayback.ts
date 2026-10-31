'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ensureMediaAccess, reportPlaybackForbidden } from '@/api/playbackAccess';
import { mediaCredentials, recordingClipUrl } from '@/api/mediaPlayback';
import { playableFrom, type RecordingPiece } from '@/features/player/recordingTimeline';
import { useMediaBuffering } from '@/features/player/useMediaBuffering';
import { windowBlobs } from './editorMedia';
import {
  boundaryAction,
  effectiveBounds,
  MEDIA_END_MARGIN_SECONDS,
  type PlaybackBounds,
  type PlaybackError,
} from './editorPlayback';
import { createPlaybackClock, type ClockedPlayback } from './playbackClock';

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

/** 구간 앞뒤로 더 받는 여유. 카드의 미리 받기(POK-499)는 녹화가 구간 끝 + 이 여유까지 올라와야 창이 굳는다고 본다 */
export const PAD_SECONDS = 10;
const REWINDOW_DELAY_MS = 500;

/** 출입증을 새로 받고도 녹화가 403이다 */
class PlaybackForbiddenError extends Error {}

export interface Window {
  startSeconds: number;
  endSeconds: number;
  /** 구간에 녹화가 하나도 없다(틈 안이거나 녹화 끝 뒤) — 받지 않고 미리보기 실패로 알린다 */
  empty?: true;
}

export function windowFor(
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
  // 구간 자체에 녹화가 없으면(통째로 틈 안이거나 녹화 끝 뒤) 받지 않는다 — 여유 10초만 다음 녹화에 닿아도 그 녹화를 보이면
  // 고른 장면이 아닌 영상이다(PR #205 codex 2·3판)
  if (!containing || containing.fromSeconds >= range.endSeconds) {
    return { startSeconds: range.startSeconds, endSeconds: range.startSeconds, empty: true };
  }
  return {
    startSeconds: Math.max(wide.startSeconds, containing.fromSeconds),
    endSeconds: Math.min(wide.endSeconds, containing.toSeconds),
  };
}

/**
 * 녹화 재생 서버에서 창 하나를 mp4 한 덩어리로 받는다. 편집기와 카드의 미리 받기(POK-499)가 함께 쓴다.
 * 출입증을 새로 받고도 403이면 PlaybackForbiddenError다
 */
export async function fetchRecordingWindow(
  streamId: string,
  url: string,
  signal?: AbortSignal,
): Promise<Blob> {
  // 출입증이 곧 만료되면 새로 받은 뒤 부른다(POK-317). 받아 둔 것이 쓸 만하면 기다리지 않는다
  const load = () => fetch(url, { signal, credentials: mediaCredentials() });
  const access = ensureMediaAccess(streamId);
  let res = await (access === null ? load() : access.then(load));
  // 403이면 쿠키가 지워졌다(다른 탭 · 쿠키 한도 · 사용자가 지움): 출입증을 버리고 다시 받아 한 번 더 부른다(PR #332 codex)
  if (res.status === 403) {
    reportPlaybackForbidden(streamId);
    await ensureMediaAccess(streamId);
    res = await load();
  }
  // 출입증을 새로 받고도 403이면 다시 받을 길이 없다: 화면이 「출입증 거절」로 알린다
  if (res.status === 403) throw new PlaybackForbiddenError();
  if (!res.ok) throw new Error(String(res.status));
  return new Blob([await res.blob()], { type: 'video/mp4' });
}

export interface EditorVideoPlayback {
  playback: ClockedPlayback;
  /** 화면이 <video ref=…> 로 받는다. 노드는 보이지 않게 두고 프레임은 VideoSurface 가 그린다 */
  videoRef: (element: HTMLVideoElement | null) => void;
  video: HTMLVideoElement | null;
  src: string;
  /** 영상이 받은 창(편집기 초): 영상 노드의 0초가 이 창의 시작이다(POK-389). 아직 못 받았거나 받을 녹화가 없으면 null */
  window: { startSeconds: number; endSeconds: number } | null;
  /**
   * 지금 받으려는 창(편집기 초). 영상을 다 받기 전에도 있다: 트랙별 미리 듣기가 같은 창의 조각을 영상과 함께 받기 시작한다
   * (PR #341 2차 — 받은 창을 기다리면 영상 수신 + 조각 수신이 이어서 걸렸다). 녹화가 없는 구간이면 null
   */
  targetWindow: { startSeconds: number; endSeconds: number } | null;
  /** 받아 둔 조각(src)의 첫 장면이 이 훅의 시간축으로 몇 초인가. 조각 안 초 = 시간축 초 − 이 값 */
  windowStartSeconds: number;
  /** 영상이 끊겨 기다리는 중(POK-439). 첫 mp4를 내려받는 동안은 영상 노드에 소스가 없어 거짓이다 */
  buffering: boolean;
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
  const buffering = useMediaBuffering(useMemo(() => ({ current: video }), [video]));
  const [win, setWin] = useState<Window>(() => windowFor(initialRange, recordingSeconds, pieces));
  const piecesRef = useRef(pieces);
  piecesRef.current = pieces;
  const [playing, setPlaying] = useState(false);
  const [currentSeconds, setCurrentSeconds] = useState(initialRange.startSeconds);
  // 재생 중 위치는 저장소에만 흐른다: 상태로 들면 이 훅을 부르는 화면부터 전부가 프레임마다 다시 돈다
  const [clock] = useState(() => createPlaybackClock(initialRange.startSeconds));
  const [failure, setFailure] = useState<PlaybackError | null>(null);
  /** 재생 위치를 끄는 중. 멈춘 영상도 seeked마다 허브를 깨우지 않게 끌기가 끝날 때 한 번만 올린다 */
  const scrubbingRef = useRef(false);

  /** 위치를 허브까지 올린다: 멈춤 · 시크 · 구간 경계에서만. 재생 틱은 clock.publish만 부른다 */
  const settle = useCallback(
    (seconds: number) => {
      clock.publish(seconds);
      setCurrentSeconds(seconds);
    },
    [clock],
  );

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
  // src에 실제로 담긴 창. 새 창을 받는 동안은 옛 영상이 그대로라 새 창을 알리면 옛 장면을 새 시각으로 읽는다
  const [srcWindow, setSrcWindow] = useState<Window | null>(null);
  // 영상 안 초 ↔ 편집기 초는 지금 <video>에 담긴 창으로 옮긴다: 새 창을 받는 몇 초 동안에도 옛 영상이 돌아서, 목표 창(win)으로
  // 옮기면 재생 시각이 창이 움직인 만큼 어긋나고 경계 검사가 옛 영상을 엉뚱한 자리로 되감는다(PR #341 claude). 아직 아무것도
  // 못 받았으면 목표 창이다(첫 영상이 곧 그 창이다). 창을 다시 받을지는 목표 창(winRef)으로 정한다
  const loadedRef = useRef<Window>(win);
  loadedRef.current = srcWindow ?? win;
  useEffect(() => {
    if (recordingSeconds <= 0) return undefined;
    if (win.empty) {
      setFailure('no-recording');
      setSrc('');
      setSrcWindow(null);
      return undefined;
    }
    const target = win;
    setFailure(null);
    // 카드가 미리 받아 둔 창이면 다시 받지 않는다(POK-499)
    const prefetched = windowBlobs.get(remoteUrl);
    if (prefetched !== undefined) {
      setSrc(URL.createObjectURL(prefetched));
      setSrcWindow(target);
      return undefined;
    }
    const abort = new AbortController();
    fetchRecordingWindow(streamId, remoteUrl, abort.signal)
      .then((blob) => {
        setSrc(URL.createObjectURL(blob));
        setSrcWindow(target);
      })
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setFailure(e instanceof PlaybackForbiddenError ? 'access' : 'fatal');
      });
    return () => abort.abort();
  }, [remoteUrl, streamId, recordingSeconds, win]);

  // 편집기가 내려가면 영상 노드가 쥔 디코더 · 버퍼를 놓는다. 노드를 문서에서 떼는 것만으로는 GC될 때까지 남는다: src 제거만으로도
  // 모자라 load()까지 해야 풀린다(useHlsPlayback과 같다). 아래 주소 지우기보다 먼저 돌도록 그 앞에 둔다
  useEffect(() => {
    if (video === null) return undefined;
    return () => {
      video.pause();
      video.removeAttribute('src');
      video.load();
    };
  }, [video]);

  // 옛 영상 주소는 새 영상이 들어간 뒤에(또는 화면을 떠날 때) 지운다: 새 창을 받는 동안에도 <video>는 옛 주소로 틀고
  // 되감기 때문에, 받기를 시작할 때 지우면 그 사이 되감기가 브라우저에 따라 실패한다(PR #341 claude)
  useEffect(() => {
    if (src === '') return undefined;
    return () => URL.revokeObjectURL(src);
  }, [src]);

  const clampToWindow = useCallback((seconds: number) => {
    const w = loadedRef.current;
    return Math.min(w.endSeconds - 0.05, Math.max(w.startSeconds, seconds));
  }, []);

  /** 경계 판정에 쓸 구간 — 받은 영상이 구간 끝보다 먼저 끝나면 영상 끝이 구간 끝이다(POK-490) */
  const boundsNow = useCallback(
    (node: HTMLVideoElement) =>
      effectiveBounds(boundsRef.current, loadedRef.current.startSeconds + node.duration),
    [],
  );

  useEffect(() => {
    if (video === null) return undefined;
    const position = () => loadedRef.current.startSeconds + video.currentTime;
    const onLoaded = () => {
      setFailure(null);
      const target = pendingSeekRef.current;
      pendingSeekRef.current = null;
      if (target !== null)
        video.currentTime = Math.max(0, clampToWindow(target) - loadedRef.current.startSeconds);
      settle(position());
    };
    const onTime = () => {
      const now = position();
      const action = boundaryAction(now, boundsNow(video));
      if (action.kind === 'seek') {
        video.currentTime = Math.max(
          0,
          clampToWindow(action.toSeconds) - loadedRef.current.startSeconds,
        );
        settle(position());
      } else if (action.kind === 'stop' && !video.paused) {
        video.pause();
        settle(position());
      } else if (video.paused && !scrubbingRef.current) {
        settle(now);
      } else {
        // 재생 중(초당 4번쯤 온다)이나 끄는 중에는 허브를 깨우지 않는다
        clock.publish(now);
      }
    };
    const onPlay = () => setPlaying(true);
    const onPause = () => {
      setPlaying(false);
      settle(position());
    };
    // 경계 틱보다 영상 끝이 먼저 왔다(영상 끝 바로 앞의 틱을 놓침): 「구간 반복」이면 구간 시작으로 되감아 이어 튼다.
    // 전에는 멈추기만 해서, 받은 영상이 구간 끝보다 짧으면 반복을 켜 둬도 끝에서 섰다(POK-490).
    // 받은 영상이 구간 시작에도 못 닿으면 되감을 자리가 영상 밖이라 멈춘다 — 되감기가 영상 길이로 잘리고 play()가 끝난 영상을
    // 0초부터 다시 틀어 구간 밖을 끝없이 돌았다(PR #454 1차 리뷰). 「못 닿음」은 effectiveBounds와 같은 여유로 본다 — 영상이 구간
    // 시작 뒤 여유 안에서 끝나면 경계 틱은 구간을 그대로 둬 끝을 못 잡는데, 여기서 되감으면 그 몇십 ms를 끝없이 돌았다(2차 리뷰)
    const onEnded = () => {
      const mediaEnd = loadedRef.current.startSeconds + video.duration;
      const loopable = mediaEnd - MEDIA_END_MARGIN_SECONDS > boundsRef.current.startSeconds;
      if (!boundsRef.current.loop || !loopable) {
        setPlaying(false);
        settle(position());
        return;
      }
      video.currentTime = Math.max(
        0,
        clampToWindow(boundsRef.current.startSeconds) - loadedRef.current.startSeconds,
      );
      settle(position());
      video.play().catch((e: unknown) => {
        if (!(e instanceof DOMException && e.name === 'AbortError')) setFailure('fatal');
      });
    };
    const onError = () => setFailure('fatal');
    video.addEventListener('loadedmetadata', onLoaded);
    // 리스너를 달기 전에 이미 메타데이터가 와 있을 수 있다(캐시된 영상) — 그러면 처음 자리로 못 간다.
    if (video.readyState >= 1 && pendingSeekRef.current !== null) onLoaded();
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('seeked', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onEnded);
    video.addEventListener('error', onError);
    return () => {
      video.removeEventListener('loadedmetadata', onLoaded);
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('seeked', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onEnded);
      video.removeEventListener('error', onError);
    };
  }, [video, clampToWindow, boundsNow, clock, settle]);

  // timeupdate 는 초당 4번쯤이라 구간 끝을 0.25초까지 넘긴다 — 재생 중에는 프레임마다 경계를 본다.
  // 위치는 프레임마다 저장소로만 간다. 허브까지 가는 것은 되감을 때뿐이고, 멈출 때는 pause 이벤트가 올린다
  useEffect(() => {
    if (video === null || !playing) return undefined;
    let raf = 0;
    const tick = () => {
      raf = requestAnimationFrame(tick);
      const now = loadedRef.current.startSeconds + video.currentTime;
      const action = boundaryAction(now, boundsNow(video));
      if (action.kind === 'seek') {
        video.currentTime = Math.max(
          0,
          clampToWindow(action.toSeconds) - loadedRef.current.startSeconds,
        );
        settle(loadedRef.current.startSeconds + video.currentTime);
        return;
      }
      if (action.kind === 'stop') video.pause();
      clock.publish(now);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [video, playing, clampToWindow, boundsNow, clock, settle]);

  const togglePlay = useCallback(() => {
    if (video === null) return;
    if (video.paused) {
      const bounds = boundsNow(video);
      const now = loadedRef.current.startSeconds + video.currentTime;
      // 구간 끝에 서 있으면 처음부터 다시 — 끝에서 재생을 누르면 곧바로 멈춰 아무 일도 없는 것처럼 보인다
      if (now >= bounds.endSeconds - 0.05 || now < bounds.startSeconds) {
        video.currentTime = Math.max(
          0,
          clampToWindow(bounds.startSeconds) - loadedRef.current.startSeconds,
        );
        settle(loadedRef.current.startSeconds + video.currentTime);
      }
      // 누르자마자 멈추거나 새 영상으로 갈아 끼우면 play()가 AbortError로 거절된다: 보통 조작이라 오류로 알리지 않는다
      video.play().catch((e: unknown) => {
        if (!(e instanceof DOMException && e.name === 'AbortError')) setFailure('fatal');
      });
    } else {
      video.pause();
    }
  }, [video, clampToWindow, boundsNow, settle]);

  const seekTo = useCallback(
    (seconds: number) => {
      if (video === null) return;
      const target = clampToWindow(seconds);
      video.currentTime = Math.max(0, target - loadedRef.current.startSeconds);
      settle(target);
    },
    [video, clampToWindow, settle],
  );

  const seekBy = useCallback(
    (delta: number) => {
      if (video === null) return;
      seekTo(loadedRef.current.startSeconds + video.currentTime + delta);
    },
    [video, seekTo],
  );

  const scrubTo = useCallback(
    (seconds: number) => {
      if (video === null) return;
      scrubbingRef.current = true;
      const target = clampToWindow(seconds);
      video.currentTime = Math.max(0, target - loadedRef.current.startSeconds);
      clock.publish(target);
    },
    [video, clampToWindow, clock],
  );

  const endScrub = useCallback(() => {
    if (!scrubbingRef.current) return;
    scrubbingRef.current = false;
    settle(clock.getSeconds());
  }, [clock, settle]);

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
        const next = windowFor(current, recordingSeconds, piecesRef.current);
        const w = winRef.current;
        // 기다리는 사이 구간을 안쪽으로 되돌렸으면 창이 그대로다: 같은 영상을 다시 받고 구간 시작으로 튀지 않게 둔다
        if (
          next.startSeconds === w.startSeconds &&
          next.endSeconds === w.endSeconds &&
          next.empty === w.empty
        )
          return;
        pendingSeekRef.current = current.startSeconds;
        setWin(next);
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

  const playback = useMemo<ClockedPlayback>(
    () => ({
      playing,
      currentSeconds,
      clock,
      durationSeconds: recordingSeconds,
      error: failure,
      togglePlay,
      seekTo,
      seekBy,
      scrubTo,
      endScrub,
      setRate,
      setBounds,
    }),
    [
      playing,
      currentSeconds,
      clock,
      recordingSeconds,
      failure,
      togglePlay,
      seekTo,
      seekBy,
      scrubTo,
      endScrub,
      setRate,
      setBounds,
    ],
  );

  const loadedWindow = useMemo(
    () =>
      srcWindow === null || recordingSeconds <= 0
        ? null
        : { startSeconds: srcWindow.startSeconds, endSeconds: srcWindow.endSeconds },
    [srcWindow, recordingSeconds],
  );
  const targetWindow = useMemo(
    () =>
      win.empty || recordingSeconds <= 0
        ? null
        : { startSeconds: win.startSeconds, endSeconds: win.endSeconds },
    [win, recordingSeconds],
  );

  return {
    playback,
    videoRef: setVideo,
    video,
    src,
    window: loadedWindow,
    targetWindow,
    windowStartSeconds: srcWindow?.startSeconds ?? win.startSeconds,
    buffering,
  };
}
