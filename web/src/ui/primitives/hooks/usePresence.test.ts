import { describe, expect, it } from 'vitest';
import { hasPositiveDuration } from './usePresence';

describe('hasPositiveDuration', () => {
  it('0 duration은 false', () => {
    expect(hasPositiveDuration('0s')).toBe(false);
    expect(hasPositiveDuration('0s, 0s')).toBe(false);
  });

  it('양수 duration이 하나라도 있으면 true', () => {
    expect(hasPositiveDuration('0.3s')).toBe(true);
    expect(hasPositiveDuration('200ms')).toBe(true);
    expect(hasPositiveDuration('0s, 200ms')).toBe(true);
    expect(hasPositiveDuration('0.3s, 0s')).toBe(true);
  });
});
