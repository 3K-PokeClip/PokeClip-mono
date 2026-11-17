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

  it('limit이 둘 이상이어도 던진 뒤에는 남은 항목을 시작하지 않는다: 돌고 있던 것만 끝까지 간다', async () => {
    const started: number[] = [];
    const finished: number[] = [];

    await expect(
      runLimited([1, 2, 3, 4, 5], 2, async (item) => {
        started.push(item);
        if (item === 1) throw new Error('boom');
        await new Promise((resolve) => setTimeout(resolve, 5));
        finished.push(item);
      }),
    ).rejects.toThrow('boom');
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(started).toEqual([1, 2]);
    expect(finished).toEqual([2]);
  });

  it('던진 뒤에도 돌고 있던 항목이 끝난 다음에 던진다: 끝났다고 알린 뒤에 일이 남아 돌지 않는다', async () => {
    const finished: number[] = [];

    await expect(
      runLimited([1, 2], 2, async (item) => {
        if (item === 1) throw new Error('boom');
        await new Promise((resolve) => setTimeout(resolve, 5));
        finished.push(item);
      }),
    ).rejects.toThrow('boom');

    expect(finished).toEqual([2]);
  });
});
