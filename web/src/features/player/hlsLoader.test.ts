import { describe, expect, it, vi } from 'vitest';
import { createModuleLoader } from './hlsLoader';

// hls.js 모듈 읽기를 재생기끼리 나눈다 — 한 번 실패한 읽기를 영구히 기억하지 않는다(cc M4)

describe('createModuleLoader', () => {
  it('겹친 읽기는 한 약속을 나눈다 — import 는 한 번이다', async () => {
    const importer = vi.fn(() => Promise.resolve('hls'));
    const load = createModuleLoader(importer);
    const [a, b] = await Promise.all([load(), load()]);
    expect([a, b]).toEqual(['hls', 'hls']);
    expect(importer).toHaveBeenCalledTimes(1);
  });

  it('읽기가 실패하면 그 실패를 기억하지 않는다 — 다음 재생기는 다시 읽는다(청크 한 번 실패로 새로고침 전까지 멎지 않게)', async () => {
    const importer = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('chunk load failed'))
      .mockResolvedValue('hls');
    const load = createModuleLoader(importer);
    await expect(load()).rejects.toThrow('chunk load failed');
    await expect(load()).resolves.toBe('hls');
    expect(importer).toHaveBeenCalledTimes(2);
  });
});
