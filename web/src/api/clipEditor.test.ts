import { afterEach, describe, expect, it, vi } from 'vitest';
import { ClipApiError, requestRender } from '@/api/clipEditor';
import { jsonResponse, stubFetch } from '@/test/mockFetch';

// clip 창구 호출은 로그인 세션의 apiFetch를 거친다(POK-251). 실패는 ClipApiError로 옮겨 사유 코드를 지킨다.

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('clip 창구 호출', () => {
  it('거절은 ClipApiError로 온다 — 상태·사유 코드·칸이 그대로다', async () => {
    stubFetch(() => jsonResponse(409, { error: 'source_not_ready' }));

    const err = await requestRender('s1', 7).catch((e: unknown) => e);

    expect(err).toBeInstanceOf(ClipApiError);
    expect((err as ClipApiError).status).toBe(409);
    expect((err as ClipApiError).code).toBe('source_not_ready');
    expect((err as ClipApiError).field).toBeNull();
  });

  it('주문은 POST로 가고 응답 봉투를 그대로 돌려준다', async () => {
    const spy = stubFetch(() => jsonResponse(201, { id: 3, status: 'queued' }));

    const clip = await requestRender('s 1', 7);

    expect(clip).toMatchObject({ id: 3, status: 'queued' });
    const [url, init] = spy.mock.calls[0] ?? [];
    expect(url).toBe('/api/clip/broadcasts/s%201/recipes/7/renders');
    expect(init?.method).toBe('POST');
  });
});
