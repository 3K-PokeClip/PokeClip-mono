import { vi } from 'vitest';

// fetch 전역 스텁 — msw 없이 URL 기반으로 응답을 돌려준다 (기존 테스트 스타일 유지).
// afterEach에서 vi.unstubAllGlobals()로 반드시 되돌린다.

export function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

export type FetchHandler = (url: string, init?: RequestInit) => Response | Promise<Response>;

/** 호출 검증은 반환된 spy로 한다 — spy.mock.calls의 [url, init] 쌍. */
export function stubFetch(handler: FetchHandler) {
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    return handler(url, init);
  });
  vi.stubGlobal('fetch', spy);
  return spy;
}
