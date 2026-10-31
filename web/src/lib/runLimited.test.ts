import { describe, expect, it, vi } from 'vitest';
import { runLimited } from './runLimited';

describe('runLimited', () => {
  it('모든 항목을 돌리되 동시에 limit개를 넘지 않는다', async () => {
    let running = 0;
    let peak = 0;
    const done: number[] = [];

    await runLimited([1, 2, 3, 4, 5, 6, 7], 3, async (item) => {
      running += 1;
      peak = Math.max(peak, running);
      await Promise.resolve();
      running -= 1;
      done.push(item);
    });

    expect(peak).toBe(3);
    expect(done.sort()).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('항목이 없으면 바로 끝난다', async () => {
    const work = vi.fn(() => Promise.resolve());

    await runLimited([], 3, work);

    expect(work).not.toHaveBeenCalled();
  });

  it('던지면 그대로 던지고 남은 항목은 시작하지 않는다', async () => {
    const work = vi.fn((item: number) =>
      item === 1 ? Promise.reject(new Error('boom')) : Promise.resolve(),
    );

    await expect(runLimited([1, 2, 3], 1, work)).rejects.toThrow('boom');

    expect(work).toHaveBeenCalledTimes(1);
  });
});
