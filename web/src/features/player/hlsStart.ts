import type Hls from 'hls.js';
import type { Events, LevelDetails } from 'hls.js';
import { liveStartFloorMs, startIsTruncated, startLoadPosition } from './playbackEdge';
import type { PlaybackSource } from './rewindSwitch';

// 라이브 기동 문지기(POK-200 c13 · 설계 r9 2절 기동 · 6절 다변 목록 표). 다변 목록(index.m3u8)은 매체 목록을
// 받기 전에는 어느 자리에서 시작할지 정할 수 없다. 그래서 autoStartLoad:false 재생기에 처리기를 달아
// MANIFEST_PARSED 에서 목록 받기만 깨우고, 첫 LEVEL_UPDATED 에서 위치를 정한다. 이 사건은
// stream-controller 의 onLevelLoaded 안에서 tick 보다 먼저 동기로 나서, 여기서 부른
// startLoad(pos) 의 tick 이 목표 조각을 먼저 청한다. LEVEL_LOADED 는 stream-controller 처리기가
// 앱보다 먼저 돌며 라이브 시작 조각을 먼저 청하므로 쓰지 않는다. 이 순서는 hlsStart.hlsjs.test.ts 가 진짜 hls.js
// 로 고정한다.

/** 첫 목록을 본 결정 — 그 자리(미디어 시각 초)에서 받기를 시작하거나, 못 본 녹화가 잘리니 시작하지 않는다 */
export type LiveStart = { kind: 'load'; position: number } | { kind: 'truncated' };

/**
 * 라이브 첫 목록에서 startAtMs 자리를 정한다. 라이브 시작 하한을 정하지 못하면(시각 표시 없음 · 모두 GAP)
 * strictStart 여도 -1(재생기의 기본 시작 위치 — hls.js 가 고른다 · 계약3 7-3절)이다. strictStart 면 그 자리가
 * 하한보다 앞일 때(startIsTruncated) 잘림이다. 아니면 startLoadPosition 이 하한으로 자른 자리다
 */
export function decideLiveStart(
  details: LevelDetails,
  startAtMs: number,
  strictStart: boolean,
): LiveStart {
  const { fragments, targetduration } = details;
  if (liveStartFloorMs(fragments, targetduration) === null) return { kind: 'load', position: -1 };
  if (strictStart && startIsTruncated(fragments, startAtMs, 'live', targetduration)) {
    return { kind: 'truncated' };
  }
  const position = startLoadPosition(fragments, startAtMs, 'live', targetduration);
  return { kind: 'load', position: position ?? -1 };
}

/**
 * autoStartLoad:false 인 hls 에 기동 처리기를 단다. 첫 LEVEL_UPDATED 에서 decide(details) 를
 * 한 번 부른다. 잘림이면 stopLoad() 뒤 onTruncated 를 다음 마이크로태스크에 부른다 — 처리기 안에서 바로 해체하면
 * onLevelLoaded 의 나머지가 부서진 컨트롤러로 돈다. 처리기는 재생기 하나에 한 번만 돈다: startLoad 가 level
 * 컨트롤러도 다시 깨워 목록을 한 번 더 받을 수 있어서다. events 는 hls.js 의 Events 값이다 — hls.js 를 동적
 * import 하는 호출부가 넘긴다
 */
export function armLiveStart(
  hls: Hls,
  events: typeof Events,
  decide: (details: LevelDetails) => LiveStart,
  onTruncated: () => void,
): void {
  hls.once(events.MANIFEST_PARSED, () => hls.startLoad(-1));
  hls.once(events.LEVEL_UPDATED, (...[, data]) => {
    const start = decide(data.details);
    if (start.kind === 'load') {
      hls.startLoad(start.position);
      return;
    }
    hls.stopLoad();
    queueMicrotask(onTruncated);
  });
}

/** 재생기 하나의 시작 — 소스의 종류 · 시작 시각 · 엄격 시작 */
export type StartSource = Pick<PlaybackSource, 'kind' | 'startAtMs' | 'strictStart'>;

/**
 * 시작 시각이 있는 재생기에 기동 처리기를 단다(RC-7 · R15 · POK-200 c13). 라이브는
 * 기동 문지기(armLiveStart)가 첫 LEVEL_UPDATED 에서, 되감기는 첫 LEVEL_LOADED 에서
 * 그 자리를 한 번만 정한다. 시작 시각이 없으면 달지 않는다 — hls.js 가 스스로 시작한다.
 * 재생기는 autoStartLoad:false 로 서 있어야 한다. 되감기가 받기 시작한 자리는 load 에
 * 적고(모르면 null), strictStart 라이브의 잘린 시작은 onTruncated 로 알린다(armLiveStart)
 */
export function armStart(
  hls: Hls,
  events: typeof Events,
  source: StartSource,
  load: Pick<LoadFrom, 'set'>,
  onTruncated: () => void,
): void {
  const { kind, startAtMs, strictStart } = source;
  if (startAtMs === null) return;
  if (kind === 'live') {
    const decide = (details: LevelDetails) => decideLiveStart(details, startAtMs, strictStart);
    armLiveStart(hls, events, decide, onTruncated);
    return;
  }
  let placed = false;
  hls.on(events.LEVEL_LOADED, (...[, data]) => {
    if (placed) return;
    placed = true;
    const { fragments, targetduration } = data.details;
    const position = startLoadPosition(fragments, startAtMs, kind, targetduration);
    load.set(position);
    hls.startLoad(position ?? -1);
  });
}

/** 재생점을 읽을 미디어 — 시험은 currentTime 하나만 준다 */
type Playhead = Pick<HTMLMediaElement, 'currentTime'>;

/**
 * 기동 전 받기 위치 장부(되감기 창 하한 지키기의 재료 — 설계 5.3.1절). hls.js 가 다음에
 * 받기 시작할 자리를 앱 쪽에서 따라 적는다: startLoad 에 준 자리 · 기동 전 시크 자리 ·
 * 떼기 전 마지막 재생점. 기동이 끝나면 버린다. 처리기는 떼어 넘겨도 된다(this 없음)
 */
export interface LoadFrom {
  /** 적힌 받기 위치(미디어 시각 초). 기동이 끝났거나 모르면 null */
  current: () => number | null;
  /** 그 자리에서 받는다 — startLoad 에 준 자리 · 창 하한으로 옮겨 다시 받는 자리 */
  set: (position: number | null) => void;
  /** timeupdate · seeked — 재생 중 마지막 0 아닌 재생점을 적는다 */
  notePlayhead: () => void;
  /** seeking — 재생점을 적고, 기동 전이면 받기 위치도 그 자리로 옮긴다 */
  noteSeeking: (beforeStart: boolean) => void;
  /** 네트워크 오류로 다시 받기 직전 — 떼지 않으니 재생점이 그대로다 */
  rememberPlayhead: () => void;
  /** MEDIA_DETACHING — 떼기 전에 적어 둔 마지막 재생점을 받기 위치로 삼는다 */
  rememberLastPlayhead: () => void;
  /** FRAG_BUFFERED — 기동이 끝났으면 시작 때 받기 위치를 버린다 */
  dropIfStarted: (hasEnoughToStart: boolean) => void;
}

/** 재생기 하나의 받기 위치 장부를 연다. 처음엔 비었다 */
export function trackLoadFrom(video: Playhead): LoadFrom {
  let loadFrom: number | null = null;
  let lastPlayhead: number | null = null;
  // 0 은 재생점으로 치지 않는다. 떼기의 load() 가 만든 0 이 앞서 적은 값을 덮지 않게,
  // 그리고 실제로 0 이면 hls.js 가 제 자리를 고른다(hls.ts 708-711 — 자동 시작이면
  // base-stream-controller.ts 339-341 이 스스로 · 아니면 startLoad()). 그때는 앞서 적은
  // 것을 두고(기동 뒤라 버렸으면 null 이라 옮기지 않는다) 낡은 시작 자리로 되돌리지
  // 않는다(cc H1)
  const isPlayhead = (time: number) => Number.isFinite(time) && time > 0;
  // 떼기에서 쓴다. 떼기의 첫 처리기(buffer-controller)가 src 를 지우고 load() 해 재생점이
  // 0 이 된 뒤라 그때의 currentTime 은 쓸 수 없다(buffer-controller.ts 575-579 —
  // hls.ts 703 이 떼기 전에 재생점을 잡는 까닭)
  const notePlayhead = () => {
    if (isPlayhead(video.currentTime)) lastPlayhead = video.currentTime;
  };
  return {
    current: () => loadFrom,
    set: (position) => {
      loadFrom = position;
    },
    notePlayhead,
    // 시크 중에는 currentTime 이 이미 목표다. seeked 전에 복구가 오면 hls.js 는 그 목표에서
    // 받으므로 같이 적는다(cx M1). 기동 전 시크는 받기 위치를 그 자리로 옮긴다 — hls.js 의
    // seeking 처리(base-stream-controller 472-476)와 같게
    noteSeeking: (beforeStart) => {
      notePlayhead();
      if (beforeStart) loadFrom = video.currentTime;
    },
    // hls.js 는 0 이 아니면 그 자리에서 받는다(stream-controller.ts 168-180)
    rememberPlayhead: () => {
      if (isPlayhead(video.currentTime)) loadFrom = video.currentTime;
    },
    // 앱의 복구와 hls.js 가 스스로 부르는 복구 모두 떼고 붙여 기동 전으로 돌아간다
    // (error-controller.ts 498-500). hls.js 는 떼기 전에 잡은 재생점에서 다시 받으므로
    // (hls.ts 703 · 708-709) 그 값을 앞서 적어 둔 마지막 재생점으로 본다
    rememberLastPlayhead: () => {
      if (lastPlayhead !== null) loadFrom = lastPlayhead;
    },
    // hls.js 가 첫 조각이 붙은 FRAG_BUFFERED 처리기에서 hasEnoughToStart 를 켜고
    // (stream-controller.ts 997-999) 그 처리기는 앱보다 먼저 등록돼 있다. 목록 갱신을
    // 기다리지 않는다 — 그 전에 복구가 오면 낡은 시작 자리가 남는다(cx H). hls.js 가 다시
    // 기동 전으로 돌아가면 그때 적는다
    dropIfStarted: (hasEnoughToStart) => {
      if (hasEnoughToStart) loadFrom = null;
    },
  };
}

/**
 * 멈춘 채 갈아 끼우기 표(설계 5.3.1절 3번 · c13f). 멈춘 채 갈아 끼운 재생기는 스스로
 * 틀지 않는다. 사람이 멈췄는지는 pause · play 사건으로만 안다 — 해체의 media.load() 는
 * pause 사건 없이 video.paused 를 돌린다. 표는 건 때의 세대를 적고, 그보다 새 재생기만
 * 먹는다(옛 재생기가 먼저 먹지 않게)
 */
export interface SwapPause {
  /** pause 사건 — 사람이 멈췄다 */
  userPaused: () => void;
  /** play 사건 — 사람이 다시 틀었다. 넘긴 것이 교체로 이어지지 않았어도 표를 지운다 */
  userPlayed: () => void;
  /** 세션의 넘김 되돌림 — 사람이 멈춰 있을 때만 그 세대에 표를 건다 */
  keepIfUserPaused: (generation: number) => void;
  /** 멈춘 채 세션에 넘겼다 — 그 세대에 표를 건다 */
  keep: (generation: number) => void;
  /** MANIFEST_PARSED — 이 세대 재생기가 스스로 틀지 말아야 하나. 표보다 새 세대면 표를 지운다 */
  holds: (generation: number) => boolean;
}

/** 훅 하나(재생기를 갈아 끼워도 남는다)의 멈춤 유지 표를 연다 */
export function createSwapPause(): SwapPause {
  let paused = false;
  let keepFrom: number | null = null;
  return {
    userPaused: () => {
      paused = true;
    },
    userPlayed: () => {
      paused = false;
      keepFrom = null;
    },
    keepIfUserPaused: (generation) => {
      if (paused) keepFrom = generation;
    },
    keep: (generation) => {
      keepFrom = generation;
    },
    holds: (generation) => {
      if (keepFrom === null) return false;
      if (generation > keepFrom) keepFrom = null;
      return true;
    },
  };
}
