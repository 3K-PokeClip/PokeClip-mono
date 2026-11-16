import { vi } from 'vitest';

// jsdom 에는 Web Audio 가 없다. 미리 듣기 소리를 모노로 내리는 코드(loadTrackAudio)는 AudioBuffer 생성자로 새 소리를 짓는다 —
// 시험은 채널 값 · 길이 · 빈도만 보면 되므로 그만큼만 흉내 낸다.

/** AudioBuffer 의 시험용 대역 — 채널마다 Float32Array 를 들고, getChannelData 는 그 배열 자체를 준다(브라우저와 같다) */
export class FakeAudioBuffer {
  readonly length: number;
  readonly sampleRate: number;
  readonly numberOfChannels: number;
  private readonly channels: Float32Array[];

  constructor(options: { length: number; sampleRate: number; numberOfChannels?: number }) {
    this.length = options.length;
    this.sampleRate = options.sampleRate;
    this.numberOfChannels = options.numberOfChannels ?? 1;
    this.channels = Array.from(
      { length: this.numberOfChannels },
      () => new Float32Array(options.length),
    );
  }

  get duration(): number {
    return this.length / this.sampleRate;
  }

  getChannelData(channel: number): Float32Array {
    const data = this.channels[channel];
    if (data === undefined) throw new RangeError(`channel ${channel} out of range`);
    return data;
  }
}

/** 채널마다 정한 값으로 채운 소리 — values[c] 가 채널 c 의 모든 표본이다 */
export function filledAudioBuffer(
  values: readonly number[],
  length: number,
  sampleRate: number,
): AudioBuffer {
  const buffer = new FakeAudioBuffer({ length, sampleRate, numberOfChannels: values.length });
  values.forEach((value, c) => buffer.getChannelData(c).fill(value));
  return buffer as unknown as AudioBuffer;
}

/** 전역 AudioBuffer 를 대역으로 바꾼다. 되돌리기는 vi.unstubAllGlobals */
export function installFakeAudioBuffer(): void {
  vi.stubGlobal('AudioBuffer', FakeAudioBuffer);
}
