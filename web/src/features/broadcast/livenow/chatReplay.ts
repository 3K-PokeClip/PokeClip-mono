// 지난 방송 채팅 다시 보기(POK-324): 채팅 창은 영상 시각까지 나온 줄만 보인다. 10분에서 5분으로 되돌리면 5~10분 채팅은
// 사라지고, 재생하면 그 시각에 나온 줄이 하나씩 다시 붙는다(치지직 다시보기 채팅처럼). 전에는 방송 전체 채팅을 한꺼번에 깔아
// 영상을 어디로 옮겨도 채팅 창은 끝부분 그대로였다.
//
// 수집기(chat-messages)는 시각 오름차순으로만 주고(커서로 이어 받는다) 한 번에 1시간까지만 받는다. 그래서 다 읽은 구간 하나를
// 쥐고 모자란 쪽만 읽는다: 영상 시각 앞 300줄이 차는 자리부터 영상 시각 30초 뒤까지. 늦은 쪽은 이어 읽고, 이른 쪽이 모자라면 그
// 앞만 읽어 붙인다. 방송 전체를 미리 받지 않는다(채팅이 많은 방송은 수만 줄이다).
//
// 시각 축: 요청 from·to는 영상 축이고(수집기가 보정을 더해 표를 찾는다) 답의 시각은 표 축이다. 줄의 at은 답의 시각에서 그 답의
// appliedOffsetMs를 뺀 영상 축 시각이다(services README 「계약 셋」 1번, ADR-066). 고정값(3,900ms)을 빼지 않는다: 채널별 보정이
// 생기면 어긋난다.

import { COLLECTOR_WINDOW_MS, type WireItem, type WirePage } from './chatCollector';
import type { LiveChart } from './liveDataStore';

/** 채팅 창이 쥐는 줄 수. 라이브(최근 300줄)와 지난 방송(영상 시각까지 최근 300줄)이 같은 양이다(사용자 결정 2026-10-12) */
export const CHAT_KEEP = 300;
/** 차트가 없을 때 영상 시각보다 이만큼 이른 데부터 읽는다 */
const FALLBACK_BACK_MS = 60_000;
/** 영상 시각보다 이만큼 늦은 데까지 미리 읽는다: 재생하며 붙는 줄이 늦지 않게 */
const AHEAD_MS = 30_000;
/**
 * 다 읽은 구간에서 이만큼 넘게 떨어진 곳으로 옮기면 처음부터 다시 읽는다. 그 안은 늦은 쪽을 이어 읽거나 이른 쪽을 덧붙인다:
 * 사이를 다 읽어도 싸고, 느린 답을 기다리는 사이 재생이 흘러 시작점이 끝을 조금 넘을 때마다 다시 시작하면 끝내 아무것도 못 읽는다
 */
const RESTART_GAP_MS = 30_000;
/** 버릴 때 영상 시각 앞에 남기는 줄. 보이는 양의 두 배다: 조금 되감을 때마다 다시 읽지 않게 */
const HOLD_BACK = CHAT_KEEP * 2;
/**
 * 버릴 때 영상 시각 앞에 남기는 시간. HOLD_BACK줄과 둘 중 더 많이 남는 쪽이다: 채팅이 빽빽한 구간(하이라이트 둘레)은 600줄이
 * 10초도 안 돼, ← 한 번(10초)이 쥔 기록을 넘어 방금 버린 줄을 다시 받는 동안 채팅 창이 비었다(리뷰 2라운드)
 */
const HOLD_BACK_MS = 30_000;
/**
 * 버릴 줄이 이만큼 쌓이면 한꺼번에 버린다: 오래 틀어도 줄이 쌓이지 않게. 매초 조금씩 버리면 매초 다시 그린다. 미리 읽어 둔 영상
 * 시각 뒤 줄은 세지 않는다
 */
const DROP_BATCH = 1_400;
/** 읽지 못하면 이만큼 뒤 다시 읽는다. 그 사이 재생이 흘러도 다시 묻지 않는다 */
const RETRY_MS = 10_000;
/**
 * 한 쪽의 답을 기다리는 시한. 넘기면 그 요청을 끊고 읽지 못한 것으로 본다(RETRY_MS 뒤 다시 읽는다). 서버가 연결만 받고 답을 끝내지
 * 않으면 읽기가 영영 멈춰, 멈춘 화면은 「불러오고 있어요」에 머물렀다(PR #230 codex). clip은 수집기를 연결 2초·읽기 3초 시한으로
 * 부르므로 정상 답은 이 안에 온다
 */
const PAGE_TIMEOUT_MS = 10_000;
/**
 * 한 번 채울 때 읽는 쪽 수 상한. 다 쓰면 실패로 두고 10초 뒤 이어 읽는다: 서버가 쪽을 끝없이 달아 줘도 요청이 쏟아지지 않고,
 * 영상이 없어 영상 시각이 안 바뀌는 화면도 「불러오고 있어요」에 멈추지 않는다(리뷰 1라운드). 처음부터 다시 읽으면 새로 센다:
 * 연달아 옮기는 동안 받은 정상 답까지 이어 세어 실패한 요청 없이 실패로 섰다(리뷰 2라운드)
 */
const PAGES_PER_PUMP = 50;

/** 다시 보기가 쥔 한 줄 */
export interface ReplayLine {
  /** 이 로더가 매긴 일련번호. 채팅 창의 줄 열쇠다: 채팅과 후원은 표가 달라 id가 겹친다 */
  seq: number;
  item: WireItem;
  /** 영상 축 시각(epoch ms) */
  at: number;
}

/** 다 읽은 것. [from, to) 안의 줄은 빠짐없다 */
export interface ReplaySnapshot {
  /** at 오름차순 */
  lines: readonly ReplayLine[];
  from: number;
  to: number;
  /**
   * 마지막 읽기가 실패했고, 그 뒤로 읽기에 성공하지도 더 읽을 것이 없어지지도 않았다. 멀리 옮겨 처음부터 다시 읽으면 읽기 전에
   * 내린다(새 자리를 읽는 중이다)
   */
  failed: boolean;
}

/**
 * ready: 영상 시각 앞 줄을 보일 만큼 다 읽었다. loading·failed: 영상 시각 쪽을 아직 다 못 읽었다. earlierLoading·earlierFailed:
 * 영상 시각 쪽은 다 있고 그 앞(맨 위)만 모자라 이른 쪽을 더 읽는다
 */
export type ReplayStatus = 'ready' | 'loading' | 'failed' | 'earlierLoading' | 'earlierFailed';

/** 아직 아무것도 읽지 않았다 */
export const EMPTY_REPLAY: ReplaySnapshot = {
  lines: [],
  from: Number.NaN,
  to: Number.NaN,
  failed: false,
};

/** at이 t보다 앞인 줄 수(inclusive면 t와 같은 줄도 센다). 줄은 at 오름차순이다 */
function countBefore(lines: readonly ReplayLine[], t: number, inclusive: boolean): number {
  let lo = 0;
  let hi = lines.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    const at = lines[mid]!.at;
    if (at < t || (inclusive && at === t)) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/**
 * 두 묶음을 잇는다. 뒤 묶음 첫 줄이 앞 묶음 끝 줄보다 이르면 시각 순서로 다시 세운다: 수집기는 표 축 오름차순으로 주고 보정이
 * 쪽마다 같으면 영상 축도 오름차순이지만, 달라져도 순서를 지킨다. 늦은 쪽 읽기·이른 쪽 덧붙이기·붙이기가 같이 쓴다(덧붙이기만
 * 쪽 사이를 보지 않았다, 리뷰 2라운드)
 */
function joinInOrder(head: readonly ReplayLine[], tail: readonly ReplayLine[]): ReplayLine[] {
  const joined = head.concat(tail);
  const last = head[head.length - 1];
  const first = tail[0];
  if (last !== undefined && first !== undefined && first.at < last.at)
    joined.sort((a, b) => a.at - b.at);
  return joined;
}

/** 영상 시각까지(같은 시각 포함)의 줄 중 마지막 CHAT_KEEP줄 */
export function linesUpTo(lines: readonly ReplayLine[], cursorMs: number): ReplayLine[] {
  const end = countBefore(lines, cursorMs, true);
  return lines.slice(Math.max(0, end - CHAT_KEEP), end);
}

/**
 * 영상 시각 앞 CHAT_KEEP줄을 보이려면 어디부터 읽어야 하나(영상 축 epoch ms). 지난 방송 차트의 칸을 영상 시각 앞에서 거꾸로 세어
 * CHAT_KEEP건이 차는 칸의 시작이다: 채팅이 많은 방송은 몇십 초, 적은 방송은 처음부터다. 영상 시각이 걸친 칸은 세지 않는다(그 뒤
 * 줄이 섞여 있다). 방송 전체 차트에서 영상 시각 앞을 다 세어도 모자라면 하한부터다: 차트 첫 칸은 방송 시작에 서서 그 앞(보정
 * 여유)의 채팅은 세지 못한다(리뷰 2라운드). 영상 시각이 첫 칸 안이라 한 칸도 못 센 때도 하한이다: 그때만 60초 앞이면 첫 칸을 넘는
 * 순간 시작점이 하한으로 바뀌어 빈 구간을 덧붙여 읽었다(리뷰 3라운드). 차트가 없거나 라이브 때의 최근 창이거나 영상 시각이
 * 차트보다 앞이면(끝 12시간만 그린 긴 방송의 앞쪽) 60초 앞이다. 하한 아래로 가지 않는다
 */
export function replayReadStart(
  chart: LiveChart | null,
  cursorMs: number,
  lowerMs: number,
): number {
  if (chart !== null && chart.scope !== 'recent' && chart.buckets.length > 0) {
    const span = chart.bucketSeconds * 1000;
    // 칸 시작은 표 축이다
    const atOf = (i: number) => Date.parse(chart.buckets[i]!.start) - chart.appliedOffsetMs;
    let sum = 0;
    let start: number | null = null;
    for (let i = chart.buckets.length - 1; i >= 0; i -= 1) {
      const at = atOf(i);
      if (at + span > cursorMs) continue;
      start = at;
      // 후원도 채팅 창에 선다
      sum += chart.buckets[i]!.chats + chart.buckets[i]!.donations;
      if (sum >= CHAT_KEEP) return Math.max(lowerMs, start);
    }
    if (chart.scope === 'broadcast' && atOf(0) <= cursorMs) return lowerMs;
    if (start !== null) return Math.max(lowerMs, start);
  }
  return Math.max(lowerMs, cursorMs - FALLBACK_BACK_MS);
}

/**
 * 영상 시각에 보일 줄과 상태. 영상 시각까지 빠짐없이 읽었으면 ready, 아니면 읽는 중이거나 실패다: 빈 창을 「채팅 없음」처럼
 * 보이지 않는다. 방송 끝까지 읽었으면 끝 뒤에는 읽을 것이 없다. 영상 시각 쪽은 다 있는데 다 읽은 구간이 읽기 시작점보다 늦게 서서
 * 그 앞 줄이 300에 못 미치면 earlierLoading·earlierFailed다(보이는 줄은 그대로). 로더가 지금 덧붙이는 중인지가 아니라 화면이 정한
 * 읽기 시작점으로 가른다: 덧붙이기는 앞 요청이 끝나야 서서, 그 사이 몇 줄만 보이는 창을 다 읽은 것처럼 그렸다(리뷰 3라운드)
 * @param readFromMs 영상 시각 앞 줄을 채우려면 읽기 시작할 자리(replayReadStart). 로더에 넘긴 것과 같은 값
 */
export function replayView(
  snap: ReplaySnapshot,
  cursorMs: number,
  upperMs: number,
  readFromMs: number,
): { lines: ReplayLine[]; status: ReplayStatus } {
  const lines = linesUpTo(snap.lines, cursorMs);
  const covered = snap.from <= cursorMs && (cursorMs < snap.to || snap.to >= upperMs);
  if (!covered) return { lines, status: snap.failed ? 'failed' : 'loading' };
  if (snap.from > readFromMs && lines.length < CHAT_KEEP)
    return { lines, status: snap.failed ? 'earlierFailed' : 'earlierLoading' };
  return { lines, status: 'ready' };
}

/**
 * 영상 축 [fromMs, toMs)의 한 쪽을 받는다. cursor는 앞 쪽의 nextCursor다. 처음부터 다시 읽거나 답이 시한(10초)을 넘기면 signal로
 * 그 요청을 끊는다
 */
export type ReplayFetch = (
  fromMs: number,
  toMs: number,
  cursor: string | null,
  signal: AbortSignal,
) => Promise<WirePage>;

/** 한 창 [fromMs, toMs)을 읽는 자리. cursor가 있으면 그 창의 다음 쪽이다 */
interface WindowAsk {
  fromMs: number;
  toMs: number;
  cursor: string | null;
}

/**
 * 이른 쪽을 덧붙이는 읽기. [start, end)를 at부터 다 읽은 뒤 한 번에 앞에 붙인다: 다 읽기 전에 붙이면 [from, to) 안에 빈 곳이
 * 생긴다. 그동안 쥔 줄은 그대로 보인다(비우고 처음부터 읽으면 보이던 줄이 사라졌다가 같은 줄을 다시 받았다, 리뷰 1라운드)
 */
interface FrontRead {
  start: number;
  at: number;
  end: number;
  open: WindowAsk | null;
  lines: ReplayLine[];
}

/** 영상 시각과, 그 앞 줄을 채우려면 읽기 시작할 자리(want가 정한다) */
interface Wanted {
  cursorMs: number;
  readFromMs: number;
}

/**
 * 한 방송의 채팅을 영상 시각 둘레만 읽어 쥔다. 방송이 바뀌면 새로 만든다(하한·상한이 방송의 것이다). React는 subscribe·
 * getSnapshot으로 붙는다(useSyncExternalStore)
 */
export class ChatReplayLoader {
  private lines: ReplayLine[] = [];
  private from = Number.NaN;
  private to = Number.NaN;
  /** 늦은 쪽을 이어 읽을 창: 마지막 쪽에 커서가 달려 왔다 */
  private open: WindowAsk | null = null;
  private front: FrontRead | null = null;
  /** 마지막으로 정한 영상 시각과 읽기 시작점. 아직 없으면 NaN이다 */
  private wanted: Wanted = { cursorMs: Number.NaN, readFromMs: Number.NaN };
  /** 이 세대의 요청을 끊는 손잡이. 처음부터 다시 읽으면 끊고 새로 만든다: 끊긴 요청의 답·실패는 버린다 */
  private controller = new AbortController();
  private nextSeq = 1;
  private pumping = false;
  private failed = false;
  private retryTimer: number | undefined;
  private disposed = false;
  private snapshot: ReplaySnapshot = EMPTY_REPLAY;
  private readonly listeners = new Set<() => void>();

  /**
   * @param lowerMs 읽을 하한(영상 축)
   * @param upperMs 읽을 상한(방송 끝)
   */
  constructor(
    private readonly fetchPage: ReplayFetch,
    private readonly lowerMs: number,
    private readonly upperMs: number,
  ) {}

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getSnapshot = (): ReplaySnapshot => this.snapshot;

  /**
   * 영상 시각이 정해졌다(재생하며 매초, 옮기면 바로). 모자란 곳만 읽는다
   * @param readFromMs 영상 시각 앞 줄을 채우려면 읽기 시작할 자리(replayReadStart)
   */
  want(cursorMs: number, readFromMs: number): void {
    if (this.disposed) return;
    const cursor = Math.min(this.upperMs, Math.max(this.lowerMs, cursorMs));
    const readFrom = Math.min(cursor, Math.max(this.lowerMs, readFromMs));
    this.wanted = { cursorMs: cursor, readFromMs: readFrom };
    if (this.mustRestart(cursor, readFrom)) this.restart(readFrom);
    else this.trim(cursor);
    this.emit();
    void this.pump();
  }

  dispose(): void {
    this.disposed = true;
    this.controller.abort();
    window.clearTimeout(this.retryTimer);
    this.listeners.clear();
  }

  private mustRestart(cursor: number, readFrom: number): boolean {
    if (Number.isNaN(this.from)) return true;
    // 다 읽은 구간보다 멀리 늦은 쪽으로 옮겼다: 사이를 다 읽지 않는다
    if (readFrom > this.to + RESTART_GAP_MS) return true;
    // 다 읽은 구간보다 멀리 이른 쪽으로 되감았다: 그 사이를 다 읽지 않는다. 가까우면 그 앞을 덧붙인다(pump)
    return cursor + RESTART_GAP_MS < this.from;
  }

  /** 영상 시각 앞 줄이 모자라 다 읽은 구간보다 이른 쪽을 더 읽어야 하나 */
  private needsFront(): boolean {
    const { cursorMs, readFromMs } = this.wanted;
    return this.from > readFromMs && countBefore(this.lines, cursorMs, true) < CHAT_KEEP;
  }

  /**
   * 덧붙이기를 세우거나 거둔다. 덧붙이는 사이 영상 시각이 늦은 쪽으로 가 더는 모자라지 않으면 거둔다: 필요 없어진 덧붙이기를
   * 끝까지 읽느라 옮긴 자리의 늦은 쪽 읽기가 늦었다(리뷰 2라운드)
   */
  private reviewFront(): void {
    const needed = this.needsFront();
    if (this.front !== null && !needed) this.front = null;
    else if (this.front === null && needed) {
      const start = this.wanted.readFromMs;
      this.front = { start, at: start, end: this.from, open: null, lines: [] };
    }
  }

  private restart(readFrom: number): void {
    // 앞서 보낸 요청은 끊는다: 기다렸다 버리면 옮긴 자리의 첫 답이 그만큼 늦고, 멈춘 요청이면 어디로 옮겨도 못 읽었다(리뷰 1라운드)
    this.controller.abort();
    this.controller = new AbortController();
    this.lines = [];
    this.from = readFrom;
    this.to = readFrom;
    this.open = null;
    this.front = null;
    this.failed = false;
    window.clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
  }

  /**
   * 영상 시각 앞 줄이 많이 쌓였으면 오래된 쪽을 버린다. 남기는 것은 영상 시각 앞 HOLD_BACK줄과 HOLD_BACK_MS 중 더 많은 쪽이다.
   * 덧붙이는 동안은 버리지 않는다: 덧붙이기는 세울 때의 앞 경계(front.end)까지 읽어 붙이므로, 그 사이 앞 경계를 올리면 붙일 때 그
   * 사이 줄이 빈 채로 「다 읽음」이 되어 영상 시각보다 이른 줄을 보였다(리뷰 2라운드. 1라운드에 「같은 회차의 버리기가 틈을
   * 지운다」며 이 막이를 지웠는데, 영상 시각이 틈 근처로 되돌아오면 지우지 못한다)
   */
  private trim(cursor: number): void {
    if (this.front !== null) return;
    const before = countBefore(this.lines, cursor, true);
    // 남길 첫 줄의 자리 = 버릴 줄 수
    const cut = Math.min(before - HOLD_BACK, countBefore(this.lines, cursor - HOLD_BACK_MS, false));
    if (cut < DROP_BATCH) return;
    const cutAt = this.lines[cut]!.at;
    // 같은 시각의 줄을 반만 버리지 않는다: cutAt인 줄은 남기고 그 앞만 버린다
    const drop = countBefore(this.lines, cutAt, false);
    if (drop <= 0) return;
    this.lines = this.lines.slice(drop);
    this.from = cutAt;
  }

  private async pump(): Promise<void> {
    if (this.pumping) return;
    this.pumping = true;
    try {
      let pages = 0;
      for (;;) {
        if (Number.isNaN(this.wanted.cursorMs) || this.disposed) return;
        // 실패 뒤 다시 읽기를 기다린다
        if (this.retryTimer !== undefined) return;
        this.reviewFront();
        const front = this.front;
        if (front === null && this.to >= Math.min(this.upperMs, this.wanted.cursorMs + AHEAD_MS)) {
          // 더 읽을 것이 없다: 앞선 실패는 이제 보이는 것과 상관없다. 남겨 두면 나중의 정상 읽기 동안 「불러오지 못했어요」가
          // 섰다(리뷰 3라운드)
          this.failed = false;
          this.emit();
          return;
        }
        if (pages >= PAGES_PER_PUMP) {
          this.fail();
          return;
        }
        const signal = this.controller.signal;
        const ask =
          front !== null
            ? (front.open ?? {
                fromMs: front.at,
                toMs: Math.min(front.end, front.at + COLLECTOR_WINDOW_MS),
                cursor: null,
              })
            : (this.open ?? {
                fromMs: this.to,
                toMs: Math.min(this.upperMs, this.to + COLLECTOR_WINDOW_MS),
                cursor: null,
              });
        let page: WirePage;
        let added: ReplayLine[];
        try {
          page = await this.fetchWithin(ask, signal);
          added = this.parse(page);
        } catch {
          if (this.disposed) return;
          // 처음부터 다시 읽기 시작한 뒤에 온 옛 실패(끊은 요청 포함)는 버리고, 새 세대는 쪽 수를 새로 센다
          if (signal.aborted) {
            pages = 0;
            continue;
          }
          this.fail();
          return;
        }
        if (this.disposed) return;
        if (signal.aborted) {
          pages = 0;
          continue;
        }
        pages += 1;
        if (front !== null) this.appendFront(front, page, added, ask);
        else this.append(page, added, ask);
        this.failed = false;
        this.trim(this.wanted.cursorMs);
        this.emit();
      }
    } finally {
      this.pumping = false;
    }
  }

  /**
   * 한 쪽을 PAGE_TIMEOUT_MS 안에 받는다. 넘기면 그 요청을 끊고 던진다(읽지 못함). 처음부터 다시 읽으면(signal) 그 요청도 끊는다.
   * 답과 시한을 겨룬다: 끊기를 듣지 않는 대기(토큰 갱신을 기다리는 동안 등)도 시한에 끝난다
   */
  private fetchWithin(ask: WindowAsk, signal: AbortSignal): Promise<WirePage> {
    const request = new AbortController();
    const stop = () => request.abort();
    signal.addEventListener('abort', stop);
    let timer: number | undefined;
    const late = new Promise<never>((_, reject) => {
      timer = window.setTimeout(() => {
        stop();
        reject(new Error('chat page timeout'));
      }, PAGE_TIMEOUT_MS);
    });
    return Promise.race([
      this.fetchPage(ask.fromMs, ask.toMs, ask.cursor, request.signal),
      late,
    ]).finally(() => {
      window.clearTimeout(timer);
      signal.removeEventListener('abort', stop);
    });
  }

  /**
   * 쪽의 줄. 받기와 같은 try 안에서 부른다: 계약을 어긴 쪽(items가 없음)은 여기서 던져 읽기 실패로 센다. 밖에서 던지면 실패로
   * 서지 않아 재생 중 매초 다시 물었다(리뷰 1라운드)
   */
  private parse(page: WirePage): ReplayLine[] {
    return page.items.map((item) => ({
      seq: this.nextSeq++,
      item,
      at: Date.parse(item.time) - page.appliedOffsetMs,
    }));
  }

  private append(page: WirePage, added: ReplayLine[], ask: WindowAsk): void {
    this.lines = joinInOrder(this.lines, added);
    if (page.nextCursor !== null && added.length > 0) {
      this.open = { fromMs: ask.fromMs, toMs: ask.toMs, cursor: page.nextCursor };
      // 마지막 줄과 같은 시각의 줄이 다음 쪽에 더 있을 수 있다: 그 시각 앞까지만 빠짐없다
      this.to = Math.max(this.to, added[added.length - 1]!.at);
    } else {
      // 이 창은 다 읽었다. 빈 쪽에 커서가 달려 와도 끝으로 본다: 그 커서를 따라가면 끝없이 묻는다
      this.open = null;
      this.to = Math.max(this.to, ask.toMs);
    }
  }

  private appendFront(front: FrontRead, page: WirePage, added: ReplayLine[], ask: WindowAsk): void {
    front.lines = joinInOrder(front.lines, added);
    if (page.nextCursor !== null && added.length > 0) {
      front.open = { fromMs: ask.fromMs, toMs: ask.toMs, cursor: page.nextCursor };
      return;
    }
    front.open = null;
    front.at = ask.toMs;
    if (front.at < front.end) return;
    // 다 읽었다: 한 번에 앞에 붙인다. 덧붙이는 동안은 버리지 않아(trim) 앞 경계가 세울 때(front.end) 그대로다
    this.lines = joinInOrder(front.lines, this.lines);
    this.from = front.start;
    this.front = null;
  }

  /**
   * 실패 표시는 다시 읽어 성공하거나 더 읽을 것이 없어져야 내린다(멀리 옮기면 restart가 바로 내린다): 다시 읽기 전에 내리면 실패가
   * 이어지는 동안 알림이 10초마다 두 번 바뀌었다(리뷰 2라운드)
   */
  private fail(): void {
    this.failed = true;
    this.emit();
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = undefined;
      void this.pump();
    }, RETRY_MS);
  }

  /**
   * 스냅숏 칸(lines·from·to·failed)이 바뀌었을 때만 알린다: 덧붙이기의 중간 쪽처럼 아무것도 안 바뀐 답에도 대시보드를 다시
   * 그렸다(리뷰 2라운드). 영상 시각 뒤 줄만 붙어도 lines가 바뀌므로 알린다
   */
  private emit(): void {
    if (this.disposed) return;
    const prev = this.snapshot;
    if (
      prev.lines === this.lines &&
      Object.is(prev.from, this.from) &&
      Object.is(prev.to, this.to) &&
      prev.failed === this.failed
    )
      return;
    this.snapshot = { lines: this.lines, from: this.from, to: this.to, failed: this.failed };
    for (const listener of this.listeners) listener();
  }
}
