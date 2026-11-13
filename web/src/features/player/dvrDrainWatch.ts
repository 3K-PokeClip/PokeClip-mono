// 404 로 받기를 멈춘 되감기 재생기가 받아 둔 영상을 다 쓰고 멎었는지(고갈) 본다(POK-547). 받기를 멈추면 hls.js 는 남은
// 버퍼 끝의 정체를 풀어 주지 않는다 — 건너뛸 뒤쪽 구간이 없기 때문이다(gap-controller.ts 505–511 · 585–591). 그대로 두면
// 스피너가 붙박이므로, 멎은 것을 알려 다시보기 진입 덮개를 띄우게 한다. 영상 노드의 미디어 사건과 값만 보는 순수 감시라
// 재생 훅 밖에 둔다(bufferingWatch 와 같은 꼴). 목록 끝 정체(끝 도달)는 여기서 셈하지 않고 settleEnd 로 재생 훅에 맡긴다 —
// 규칙이 두 벌로 갈라지지 않게 한다.

/** 앞 버퍼(지금 위치를 담은 구간의 남은 길이)가 이보다 짧아야 멎은 후보로 본다 */
export const DRAIN_AHEAD_S = 0.5;
/** 후보 기간 전체에 이만큼 나아가면 멎은 것이 아니다. 고갈을 푸는 진행 문턱도 같다 */
export const DRAIN_PROGRESS_S = 0.1;
/** 후보 기간에 waiting 을 봤을 때 고갈로 판정하기까지 */
export const DRAIN_STALL_WAITING_MS = 1_000;
/** waiting 을 못 봤을 때 — 브라우저가 waiting 을 늘 내지는 않는다 */
export const DRAIN_STALL_SILENT_MS = 2_000;

/** HAVE_FUTURE_DATA — 받아 둔 영상으로 재생이 이어진다 */
const CAN_KEEP_PLAYING = 3;

/** 점검 계기. stalled 는 계기로만 쓰고 정체의 증거로 치지 않는다(받아 둔 영상을 트는 중에도 온다) */
const CHECK_EVENTS = ['waiting', 'stalled', 'timeupdate', 'seeked', 'playing', 'canplay'] as const;

export interface DvrDrainWatch {
  /**
   * 무장한다(받기 중단 뒤 한 번). 이 순간 seeking 이고 그 목표가 어느 buffered 구간에도 없으면 곧바로 고갈을 알린다 —
   * 멈춘 채 버퍼 밖으로 시크한 뒤 다시 틀면 seeking 사건은 이미 지나갔다. 이어서 점검을 한 번 돈다. 이미 무장했으면
   * 점검만 돈다(재던 후보는 그대로)
   */
  arm(): void;
  /** 바깥 계기(재생 훅의 1초 틱)의 점검. 나머지 계기는 스스로 듣는다 */
  check(): void;
  /** 사건 듣기를 멈춘다. 그 뒤의 무장 · 점검 · 사건은 아무것도 알리지 않는다 */
  dispose(): void;
}

export interface DvrDrainWatchOptions {
  /**
   * 판정 직전마다 부른다(무장한 뒤에만). 재생 훅이 ended · 목록 끝 정체를 먼저 셈해 끝을 올리고, 끝이 섰으면 참이다.
   * 참이면 그 점검에서는 고갈을 보지 않는다 — 끝에 닿은 시청자는 처음부터 봐야 한다
   */
  settleEnd: () => boolean;
  /** 위치 시대 — 고갈 중 같은 시대의 버퍼 밖 시크는 다시 알리지 않는다 */
  epoch: () => number;
  /** 고갈(true) · 회복(false). atTime = 멎은 currentTime · 버퍼 밖 시크면 그 목표 · 회복이면 그때의 currentTime */
  onChange: (drained: boolean, atTime: number) => void;
  /** 시계(ms) — 시험이 바꿔 끼운다 */
  now?: () => number;
}

/** 멎은 후보 — 후보가 처음 선 시각 · 자리와, 그 뒤 waiting 을 봤는지 */
interface DrainCandidate {
  readonly sinceMs: number;
  readonly fromTime: number;
  readonly sawWaiting: boolean;
}

/** 고갈 상태 — 알린 위치 시대와, 회복을 재는 기준 자리(멎은 자리 · 시크가 닿은 자리) */
interface DrainedState {
  readonly epoch: number;
  readonly fromTime: number;
}

/** 이 자리를 담은 buffered 구간의 끝. 그런 구간이 없으면 null */
function bufferedEndAt(buffered: TimeRanges, time: number): number | null {
  for (let i = 0; i < buffered.length; i++) {
    if (buffered.start(i) <= time && time <= buffered.end(i)) return buffered.end(i);
  }
  return null;
}

/** 지금 위치를 담은 구간의 남은 길이. 그런 구간이 없으면 0 — 뒤쪽 구간은 더하지 않는다 */
function bufferAhead(buffered: TimeRanges, time: number): number {
  const end = bufferedEndAt(buffered, time);
  return end === null ? 0 : end - time;
}

/** 재생하려는데 받아 둔 영상이 바닥나 멎어 있는 모양인가(진행 여부는 따로 센다) */
function looksStarved(video: HTMLVideoElement): boolean {
  return (
    !video.paused &&
    !video.seeking &&
    !video.ended &&
    video.error === null &&
    video.playbackRate > 0 &&
    video.readyState < CAN_KEEP_PLAYING &&
    bufferAhead(video.buffered, video.currentTime) < DRAIN_AHEAD_S
  );
}

/** 받기를 멈춘 재생기의 고갈 · 회복을 상태가 바뀔 때만 알린다. 무장하기 전에는 아무것도 셈하지 않는다 */
export function watchDvrDrain(video: HTMLVideoElement, opts: DvrDrainWatchOptions): DvrDrainWatch {
  const now = opts.now ?? Date.now;
  let armed = false;
  let disposed = false;
  let candidate: DrainCandidate | null = null;
  let drained: DrainedState | null = null;

  const notifyDrained = (atTime: number) => {
    const epoch = opts.epoch();
    // 고갈 중 같은 시대에서 다시 알리지 않는다. 시대가 바뀐 버퍼 밖 시크는 새 자리를 알린다
    if (drained !== null && drained.epoch === epoch) return;
    drained = { epoch, fromTime: atTime };
    candidate = null;
    console.info('[player] 받기를 멈춘 뒤 재생이 멎었어요:', atTime);
    opts.onChange(true, atTime);
  };

  /** 시크 목표가 받아 둔 영상 밖이면 seeked 를 기다리지 않는다 — 받기를 멈춰 그 자리는 영영 오지 않는다 */
  const checkSeekTarget = () => {
    if (!video.seeking || bufferedEndAt(video.buffered, video.currentTime) !== null) return;
    notifyDrained(video.currentTime);
  };

  /** 고갈 중: 시크 요청이 아니라 실제 진행으로만 푼다 */
  const checkRecovery = (state: DrainedState) => {
    if (video.paused || video.seeking) return;
    if (video.currentTime - state.fromTime < DRAIN_PROGRESS_S) return;
    drained = null;
    candidate = null;
    opts.onChange(false, video.currentTime);
  };

  const checkStarved = (sawWaiting: boolean) => {
    if (!looksStarved(video)) {
      candidate = null;
      return;
    }
    const at = now();
    // 진행은 후보가 선 뒤 쌓인 양이다 — 직전 점검과의 차이로 세면, 점검마다 조금씩이지만 실제로 나아가는 재생을 멎은
    // 것으로 잘못 잡는다
    if (
      candidate === null ||
      Math.abs(video.currentTime - candidate.fromTime) >= DRAIN_PROGRESS_S
    ) {
      candidate = { sinceMs: at, fromTime: video.currentTime, sawWaiting };
      return;
    }
    if (sawWaiting && !candidate.sawWaiting) candidate = { ...candidate, sawWaiting };
    const limitMs = candidate.sawWaiting ? DRAIN_STALL_WAITING_MS : DRAIN_STALL_SILENT_MS;
    if (at - candidate.sinceMs >= limitMs) notifyDrained(video.currentTime);
  };

  const runCheck = (sawWaiting: boolean) => {
    // 무장 전에는 끝 셈(settleEnd)도 부르지 않는다 — 무장하지 않은 재생기의 끝 판정 시각을 바꾸지 않는다
    if (!armed || disposed) return;
    if (opts.settleEnd()) {
      candidate = null;
      return;
    }
    if (drained !== null) {
      checkRecovery(drained);
      return;
    }
    checkStarved(sawWaiting);
  };

  /** 고갈 중이면 회복을 지금 자리(시크 목표 · 시크가 닿은 자리)부터 잰다 — 시크 거리는 나아간 것이 아니다 */
  const restartRecovery = () => {
    if (drained !== null) drained = { ...drained, fromTime: video.currentTime };
  };

  const onCheckEvent = (event: Event) => {
    if (event.type === 'seeked') restartRecovery();
    runCheck(event.type === 'waiting');
  };
  const onSeeking = () => {
    candidate = null;
    // 표준 순서로는 seeking 이 내려간 뒤 timeupdate 가 seeked 보다 먼저 온다 — 그 timeupdate 가 시크 거리를 회복으로
    // 세지 않게 시크가 시작될 때 기준을 옮긴다
    restartRecovery();
    if (armed && !disposed) checkSeekTarget();
  };
  const onPause = () => {
    candidate = null;
  };

  for (const type of CHECK_EVENTS) video.addEventListener(type, onCheckEvent);
  video.addEventListener('seeking', onSeeking);
  video.addEventListener('pause', onPause);

  return {
    arm() {
      if (disposed) return;
      if (!armed) {
        armed = true;
        checkSeekTarget();
      }
      runCheck(false);
    },
    check() {
      runCheck(false);
    },
    dispose() {
      disposed = true;
      for (const type of CHECK_EVENTS) video.removeEventListener(type, onCheckEvent);
      video.removeEventListener('seeking', onSeeking);
      video.removeEventListener('pause', onPause);
    },
  };
}
