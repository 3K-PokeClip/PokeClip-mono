import { describe, expect, it } from 'vitest';
import {
  formatKbps,
  formatPairingInput,
  formatUptime,
  isCompletePairingCode,
  sparklinePath,
  takeSseFrames,
} from './format';

describe('formatPairingInput', () => {
  it('서버 규칙대로 대문자·I/L→1·O→0 으로 바꾸고 XXXX-XXXX 로 끊는다', () => {
    expect(formatPairingInput('abcd-efgh')).toBe('ABCD-EFGH');
    expect(formatPairingInput('ilo0ab12')).toBe('1100-AB12');
  });
  it('알파벳 밖 문자(U·공백·기호)는 버리고 8자에서 멈춘다', () => {
    expect(formatPairingInput('u!@ 12 34 56 78 99')).toBe('1234-5678');
  });
  it('4자 이하는 하이픈을 넣지 않는다', () => {
    expect(formatPairingInput('ab')).toBe('AB');
    expect(isCompletePairingCode('ABCD')).toBe(false);
    expect(isCompletePairingCode('ABCD-EFGH')).toBe(true);
  });
});

describe('formatUptime / formatKbps', () => {
  it('한 시간 미만은 mm:ss, 이상은 h:mm:ss', () => {
    expect(formatUptime(65)).toBe('01:05');
    expect(formatUptime(3725)).toBe('1:02:05');
    expect(formatUptime(-3)).toBe('00:00');
  });
  it('비트레이트는 정수·천 단위 구분', () => {
    expect(formatKbps(6240.4)).toBe('6,240');
    expect(formatKbps(Number.NaN)).toBe('0');
  });
});

describe('sparklinePath', () => {
  it('빈 배열은 바닥선', () => {
    expect(sparklinePath([], 100, 20)).toBe('M0 20 L100 20');
  });
  it('최댓값이 위쪽 1px 에 닿는다', () => {
    expect(sparklinePath([0, 10], 100, 20)).toBe('M0.0 19.0 L100.0 1.0');
  });
});

describe('takeSseFrames', () => {
  it('완성된 프레임만 꺼내고 나머지는 남긴다', () => {
    const { frames, rest } = takeSseFrames('event: state\ndata: {"a":1}\n\n: keep-alive\n\nevent: state\ndata: {"a"');
    expect(frames).toEqual([{ event: 'state', data: '{"a":1}' }]);
    expect(rest).toBe('event: state\ndata: {"a"');
  });
  it('CRLF 도 받는다', () => {
    const { frames } = takeSseFrames('event: state\r\ndata: x\r\n\r\n');
    expect(frames).toEqual([{ event: 'state', data: 'x' }]);
  });
});
