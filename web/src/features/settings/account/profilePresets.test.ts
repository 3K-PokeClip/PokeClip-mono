import { describe, expect, it } from 'vitest';
import { firstGrapheme } from './profilePresets';

// 기본 아바타 6종과 캔버스 글리프가 같은 재료를 쓴다 — 여기서 쪼개지면 둘 다 깨진다.
describe('firstGrapheme', () => {
  it('보통 글자는 첫 글자를 준다', () => {
    expect(firstGrapheme('김재환')).toBe('김');
    expect(firstGrapheme('  너구리 ')).toBe('너');
  });

  it('빈 이름은 빈 문자열이다', () => {
    expect(firstGrapheme('')).toBe('');
    expect(firstGrapheme('   ')).toBe('');
  });

  it('서로게이트 쌍을 반으로 자르지 않는다', () => {
    expect(firstGrapheme('\u{1F99D}너구리')).toBe('\u{1F99D}');
  });

  it('여러 코드 포인트가 한 글자를 이루는 것도 통째로 준다', () => {
    expect(firstGrapheme('\u{1F1F0}\u{1F1F7} 방송')).toBe('\u{1F1F0}\u{1F1F7}'); // 국기
    expect(firstGrapheme('\u{1F44D}\u{1F3FD}!')).toBe('\u{1F44D}\u{1F3FD}'); // 피부색 수식자
  });
});
