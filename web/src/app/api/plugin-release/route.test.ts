import { afterEach, describe, expect, it, vi } from 'vitest';

const load = vi.hoisted(() => vi.fn());
vi.mock('@/lib/pluginRelease/loadPluginRelease', () => ({ loadPluginRelease: load }));

import { GET } from './route';

afterEach(() => load.mockReset());

describe('GET /api/plugin-release', () => {
  it('읽은 결과를 공개 캐시 헤더와 함께 준다(아직 없음도 200)', async () => {
    load.mockResolvedValue({ body: { status: 'unavailable' }, cache: 'miss' });
    const res = await GET();
    expect(res.status).toBe(200);
    expect(res.headers.get('Cache-Control')).toMatch(/^public, max-age=60/);
    expect(res.headers.get('X-Cache')).toBe('miss');
    expect(await res.json()).toEqual({ status: 'unavailable' });
  });

  it('못 읽으면 503 no-store이고, 원문 오류는 내보내지 않는다', async () => {
    load.mockRejectedValue(new Error('GitHub 403 token=ghp_secret'));
    const res = await GET();
    expect(res.status).toBe(503);
    expect(res.headers.get('Cache-Control')).toBe('no-store');
    const text = await res.text();
    expect(text).toBe(JSON.stringify({ error: 'upstream_unavailable' }));
  });
});
