import { describe, expect, it } from 'vitest';
import enIni from '../../../data/locale/en-US.ini?raw';
import koIni from '../../../data/locale/ko-KR.ini?raw';
import { AUDIO_KIND_LABEL, KEY_SUSPECT_HINT, markToast, REASON, reasonText } from './copy';
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
      'mark_rate_limited',
      'mark_insecure',
      'bridge_unreachable',
    ]) {
      expect(reasonText(code)).not.toContain(code);
    }
  });

  it('SRT 정지 사유는 실제 원인대로 적고, 독과 폴백 문구가 같다', () => {
    // bad_path는 주소(DNS)를 못 찾은 것이다 — 예전처럼 「암호를 거절」이라고 하면 엉뚱한 곳을 고치게 된다
    expect(REASON.bad_path).toContain('주소를 찾지 못했어요');
    expect(REASON.bad_path).not.toContain('암호');
    // 무응답과 거절이 같은 코드다 — 예전처럼 「거절했어요」로 단정하지 않는다(거절이 이어질 때만 키 안내가 따로 나온다)
    expect(REASON.connect_failed).not.toContain('거절');
    // 끊긴 뒤에는 자동으로 다시 시도한다 — 「재시도를 모두 실패」는 포기했을 때 따로 알린다
    expect(REASON.disconnected).not.toContain('실패');

    const koReason = (code: string) => new RegExp(`^Reason\\.${code}="(.*)"$`, 'm').exec(koIni)?.[1];
    for (const code of ['bad_path', 'connect_failed', 'timeout', 'disconnected', 'encoder_active']) {
      expect(koReason(code), code).toBe(REASON[code]);
    }
    expect(/^Retry\.KeyHint="(.*)"$/m.exec(koIni)?.[1]).toBe(KEY_SUSPECT_HINT);
  });

  it('상태 카드에 쌓이는 원인 문구는 독 폭(300px)에서 두 줄을 넘지 않게 짧다', () => {
    // 재시도 중에는 제목·설명·원인·진행 줄·버튼이 한 카드에 쌓인다 — 원인이 길면 카드가 글로 덮인다.
    // 46자는 미리보기에서 잰 값이다(encoder_active 46자가 딱 두 줄). 글자 수는 어림이라, 늘릴 때는 미리보기로 확인한다.
    for (const code of ['bad_path', 'connect_failed', 'timeout', 'disconnected', 'output_error', 'encoder_active']) {
      expect(REASON[code].length, code).toBeLessThanOrEqual(46);
    }
    expect(KEY_SUSPECT_HINT.length).toBeLessThanOrEqual(46);
  });

  it('A5 「다시 연결」·「재시도 중지」 거절 사유를 문구로 바꾼다', () => {
    for (const code of ['main_not_live', 'send_unavailable', 'not_retrying']) {
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
