import type Hls from 'hls.js';
import type { PlaybackSource } from './rewindSwitch';

// 라이브를 보는 중인 시청자만 앱이 직접 따라잡는다(POK-583 · kty 결정 3). 라이브러리(hls.js)의 배속 추격은 끈 채로 두고
// (hlsConfig.ts maxLiveSyncPlaybackRate: 1), 조금 밀리면 배속을 1.05 · 1.1 로 올려 목표 지연까지 좁히고 10초를 넘게 밀리면
// 라이브 동기 위치(hls.liveSyncPosition)로 건너뛴다. 목표에 닿으면 배속을 우리가 1 로 되돌린다.
//
// 「라이브를 보는 중」 은 사람의 의도로 정한다 — 거리 · DOM seeking 으로 추정하지 않는다(hls.js 도 스스로 시크하고, 거리로는
// 창 안 되감기를 못 가른다). 그래서 이 모듈은 켜고 끄는 명령(arm · disarm)을 받기만 하고, 언제 켜는지는 사람 명령 자리를 쥔
// 재생 훅이 정한다. 판정은 표본 하나 → 행동 하나의 순수 함수(catchupStep)이고, 영상 노드에 거는 몫(watchLiveCatchup)은
// 표본을 모아 행동을 적용할 뿐이다. 브라우저마다 달라지는 것은 배속을 쓰는지와 켜는 문턱(정책) 하나다.

/**
 * 따라잡기 모드. off = 아무것도 안 함 · rate = 배속만(되감기 → 라이브 자동 넘김 뒤 — 점프는 계약3 7-3 넘김 확인을 깬다) ·
 * full = 배속과 점프. rate 는 넘김 확인이 끝난 첫 표본(handoffConfirming 거짓)에서 catchupStep 이 스스로 full 로 올린다
 */
export type LiveCatchupMode = 'off' | 'rate' | 'full';

/**
 * 브라우저별 배속 정책 — 따라잡기에서 브라우저마다 다른 유일한 축. rateAndJump = 배속과 점프 · jumpOnly = 배속을 켜지 않고
 * 점프만(rate 모드에서는 아무것도 하지 않는다)
 */
export type LiveCatchupPolicy =
  | {
      readonly kind: 'rateAndJump';
      /** 「지연 − 목표」 가 이 초를 넘어야 배속을 켠다 */
      readonly rateOnExcessS: number;
    }
  | { readonly kind: 'jumpOnly' };

/** Chromium 계열 · Firefox 의 정책 */
export const LIVE_CATCHUP_POLICY: LiveCatchupPolicy = { kind: 'rateAndJump', rateOnExcessS: 0.5 };

/**
 * WebKit(Safari · iOS 의 모든 브라우저) 정책 — 배속을 바꾸는 순간 멎으므로(WebKit 163433 · 로컬 headless WebKit 실측 전환
 * 1.05 · 1.1 각 40/40 · 265–335ms) 배속 없이 10초 넘게 밀릴 때만 점프한다(Q1 (다)). 점프 조건 · 쿨다운은 같다
 */
export const WEBKIT_LIVE_CATCHUP_POLICY: LiveCatchupPolicy = { kind: 'jumpOnly' };

/** 초과가 이 초 이상이면 빠른 배속으로 켠다 */
const FAST_EXCESS_S = 1;
const SLOW_RATE = 1.05;
const FAST_RATE = 1.1;
/** 켠 배속은 「지연 − 목표」 가 이 초 이하가 되면 끈다(켜는 문턱과 벌려 둔 히스테리시스) */
const RATE_OFF_EXCESS_S = 0.1;
/**
 * 「지연 − 목표」 를 µs 로 반올림해 문턱과 견준다 — 1.35 − 1.25 = 0.10000000000000009 같은 뺄셈 오차로 끄는 경계를 한 표본
 * 늦게 넘지 않게
 */
const EXCESS_ROUND_PER_S = 1_000_000;
/** 앞 버퍼가 이 초보다 짧으면 배속을 켜지 않고, 켜 있으면 끈다 — 배속이 버퍼를 깎아 끊김을 부르지 않게 */
const MIN_BUFFER_AHEAD_S = 1;
/** 지연이 이 초를 넘으면 full 은 건너뛴다 */
const JUMP_LATENCY_S = 10;
/** 라이브 동기 위치가 지금 자리보다 이 초를 넘게 앞일 때만 건너뛴다 — 제자리 · 뒤로 가는 시크는 하지 않는다 */
const JUMP_MIN_FORWARD_S = 1;
/** 건너뛴 뒤 이 시간 안에는 다시 건너뛰지 않는다 */
const JUMP_COOLDOWN_MS = 8_000;
/** 목록이 목표 조각 길이(TD)의 이 배수를 넘게 자라지 않으면 정체로 본다 — 정체 중의 지연 증가는 따라잡을 것이 아니다 */
const LIVE_CATCHUP_STALE_TD_MULTIPLE = 2;
/**
 * 정체에서 풀리려면 age < TD 인 표본이 이만큼 연속이어야 한다 — 전진 한 번 뒤 재생이 멎어 다음 표본이 TD 넘게 늦게 오는 꼴만
 * 거른다. hls.js 의 age 는 전진 때 0 에서 다시 오르므로 띄엄띄엄 자라는 목록에서는 전진마다 두 표본 뒤 풀린다 — 그 송출의
 * 배속 켬 · 끔 흔들림(실측 Chromium 40초 5회)은 막지 못한다 — POK-583 에서 규칙을 그대로 두기로 했다
 */
const FRESH_SAMPLES_TO_RECOVER = 2;

/** 지금 라이브 목록(hls.latestLevelDetails)에서 읽은 값 */
export interface LiveListSample {
  /** 목록이 마지막으로 전진(파트 포함)한 뒤 흐른 초 — details.age */
  readonly ageS: number;
  /** 목표 조각 길이(초) — details.targetduration */
  readonly targetDurationS: number;
}

/** 재생기(hls.js) 쪽에서 읽어 결합기에 넘기는 값 */
export interface LiveCatchupReading {
  /**
   * 라이브 끝 추정 위치(초, 영상 시간축) — details.edge + details.age. hls.js 1.7.3 지연 제어기의 estimateLiveEdge 와 같은
   * 식이고, 결합기가 표본마다 이 값 − currentTime 으로 지연을 잰다. hls.latency 를 읽지 않는 까닭: 그 값은 hls.js 가 자기
   * timeupdate 에서만 다시 재는 캐시인데 그 리스너가 결합기보다 늦게 붙어, 사람 시크 · LIVE 직후 첫 표본이 시크 전 지연을
   * 읽는다
   */
  readonly liveEdgeS: number;
  /** 목표 지연(초) — hls.targetLatency(PART-HOLD-BACK 에 끊김마다 가산) */
  readonly targetLatencyS: number;
  /** 라이브 동기 위치(초) — hls.liveSyncPosition. 모르면 null */
  readonly liveSyncPositionS: number | null;
  /** 지금 라이브 목록. 아직 없으면 null — 그 표본은 판정하지 않는다 */
  readonly list: LiveListSample | null;
  /** 되감기 → 라이브 넘김을 확인하는 중인가(계약3 7-3) — 확인 중에는 건너뛰지 않는다 */
  readonly handoffConfirming: boolean;
}

/** 판정 하나의 입력 — 재생기 값에 영상 노드 값과 시각을 더한 것 */
export interface LiveCatchupSample extends Omit<LiveCatchupReading, 'liveEdgeS'> {
  readonly nowMs: number;
  /** 라이브 끝에서 지금 자리까지의 지연(초) — 라이브 끝 추정 위치 − currentTimeS */
  readonly latencyS: number;
  readonly currentTimeS: number;
  /** 지금 자리를 담은 buffered 구간의 남은 길이(초) */
  readonly bufferAheadS: number;
  readonly paused: boolean;
  readonly seeking: boolean;
}

/** 판정 사이에 이어지는 상태 */
export interface LiveCatchupState {
  readonly mode: LiveCatchupMode;
  /** 우리가 걸어 둔 배속 — 1 이면 따라잡는 중이 아니다 */
  readonly rate: number;
  /** 목록 정체 중인가 */
  readonly stale: boolean;
  /** 정체 중 age < TD 표본의 연속 수 */
  readonly freshStreak: number;
  /** 마지막으로 건너뛴 시각(ms). 아직 없으면 null */
  readonly lastJumpAtMs: number | null;
}

/** 판정 하나의 행동. jump 는 배속도 1 로 되돌린다 */
export type LiveCatchupAction =
  | { readonly kind: 'none' }
  | { readonly kind: 'rate'; readonly rate: number }
  | { readonly kind: 'jump'; readonly toS: number };

/** catchupStep 의 결과 */
export interface LiveCatchupStep {
  readonly state: LiveCatchupState;
  readonly action: LiveCatchupAction;
}

const NONE: LiveCatchupAction = { kind: 'none' };

/** 이 모드로 막 켠 상태 — 배속 1 · 정체 아님 · 건너뛴 적 없음 */
export function initialCatchupState(mode: LiveCatchupMode): LiveCatchupState {
  return { mode, rate: 1, stale: false, freshStreak: 0, lastJumpAtMs: null };
}

/**
 * 재생기를 세울 때의 모드. 라이브가 아니면 off. 시작 위치가 없으면(첫 진입 · 「실시간으로」) full. 시작 위치가 있으면 되감기 →
 * 라이브 자동 넘김이라 rate(넘김에서만 생긴다 — liveHandoff.ts · rewindSwitch.ts)
 */
export function liveCatchupStartMode(
  source: Pick<PlaybackSource, 'kind' | 'startAtMs'>,
): LiveCatchupMode {
  if (source.kind !== 'live') return 'off';
  return source.startAtMs === null ? 'full' : 'rate';
}

/** 배속을 1 로 되돌리는 행동(이미 1 이면 무행동) */
function resetRate(state: LiveCatchupState): LiveCatchupStep {
  if (state.rate === 1) return { state, action: NONE };
  return { state: { ...state, rate: 1 }, action: { kind: 'rate', rate: 1 } };
}

/** 정체 판정을 이 표본으로 갱신한다 — 진입 age > 2 × TD, 회복 age < TD 가 두 표본 연속 */
function trackStale(state: LiveCatchupState, list: LiveListSample): LiveCatchupState {
  if (!state.stale) {
    const stale = list.ageS > LIVE_CATCHUP_STALE_TD_MULTIPLE * list.targetDurationS;
    return stale ? { ...state, stale: true, freshStreak: 0 } : state;
  }
  const freshStreak = list.ageS < list.targetDurationS ? state.freshStreak + 1 : 0;
  if (freshStreak >= FRESH_SAMPLES_TO_RECOVER) return { ...state, stale: false, freshStreak: 0 };
  return { ...state, freshStreak };
}

/** 이 표본에서 건너뛸 자리. 건너뛰지 않으면 null — 넘김 확인 게이트가 모드보다 먼저다 */
function jumpTarget(state: LiveCatchupState, sample: LiveCatchupSample): number | null {
  const target = sample.liveSyncPositionS;
  if (sample.handoffConfirming || state.mode !== 'full') return null;
  if (sample.latencyS <= JUMP_LATENCY_S || target === null) return null;
  if (target - sample.currentTimeS <= JUMP_MIN_FORWARD_S) return null;
  const cooled =
    state.lastJumpAtMs === null || sample.nowMs - state.lastJumpAtMs >= JUMP_COOLDOWN_MS;
  return cooled ? target : null;
}

/**
 * 배속 히스테리시스 — 켤 때 1.05 · 1.1 을 고르고, 켠 뒤에는 끌 때까지 바꾸지 않는다. 점프만 하는 정책은 배속을 켜지 않고,
 * 걸려 있으면 1 로 되돌린다
 */
function stepRate(
  state: LiveCatchupState,
  sample: LiveCatchupSample,
  policy: LiveCatchupPolicy,
): LiveCatchupStep {
  if (policy.kind === 'jumpOnly') return resetRate(state);
  const excess =
    Math.round((sample.latencyS - sample.targetLatencyS) * EXCESS_ROUND_PER_S) / EXCESS_ROUND_PER_S;
  const bufferShort = sample.bufferAheadS < MIN_BUFFER_AHEAD_S;
  if (state.rate !== 1) {
    return excess <= RATE_OFF_EXCESS_S || bufferShort ? resetRate(state) : { state, action: NONE };
  }
  if (excess <= policy.rateOnExcessS || bufferShort) return { state, action: NONE };
  const rate = excess >= FAST_EXCESS_S ? FAST_RATE : SLOW_RATE;
  return { state: { ...state, rate }, action: { kind: 'rate', rate } };
}

/**
 * 표본 하나로 다음 상태와 행동을 정한다. 읽는 순서: rate 는 넘김 확인이 끝난 표본에서 full 로 오른다 → off 면 무행동 → 목록이
 * 없거나 정체면 무행동(배속 중이면 1 로) → 멈춤 · 시크 중이면 무행동 → 건너뛸 수 있으면 건너뜀 → 배속 히스테리시스
 */
export function catchupStep(
  state: LiveCatchupState,
  sample: LiveCatchupSample,
  policy: LiveCatchupPolicy,
): LiveCatchupStep {
  const promoted =
    state.mode === 'rate' && !sample.handoffConfirming
      ? { ...state, mode: 'full' as const }
      : state;
  if (promoted.mode === 'off') return { state: promoted, action: NONE };
  if (sample.list === null) return resetRate(promoted);

  const tracked = trackStale(promoted, sample.list);
  if (tracked.stale) return resetRate(tracked);
  if (sample.paused || sample.seeking) return { state: tracked, action: NONE };

  const target = jumpTarget(tracked, sample);
  if (target !== null) {
    return {
      state: { ...tracked, rate: 1, lastJumpAtMs: sample.nowMs },
      action: { kind: 'jump', toS: target },
    };
  }
  return stepRate(tracked, sample, policy);
}

/**
 * 배속을 바꾸면 잠깐 멎는 WebKit 재생인가 — Safari 와 iOS 의 모든 브라우저(CriOS · FxiOS · EdgiOS 도 WebKit 이다). Chromium
 * 계열은 UA 에 AppleWebKit 을 달고도 Chrome/ 를 함께 단다. 판별할 수 없으면 거짓(Chromium 정책)
 */
export function isWebKitPlayback(nav: Pick<Navigator, 'userAgent'>): boolean {
  const ua = nav.userAgent;
  return /AppleWebKit\//.test(ua) && !/(?:Chrome|Chromium)\//.test(ua);
}

/** 이 브라우저의 배속 정책 — WebKit 재생이면 WEBKIT_LIVE_CATCHUP_POLICY, 아니면 LIVE_CATCHUP_POLICY */
export function liveCatchupPolicy(nav: Pick<Navigator, 'userAgent'>): LiveCatchupPolicy {
  return isWebKitPlayback(nav) ? WEBKIT_LIVE_CATCHUP_POLICY : LIVE_CATCHUP_POLICY;
}

/** 따라잡기가 읽는 hls.js 재생기의 몫(hls.js 1.7.3 getter) */
export type CatchupHls = Pick<Hls, 'targetLatency' | 'liveSyncPosition' | 'latestLevelDetails'>;

/** 유한한 수면 그 값, 아니면 null — 재생기 값은 아직 못 잰 동안 null · 0 · 없음으로 온다 */
function finiteOrNull(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * hls.js 재생기에서 따라잡기 값을 읽는다. 재생기가 없거나(내렸다) · 목록이 없거나 끝났거나(ENDLIST) · 목록 끝 · 목표 · age ·
 * TD 가 수가 아니면 목록을 null 로 낸다 — 그 표본은 판정하지 않고 배속 중이면 1 로 되돌린다(catchupStep)
 */
export function readHlsCatchup(
  hls: CatchupHls | null,
  handoffConfirming: boolean,
): LiveCatchupReading {
  const details = hls?.latestLevelDetails ?? null;
  const edgeS = finiteOrNull(details?.edge);
  const targetLatencyS = finiteOrNull(hls?.targetLatency);
  const ageS = finiteOrNull(details?.age);
  const targetDurationS = finiteOrNull(details?.targetduration);
  const judged =
    details !== null &&
    details.live &&
    edgeS !== null &&
    targetLatencyS !== null &&
    ageS !== null &&
    targetDurationS !== null;
  return {
    liveEdgeS: judged ? edgeS + ageS : 0,
    targetLatencyS: targetLatencyS ?? 0,
    liveSyncPositionS: finiteOrNull(hls?.liveSyncPosition),
    list: judged ? { ageS, targetDurationS } : null,
    handoffConfirming,
  };
}

/** 라이브 재생기 하나의 따라잡기 — watchLiveCatchup 이 돌려준다 */
export interface LiveCatchupWatch {
  /**
   * 이 모드로 켠다(off 면 disarm 과 같다). 이미 켜 있으면 모드만 바꾸고 걸어 둔 배속 · 정체 판정 · 점프 시각은 잇는다.
   * 라이브 재생기(source.kind === 'live')에서만 부른다 — 되감기 · 다시보기 재생기에서는 켜지 않는 것이 배선의 몫이다(D1)
   */
  arm(mode: LiveCatchupMode): void;
  /** 끈다 — 배속을 1 로 되돌린다. 사람의 시크 · 일시정지 · 키 이동 자리에서 부른다 */
  disarm(): void;
  /** 사건 듣기를 멈추고 배속을 1 로 되돌린다. 그 뒤의 arm · 사건은 아무것도 하지 않는다 */
  dispose(): void;
}

/** watchLiveCatchup 에 넘기는 재생기 쪽 값 · 정책 · 시계 */
export interface LiveCatchupWatchOptions {
  /** 켜 있는 동안 timeupdate 마다 부른다 — 재생기의 라이브 끝 추정 위치 · 목표 · 동기 위치 · 목록 · 넘김 확인 여부 */
  read: () => LiveCatchupReading;
  policy: LiveCatchupPolicy;
  /** 시계(ms) — 시험이 바꿔 끼운다 */
  now?: () => number;
}

/** 지금 위치를 담은 buffered 구간의 남은 길이. 그런 구간이 없으면 0 — 뒤쪽 구간은 더하지 않는다 */
function bufferAheadAt(buffered: TimeRanges, time: number): number {
  for (let i = 0; i < buffered.length; i++) {
    if (buffered.start(i) <= time && time < buffered.end(i)) return buffered.end(i) - time;
  }
  return 0;
}

/**
 * 우리가 건 배속이 영상에서 기본 배속(1)으로 풀렸으면 상태도 1 로 맞춘다 — 미디어 load(recoverMediaError)는 ratechange
 * 없이 기본 배속으로 되돌린다. 맞추지 않으면 걸려 있다고 믿고 다시 걸지 않아 따라잡기가 멎는다. 1 이 아닌 다른 배속은 밖
 * (확장 · 브라우저 메뉴)에서 고른 것이라 맞추지 않는다 — 다음 표본에서 덮어쓰지 않게
 */
function syncRate(state: LiveCatchupState, playbackRate: number): LiveCatchupState {
  return state.rate !== 1 && playbackRate === 1 ? { ...state, rate: 1 } : state;
}

/**
 * 영상 노드의 timeupdate 마다 따라잡기를 판정해 배속 · 위치를 바꾼다. 켜기(arm) 전에는 읽지도 바꾸지도 않는다. 스스로
 * 건너뛴 시크로는 꺼지지 않는다 — 자동 점프는 사람 시크가 아니라 seeking 사건을 듣지 않는다
 */
export function watchLiveCatchup(
  video: HTMLVideoElement,
  opts: LiveCatchupWatchOptions,
): LiveCatchupWatch {
  const now = opts.now ?? Date.now;
  let state = initialCatchupState('off');
  let disposed = false;

  const apply = (action: LiveCatchupAction) => {
    if (action.kind === 'rate') {
      video.playbackRate = action.rate;
    } else if (action.kind === 'jump') {
      video.playbackRate = 1;
      video.currentTime = action.toS;
    }
  };

  const onTimeUpdate = () => {
    if (disposed || state.mode === 'off') return;
    const { liveEdgeS, ...reading } = opts.read();
    const currentTimeS = video.currentTime;
    const latencyS = liveEdgeS - currentTimeS;
    // 재생 위치나 잰 지연이 유한한 수가 아니면 문턱 비교가 모두 거짓이 되어 점프 게이트를 지나간다 — 그 표본은 판정하지
    // 않는다. 재생 위치가 유한하지 않으면 지연도 유한하지 않으므로 지연 하나만 본다
    if (!Number.isFinite(latencyS)) return;
    const sample: LiveCatchupSample = {
      ...reading,
      nowMs: now(),
      latencyS,
      currentTimeS,
      bufferAheadS: bufferAheadAt(video.buffered, currentTimeS),
      paused: video.paused,
      seeking: video.seeking,
    };
    const step = catchupStep(syncRate(state, video.playbackRate), sample, opts.policy);
    state = step.state;
    apply(step.action);
  };

  const disarm = () => {
    if (disposed) return;
    state = initialCatchupState('off');
    video.playbackRate = 1;
  };

  video.addEventListener('timeupdate', onTimeUpdate);

  return {
    arm(mode) {
      if (disposed) return;
      if (mode === 'off') {
        disarm();
        return;
      }
      state = { ...state, mode };
    },
    disarm,
    dispose() {
      if (disposed) return;
      disarm();
      disposed = true;
      video.removeEventListener('timeupdate', onTimeUpdate);
    },
  };
}
