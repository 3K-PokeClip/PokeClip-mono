import type { TrackPiece } from './loadTrackAudio';
import { frameClock, sameMark, type ClockMark, type VideoClock } from './videoFrameClock';

// 트랙별 미리 듣기 재생기(POK-389). 트랙마다 음량 노드 하나를 두고, 풀어 둔 덩어리를 「지금 자리」에 맞춰 건다.
// 영상은 그대로 <video>가 틀고 소리만 여기서 난다: followVideo가 영상 노드의 사건을 따라 다시 건다.

export type AudioContextLike = Pick<
  AudioContext,
  'currentTime' | 'state' | 'destination' | 'resume' | 'createGain' | 'createBufferSource'
>;

/** 소리와 영상이 이만큼 넘게 벌어지면 영상 자리로 다시 건다. 한 프레임(30fps 33ms)보다 크고 귀에 띄는 어긋남(약 0.1초)보다 작다 */
export const DRIFT_SECONDS = 0.08;
const DRIFT_CHECK_MS = 250;
/**
 * 재생 시작 · 옮긴 뒤 영상이 처음 나아간 것을 보고 이만큼 지나 한 번 더 건다(ms). 실측 M6 r2(2026-11-13 · README 40–41줄)
 * 에서 시작 뒤 영상이 뒤처지는 몫(Chrome 34–44ms · WebKit · Safari 55–106ms)은 playing 닻 뒤 50–200ms 사이에 생겼다.
 * 그 뒤로 더 뒤처진 회차도 있다(results/summary.txt WebKit 「닻 뒤」 줄의 +300ms 값): 그 몫은 어긋남 검사(DRIFT_SECONDS)가
 * 맡는다
 */
export const SETTLE_MS = 300;
/** 영상이 처음 나아갔는지 이 간격으로 본다(ms). 본 때는 실제보다 이만큼까지 늦을 뿐 이르지 않다: 거는 때는 늘 나아간 뒤 SETTLE_MS 이상이다 */
export const SETTLE_POLL_MS = 50;

export class TrackAudioPlayer {
  private readonly gains: GainNode[];
  private sources: AudioBufferSourceNode[] = [];
  private anchor: { contextTime: number; sourceSeconds: number; rate: number } | null = null;

  constructor(
    private readonly ctx: AudioContextLike,
    private readonly tracks: readonly (readonly TrackPiece[])[],
    /** 소리 문맥을 깨우지 못했다. 미리 듣기를 접고 영상 소리로 물러나야 한다(영상은 꺼 둔 채라 아무 소리도 안 난다) */
    private readonly onBroken: () => void = () => {},
  ) {
    this.gains = tracks.map(() => {
      const gain = ctx.createGain();
      gain.gain.value = 0;
      gain.connect(ctx.destination);
      return gain;
    });
  }

  /** 트랙 번호 순 음량(1 = 100%). 끈 트랙은 0 */
  setGains(values: readonly number[]): void {
    this.gains.forEach((gain, i) => {
      gain.gain.value = values[i] ?? 0;
    });
  }

  /** 편집기 시간축 sourceSeconds부터 rate 배속으로 튼다. 앞에 걸어 둔 것은 세운다 */
  play(sourceSeconds: number, rate: number): void {
    this.stop();
    // 브라우저는 사람의 조작 전에 만든 소리 문맥을 멈춰 둔다: 재생 버튼 뒤에 깨운다
    if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => this.onBroken());
    const now = this.ctx.currentTime;
    this.tracks.forEach((pieces, i) => {
      const gain = this.gains[i]!; // 트랙마다 하나씩 만들었다(생성자)
      for (const piece of pieces) {
        if (piece.startSeconds + piece.buffer.duration <= sourceSeconds) continue;
        const node = this.ctx.createBufferSource();
        node.buffer = piece.buffer;
        node.playbackRate.value = rate;
        node.connect(gain);
        const ahead = piece.startSeconds - sourceSeconds;
        if (ahead >= 0) node.start(now + ahead / rate);
        else node.start(now, -ahead);
        this.sources.push(node);
      }
    });
    this.anchor = { contextTime: now, sourceSeconds, rate };
  }

  stop(): void {
    for (const node of this.sources) {
      node.stop();
      node.disconnect();
    }
    this.sources = [];
    this.anchor = null;
  }

  /** 소리 시계가 흐르는가. 브라우저가 문맥을 멈춰 두면(suspended) 시계가 서서 어긋남을 잴 수 없다 */
  get running(): boolean {
    return this.ctx.state === 'running';
  }

  /** 소리가 지금 내고 있는 자리(편집기 초). 세워 두었으면 null */
  position(): number | null {
    const anchor = this.anchor;
    if (anchor === null) return null;
    return anchor.sourceSeconds + (this.ctx.currentTime - anchor.contextTime) * anchor.rate;
  }

  dispose(): void {
    this.stop();
    for (const gain of this.gains) gain.disconnect();
  }
}

/** HTMLMediaElement.HAVE_FUTURE_DATA: 지금 장면 다음까지 받아 두었다(흐를 수 있다) */
const HAVE_FUTURE_DATA = 3;

/**
 * 소리를 영상 노드에 묶는다. 영상 소리를 끄는 것은 훅(useTrackPreview)이 맡는다: 다시 받는 동안에도 꺼 둬야 해서다.
 * @param clock 영상의 지금 자리를 재는 시계. 그만두면 같이 버린다
 * @returns 그만두기: 소리를 세우고 영상 소리를 원래대로
 */
export function followVideo(
  player: TrackAudioPlayer,
  video: HTMLVideoElement,
  clock: VideoClock = frameClock(video),
): () => void {
  // 장면이 실제로 나가고 있을 때만 튼다. 버퍼링 중(waiting)에는 paused·seeking이 false라도 장면이 멈춰 있다:
  // 그때 틀면 소리만 앞서 가고 어긋남 검사가 0.25초마다 같은 자리를 다시 틀었다(로컬 리뷰 1라운드)
  const flowing = () => !video.paused && !video.seeking && video.readyState >= HAVE_FUTURE_DATA;
  // 흐른다던 영상이 섰다고 주기 검사가 알아챘다(아래 check). 이 동안은 어느 길(배속 바꿈 · 다시 맞춤)로도 소리를 켜지 않는다:
  // 켜면 영상은 서 있는데 소리만 흐르고, 검사는 이미 멈춤이라 다시 세우지 않았다(리뷰 M6 2차 r1). 푸는 것은 start 뿐이다
  let stalled = false;
  const sync = () => {
    if (flowing() && !stalled) player.play(clock.position(), video.playbackRate);
    else player.stop();
  };

  // 재생 시작 · 옮긴 뒤(playing · seeked)에는 영상 시계가 아직 출발 전이고, 출발한 뒤에도 잠깐 더 뒤처진다(SETTLE_MS). 문턱
  // (DRIFT_SECONDS) 안이면 어긋남 검사로는 다시 맞추지 않아 재생 내내 굳는다: 영상이 처음 나아간 것을 SETTLE_POLL_MS 마다
  // 살피다가, 본 때부터 SETTLE_MS 뒤 한 번만 더 건다. 시작 때부터 재면 늦게 출발한 만큼 덜 기다린다(리뷰 M6 2차 r1: 280ms 에
  // 출발하면 20ms). 시작 직후 5–16ms 에 오는 timeupdate 로 맞추면 그 뒤 뒤처짐이 그대로 남았다(실측 M6 r2 README 41줄)
  let settleTimer: ReturnType<typeof setTimeout> | null = null;
  const cancelSettle = () => {
    if (settleTimer !== null) clearTimeout(settleTimer);
    settleTimer = null;
  };
  const settle = () => {
    settleTimer = null;
    if (flowing() && player.running) sync();
  };
  const armSettle = (from: ClockMark) => {
    cancelSettle();
    settleTimer = setTimeout(() => {
      settleTimer = null;
      if (!flowing()) return;
      if (sameMark(from, clock.mark())) armSettle(from);
      else settleTimer = setTimeout(settle, SETTLE_MS);
    }, SETTLE_POLL_MS);
  };

  // 주기 검사의 앞 표지. 그 사이 영상이 전혀 안 나아갔으면 멈춘 것이다
  let lastMark: ClockMark | null = null;
  // 멈춤은 두 시계(장면 · currentTime)를 다 믿을 수 있을 때만 판정한다. 장면을 아직 하나도 못 받았으면(프레임 콜백이 없는
  // 엔진) currentTime 만 남는데 검사 간격보다 드물게 바뀔 수 있고, 숨은 탭은 장면 콜백이 안 돈다: 정상 재생을 멈춤으로 보고
  // 소리를 껐다(리뷰 M6 2차 r1)
  const canJudgeStall = (mark: ClockMark) =>
    mark.frames > 0 && document.visibilityState === 'visible';
  const start = () => {
    stalled = false;
    lastMark = null;
    sync();
    if (flowing()) armSettle(clock.mark());
    else cancelSettle();
  };
  const halt = () => {
    cancelSettle();
    player.stop();
  };
  // 배속 바꿈: 새 배속으로 바로 걸고, 영상이 새 배속에 자리 잡는 동안 뒤처지는 몫(문턱 안 51–66ms)이 그 구간 내내 남지 않게
  // 재생 시작처럼 한 번 더 건다(리뷰 M6 2차 cp1). 멈춤 동안은 sync 가 켜지 않고 다시 맞출 것도 무장하지 않는다
  const rerate = () => {
    sync();
    if (flowing() && !stalled) armSettle(clock.mark());
  };
  const startEvents = ['playing', 'seeked'] as const;
  const haltEvents = ['pause', 'ended', 'waiting', 'seeking'] as const;
  for (const name of startEvents) video.addEventListener(name, start);
  video.addEventListener('ratechange', rerate);
  for (const name of haltEvents) video.addEventListener(name, halt);

  // 영상이 버벅이거나(버퍼링) 시계가 서로 조금씩 밀리면 소리가 앞서 간다: 주기적으로 재서 맞춘다
  const check = () => {
    if (!flowing()) return;
    // 재생 중 장면이 서도 readyState 가 3 아래로 안 내려가고 waiting 도 안 오는 때가 있다(실측 M6 r2 WebKit 최대 1925ms ·
    // README 59 · 62줄). 그때 소리를 흘리면 영상보다 앞서 가고, 같은 자리로 다시 걸면 같은 소리가 되풀이된다: 세워 두고 다시
    // 흐르면 처음처럼 건다
    const mark = clock.mark();
    const stood = lastMark !== null && sameMark(lastMark, mark);
    lastMark = mark;
    // 멈춤 동안은 서 있는 한 그대로 둔다(판정을 못 하게 되어도 — 예: 탭을 숨김 — 풀린 것은 아니다)
    if (stood && stalled) return;
    if (stood && canJudgeStall(mark)) {
      stalled = true;
      halt();
      clock.restart();
      return;
    }
    if (stalled) {
      start();
      return;
    }
    // 소리 시계가 서 있으면(suspended) 잴 수 없다: 다시 걸어도 같다. 시계가 흐르기 시작하면 한 번 맞춘다
    if (!player.running) return;
    const heard = player.position();
    if (heard === null || Math.abs(heard - clock.position()) > DRIFT_SECONDS) sync();
  };
  const timer = setInterval(check, DRIFT_CHECK_MS);

  if (!video.paused) start();

  return () => {
    for (const name of startEvents) video.removeEventListener(name, start);
    video.removeEventListener('ratechange', rerate);
    for (const name of haltEvents) video.removeEventListener(name, halt);
    clearInterval(timer);
    cancelSettle();
    player.stop();
    clock.dispose();
  };
}
