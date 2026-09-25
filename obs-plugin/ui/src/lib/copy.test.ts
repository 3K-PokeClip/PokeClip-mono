import { describe, expect, it } from 'vitest';
import enIni from '../../../data/locale/en-US.ini?raw';
import koIni from '../../../data/locale/ko-KR.ini?raw';
import { AUDIO_KIND_LABEL, REASON, reasonText } from './copy';

// 브리지가 보내는 사유 코드는 독(copy.ts)과 Qt 폴백(locale/*.ini)이 각자 문구로 바꾼다 —
// 한쪽에만 더하면 다른 쪽 화면에 코드가 그대로 나온다.
const reasonKeys = (ini: string) =>
  [...ini.matchAll(/^Reason\.([a-z0-9_]+)=/gm)].map((m) => m[1]).filter((key) => key !== 'unknown');

describe('사유 문구', () => {
  it('Qt 폴백 로케일의 사유 코드는 독에도 전부 있다', () => {
    const keys = reasonKeys(koIni);
    expect(keys.length).toBeGreaterThan(20);
    for (const key of keys) expect(REASON[key], key).toBeTruthy();
  });

  it('두 로케일의 사유 코드가 같다', () => {
    expect(reasonKeys(enIni).sort()).toEqual(reasonKeys(koIni).sort());
  });

  it('A2 오디오 트랙 사유를 문구로 바꾼다', () => {
    for (const code of ['output_no_multitrack', 'audio_encoder_failed', 'audio_track_attach_failed']) {
      expect(reasonText(code)).not.toContain(code);
    }
  });

  it('오디오 소스 종류 여섯에 이름이 있다 (src/audio-assign.cpp AudioKindName 과 같은 키)', () => {
    expect(Object.keys(AUDIO_KIND_LABEL).sort()).toEqual(['app', 'browser', 'desktop', 'media', 'mic', 'other']);
  });
});
