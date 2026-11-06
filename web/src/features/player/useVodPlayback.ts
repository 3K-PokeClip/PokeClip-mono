'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useReducer,
  useRef,
  type RefObject,
} from 'react';
import type { VodPhase } from './pastVod';
import { behindFromSeekFraction, isAtEdge } from './playerMath';
import type { SeekView } from './rewindSwitch';
import { useHlsPlayback, type PlaybackHooks } from './useHlsPlayback';
import type { PlayerSimulation, PlayerSimulationOptions } from './usePlayerSimulation';

// 끝난 뒤 들어온 화면(A)의 다시보기 재생기(POK-546 설계 2-3) — 회차 경로가 답한 VOD 목록 하나를 PDT 축으로 튼다.
// useHlsPlayback 을 세션 모드(onSeekRequest)로 세워 카드의 실제 시각 옮기기가 PDT 와 범위 밖 판정을 쓰게 하고, 그 반환을
// 펼쳐 세 가지를 덮는다: 시크바(목록 전체 길이 축 — 세션 축의 1시간 상한을 거치지 않는다) · 위치(화면에 붙은 자리를
// 모르면 끝까지 null) · 받아 둔 자리(재생기가 서기 전에 누른 카드). 다 봤나(ended)는 useHlsPlayback 에 없어 더한다.
// PlayerSimulation 계약 그대로라 GlassPlayer 몸통은 어느 훅인지 모른다.

/** 회차 VOD 목록 하나(회차 경로의 vod ∧ ready 답)와 카드 · 채팅 축 */
export interface VodSource {
  url: string;
  firstPdtMs: number;
  headPdtMs: number;
  /** 카드 · 채팅 축 0초 = 서버 기준점(originMs) ?? 첫 PDT */
  axisZeroMs: number;
  /** axisZeroMs 가 서버 기준점이다(카드 추가가 이 축이어야 보낸다 — POK-392) */
  originAxis: boolean;
}

/** 재생 위치에 영상 자료가 있다(HTMLMediaElement.HAVE_CURRENT_DATA — useHlsPlayback 의 문턱과 같다) */
const HAVE_CURRENT_DATA = 2;

/** 재생기가 받은 목록의 범위 — 소스가 바뀔 때까지 지우지 않는다(재생기를 내린 뒤에도 시크바 폭이 남는다) */
interface VodList {
  firstMs: number;
  lastEndMs: number;
}

/**
 * 사건(SAMPLE_EVENTS) 때 잰 두 자리. axisPdtMs 는 재생 위치의 PDT(막대 값), shownPdtMs 는 화면에 붙은 자리의 PDT(알리는
 * 위치)다. 새 값이 null 이면 그 칸만 직전 값을 둔다
 */
interface VodSample {
  axisPdtMs: number | null;
  shownPdtMs: number | null;
}

/**
 * 소스 하나로 쌓은 것 — 받은 목록 · 잰 자리 · 다 봤나를 한 묶음으로 둔다. 주소가 바뀌면 묶음째 새로 세워, 다른 주소로 갔다 돌아와도
 * 옛 목록 · 옛 자리가 되살아나지 않는다(설계 2-3 「소스가 바뀔 때까지」)
 */
interface VodHeld {
  forUrl: string;
  list: VodList | null;
  sample: VodSample | null;
  /** 목록 끝까지 봤다(video ended) — 다시 틀거나 옮기면 푼다 */
  ended: boolean;
}

/** 주소 하나의 새 묶음 — 목록도 잰 자리도 아직 없다 */
function freshHeld(url: string): VodHeld {
  return { forUrl: url, list: null, sample: null, ended: false };
}

/**
 * 묶음을 바꾸는 일 — 주소가 바뀜 · 재생기가 받은 목록 · 사건 때 잰 자리 · 끝에 닿음과 풀림. 주소 말고는 그것을 낸 주소를
 * 싣는다
 */
type VodHeldAction =
  | { type: 'source'; url: string }
  | ({ type: 'list'; url: string } & VodList)
  | ({ type: 'sample'; url: string } & VodSample)
  | { type: 'ended'; url: string; ended: boolean };

/** 주소가 바뀌면 묶음째 새로 세우고, 목록 · 자리는 묶음의 주소와 낸 주소가 같을 때만 받는다(앞 주소의 것은 버린다) */
function heldReducer(held: VodHeld, action: VodHeldAction): VodHeld {
  if (action.type === 'source') {
    return held.forUrl === action.url ? held : freshHeld(action.url);
  }
  if (held.forUrl !== action.url) {
    return held;
  }
  if (action.type === 'list') {
    return { ...held, list: { firstMs: action.firstMs, lastEndMs: action.lastEndMs } };
  }
  if (action.type === 'ended') {
    return held.ended === action.ended ? held : { ...held, ended: action.ended };
  }
  const axisPdtMs = action.axisPdtMs ?? held.sample?.axisPdtMs ?? null;
  const shownPdtMs = action.shownPdtMs ?? held.sample?.shownPdtMs ?? null;
  if (held.sample?.axisPdtMs === axisPdtMs && held.sample.shownPdtMs === shownPdtMs) {
    return held;
  }
  return { ...held, sample: { axisPdtMs, shownPdtMs } };
}

/**
 * 다시 그리는 계기. loadeddata · canplay 는 자동 재생이 막혀 첫 프레임 뒤 timeupdate · seeking · seeked 가 나지 않는
 * 브라우저에서도 화면에 붙은 첫 자리를 재게 한다
 */
const SAMPLE_EVENTS = ['timeupdate', 'seeking', 'seeked', 'loadeddata', 'canplay'] as const;

/** 다 본 것을 푸는 사건 — 다시 틀기(다 본 뒤 재생은 처음부터) · 옮기기(시크바 · 키 · 카드) */
const RESUME_EVENTS = ['play', 'seeking'] as const;

/** 목표 실제 시각을 목록 범위(첫 조각 · 끝) 안으로 가둔다 */
function clampToView(targetMs: number, view: SeekView): number {
  return Math.min(view.endMs, Math.max(view.lowerMs, targetMs));
}

/**
 * 끝으로 갈 때 목록 끝에서 물러설 거리 — 녹화 다시보기의 꼬리(useRecordedPlayback tailStart)와 같은 1초. 목록 끝(마지막 조각
 * PDT + 길이)은 실제로 받는 영상 끝보다 조금 뒤일 수 있어, 그 자리에 놓으면 WebKit 이 받아 둔 끝에 놓고 멎었다(V0-c Home/End).
 * 마지막 1초를 틀어 끝에 닿는다
 */
const END_TAIL_MS = 1_000;

/** 끝으로 갈 때 틀 자리 — 목록 끝 1초 앞. 목록이 1초보다 짧으면 첫 조각이다 */
function tailMs(list: VodList): number {
  return Math.max(list.firstMs, list.lastEndMs - END_TAIL_MS);
}

/**
 * 재생기 국면. 기본 HLS 는 조각 목록이 없고, hls.js · 기본 HLS 가 모두 없으면 틀 수 없다 — 둘 다 unsupported 다. 화면
 * 위치를 한 번 냈으면 그 뒤 멎어도 showing 이다(채팅은 마지막 자리 · 영상 위 알림). 그 전에 멎으면 failed, 아니면 첫 목록
 * 유무로 listed · loading 이다
 */
function vodPhase(input: {
  engine: 'hlsjs' | 'native' | 'none' | null;
  listed: boolean;
  shown: boolean;
  stopped: boolean;
}): VodPhase {
  if (input.engine === 'native' || input.engine === 'none') {
    return 'unsupported';
  }
  if (input.shown) {
    return 'showing';
  }
  if (input.stopped) {
    return 'failed';
  }
  return input.listed ? 'listed' : 'loading';
}

/**
 * @param vod 틀 목록과 카드 · 채팅 축. 주소가 바뀌면 받은 목록 · 잰 자리를 버린다(앞 주소로 돌아와도 되살리지 않는다)
 * @param onSeek 사람이 옮겼다(시크바 · 키 · 카드 · 다 본 뒤 처음부터). 재생으로 흐를 때는 부르지 않는다
 * @param mediaKey 영상 키(출입증 쿠키의 경로) — useHlsPlayback 과 같다
 * @param afterForbidden 403 으로 출입증을 다시 받은 뒤 새로 선 재생기다 — useHlsPlayback 과 같다
 * @param broadcastId 방송 번호(회차) — 사용 통계 「방송마다 한 번」의 기억 키
 * @param startAtSeconds 받아 둔 자리(기준점 축 초 — 화면의 pendingStart). 목록이 서 있으면 그 커밋에 한 번 옮긴다
 * @param onStartApplied 받아 둔 자리를 적용했다(옮김 · 범위 밖이라 첫 조각으로 자름) — 부른 쪽이 받아 둔 자리를 비운다. 그
 *   시각의 영상 자리를 모르면 부르지 않고 남겨 둔다(다음 목록에서 다시 해 본다)
 */
export function useVodPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  vod: VodSource,
  options: PlayerSimulationOptions = {},
  onSeek?: () => void,
  mediaKey?: string,
  afterForbidden = false,
  broadcastId?: string,
  startAtSeconds: number | null = null,
  onStartApplied?: () => void,
): PlayerSimulation & { positionSeconds: number | null; phase: VodPhase } {
  // useState 가 아니라 useReducer 다(usePastVod 와 같은 까닭). useState 의 갱신은 마지막으로 렌더한 상태로 결과를 미리 셈해
  // 같으면 렌더를 건너뛰는데, 그 상태가 버려진 렌더의 것이면 지금 주소의 목록 · 자리를 「다른 주소」로 셈해 버린다
  const [held, dispatchHeld] = useReducer(heldReducer, vod.url, freshHeld);
  // 주소가 바뀐 렌더에서 곧바로 새 묶음으로 바꾼다(렌더 중 갱신 — 다음 렌더 전에 반영되고, 렌더가 버려지면 함께 버려진다)
  let sourceHeld = held;
  if (held.forUrl !== vod.url) {
    sourceHeld = freshHeld(vod.url);
    dispatchHeld({ type: 'source', url: vod.url });
  }
  // 훅 고리는 재생기보다 먼저 만든다 — 그래서 고리가 부를 seekToMs 는 ref 로 잇는다
  const seekToMsRef = useRef<(atMs: number) => void>(() => undefined);
  const hooks = useMemo<PlaybackHooks>(
    () => ({
      // 세션 모드: 카드의 실제 시각 옮기기(seekToDate)가 범위 밖 판정을 하고 여기로 넘긴다. VOD 하한은 첫 조각이라 그 앞은 첫
      // 조각으로 조용히 자르고, 끝 뒤는 끝이다
      onSeekRequest: (targetMs, view) => seekToMsRef.current(clampToView(targetMs, view)),
      onListLoaded: ({ url, firstMs, lastEndMs }) =>
        dispatchHeld({ type: 'list', url, firstMs, lastEndMs }),
    }),
    [],
  );
  const sim = useHlsPlayback(
    videoRef,
    { kind: 'vod', url: vod.url, startAtMs: null, generation: 0, strictStart: false },
    options,
    onSeek,
    mediaKey,
    afterForbidden,
    broadcastId,
    hooks,
  );
  const { seekToMs, seekToDate, currentProgramDateMs, togglePlay: toggleHlsPlay } = sim;
  // 커밋된 렌더의 것만 잇는다 — 렌더 중에 쓰면 버려진 렌더(전이 중단)의 seekToMs · onSeek 가 남아 카드 이동이 그것을 부른다
  useLayoutEffect(() => {
    seekToMsRef.current = seekToMs;
  }, [seekToMs]);

  const { list, sample, ended } = sourceHeld;
  const axisPdtMs = sample?.axisPdtMs ?? null;
  const shownPdtMs = sample?.shownPdtMs ?? null;

  /** 화면에 붙은 자리의 PDT — 재생 위치에 영상이 없으면 모른다 */
  const readShownPdtMs = useCallback((): number | null => {
    const video = videoRef.current;
    if (video === null || video.readyState < HAVE_CURRENT_DATA) {
      return null;
    }
    return currentProgramDateMs();
  }, [videoRef, currentProgramDateMs]);

  // 다시 그리는 계기를 직접 듣는다 — 재생기 자리(livePosition)는 실제 초로 묶여, 기준점 축(ms 자리가 있다)의 초 경계를
  // 넘어도 바뀌지 않을 수 있다
  const url = vod.url;
  useEffect(() => {
    const video = videoRef.current;
    if (video === null) {
      return;
    }
    const measure = () =>
      dispatchHeld({
        type: 'sample',
        url,
        axisPdtMs: currentProgramDateMs(),
        shownPdtMs: readShownPdtMs(),
      });
    for (const type of SAMPLE_EVENTS) {
      video.addEventListener(type, measure);
    }
    return () => {
      for (const type of SAMPLE_EVENTS) {
        video.removeEventListener(type, measure);
      }
    };
  }, [videoRef, url, currentProgramDateMs, readShownPdtMs]);

  // 다 봤나 — 재생 단추가 「처음부터」가 된다(녹화 다시보기와 같은 뜻, POK-518). useHlsPlayback 의 끝 도달(onEndReached)은
  // 목록 끝 정체까지 세는 세션 알림이라 쓰지 않고, 브라우저의 ended 만 따른다
  useEffect(() => {
    const video = videoRef.current;
    if (video === null) {
      return;
    }
    const reachEnd = () => dispatchHeld({ type: 'ended', url, ended: true });
    const resume = () => dispatchHeld({ type: 'ended', url, ended: false });
    video.addEventListener('ended', reachEnd);
    for (const type of RESUME_EVENTS) {
      video.addEventListener(type, resume);
    }
    return () => {
      video.removeEventListener('ended', reachEnd);
      for (const type of RESUME_EVENTS) {
        video.removeEventListener(type, resume);
      }
    };
  }, [videoRef, url]);

  // 받아 둔 자리 — 첫 목록 때 이미 있었든 목록 뒤에 들어왔든 같은 규칙이다(부모 · 자식 국면이 한 박자 엇갈려도 다음 커밋에
  // 적용된다). 칠하기 전에 돈다: 첫 조각이 화면에 붙은 뒤로 늦으면 0초 장면이 한 번 비칠 수 있다(V0 실측)
  const startRef = useRef({ seekToDate, seekToMs, axisZeroMs: vod.axisZeroMs, onStartApplied });
  startRef.current = { seekToDate, seekToMs, axisZeroMs: vod.axisZeroMs, onStartApplied };
  useLayoutEffect(() => {
    if (list === null || startAtSeconds === null) {
      return;
    }
    const startInputs = startRef.current;
    const result =
      startInputs.seekToDate?.(startInputs.axisZeroMs + startAtSeconds * 1000) ?? 'unknown';
    // 시각 축을 읽지 못했다 — 받아 둔 자리를 남겨 다음 목록에서 다시 해 본다(K2 (iii))
    if (result === 'unknown') {
      return;
    }
    if (result === 'outside') {
      startInputs.seekToMs(list.firstMs);
    }
    startInputs.onStartApplied?.();
  }, [list, startAtSeconds]);

  // 시크바 — 목록 첫 조각부터 끝까지(이 표가 정본: 설계 2-3). 막대 값은 재생 위치의 PDT 로 센다(놓은 직후에도 놓은 자리)
  const windowSeconds = list === null ? 0 : Math.round((list.lastEndMs - list.firstMs) / 1000);
  const behindSeconds =
    list === null || axisPdtMs === null
      ? windowSeconds
      : Math.min(windowSeconds, Math.max(0, Math.round((list.lastEndMs - axisPdtMs) / 1000)));

  // 시크바 · 키가 옮길 자리 — 첫 조각부터 꼬리(끝 1초 앞)까지로 가둔다. 다 본 뒤 꼬리로 가면 옮기지 않는다: 옮기면 seeking 이
  // 다 봤나를 풀어 「처음부터」 단추가 깜빡이거나 끝 1초를 또 돈다(녹화 다시보기 jumpTo 와 같은 뜻 — POK-518)
  const moveWithinList = useCallback(
    (targetMs: number) => {
      if (list === null) {
        return;
      }
      const tail = tailMs(list);
      const atMs = clampToView(targetMs, { lowerMs: list.firstMs, endMs: tail });
      if (atMs === tail && ended) {
        return;
      }
      seekToMs(atMs);
    },
    [list, ended, seekToMs],
  );

  const seekToFraction = useCallback(
    (fraction: number) => {
      const nowMs = currentProgramDateMs();
      if (list === null || nowMs === null) {
        return;
      }
      moveWithinList(list.lastEndMs - behindFromSeekFraction(fraction, windowSeconds) * 1000);
    },
    [list, windowSeconds, currentProgramDateMs, moveWithinList],
  );

  // 양수가 과거 쪽이다(playerKeys 의 부호). 명령 순간의 재생 위치로 센다 — timeupdate 표본은 연달아 누르면 낡았다
  const seekBy = useCallback(
    (deltaSeconds: number) => {
      const nowMs = currentProgramDateMs();
      if (list === null || nowMs === null) {
        return;
      }
      moveWithinList(nowMs - deltaSeconds * 1000);
    },
    [list, currentProgramDateMs, moveWithinList],
  );

  // 다시보기의 오른쪽 끝은 목록 끝 1초 앞(꼬리)이다(실시간이 아니다). 시크바는 시차 1초라 끝으로 보인다(isAtEdge)
  const returnToLive = useCallback(() => seekToFraction(1), [seekToFraction]);

  // 카드처럼 정확한 자리 — 옮기지 못하면(시각 축을 모름 · 목록 앞) false 로 알려 부른 쪽이 「옮기지 못했어요」를 고른다. 이
  // 주소의 목록이 아직 없으면 붙어 있는 재생기가 이 주소의 것인지 모른다 — 지금 자리(currentPositionSeconds)와 같은 까닭
  const seekToPosition = useCallback(
    (seconds: number): boolean =>
      list !== null && seekToDate?.(vod.axisZeroMs + seconds * 1000) === 'moved',
    [list, seekToDate, vod.axisZeroMs],
  );

  // 부르는 순간 화면에 붙은 자리로 센다(카드 추가가 ms 로 보낸다). 모르면 null — 지어내지 않는다. 이 주소의 목록이 아직
  // 없으면 붙어 있는 재생기가 이 주소의 것인지 모른다(주소를 바꾼 커밋 직후 · passive 전에는 옛 재생기가 남아 있다) — 그
  // 자리를 이 주소의 기준점으로 셈하지 않는다. 목록은 이 주소로 선 재생기만 싣는다(heldReducer)
  const currentPositionSeconds = useCallback((): number | null => {
    if (list === null) {
      return null;
    }
    const shownNow = readShownPdtMs();
    return shownNow === null ? null : (shownNow - vod.axisZeroMs) / 1000;
  }, [list, readShownPdtMs, vod.axisZeroMs]);

  // 다 본 뒤 재생은 처음부터 다시 도는 것이라 사람이 옮긴 것으로 친다(녹화 다시보기와 같은 뜻)
  const togglePlay = useCallback(() => {
    const restarts = videoRef.current?.ended === true;
    toggleHlsPlay();
    if (restarts) {
      onSeek?.();
    }
  }, [videoRef, toggleHlsPlay, onSeek]);

  return {
    ...sim,
    windowSeconds,
    behindSeconds,
    atEdge: isAtEdge(behindSeconds),
    seekToFraction,
    seekBy,
    returnToLive,
    seekToPosition,
    currentPositionSeconds,
    togglePlay,
    replay: true,
    ended,
    positionSeconds: shownPdtMs === null ? null : Math.floor((shownPdtMs - vod.axisZeroMs) / 1000),
    phase: vodPhase({
      engine: sim.engine,
      listed: list !== null,
      shown: shownPdtMs !== null,
      stopped: sim.playbackError != null,
    }),
  };
}
