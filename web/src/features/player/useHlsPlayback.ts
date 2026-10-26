'use client';

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type Hls from 'hls.js';
import type { LevelDetails } from 'hls.js';
import {
  behindFromCurrentTime,
  dvrRange,
  liveEdgePosition,
  mediaTimeAt,
  programDateAt,
  rewindWindowSeconds,
} from './dvrWindow';
import {
  isPlaybackForbidden,
  recoverForbiddenRecording,
  reportPlaybackForbidden,
} from '@/api/playbackAccess';
import { HLS_LIVE_CONFIG, HLS_REWIND_CONFIG } from './hlsConfig';
import { isPlaylistLoadError } from './hlsErrors';
import { loadHls } from './hlsLoader';
import { behindFromSeekFraction, isAtEdge } from './playerMath';
import {
  endStalled,
  loadedList,
  sessionAxis,
  startLoadPosition,
  type LoadedList,
  type SessionAxis,
} from './playbackEdge';
import { nextLivePosition, type LivePosition } from './livePosition';
import { trackPlaybackStartOnce } from './playbackStarts';
import { guardRewindFloor } from './rewindFloor';
import { SESSION_NOTICES, type PlaybackSource, type SeekView } from './rewindSwitch';
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
// jsdom엔 미디어 구현이 없어 이 훅만 따로 시험하지 않는다: src 없는 시험은 시뮬레이션 경로를 탄다. 라이브 화면 시험이
// hls.js를 흉내 내 재생기의 자리(livePosition)·실제 시각으로 옮기기(seekToDate)·사람이 옮김(onSeek)·멎은 까닭(playbackError)을
// 잰다(POK-326 · POK-337).

/** 재생이 멎은 까닭별 알림(POK-337) */
const STOPPED_NOTES = {
  // 출입증을 버렸으니 라이브 화면이 1분 안에 다시 받고 재생기를 새로 세운다(LiveScreen의 accessRecoveries)
  forbidden: '영상 출입증이 끝나 다시 받고 있어요',
  broken: '영상이 끊겼어요. 새로고침하면 다시 연결해요',
  // 다시 받고 새로 선 재생기도 403이다: 서명 설정 문제라 더 받아도 같다(계약3 재시도 정책, POK-419)
  denied: '영상에 접근할 수 없어요. 새로고침해 주세요',
} as const;

/** 재생 위치에 영상 자료가 있다(HTMLMediaElement.HAVE_CURRENT_DATA) */
const HAVE_CURRENT_DATA = 2;
/** 라이브 지연을 아직 못 쟀을 때 되감기 재생기의 라이브 끝 = 지금 − 이 값(설계 5.2절) */
const DEFAULT_LIVE_LAG_MS = 3_000;
/** 창 하한으로 옮긴 뒤 이 배수 × TD 안에 또 옮기면 알리지 않는다(숨은 탭의 밀린 갱신이 한꺼번에 올 때 — 5.3.1절) */
const FLOOR_NOTICE_QUIET_TD = 2;

/** 재생기가 로드한 목록 하나(세션의 observedEndMs · roundRewindUrls 재료) */
export interface LoadedPlaylist extends LoadedList {
  url: string;
}

/**
 * 재생 세션(POK-200 M5)이 재생기에 거는 고리. 없으면 지금 동작이다(라이브 창 안 시크만). onSeekRequest 가 있으면 「세션
 * 모드」다: 사람이 옮긴 목표를 재생기가 스스로 쓰지 않고 세션에 넘긴다 — 창 밖이면 세션이 되감기로 갈아 끼운다(설계 5.3.1절)
 */
export interface PlaybackHooks {
  /** LEVEL_UPDATED 의 live:false — 「이 목록에 조각이 더 붙지 않는다」일 뿐 재생 완료가 아니다(RFC 8216 4.3.3.4) */
  onEndlist?: () => void;
  /** <video> ended 또는 목록 끝 정체(D9) */
  onEndReached?: () => void;
  /** 라이브 목록 치명 404 */
  onLiveClosed?: () => void;
  /** 라이브 목록 치명 400(송출 재시작 뒤 옛 _HLS_msn — hls.js 는 4xx 를 다시 해 보지 않는다) */
  onLive400?: () => void;
  onListLoaded?: (list: LoadedPlaylist) => void;
  /** 새 재생기의 첫 재생 성공 */
  onPlaying?: () => void;
  onSeekRequest?: (targetMs: number, view: SeekView) => void;
  onLiveButton?: () => void;
  /** 세션 알림(되감기 재생점이 창 밖으로 떨어지기 전에 가장 오래된 지점으로 옮김 — 5.3.1절) */
  onNotice?: (text: string) => void;
  /**
   * 재생 단추를 막는다 — 끝에 닿아 덮개가 선 동안. 끝난 목록에 play() 를 부르면 처음부터 돌아 덮개 아래에서 세션 모르게
   * 자리가 바뀐다. 덮개 아래에서 재생을 다시 여는 길은 시크(세션이 atEnd 를 내린다)와 덮개 버튼이다(설계 5.3절)
   */
  holdPlay?: boolean;
  /** 시크바 왼쪽 끝(되감기 범위 첫 시각). 라이브 재생기의 시크바가 라이브 창 밖까지 넓어진다 */
  axisLowerMs?: number | null;
}

export type { LivePosition } from './livePosition';

/**
 * @param onSeek 사람이 옮겼다(시크바·키·실시간으로·카드). 재생으로 흐를 때는 부르지 않는다: 라이브 채팅이 맨 아래(되감은 자리,
 *   실시간이면 최신)로 간다(POK-326)
 * @param mediaKey 영상 키(출입증 쿠키의 경로). 영상 요청이 403이면 그 키의 출입증을 버린다(POK-410). 없으면 버리지 않는다
 * @param afterForbidden 이 재생기는 403으로 출입증을 다시 받은 뒤 새로 섰다. 영상 조각을 하나도 못 받은 채 또 403이면 더 받지 않고
 *   멈춘다(계약3 재시도 정책: 재호출 1회 뒤에도 403이면 접근 불가, POK-419). 한 번이라도 받았으면 회복한 것이라 다음 403은 다시 받는다
 * @param broadcastId 방송 번호(회차) — 사용 통계 「방송마다 한 번」의 기억 키(playbackStarts.ts). 없으면 세지 않는다
 * @param source 재생할 목록(종류 · 주소 · 시작 시각 · 세대). 세대가 오르면 같은 주소여도 재생기를 새로 세운다(설계 5.4절)
 * @param hooks 재생 세션의 고리(PlaybackHooks). 없으면 지금 동작이다
 */
export function useHlsPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  source: PlaybackSource,
  options: PlayerSimulationOptions = {},
  onSeek?: () => void,
  mediaKey?: string,
  afterForbidden = false,
  broadcastId?: string,
  hooks: PlaybackHooks = {},
): PlayerSimulation & {
  livePosition: LivePosition | null;
  /** 그 실제 시각으로 옮긴다(세션의 「안에서 시크」) */
  seekToMs: (atMs: number) => void;
  /** 라이브 끝으로 옮긴다(세션의 라이브 국면 LIVE) */
  seekToLiveEdge: () => void;
} {
  // playing은 video 이벤트로만 바꾼다 — 자동재생이 거부되면 false로 남아 재생 버튼이 노출된다
  const [playing, setPlaying] = useState(false);
  // 자동재생 정책상 muted로 시작한다 (시뮬레이션의 muted=false와 다른 점)
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  const [behindSeconds, setBehind] = useState(0);
  // 매니페스트 로드 전엔 되감을 곳이 없다 — 0이면 시크바가 엣지에 붙는다 (POK-32)
  const [windowSeconds, setWindowSeconds] = useState(0);
  const [uptimeSeconds, setUptime] = useState(options.initialUptimeSeconds ?? 0);
  // 재생기의 자리(재생 위치·실시간 끝의 실제 시각, 시차). 라이브 채팅이 되감은 영상의 시각을 따른다(POK-326). 목록에 시각
  // 표시가 없거나 아직 모르면 null. 셋을 한 순간에 같이 잰다(paint): 시크는 시차를 바로 바꾸는데 시각은 다음 paint에 바뀌어,
  // 따로 알리면 새 시차에 옛 시각이 붙어 실시간 끝을 시크 폭만큼 늦게 셈했다(채팅이 아직 표에 없는 구간까지 「다 읽음」으로 뒀다)
  const [livePosition, setLivePosition] = useState<LivePosition | null>(null);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const [lowLatency, setLowLatency] = useState(true);
  const [clipMarked, setClipMarked] = useState(false);
  /** 복구를 포기한 주소와 까닭. 주소가 바뀌면 알림이 저절로 사라진다 */
  const [stopped, setStopped] = useState<{ src: string; why: keyof typeof STOPPED_NOTES } | null>(
    null,
  );
  // 키가 늦게 와도 재생기를 다시 세우지 않는다(효과는 src에만 걸린다): 오류가 난 그때의 키를 읽는다
  const mediaKeyRef = useRef(mediaKey);
  mediaKeyRef.current = mediaKey;
  const afterForbiddenRef = useRef(afterForbidden);
  afterForbiddenRef.current = afterForbidden;
  const broadcastIdRef = useRef(broadcastId);
  broadcastIdRef.current = broadcastId;
  const hooksRef = useRef(hooks);
  hooksRef.current = hooks;
  const sourceRef = useRef(source);
  sourceRef.current = source;
  const src = source.url;
  // 세션 모드의 창 가장자리 상태(5.3.1절). 재생기를 갈아 끼워도 남는다: 라이브 지연은 되감기 재생기의 라이브 끝 재료다
  const lagMsRef = useRef(DEFAULT_LIVE_LAG_MS);
  /** 일시정지한 자리의 실제 시각. 재개 판정은 currentTime 이 아니라 이 값으로 한다 */
  const pausedAtMsRef = useRef<number | null>(null);
  /** 멈춘 자리가 창 하한에 닿아 세션에 넘겼다(멈춤 하나에 한 번) */
  const pausedHandledRef = useRef(false);
  /** 멈춘 채 갈아 끼운 재생기는 스스로 틀지 않는다 */
  const keepPausedRef = useRef(false);
  /**
   * 세션 모드인가 — 세션 고리가 있고 hls.js 재생기가 섰다. hls.js 가 없는 브라우저(네이티브 HLS)는 조각 목록 · 시각 축이 없어
   * 세션 고리가 있어도 지금처럼 라이브 창 안에서 시크한다(seekable 축)
   */
  const sessionActive = useCallback(
    () => hooksRef.current.onSeekRequest !== undefined && hlsRef.current !== null,
    [],
  );
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);

  const hlsRef = useRef<Hls | null>(null);
  const clipTimer = useRef<number>(undefined);

  // 방송 경과는 스트림 메타데이터에 없다 — 시뮬레이션처럼 시드+티커로 표시만 유지한다.
  // 방송 상태 API가 생기면 그 티켓에서 교체하며, 그때 아래 src 전환 초기화에 uptime도
  // 넣어야 한다 — 지금은 시드가 스트림과 무관한 단일 상수라 안 넣어도 티가 안 난다.
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

  /** 세션 모드의 시각 축(설계 5.2절). 목록 · 라이브 끝을 모르면 null */
  const readAxis = useCallback((): SessionAxis | null => {
    const video = videoRef.current;
    const details = hlsRef.current?.latestLevelDetails;
    if (!video || !details) return null;
    const kind = sourceRef.current.kind;
    const range = dvrRange(video.seekable);
    const sync = hlsRef.current?.liveSyncPosition ?? null;
    const axis = sessionAxis({
      kind,
      fragments: details.fragments,
      targetDurationS: details.targetduration,
      currentTime: video.currentTime,
      liveEdgeTime: kind === 'live' && range ? liveEdgePosition(range, sync) : null,
      nowMs: Date.now(),
      lagMs: lagMsRef.current,
      axisLowerMs: hooksRef.current.axisLowerMs ?? null,
    });
    if (axis !== null && kind === 'live') lagMsRef.current = Math.max(0, Date.now() - axis.edgeMs);
    return axis;
  }, [videoRef]);

  /** 세션 모드: 목표 실제 시각을 세션에 넘긴다. 축을 모르면 false */
  const requestSeek = useCallback(
    (targetMs: number): boolean => {
      const axis = readAxis();
      const ask = hooksRef.current.onSeekRequest;
      if (axis === null || ask === undefined) return false;
      ask(targetMs, axis.view);
      return true;
    },
    [readAxis],
  );

  // 유일한 시크 경로 (계약3 4절 2번) — 마우스·키보드·LIVE 복귀가 전부 이 함수를 탄다.
  // 라이브 재동기화를 호출하지 않는다. 세션 모드면 목표 시각을 세션에 넘긴다(currentTime 을 쓰기 전에 판정 — 5.3.1절 1번)
  const seekToBehind = useCallback(
    (behind: number) => {
      const video = videoRef.current;
      if (!video) return;
      if (sessionActive()) {
        const axis = readAxis();
        if (axis !== null) requestSeek(axis.edgeMs - Math.max(0, behind) * 1000);
        return;
      }
      const range = dvrRange(video.seekable);
      if (!range) return;
      // 되감기 기준점은 엣지와 같은 지점이어야 한다 — range.end 기준으로 시크하고
      // liveEdgePosition 기준으로 시차를 재면 둘이 그 차이만큼 어긋난다.
      const sync = hlsRef.current?.liveSyncPosition ?? null;
      const live = liveEdgePosition(range, sync);
      // 상한은 시크바 좌측 끝과 같은 함수에서 온다 — 어긋나면 눌러도 안 가는 영역이 생긴다
      const clamped = Math.min(rewindWindowSeconds(range, sync), Math.max(0, behind));
      if (isAtEdge(clamped)) {
        // 창이 라이브 지연폭보다 짧으면(방송 시작 직후) live가 range.start로 붕괴한다 —
        // 스냅할 지점이 없는데 시크하면 재생 중인 위치에서 뒤로 끌려간다. 그땐 두고 본다.
        if (live > range.start) video.currentTime = live;
        setBehind(0);
      } else {
        video.currentTime = Math.max(range.start, live - clamped);
        setBehind(Math.round(clamped));
      }
      onSeek?.();
    },
    [videoRef, onSeek, readAxis, requestSeek, sessionActive],
  );

  const seekToFraction = useCallback(
    (fraction: number) => seekToBehind(behindFromSeekFraction(fraction, windowSeconds)),
    [seekToBehind, windowSeconds],
  );

  const seekBy = useCallback(
    (deltaSeconds: number) => {
      const video = videoRef.current;
      if (!video) return;
      if (sessionActive()) {
        const axis = readAxis();
        if (axis !== null) seekToBehind(axis.behindSeconds + deltaSeconds);
        return;
      }
      const range = dvrRange(video.seekable);
      if (!range) return;
      seekToBehind(
        behindFromCurrentTime(range, video.currentTime, hlsRef.current?.liveSyncPosition ?? null) +
          deltaSeconds,
      );
    },
    [videoRef, seekToBehind, readAxis, sessionActive],
  );

  // 실제 시각으로 옮긴다(라이브 카드, POK-326). 그 시각을 품은 조각의 시각 표시로 영상 시각을 셈하고, 시크는 같은 한 길
  // (seekToBehind)을 탄다: 되감기 창 밖은 가장 오래된 지점으로 자른다. 방송 경과 시계로 옮기면 숨은 탭에서 그 시계의 타이머가
  // 늦춰져 카드보다 몇 분 늦은 자리(심하면 실시간 끝)로 갔다(리뷰 1라운드). 시각 표시가 없으면 false: 부르는 쪽이 경과 시계로 옮긴다
  const seekToDate = useCallback(
    (epochMs: number): boolean => {
      const video = videoRef.current;
      if (!video) return false;
      if (sessionActive()) return requestSeek(epochMs);
      const range = dvrRange(video.seekable);
      const target = mediaTimeAt(hlsRef.current?.latestLevelDetails?.fragments ?? [], epochMs);
      if (!range || target === null) return false;
      const live = liveEdgePosition(range, hlsRef.current?.liveSyncPosition ?? null);
      seekToBehind(live - target);
      return true;
    },
    [videoRef, seekToBehind, requestSeek, sessionActive],
  );

  const seekToMs = useCallback(
    (atMs: number) => {
      const video = videoRef.current;
      const fragments = hlsRef.current?.latestLevelDetails?.fragments ?? [];
      if (!video) return;
      const axis = readAxis();
      const range = dvrRange(video.seekable);
      let target = mediaTimeAt(fragments, atMs);
      if (target === null) return;
      // 라이브 끝보다 뒤는 라이브 끝이다: 부분 조각을 기다리며 멎지 않게
      if (sourceRef.current.kind === 'live' && axis !== null && range && atMs >= axis.edgeMs) {
        target = liveEdgePosition(range, hlsRef.current?.liveSyncPosition ?? null);
      }
      video.currentTime = target;
      if (video.paused) {
        pausedAtMsRef.current = atMs;
        pausedHandledRef.current = false;
      }
      onSeek?.();
    },
    [videoRef, readAxis, onSeek],
  );

  const seekToLiveEdge = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    const range = dvrRange(video.seekable);
    if (range) {
      video.currentTime = liveEdgePosition(range, hlsRef.current?.liveSyncPosition ?? null);
    }
    pausedAtMsRef.current = null;
    video.play().catch(() => {});
    onSeek?.();
  }, [videoRef, onSeek]);

  // 라이브 복귀는 이 액션(LIVE 버튼)뿐이다 — 플레이어가 스스로 복귀하는 경로는 없다 (계약3 4절 3번)
  // 세션 모드면 세션이 정한다(라이브 국면은 라이브 끝으로 · 되감기는 지금 방송 번호를 대조해 라이브로 — 설계 5.3절)
  const returnToLive = useCallback(() => {
    const onLiveButton = hooksRef.current.onLiveButton;
    if (onLiveButton) {
      onLiveButton();
      return;
    }
    seekToBehind(0);
    videoRef.current?.play().catch(() => {});
  }, [seekToBehind, videoRef]);

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    // 재개 시 currentTime을 만지지 않는다 — 되감은 위치를 그대로 이어서 튼다 (계약3 4절 1번)
    if (video.paused && hooksRef.current.holdPlay) {
      wake();
      return;
    }
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
    // 세대가 오를 때 다시 서는 재생기 하나의 소스(종류 · 시작 시각). 효과는 종류 · 주소 · 세대에만 걸린다(설계 5.4절)
    const { kind, startAtMs } = sourceRef.current;

    // StrictMode 이중 이펙트·동적 import 경합 방어 — 클린업 후 도착한 인스턴스를 버린다
    let disposed = false;
    // 재생기 하나의 사건 상태: 시작 위치를 한 번 정했나 · ENDLIST 를 올렸나 · 목록이 마지막으로 자란 때 · 끝 도달을 올렸나
    let startPlaced = false;
    let endlistSent = false;
    let lastEndMs: number | null = null;
    let lastGrowAt = Date.now();
    let endSent = false;
    let waiting = false;
    // 되감기 창 하한 지키기(5.3.1절)의 상태: 기동 전 받기 위치(startLoad 에 준 자리 · 기동 전 시크 자리 · 떼기 전 마지막
    // 재생점 — 기동이 끝나면 버린다) · 재생 중 마지막 0 아닌 재생점 · 옮겨 알린 때
    let loadFrom: number | null = null;
    let lastPlayhead: number | null = null;
    let floorNoticeAt: number | null = null;
    const reachEnd = () => {
      if (endSent) return;
      endSent = true;
      hooksRef.current.onEndReached?.();
    };
    // 끝에서 물러난 시크는 폭과 무관하게 끝 도달 판정을 다시 연다 — 끝 → 뒤로 → 다시 끝이면 두 번째도 올린다(cx H1 · R55).
    // 「끝 1초 안」은 정체 판정 폭이지 시크 예외가 아니다. 같은 끝을 두 번 올리지 않는 것은 시크 앞의 waiting 을 버려서 한다:
    // 정체는 시크 뒤 새 waiting 을 봐야 다시 선다(ended 는 브라우저가 다시 끝에 닿아야 난다)
    const rearmEnd = () => {
      const details = hlsRef.current?.latestLevelDetails;
      if (!endSent || !details || lastEndMs === null) return;
      const atMs = programDateAt(details.fragments, video.currentTime);
      if (atMs === null || atMs >= lastEndMs) return;
      endSent = false;
      waiting = false;
    };
    let recoveredNetwork = false;
    let recoveredMedia = false;
    // 브라우저 기본 HLS(옛 iOS Safari)의 오류 처리. hls.js 갈래에서만 단다
    let onNativeError: (() => void) | null = null;
    // 영상 조각을 한 번이라도 받았나(hls.js 조각 받기 완료 · 기본 재생 loadeddata). 받았으면 403에서 회복한 것이다. 목록 읽기로
    // 가르면 목록만 열리고 조각이 403인 곳에서 1분마다 다시 받았다(로컬 리뷰 1라운드)
    let healed = false;
    const onHealed = () => {
      healed = true;
    };
    // 다시 받은 뒤에도 그대로 403이면 더 받지 않는다(POK-419)
    const stillForbidden = () => afterForbiddenRef.current && !healed;

    // src 전환(?stream= 변경) 시 이전 스트림의 상태가 남지 않게 초기화 — 첫 마운트에선
    // 같은 값이라 no-op. 새 소스의 자동재생이 거부되면 playing=false로 남아 재생 버튼이 노출된다.
    setPlaying(false);
    setBehind(0);
    // 이전 스트림의 창 폭이 새 스트림 시크바에 남으면 안 된다
    setWindowSeconds(0);
    // 이전 스트림의 자리도 남기지 않는다: 새 스트림 채팅이 앞 방송 시각을 따른다
    setLivePosition(null);

    const onPlay = () => {
      setPlaying(true);
      // 재개 판정은 멈춘 시각으로 한다: 멈춘 동안 라이브러리가 재생점을 옮겼을 수 있다(5.3.1절 2번)
      const pausedAtMs = pausedAtMsRef.current;
      pausedAtMsRef.current = null;
      // 사용자가 다시 틀었다 — 멈춘 채 넘긴 것이 교체로 이어지지 않았어도 다음 재생기의 자동 재생을 막지 않는다
      keepPausedRef.current = false;
      const axis = sessionActive() ? readAxis() : null;
      if (pausedAtMs !== null && axis !== null && pausedAtMs < axis.view.lowerMs) {
        hooksRef.current.onSeekRequest?.(pausedAtMs, axis.view);
      }
    };
    // 그 방송을 처음 틀었을 때 한 번만 센다 — 멈췄다 다시 틀기·재연결은 세지 않는다. 주소는 싣지 않는다 (POK-357).
    // 키는 방송 번호다: 주소는 물리 키(스트림 키)로 만들어 한 스트리머의 방송이 모두 같다(PR #382 2차 리뷰).
    // play가 아니라 playing에서 센다 — play는 play()를 부른 순간 나가, 첫 조각을 못 받아 화면이 안 나와도 셌다(PR #382 3차 리뷰)
    const onPlaying = () => {
      waiting = false;
      // 재생에 성공했다 — 앞 재생기가 멎은 알림을 내린다(같은 주소로 새로 선 재생기 · 알림은 주소가 바뀔 때만 저절로 사라진다)
      setStopped(null);
      const id = broadcastIdRef.current;
      if (id) trackPlaybackStartOnce(id, 'live');
      hooksRef.current.onPlaying?.();
    };
    const onPause = () => {
      setPlaying(false);
      if (!sessionActive()) return;
      pausedAtMsRef.current = readAxis()?.atMs ?? null;
      pausedHandledRef.current = false;
    };
    const onWaiting = () => {
      waiting = true;
    };
    const onSeeking = () => {
      rearmEnd();
      // 시크 중에는 currentTime 이 이미 목표다. seeked 전에 복구가 오면 hls.js 는 그 목표에서 받으므로 같이 적는다(cx M1)
      notePlayhead();
      // 기동 전 시크는 받기 위치를 그 자리로 옮긴다 — hls.js 의 seeking 처리(base-stream-controller 472-476)와 같게
      if (hlsRef.current?.hasEnoughToStart === false) loadFrom = video.currentTime;
    };
    // 0 은 재생점으로 치지 않는다. 떼기의 load() 가 만든 0 이 앞서 적은 값을 덮지 않게, 그리고 실제로 0 이면 hls.js 가
    // 제 자리를 고른다(hls.ts 708-711 — 자동 시작이면 base-stream-controller.ts 339-341 이 스스로 · 아니면
    // startLoad()). 그때는 앞서 적은 것을 두고(기동 뒤라 버렸으면 null 이라 옮기지 않는다) 낡은 시작 자리로 되돌리지
    // 않는다(cc H1)
    const isPlayhead = (time: number) => Number.isFinite(time) && time > 0;
    // 떼기에서 쓴다. 떼기의 첫 처리기(buffer-controller)가 src 를 지우고 load() 해 재생점이 0 이 된 뒤라 그때의 currentTime 은
    // 쓸 수 없다(buffer-controller.ts 575-579 — hls.ts 703 이 떼기 전에 재생점을 잡는 까닭)
    const notePlayhead = () => {
      if (isPlayhead(video.currentTime)) lastPlayhead = video.currentTime;
    };
    // 네트워크 오류로 다시 받기 직전 — 떼지 않으니 재생점이 그대로다. hls.js 는 0 이 아니면 그 자리에서 받는다
    // (stream-controller.ts 168-180)
    const rememberPlayhead = () => {
      if (isPlayhead(video.currentTime)) loadFrom = video.currentTime;
    };
    // hls.js 가 떼고 붙여 기동 전으로 돌아간다(앱의 복구와 hls.js 가 스스로 부르는 복구 — error-controller.ts 498-500).
    // hls.js 는 떼기 전에 잡은 재생점에서 다시 받으므로(hls.ts 703 · 708-709) 그 값을 앞서 적어 둔 마지막 재생점으로 본다
    const rememberLastPlayhead = () => {
      if (lastPlayhead !== null) loadFrom = lastPlayhead;
    };
    // 기동이 끝났다 — 시작 때 받기 위치는 낡는다(뒤의 시크로 갱신되지 않는다). hls.js 가 첫 조각이 붙은 FRAG_BUFFERED 처리기에서
    // hasEnoughToStart 를 켜고(stream-controller.ts 997-999) 그 처리기는 앱보다 먼저 등록돼 있다. 목록 갱신을 기다리지 않는다 —
    // 그 전에 복구가 오면 낡은 시작 자리가 남는다(cx H). hls.js 가 다시 기동 전으로 돌아가면 그때 적는다
    const dropStartPosition = (hls: Hls) => {
      if (hls.hasEnoughToStart) loadFrom = null;
    };
    // 되감기 재생점이 창 밖으로 떨어지기 전에 옮긴다 — LEVEL_UPDATED 처리기 안에서 동기로(hls.js 가 옮기기 전, 5.3.1절)
    const keepRewindFloor = (hls: Hls, details: LevelDetails) => {
      const moved = guardRewindFloor({ hls, video, details, loadFrom });
      if (moved === null) return;
      // 알림은 조용한 구간에 묶이므로 옮길 때마다 남겨 둔다(cc L5)
      console.info('[player] 되감기 재생점을 창 하한으로 옮겼어요:', moved.kind, moved.to);
      if (moved.kind === 'reload') loadFrom = moved.to;
      // 멈춘 채 옮겼으면 재개 판정도 옮긴 자리로 한다 — 옛 자리로 세션에 다시 넘겨 알림이 겹치지 않게
      if (video.paused && pausedAtMsRef.current !== null) {
        pausedAtMsRef.current = programDateAt(details.fragments, moved.to);
      }
      const now = Date.now();
      const quietMs = FLOOR_NOTICE_QUIET_TD * details.targetduration * 1000;
      if (floorNoticeAt !== null && now - floorNoticeAt <= quietMs) return;
      floorNoticeAt = now;
      hooksRef.current.onNotice?.(SESSION_NOTICES.oldest);
    };
    const onVolumeChange = () => {
      setMuted(video.muted);
      setVolumeState(Math.round(video.volume * 100));
    };
    // 세션 모드의 그리기: 세 재생기가 같은 실제 시각 축(설계 5.2절). 끝 정체(D9)와 라이브의 멈춘 자리(5.3.1절)도 여기서 본다
    const paintSession = () => {
      const axis = readAxis();
      if (axis === null) return;
      setWindowSeconds(axis.windowSeconds);
      setBehind(axis.behindSeconds);
      // 라이브는 되감기가 준비됐을 때만 멈춘 채 갈아 끼운다. 준비 전이면 재개 때 자른다(onPlay) — 창이 밀릴 때마다 알리지
      // 않게. 되감기의 멈춘 자리는 LEVEL_UPDATED 가 지킨다(keepRewindFloor)
      const handsOverPaused = kind === 'live' && hooksRef.current.axisLowerMs != null;
      const pausedAtMs = pausedAtMsRef.current;
      if (
        handsOverPaused &&
        video.paused &&
        pausedAtMs !== null &&
        !pausedHandledRef.current &&
        pausedAtMs < axis.view.lowerMs
      ) {
        pausedHandledRef.current = true;
        keepPausedRef.current = true;
        hooksRef.current.onSeekRequest?.(pausedAtMs, axis.view);
      }
      if (video.readyState < HAVE_CURRENT_DATA || axis.atMs === null) return;
      const { atMs, edgeMs, behindSeconds: behind } = axis;
      setLivePosition((prev) => nextLivePosition(prev, atMs, edgeMs, behind));
    };
    // 목록 끝 정체(D9) — ENDLIST 없는 목록의 회차 경계를 맡는 「끝 도달」이다. 재생기 하나에 한 번(목록이 자라면 다시 센다)
    const checkStall = () => {
      const details = hlsRef.current?.latestLevelDetails;
      if (endSent || !hooksRef.current.onEndReached || !details) return;
      const list = loadedList(details.fragments, details.targetduration);
      const atMs = programDateAt(details.fragments, video.currentTime);
      if (list === null || atMs === null) return;
      if (endStalled({ list, atMs, waiting, sinceGrowMs: Date.now() - lastGrowAt })) reachEnd();
    };
    // 시차는 정수 초로 반올림돼 있어 같은 값이면 setState가 리렌더를 건너뛴다
    const paint = () => {
      checkStall();
      if (sessionActive()) {
        paintSession();
        return;
      }
      const range = dvrRange(video.seekable);
      if (!range) return;
      const sync = hlsRef.current?.liveSyncPosition ?? null;
      // 창 폭도 정수로 반올림한다 — live - range.start는 계속 흐르는 실수라 그대로 세팅하면
      // timeupdate마다 값이 달라져 리렌더가 돈다 (시차를 반올림하는 이유와 같다)
      setWindowSeconds(Math.round(rewindWindowSeconds(range, sync)));
      const behind = behindFromCurrentTime(range, video.currentTime, sync);
      setBehind(behind);
      // 재생 위치에 영상이 없으면(첫 조각이 붙기 전, 옮긴 자리를 받는 중, 미디어 오류 복구로 다시 붙는 중) 자리를 바꾸지 않는다:
      // 그때의 재생 위치(첫 조각 전엔 0초)는 보고 있는 화면이 아니라, 라이브를 여는 순간 1시간 되감은 것처럼 채팅이 따라갔다
      // (POK-326 리뷰 1라운드)
      if (video.readyState < HAVE_CURRENT_DATA) return;
      // 시각은 조각 목록으로 센다(programDateAt) — 같은 초 · 같은 시차면 앞 값을 둔다(nextLivePosition)
      const fragments = hlsRef.current?.latestLevelDetails?.fragments ?? [];
      const at = programDateAt(fragments, video.currentTime);
      const edge = programDateAt(fragments, liveEdgePosition(range, sync));
      setLivePosition((prev) =>
        at === null || edge === null ? null : nextLivePosition(prev, at, edge, behind),
      );
    };
    video.addEventListener('play', onPlay);
    video.addEventListener('playing', onPlaying);
    video.addEventListener('pause', onPause);
    video.addEventListener('volumechange', onVolumeChange);
    video.addEventListener('timeupdate', paint);
    video.addEventListener('waiting', onWaiting);
    video.addEventListener('seeking', onSeeking);
    video.addEventListener('timeupdate', notePlayhead);
    video.addEventListener('seeked', notePlayhead);
    video.addEventListener('ended', reachEnd);
    // 일시정지 중엔 timeupdate가 멎지만 라이브 엣지는 계속 흐른다 — 시차 표시용 보조 틱
    const paintTimer = window.setInterval(paint, 1000);

    void (async () => {
      // hls.js는 클라이언트 전용(월드 오브젝트 참조) — 동적 import로 번들 분리까지 겸한다. 읽기가 실패하면 영상 위에 알리고
      // 원인을 남긴다(다음 재생기는 다시 읽는다 — hlsLoader)
      let HlsCtor: typeof import('hls.js').default;
      try {
        ({ default: HlsCtor } = await loadHls());
      } catch (e) {
        console.error('[player] hls.js 를 읽지 못했어요:', e);
        if (!disposed) setStopped({ src, why: 'broken' });
        return;
      }
      if (disposed) return;

      if (HlsCtor.isSupported()) {
        const base = kind === 'live' ? HLS_LIVE_CONFIG : HLS_REWIND_CONFIG;
        // 시작 시각이 있으면 자동 시작을 끄고 첫 목록에서 그 자리를 한 번만 정한다(RC-7 · R15)
        const hls = new HlsCtor(startAtMs === null ? base : { ...base, autoStartLoad: false });
        hlsRef.current = hls;
        hls.on(HlsCtor.Events.LEVEL_LOADED, (_event, data) => {
          if (startAtMs === null || startPlaced) return;
          startPlaced = true;
          const { fragments, targetduration } = data.details;
          const position = startLoadPosition(fragments, startAtMs, kind, targetduration);
          loadFrom = position;
          hls.startLoad(position ?? -1);
        });
        hls.on(HlsCtor.Events.LEVEL_UPDATED, (_event, data) => {
          if (kind === 'rewind') keepRewindFloor(hls, data.details);
          const list = loadedList(data.details.fragments, data.details.targetduration);
          if (list !== null) {
            if (lastEndMs === null || list.lastEndMs > lastEndMs) {
              lastGrowAt = Date.now();
              endSent = false;
            }
            lastEndMs = list.lastEndMs;
            hooksRef.current.onListLoaded?.({ url: src, ...list });
          }
          // ENDLIST 는 「조각이 더 붙지 않는다」뿐이다 — 끝 도달과 따로 올린다(cx H1)
          if (!data.details.live && !endlistSent) {
            endlistSent = true;
            hooksRef.current.onEndlist?.();
          }
        });
        hls.on(HlsCtor.Events.ERROR, (_event, data) => {
          if (!data.fatal) return;
          // 403은 출입증 쿠키가 없다는 뜻이다(만료 · 다른 탭이 쿠키 한도를 넘겨 지워짐). 다시 불러도 또 403이라 한 번 더 해 보지
          // 않고 출입증을 버린다: 라이브 화면이 1분 안에 다시 받고 재생기를 새로 세운다(POK-410)
          const key = mediaKeyRef.current;
          if (
            data.type === HlsCtor.ErrorTypes.NETWORK_ERROR &&
            data.response?.code === 403 &&
            key !== undefined
          ) {
            if (stillForbidden()) {
              setStopped({ src, why: 'denied' });
            } else {
              reportPlaybackForbidden(key);
              setStopped({ src, why: 'forbidden' });
            }
            hls.destroy();
            hlsRef.current = null;
            return;
          }
          // 라이브 목록 404 = 라이브 닫힘(끝 도달로 친다) · 400 = 송출 재시작 뒤 옛 _HLS_msn
          // (bluenviron/mediamtx#4320) — 세션이 재생기를 새로 세운다. hls.js 는 4xx 를 다시 해 보지 않는다(RC-16).
          // 목록 오류만 본다: 조각 404 도 hls.js 재시도를 다 쓰면 치명으로 오지만 목록은 살아 있을 수 있어 아래의 한 번
          // 다시 받기로 보낸다(cx H1)
          const code = data.response?.code;
          const { onLiveClosed, onLive400 } = hooksRef.current;
          const liveGone = code === 404 ? onLiveClosed : code === 400 ? onLive400 : undefined;
          const listError = isPlaylistLoadError(data, HlsCtor.ErrorDetails);
          if (
            kind === 'live' &&
            data.type === HlsCtor.ErrorTypes.NETWORK_ERROR &&
            listError &&
            liveGone
          ) {
            hls.destroy();
            hlsRef.current = null;
            liveGone();
            return;
          }
          // 최소 복구: 각 1회만 시도, 재발 시 정리하고 영상 위에 알린다
          if (data.type === HlsCtor.ErrorTypes.NETWORK_ERROR && !recoveredNetwork) {
            recoveredNetwork = true;
            rememberPlayhead();
            hls.startLoad();
            return;
          }
          if (data.type === HlsCtor.ErrorTypes.MEDIA_ERROR && !recoveredMedia) {
            recoveredMedia = true;
            hls.recoverMediaError();
            return;
          }
          // 화면은 마지막 프레임에서 멎는다: 영상 위 덮개로 알리고(playbackError) 원인은 로그로 남긴다
          console.error('[player] 복구 불가 hls.js 오류:', data.type, data.details);
          setStopped({ src, why: 'broken' });
          hls.destroy();
          hlsRef.current = null;
        });
        hls.on(HlsCtor.Events.MEDIA_DETACHING, rememberLastPlayhead);
        hls.on(HlsCtor.Events.FRAG_BUFFERED, () => dropStartPosition(hls));
        hls.on(HlsCtor.Events.FRAG_LOADED, onHealed);
        hls.on(HlsCtor.Events.MANIFEST_PARSED, () => {
          // 멈춘 채 갈아 끼운 재생기는 스스로 틀지 않는다(5.3.1절 3번)
          if (keepPausedRef.current) {
            keepPausedRef.current = false;
            return;
          }
          // 자동재생 거부는 reject로 온다 — playing=false로 남아 재생 버튼이 노출된다
          video.play().catch(() => {});
        });
        hls.attachMedia(video);
        // index.m3u8의 302(세션 파라미터)는 기본 로더가 따라간다 — 커스텀 loader 금지 (.env.example 참조)
        hls.loadSource(src);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari 네이티브 HLS — liveSyncPosition이 없어 엣지 스냅은 백오프 경로를 탄다
        // <video>는 응답 코드를 못 읽어 첫 바이트를 물어 403인지 본다(다시보기와 같은 길). 403이면 출입증을 버리고 다시 받아 둔다:
        // 라이브 화면이 1분 안에 재생기를 새로 세운다. 기본 재생은 스스로 다시 해 보므로 error가 왔으면 멎은 것이다(PR #347 codex)
        onNativeError = () => {
          const key = mediaKeyRef.current;
          if (key === undefined) {
            setStopped({ src, why: 'broken' });
            return;
          }
          if (stillForbidden()) {
            void isPlaybackForbidden(src).then((forbidden) => {
              if (!disposed) setStopped({ src, why: forbidden ? 'denied' : 'broken' });
            });
            return;
          }
          void recoverForbiddenRecording(src, key).then((forbidden) => {
            if (!disposed) setStopped({ src, why: forbidden ? 'forbidden' : 'broken' });
          });
        };
        video.addEventListener('error', onNativeError);
        video.addEventListener('loadeddata', onHealed);
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
      video.removeEventListener('playing', onPlaying);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('volumechange', onVolumeChange);
      video.removeEventListener('timeupdate', paint);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('seeking', onSeeking);
      video.removeEventListener('timeupdate', notePlayhead);
      video.removeEventListener('seeked', notePlayhead);
      video.removeEventListener('ended', reachEnd);
      if (onNativeError !== null) video.removeEventListener('error', onNativeError);
      video.removeEventListener('loadeddata', onHealed);
      hlsRef.current?.destroy();
      hlsRef.current = null;
      // src 제거만으로는 네트워크·디코더가 붙어 있다 — load()까지 해야 리소스가 해제된다
      video.removeAttribute('src');
      video.load();
    };
  }, [videoRef, source.kind, src, source.generation, readAxis, sessionActive]);

  return {
    playing,
    muted,
    volume,
    behindSeconds,
    livePosition,
    atEdge: isAtEdge(behindSeconds),
    windowSeconds,
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
    seekToDate,
    returnToLive,
    seekToMs,
    seekToLiveEdge,
    // 화질은 표시용: 렌디션이 하나라 고를 것이 없어 메뉴를 안 보인다(POK-253)
    settingsMenu: false,
    replay: false,
    playbackError: stopped !== null && stopped.src === src ? STOPPED_NOTES[stopped.why] : null,
    setQuality,
    // 표시 전용: lowLatencyMode는 Hls 생성자 전용 옵션이라 실반영엔 인스턴스 재생성이
    // 필요하다. 시킹 위치 복원 등 엣지가 커서 반영하지 않는다.
    toggleLowLatency: useCallback(() => setLowLatency((v) => !v), []),
    markClip,
    wake,
    sleep,
  };
}
