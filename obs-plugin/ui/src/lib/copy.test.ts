import { describe, expect, it } from 'vitest';
import enIni from '../../../data/locale/en-US.ini?raw';
import koIni from '../../../data/locale/ko-KR.ini?raw';
import { AUDIO_KIND_LABEL, markToast, REASON, reasonText } from './copy';
import type { MarkStats } from './types';

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

  it('A4 마크 사유를 문구로 바꾼다', () => {
    for (const code of [
      'mark_not_live',
      'mark_too_soon',
      'mark_unsupported',
      'mark_unauthorized',
      'mark_rejected',
      'mark_no_broadcast',
      'mark_not_ready',
      'mark_expired',
      'mark_insecure',
      'bridge_unreachable',
    ]) {
      expect(reasonText(code)).not.toContain(code);
    }
  });

  it('오디오 소스 종류 여섯에 이름이 있다 (src/audio-assign.cpp AudioKindName 과 같은 키)', () => {
    expect(Object.keys(AUDIO_KIND_LABEL).sort()).toEqual(['app', 'browser', 'desktop', 'media', 'mic', 'other']);
  });
});

describe('마크 결과 토스트', () => {
  const marks = (patch: Partial<MarkStats>): MarkStats => ({
    hotkey: '⌃⇧M',
    sent: 0,
    pending: 0,
    failed: 0,
    seq: 1,
    result: '',
    reason: '',
    lastAt: 0,
    ...patch,
  });

  it('보냈으면 성공 톤', () => {
    expect(markToast(marks({ result: 'sent' }))).toEqual({ tone: 'success', message: '지금 이 순간을 표시했어요.' });
  });

  it('다시 보내는 중이면 사유와 자동 재전송을 함께 알린다', () => {
    const t = markToast(marks({ result: 'retrying', reason: 'mark_not_ready' }));
    expect(t?.tone).toBe('warning');
    expect(t?.message).toBe(`${REASON.mark_not_ready} 자동으로 다시 보내요.`);
  });

  it('버렸거나 거절했으면 사유 문구', () => {
    expect(markToast(marks({ result: 'failed', reason: 'mark_unsupported' }))?.message).toBe(REASON.mark_unsupported);
    expect(markToast(marks({ result: 'rejected', reason: 'mark_not_live' }))?.message).toBe(REASON.mark_not_live);
  });

  it('결과가 없으면 띄우지 않는다', () => {
    expect(markToast(marks({ result: '' }))).toBeNull();
  });
});
