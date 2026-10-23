import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { demuxAudioTracks, toAdts } from './fmp4Audio';

// 시험 조각은 ffmpeg로 만든 1초짜리 fMP4다(영상 1 + 소리 3, 상자 모양은 녹화 조각과 같다: ftyp·moov·moof/mdat 여러 쌍).
// 소리 트랙마다 표본 빈도·채널을 다르게 해 「파일 순서대로 꺼냈나」가 값으로 드러난다. 프레임 수는 ffprobe -count_packets 값.
//   ffmpeg -f lavfi -i testsrc=size=64x36:rate=10:duration=1 -f lavfi -i sine=440:48000 -f lavfi -i sine=880:44100
//          -f lavfi -i sine=220:24000 -map 0:v -map 1:a -map 2:a -map 3:a -c:v libx264 -g 5 -c:a aac -b:a 32k
//          -ac:a:0 2 -ac:a:1 1 -ac:a:2 2 -movflags frag_keyframe+empty_moov+default_base_moof three-tracks.m4s
const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));

const FREQUENCIES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000,
];

describe('demuxAudioTracks — 조각 하나에서 소리 트랙을 파일 순서대로 꺼낸다', () => {
  const tracks = demuxAudioTracks(fixture);

  it('영상 트랙은 빼고 소리 트랙 셋을 파일 순서대로 준다 — 렌더의 [0:a:N]과 같은 번호다', () => {
    expect(tracks).toHaveLength(3);
    expect(tracks.map((t) => FREQUENCIES[t.config.frequencyIndex])).toEqual([48000, 44100, 24000]);
    expect(tracks.map((t) => t.config.channelConfig)).toEqual([2, 1, 2]);
    expect(tracks.every((t) => t.config.objectType === 2)).toBe(true); // AAC LC
  });

  it('조각 안의 moof 전부에서 프레임을 모은다', () => {
    expect(tracks.map((t) => t.frames.length)).toEqual([48, 45, 25]);
    expect(tracks.every((t) => t.frames.every((f) => f.length > 0))).toBe(true);
  });

  it('소리 트랙이 없거나 상자가 깨졌으면 빈 목록이다(던지지 않는다)', () => {
    expect(demuxAudioTracks(new Uint8Array(0))).toEqual([]);
    expect(demuxAudioTracks(fixture.subarray(0, 40))).toEqual([]);
  });
});

describe('toAdts — 브라우저가 풀 수 있게 프레임마다 ADTS 머리(7바이트)를 붙인다', () => {
  it('머리의 동기 표시·길이·설정이 맞고 프레임이 이어 붙는다', () => {
    const first = demuxAudioTracks(fixture)[0]!;
    const adts = toAdts(first);
    let offset = 0;
    let count = 0;
    while (offset < adts.length) {
      expect(adts[offset]).toBe(0xff);
      const at = (i: number) => adts[offset + i]!;
      expect(at(1) & 0xf6).toBe(0xf0);
      const profile = (at(2) >> 6) + 1;
      const frequencyIndex = (at(2) >> 2) & 0x0f;
      const channels = ((at(2) & 0x01) << 2) | (at(3) >> 6);
      const length = ((at(3) & 0x03) << 11) | (at(4) << 3) | (at(5) >> 5);
      expect([profile, frequencyIndex, channels]).toEqual([2, 3, 2]);
      expect(adts.subarray(offset + 7, offset + length)).toEqual(first.frames[count]);
      offset += length;
      count += 1;
    }
    expect(count).toBe(first.frames.length);
    expect(offset).toBe(adts.length);
  });
});
