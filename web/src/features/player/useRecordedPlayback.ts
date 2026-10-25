'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { recordingClipUrl } from '@/api/mediaPlayback';
import { recoverForbiddenRecording } from '@/api/playbackAccess';
import { AT_EDGE_THRESHOLD_SECONDS, behindFromSeekFraction } from './playerMath';
import { trackPlaybackStartOnce } from './playbackStarts';
import { useControlsAutoHide } from './useControlsAutoHide';
import { PLAYER_QUALITIES, type PlayerQuality, type PlayerSimulation } from './usePlayerSimulation';
import { playableBackFrom, playableFrom, type RecordingTimeline } from './recordingTimeline';

// 끝난 방송 다시보기(POK-251) — 녹화 재생 서버에서 「지금 자리부터 끝까지」를 한 줄기로 받아 튼다.
// 그 응답은 만들어 가며 흘려보내는 영상이라 브라우저가 건너뛰지 못한다(seekable 0~0, 실측). 그래서
// 「이동」은 주소를 새 시작점으로 갈아 끼우는 것이다 — 시크바·카드 클릭·화살표가 전부 그 한 길을 탄다.
//
// 라이브 플레이어와 같은 계약(PlayerSimulation)을 채운다: 끝(방송 종료 시점)이 「엣지」이고
// behindSeconds = 끝에서 얼마나 앞인가, windowSeconds = 녹화 처음부터 끝까지의 길이(축의 0초부터가 아니다, POK-474).
//
// 녹화가 구간 여럿이면(송출 끊김·녹화기 재시작) 한 번에 한 구간만 달라 한다 — 재생 서버는 틈을 건너 주지 않는다
// (recordingTimeline). 한 구간이 끝나면 다음 구간을 이어 틀고, 틈 안을 누르면 다음 구간 시작으로 간다(POK-253).

export type RecordedSource = RecordingTimeline & {
  streamId: string;
  /**
   * 재생기의 0초가 서버 기준점(카드·조각 ms의 0초, POK-255)인가. 기준점을 몰라 녹화 시작에 세웠으면 거짓이다: 그 자리를 카드
   * 위치로 보내면 두 시작의 차만큼 어긋난다(POK-392 수동 마킹이 이 값 하나로 막는다)
   */
  originAxis?: boolean;
};

const SEEK_DEBOUNCE_MS = 250;

/** 녹화 안으로 자른 자리(초). 끝은 마지막 1초 앞이다 */
function clampToRecording(total: number, seconds: number): number {
  return Math.min(Math.max(0, total - 1), Math.max(0, seconds));
}

/** 줄기 주소의 시작 자리(녹화 시작부터 ms). 주소와 「같은 자리인가」 판정이 같은 반올림을 써야 둘이 갈라지지 않는다 */
function pieceOffsetMs(seconds: number): number {
  return Math.round(seconds * 1000);
}

/** 앞으로 가는 이동의 도착점: 녹화 안으로 자르고, 틈 안이면 다음 구간 시작이다(재생 서버는 틈을 건너 주지 않는다) */
function forwardTarget(recorded: RecordingTimeline, seconds: number): number {
  const wanted = clampToRecording(recorded.durationSeconds, seconds);
  return playableFrom(recorded, wanted)?.fromSeconds ?? wanted;
}

/**
 * @param startAtSeconds 처음 달라는 줄기의 자리(방송 기준 초). 마운트 때 한 번만 읽는다. 녹화가 서기 전에 고른 시점(홈에서 고른
 *   카드 등)은 옮길 플레이어가 없었으므로 여기로 받아 첫 줄기부터 그 자리다(QA 2026-10-11). 선 다음의 이동은 컨트롤러로 한다.
 *   0이면 첫 구간 시작이다: 서버 기준점 축이면 녹화가 몇 초 뒤에 서는데 자리를 0초로 알리면 지난 방송 채팅이 영상보다 앞 시각에
 *   섰다(리뷰 3라운드)
 * @param onSeek 사람이 옮겼다(시크바·키(화살표·PageUp·PageDown·Home)·끝으로·카드·다 본 뒤 처음부터). 재생으로 흐르거나 녹화 틈을 넘을 때는 부르지 않는다:
 *   녹화가 옮긴 자리는 지난 방송 채팅이 이것으로만 따라가 맨 아래로 간다(POK-324)
 * @param broadcastId 방송 번호(회차) — 사용 통계 「방송마다 한 번」의 기억 키(playbackStarts.ts). 없으면 세지 않는다
 */
export function useRecordedPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  recorded: RecordedSource,
  startAtSeconds = 0,
  onSeek?: () => void,
  broadcastId?: string,
): PlayerSimulation & { positionSeconds: number } {
  const total = recorded.durationSeconds;
  // 시크바 왼쪽 끝 = 녹화 처음(첫 구간 시작, 0초 앞은 못 간다). 축의 0초(서버 기준점)는 녹화보다 한참 앞일 수 있다: 송출 회차가 방송
  // 여럿을 넘어 이어지면 조각의 pts가 몇 시간이라 기준점이 그만큼 앞에 선다(로컬 실측 13.9시간). 0초부터 그리면 10분 녹화가
  // 시크바 오른쪽 끝에 몰려 손잡이가 끝에 붙고, 끌어 놓은 자리가 녹화 앞 빈 곳이라 첫 구간 시작으로 올려져 끝으로 되돌아갔다(POK-474)
  const head = Math.max(0, recorded.pieces[0]!.fromSeconds);
  const span = Math.max(0, total - head);
  const startAt = () => forwardTarget(recorded, startAtSeconds);
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  const volumeRef = useRef(70);
  volumeRef.current = volume;
  /** 가고 싶은 자리(방송 기준 초). 실제 줄기는 여기서 틀 수 있는 구간부터다 */
  const [offset, setOffset] = useState(startAt);
  const [position, setPosition] = useState(startAt);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  /** 다시 붙이지 못한 줄기의 주소. 줄기가 바뀌면(옮김·다음 구간) 알림이 저절로 사라진다 */
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
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
  /**
   * 옮기는 중이면 그 도착점(방송 기준 초), 아니면 null. 옮긴 자리의 줄기가 붙기 전에는 옛 줄기가 흐르거나 끝나도 그 시각으로 자리를
   * 되돌리지 않는다: 줄기를 갈아 끼우기 전 250ms 동안 시크바가 옛 자리로 튀었고, 이 자리를 따라가는 지난 방송 채팅도 옛 시각으로
   * 돌아갔다(POK-324)
   */
  const pendingRef = useRef<number | null>(null);
  /** 붙어 있는 줄기의 0초(방송 기준 초). 줄기를 붙일 때 정한다 */
  const baseRef = useRef(0);
  const recordedRef = useRef(recorded);
  recordedRef.current = recorded;
  const onSeekRef = useRef(onSeek);
  onSeekRef.current = onSeek;
  const broadcastIdRef = useRef(broadcastId);
  broadcastIdRef.current = broadcastId;

  const src = useMemo(
    () =>
      recordingClipUrl(
        recorded.streamId,
        recorded.startMs + pieceOffsetMs(piece.fromSeconds),
        Math.max(1, piece.toSeconds - piece.fromSeconds),
      ),
    [recorded.streamId, recorded.startMs, piece],
  );

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return undefined;
    // 이 줄기의 0초. 붙일 때 정한다: 렌더 중에 바뀐 offsetRef를 옛 줄기의 timeupdate가 읽으면 엉뚱한 자리가 섰다
    const base = offsetRef.current;
    baseRef.current = base;
    // 옮긴 자리의 줄기가 붙었다
    pendingRef.current = null;
    video.src = src;
    video.muted = muted;
    // 70%로 보이는데 노드는 기본 100%였다(POK-253) — 줄기를 갈아 끼울 때마다 보이는 값을 싣는다
    video.volume = volumeRef.current / 100;
    if (wantPlayRef.current) {
      void video.play().catch(() => {
        /* 자동재생 거부 — 재생 버튼이 남는다 */
      });
    }
    const onTime = () => {
      if (pendingRef.current === null) setPosition(base + video.currentTime);
    };
    const onPlay = () => {
      wantPlayRef.current = true;
      setPlaying(true);
    };
    // 그 방송을 처음 틀었을 때 한 번만 센다 — 줄기를 갈아 끼우는 구간 넘김·옮김은 세지 않는다 (POK-357).
    // 키는 방송 번호다: recorded.streamId는 물리 키라 한 스트리머의 방송이 모두 같다(PR #382 2차 리뷰).
    // play가 아니라 playing에서 센다 — play는 play()를 부른 순간 나가, 녹화 줄기를 못 받아 화면이 안 나와도 셌다(PR #382 3차 리뷰)
    const onPlaying = () => {
      const id = broadcastIdRef.current;
      if (id) trackPlaybackStartOnce(id, 'vod');
    };
    const onPause = () => {
      // 끝나서 멈춘 것은 사용자가 멈춘 것이 아니다 — 다음 구간을 이어 틀지는 ended가 정한다
      if (!video.ended) wantPlayRef.current = false;
      setPlaying(false);
    };
    const onEnded = () => {
      // 옮기는 중에 옛 줄기가 끝났다: 이어 틀 구간은 도착점의 줄기가 정한다. 여기서 다음 구간을 붙이면 자리가 옮긴 곳 → 다음 구간
      // → 옮긴 곳으로 오갔다(리뷰 2라운드)
      if (pendingRef.current !== null) return;
      const next = playableFrom(recordedRef.current, pieceEndRef.current);
      if (next === null) {
        wantPlayRef.current = false;
        return;
      }
      setPosition(next.fromSeconds);
      setOffset(next.fromSeconds);
    };
    // 줄기 오류가 쿠키 지워짐(403)이면 출입증을 다시 받아 같은 줄기를 다시 붙인다(PR #332 codex). <video>는 응답 코드를 못 읽어
    // 확인은 playbackAccess가 첫 바이트를 물어 한다. 줄기마다 한 번만: 다시 받아도 403이면 끝없이 돌지 않는다.
    // 다시 붙일 수 없으면(403이 아님, 또는 다시 붙인 줄기도 오류) 영상 위에 알린다: 전에는 마지막 장면에서 조용히 멎었다(POK-337)
    let recovering = false;
    let gone = false;
    const onError = () => {
      if (gone) return;
      if (recovering) {
        setFailedSrc(src);
        return;
      }
      recovering = true;
      void recoverForbiddenRecording(src, recordedRef.current.streamId).then((retry) => {
        if (gone) return;
        if (!retry) {
          setFailedSrc(src);
          return;
        }
        video.src = src;
        video.load();
        if (wantPlayRef.current)
          void video.play().catch(() => {
            /* 자동재생 거부: 재생 버튼이 남는다 */
          });
      });
    };
    video.addEventListener('timeupdate', onTime);
    video.addEventListener('play', onPlay);
    video.addEventListener('playing', onPlaying);
    video.addEventListener('pause', onPause);
    video.addEventListener('ended', onEnded);
    video.addEventListener('error', onError);
    return () => {
      gone = true;
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('ended', onEnded);
      video.removeEventListener('error', onError);
    };
    // muted 는 아래 토글이 노드에 직접 쓴다 — 여기 의존성에 넣으면 음소거를 풀 때마다 영상이 처음부터 다시 온다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, videoRef]);

  const jumpTo = useCallback(
    (seconds: number, backward = false) => {
      // 틈 안이면 다음 구간 시작으로(뒤로 가기면 앞 구간 끝으로) — 시크바도 실제로 틀 자리를 보인다
      const target = backward
        ? playableBackFrom(recordedRef.current, clampToRecording(total, seconds))
        : forwardTarget(recordedRef.current, seconds);
      pendingRef.current = target;
      setPosition(target);
      onSeekRef.current?.();
      if (seekTimer.current !== null) window.clearTimeout(seekTimer.current);
      // 시크바를 끄는 동안 매 좌표마다 새 줄기를 받지 않는다. 언마운트 때 이 타이머를 지우지 않는다: 진짜 언마운트면 걸려도
      // 아무 일도 없고(언마운트된 부품의 setOffset은 버려지고 영상 노드도 떨어져 있다), 개발 서버(StrictMode)가 새로 선 부품을
      // 껐다 켤 때 선 직후에 건 이동을 잃지 않는다(QA 2026-10-11)
      seekTimer.current = window.setTimeout(() => {
        seekTimer.current = null;
        // 새 줄기가 붙을 때(위 효과) 옮기기가 끝난다. 도착점은 틀 수 있는 자리라 줄기 주소가 반드시 바뀐다(playableFrom)
        if (pieceOffsetMs(target) !== pieceOffsetMs(offsetRef.current)) {
          setOffset(target);
          return;
        }
        // 지금 줄기가 이미 그 자리에서 시작하면 주소가 같아 다시 받지 않으므로 줄기 처음으로 되감는다: 같은 카드를 다시
        // 눌렀는데 영상이 그대로 흐르던 결함(QA 2026-10-11 리뷰). 같은 자리인지는 주소가 쓰는 ms로 가른다: 1ms 안쪽 차이를
        // 다른 자리로 보면 같은 주소라 다시 받지도 되감지도 않는다(리뷰 2라운드)
        pendingRef.current = null;
        const video = videoRef.current;
        if (video === null) return;
        video.currentTime = 0;
        if (wantPlayRef.current) void video.play().catch(() => undefined);
      }, SEEK_DEBOUNCE_MS);
    },
    [total, videoRef],
  );

  /**
   * 지금 자리(방송 기준 초): 옮기는 중이면 그 도착점, 아니면 붙어 있는 줄기의 0초 + 영상 노드 시각(timeupdate와 같은 기준).
   * 화살표가 옛 줄기 시각에서 세면 250ms 안의 두 번째 누름이 첫 번째를 덮었다(리뷰 1라운드)
   */
  const now = useCallback(
    () => pendingRef.current ?? baseRef.current + (videoRef.current?.currentTime ?? 0),
    [videoRef],
  );

  const behindSeconds = Math.max(0, Math.round(total - position));

  return {
    playing,
    muted,
    volume,
    /** 지금 자리(방송 기준 초). 옮기면 줄기가 붙기 전부터 옮긴 자리다 */
    positionSeconds: position,
    // 초로 내린 positionSeconds가 아니라 영상 노드의 정확한 자리다(수동 마킹이 ms로 보낸다, POK-392)
    currentPositionSeconds: now,
    behindSeconds,
    atEdge: behindSeconds < AT_EDGE_THRESHOLD_SECONDS,
    windowSeconds: span,
    uptimeSeconds: total,
    quality,
    lowLatency: false,
    settingsMenu: false,
    replay: true,
    playbackError: failedSrc === src ? '녹화 영상을 불러오지 못했어요' : null,
    clipMarked: false,
    controlsVisible,
    togglePlay: useCallback(() => {
      const video = videoRef.current;
      if (video === null) return;
      // 옮기는 중이면 옮긴 자리의 줄기를 틀지 말지만 정한다(줄기를 붙이는 효과가 그대로 따른다). 끝난 옛 줄기의 ended를 보고
      // 처음부터로 덮으면 걸린 이동이 옮긴 자리로 다시 돌려 두 번 옮겼다(리뷰 3라운드)
      if (pendingRef.current !== null) {
        if (video.paused) wantPlayRef.current = true;
        else {
          wantPlayRef.current = false;
          video.pause();
        }
        return;
      }
      // 다 본 뒤 다시 누르면 처음부터다: 그대로 틀면 브라우저가 지금 줄기(마지막 구간)만 다시 튼다(PR #205 codex).
      // 「처음」은 0초가 아니라 첫 구간 시작이다(서버 기준점 축이면 녹화가 몇 초 뒤에 선다). 이미 첫 구간이면 브라우저가 다시 튼다.
      // 어느 쪽이든 사람이 처음으로 옮긴 것이다: 구간이 하나면 알리지 않아 지난 방송 채팅이 처음을 따라가지 않았다(리뷰 2라운드)
      if (video.ended) {
        onSeekRef.current?.();
        const first = playableFrom(recordedRef.current, 0)?.fromSeconds ?? 0;
        if (offsetRef.current > first) {
          wantPlayRef.current = true;
          setPosition(first);
          setOffset(first);
          return;
        }
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
      (fraction: number) => jumpTo(total - behindFromSeekFraction(fraction, span)),
      [jumpTo, total, span],
    ),
    // 음수는 엣지(끝) 방향 — 라이브 플레이어와 같은 부호
    seekBy: useCallback((delta: number) => jumpTo(now() - delta, delta > 0), [jumpTo, now]),
    // 카드는 그 자리로 바로 간다: 시크바 비율을 거치면 시차를 초 단위로 반올림해 카드보다 1초 안쪽 앞에 섰다(리뷰 1라운드)
    seekToPosition: useCallback((seconds: number) => jumpTo(seconds), [jumpTo]),
    // 지난 방송의 「실시간」 쪽 끝은 녹화 끝이다 — 시크바 오른쪽 끝·「실시간」 표기와 같은 자리(POK-253). 전에는 처음으로 갔다
    returnToLive: useCallback(() => jumpTo(total), [jumpTo, total]),
    setQuality,
    toggleLowLatency: useCallback(() => undefined, []),
    markClip: useCallback(() => undefined, []),
    wake,
    sleep,
  };
}
