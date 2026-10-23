import type { TrackPiece } from './loadTrackAudio';

// 트랙별 미리 듣기 재생기(POK-389). 트랙마다 음량 노드 하나를 두고, 풀어 둔 덩어리를 「지금 자리」에 맞춰 건다.
// 영상은 그대로 <video>가 틀고 소리만 여기서 난다 — followVideo가 영상 노드의 사건을 따라 다시 건다.

export type AudioContextLike = Pick<
  AudioContext,
  'currentTime' | 'state' | 'destination' | 'resume' | 'createGain' | 'createBufferSource'
>;

/** 소리와 영상이 이만큼 넘게 벌어지면 영상 자리로 다시 건다. 한 프레임(30fps 33ms)보다 크고 귀에 띄는 어긋남(약 0.1초)보다 작다 */
export const DRIFT_SECONDS = 0.08;
const DRIFT_CHECK_MS = 250;

export class TrackAudioPlayer {
  private readonly gains: GainNode[];
  private sources: AudioBufferSourceNode[] = [];
  private anchor: { contextTime: number; sourceSeconds: number; rate: number } | null = null;

  constructor(
    private readonly ctx: AudioContextLike,
    private readonly tracks: readonly (readonly TrackPiece[])[],
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
    // 브라우저는 사람의 조작 전에 만든 소리 문맥을 멈춰 둔다 — 재생 버튼 뒤에 깨운다
    if (this.ctx.state === 'suspended') void this.ctx.resume();
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

/**
 * 소리를 영상 노드에 묶는다. 따라가는 동안 영상 소리는 끈다(두 소리가 겹치면 트랙을 꺼도 들린다).
 * @param sourceSeconds 영상 노드의 지금 자리를 편집기 초로(창 시작 + currentTime)
 * @returns 그만두기 — 소리를 세우고 영상 소리를 원래대로
 */
export function followVideo(
  player: TrackAudioPlayer,
  video: HTMLVideoElement,
  sourceSeconds: () => number,
): () => void {
  const sync = () => {
    if (video.paused || video.seeking) player.stop();
    else player.play(sourceSeconds(), video.playbackRate);
  };
  const halt = () => player.stop();
  const resyncEvents = ['playing', 'seeked', 'ratechange'] as const;
  const haltEvents = ['pause', 'ended', 'waiting', 'seeking'] as const;
  for (const name of resyncEvents) video.addEventListener(name, sync);
  for (const name of haltEvents) video.addEventListener(name, halt);

  // 영상이 버벅이거나(버퍼링) 시계가 서로 조금씩 밀리면 소리가 앞서 간다 — 주기적으로 재서 맞춘다
  const timer = setInterval(() => {
    if (video.paused || video.seeking) return;
    const heard = player.position();
    if (heard === null || Math.abs(heard - sourceSeconds()) > DRIFT_SECONDS) sync();
  }, DRIFT_CHECK_MS);

  const wasMuted = video.muted;
  video.muted = true;
  if (!video.paused) sync();

  return () => {
    for (const name of resyncEvents) video.removeEventListener(name, sync);
    for (const name of haltEvents) video.removeEventListener(name, halt);
    clearInterval(timer);
    player.stop();
    video.muted = wasMuted;
  };
}
