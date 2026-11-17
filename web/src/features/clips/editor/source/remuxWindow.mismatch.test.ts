import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type * as Mediabunny from 'mediabunny';
import type { Input, Output } from 'mediabunny';
import { describe, expect, it, vi } from 'vitest';
import { remuxWindow, RemuxMismatchError } from './remuxWindow';

// 패킷을 조용히 버리는 변환기(Vanilagy/mediabunny#614 — 1.65.1 부터 GOP 시각 규칙을 어긴 패킷을 버린다)를 흉내 낸다.
// Conversion 만 가짜로 바꾸고 나머지(입력 · 출력 · 패킷 싱크)는 진짜 mediabunny 다. 가짜는 진짜 패킷을 그대로 옮기되
// 영상 마지막 패킷 하나만 빼고 쓴다.
vi.mock('mediabunny', async (importOriginal) => {
  const mb = await importOriginal<typeof Mediabunny>();

  async function copyDroppingLastVideo(input: Input, output: Output) {
    const video = (await input.getPrimaryVideoTrack())!;
    const audio = (await input.getPrimaryAudioTrack())!;
    const videoSource = new mb.EncodedVideoPacketSource('avc');
    const audioSource = new mb.EncodedAudioPacketSource('aac');
    output.addVideoTrack(videoSource);
    output.addAudioTrack(audioSource);
    await output.start();
    const videoPackets = [];
    for await (const packet of new mb.EncodedPacketSink(video).packets()) videoPackets.push(packet);
    const videoConfig = (await video.getDecoderConfig())!;
    for (const [i, packet] of videoPackets.slice(0, -1).entries()) {
      await videoSource.add(packet, i === 0 ? { decoderConfig: videoConfig } : undefined);
    }
    const audioConfig = (await audio.getDecoderConfig())!;
    let first = true;
    for await (const packet of new mb.EncodedPacketSink(audio).packets()) {
      await audioSource.add(packet, first ? { decoderConfig: audioConfig } : undefined);
      first = false;
    }
    await output.finalize();
  }

  const Conversion = {
    init: async (options: { input: Input; output: Output }) => ({
      isValid: true,
      discardedTracks: [],
      execute: () => copyDroppingLastVideo(options.input, options.output),
    }),
  };
  return { ...mb, Conversion };
});

const TESTDATA = join(__dirname, 'testdata');
const read = (name: string) => new Uint8Array(readFileSync(join(TESTDATA, name)));

describe('remuxWindow: 변환기가 패킷을 버리면 창을 내보내지 않는다', () => {
  it('입력 영상 표본 360 · 출력 영상 패킷 359 이면 RemuxMismatchError 다', async () => {
    const bytes = {
      init: read('init.mp4'),
      segments: [300, 301, 302].map((seq) => ({ seq, bytes: read(`seg_0000${seq}.m4s`) })),
    };

    const failure = remuxWindow(bytes);

    await expect(failure).rejects.toBeInstanceOf(RemuxMismatchError);
    await expect(failure).rejects.toMatchObject({ inputFrames: 360, outputPackets: 359 });
  });
});
