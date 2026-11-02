import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useChannelStore } from '@/stores/channel';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { forgetLiveStreamId, resolveLiveStreamId } from './streamSelection';

// 지금 방송 중인 번호 묻기를 채널마다 따로 기억한다(POK-390). 하나로 기억하면 채널을 바꾼 뒤 30초 동안
// 앞 채널의 방송이 라이브 화면에 뜬다.

function liveOf(streamerId: string | null) {
  return streamerId === null ? 'mixed' : `live-of-${streamerId}`;
}

function stubLive() {
  return stubFetch((url) => {
    const u = new URL(url, 'http://localhost');
    return jsonResponse(200, {
      broadcasts: [{ streamId: liveOf(u.searchParams.get('streamerId')) }],
      nextCursor: null,
    });
  });
}

beforeEach(() => {
  forgetLiveStreamId();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('resolveLiveStreamId: 채널마다', () => {
  it('채널 번호를 실어 묻고, 채널 3과 5의 답을 따로 기억한다', async () => {
    const spy = stubLive();

    expect(await resolveLiveStreamId(3)).toBe('live-of-3');
    expect(await resolveLiveStreamId(5)).toBe('live-of-5');
    expect(await resolveLiveStreamId(3)).toBe('live-of-3');

    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('번호가 없으면 streamerId 없이 묻는다(섞어서)', async () => {
    const spy = stubLive();

    expect(await resolveLiveStreamId(null)).toBe('mixed');
    expect(String(spy.mock.calls[0]?.[0])).not.toContain('streamerId');
  });

  it('fresh면 그 채널만 다시 묻는다', async () => {
    const spy = stubLive();
    await resolveLiveStreamId(3);
    await resolveLiveStreamId(5);

    await resolveLiveStreamId(3, true);
    await resolveLiveStreamId(5);

    expect(spy).toHaveBeenCalledTimes(3);
  });

  it('forgetLiveStreamId는 모든 채널의 답을 비운다', async () => {
    const spy = stubLive();
    await resolveLiveStreamId(3);
    await resolveLiveStreamId(5);

    forgetLiveStreamId();
    await resolveLiveStreamId(3);
    await resolveLiveStreamId(5);

    expect(spy).toHaveBeenCalledTimes(4);
  });

  it('모르는 답(오류)은 그 채널만 기억하지 않는다', async () => {
    let fail = true;
    const spy = stubFetch((url) => {
      const u = new URL(url, 'http://localhost');
      if (fail && u.searchParams.get('streamerId') === '3') return jsonResponse(500, {});
      return jsonResponse(200, {
        broadcasts: [{ streamId: liveOf(u.searchParams.get('streamerId')) }],
        nextCursor: null,
      });
    });

    expect(await resolveLiveStreamId(3)).toBeNull();
    expect(await resolveLiveStreamId(5)).toBe('live-of-5');
    fail = false;
    expect(await resolveLiveStreamId(3)).toBe('live-of-3');
    expect(await resolveLiveStreamId(5)).toBe('live-of-5');

    expect(spy).toHaveBeenCalledTimes(3);
  });

  it('고른 채널이 404면 끊김 신호를 한 번 보내고 모른다(null)로 접는다', async () => {
    stubFetch(() => jsonResponse(404, { error: 'broadcast_not_found' }));
    const before = useChannelStore.getState().goneSignal;

    expect(await resolveLiveStreamId(3)).toBeNull();

    expect(useChannelStore.getState().goneSignal).toBe(before + 1);
    expect(useChannelStore.getState().goneFor).toBe(3);
  });

  it('번호 없이 404면 신호를 안 보낸다', async () => {
    stubFetch(() => jsonResponse(404, { error: 'broadcast_not_found' }));
    const before = useChannelStore.getState().goneSignal;

    expect(await resolveLiveStreamId(null)).toBeNull();

    expect(useChannelStore.getState().goneSignal).toBe(before);
  });
});
