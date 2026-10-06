import { afterEach, describe, expect, it, vi } from 'vitest';
import { createHttpBridge } from './bridge';

describe('createHttpBridge', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('브리지에 닿지 못하면 동작 결과를 실패로 돌려준다 — 버튼이 로딩에 묶이지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const bridge = createHttpBridge('t'.repeat(64));
    const unreachable = { ok: false, reason: 'bridge_unreachable' };

    await expect(bridge.pair('ABCD-EFGH')).resolves.toEqual(unreachable);
    await expect(bridge.unpair()).resolves.toEqual(unreachable);
    await expect(bridge.putSettings({ audio_assign_prompted: true })).resolves.toEqual(unreachable);
    await expect(bridge.mark()).resolves.toEqual({ ...unreachable, status: 0 });
    await expect(bridge.sendNow()).resolves.toEqual(unreachable);
    await expect(bridge.stopRetry()).resolves.toEqual(unreachable);
  });

  it('「지금 보내기」·「재시도 멈추기」는 본문 없는 POST로 가고 거절 사유를 돌려준다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: false, reason: 'not_retrying' }), { status: 409 }));
    vi.stubGlobal('fetch', fetchMock);
    const bridge = createHttpBridge('t'.repeat(64));

    await expect(bridge.sendNow()).resolves.toEqual({ ok: true });
    await expect(bridge.stopRetry()).resolves.toEqual({ ok: false, reason: 'not_retrying' });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/send-now');
    expect(fetchMock.mock.calls[1][0]).toBe('/api/stop-retry');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${'t'.repeat(64)}`);
  });

  it('브리지가 답하면 상태 코드의 사유를 그대로 돌려준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: false, reason: 'streaming' }), { status: 409 })),
    );
    const bridge = createHttpBridge('t'.repeat(64));
    await expect(bridge.putSettings({ ingest_port: 9000 })).resolves.toEqual({ ok: false, reason: 'streaming' });
  });
});
