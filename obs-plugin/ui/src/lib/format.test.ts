import { describe, expect, it } from 'vitest';
import {
  extractPairingCode,
  formatCountdown,
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

describe('extractPairingCode — 붙여넣기', () => {
  it('앞뒤 공백·줄바꿈이 섞여도 코드를 통째로 받는다', () => {
    expect(extractPairingCode(' KQ4M-7X2P')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('KQ4M-7X2P\n')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('\tkq4m-7x2p  ')).toBe('KQ4M-7X2P');
  });
  it('안내 문구와 같이 복사돼도 코드 모양 토막을 고른다', () => {
    expect(extractPairingCode('코드: KQ4M-7X2P')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('PokeClip code KQ4M-7X2P (10분)')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('연결 코드는 KQ4M7X2P 입니다')).toBe('KQ4M-7X2P');
    // 8글자 낱말이 앞에 있어도 숫자가 든 토막을 코드로 본다
    expect(extractPairingCode('PokeClip KQ4M7X2P')).toBe('KQ4M-7X2P');
  });
  it('가운데가 공백·긴 줄표여도 받는다', () => {
    expect(extractPairingCode('KQ4M 7X2P')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('KQ4M - 7X2P')).toBe('KQ4M-7X2P');
    expect(extractPairingCode('KQ4M–7X2P')).toBe('KQ4M-7X2P');
  });
  it('서버 규칙대로 I·L→1, O→0 으로 바꾼다', () => {
    expect(extractPairingCode('ilo0-ab12')).toBe('1100-AB12');
  });
  it('코드 모양이 없으면 입력할 때처럼 앞에서부터 모은다', () => {
    expect(extractPairingCode('12 34 56 78')).toBe('1234-5678');
    expect(extractPairingCode('abc')).toBe('ABC');
    expect(extractPairingCode('한글만')).toBe('');
  });
});

describe('formatCountdown', () => {
  it('남은 시간을 올림한 초로 적고, 지났으면 곧이라고 한다', () => {
    expect(formatCountdown(11_400)).toBe('12초 뒤');
    expect(formatCountdown(60_000)).toBe('60초 뒤');
    expect(formatCountdown(1)).toBe('1초 뒤');
    expect(formatCountdown(0)).toBe('곧');
    expect(formatCountdown(-800)).toBe('곧');
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
