// 운영처럼 하위 도메인에서 띄운다. jsdom 기본 주소(localhost)에서는 cross_subdomain_cookie 쿠키가 써지지 않는다.
/** @vitest-environment-options {"url": "https://app.pokeclip.com/"} */
import { PostHog } from 'posthog-js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ANALYTICS_CONFIG } from './client';

// 진짜 SDK(설치 판)로 본다. client.test.ts는 posthog-js를 목으로 바꿔 SDK의 영속 저장을 보지 못한다.
// SDK는 캡처할 때 최초 방문 주소($initial_person_info)를 처음 한 번 쿠키(.pokeclip.com 공유)와 localStorage에
// 적고, 세션이 새로 시작되면 그 세션의 진입 주소($client_session_props)를 localStorage에 적는다. 콜백 화면이 주소를
// 지우기 전에 SDK가 먼저 붙으면 그 주소에 code·state가 실려 있다. before_send(scrubEvent)는 나가는 이벤트만
// 고치므로 이 저장값에는 닿지 않는다 (POK-485).

const TOKEN = 'phc_persist_485';
const CODE = 'code-secret-485';
const STATE = 'state-secret-485';

/** 이 출처의 localStorage 전체와 쿠키를 한 문자열로. SDK가 어느 키에 적든 찾는다. */
function persistedText(): string {
  const entries = Array.from({ length: localStorage.length }, (_, index) => {
    const key = localStorage.key(index) ?? '';
    return `${key}=${localStorage.getItem(key) ?? ''}`;
  });
  return [...entries, decodeURIComponent(document.cookie)].join('\n');
}

beforeEach(() => {
  localStorage.clear();
  // 네트워크는 나가지 않게 막는다. 보는 것은 브라우저 저장소뿐이다.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('{}', { status: 200 })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
  window.history.replaceState(null, '', '/');
});

describe('PostHog 영속 저장: OAuth 콜백의 code·state (POK-485)', () => {
  it('콜백 주소에서 SDK가 처음 기록해도 쿠키·localStorage에 code·state 값이 남지 않는다', async () => {
    window.history.replaceState(null, '', `/auth/callback?code=${CODE}&state=${STATE}&scope=email`);
    const posthog = new PostHog();
    posthog.init(TOKEN, { ...ANALYTICS_CONFIG, api_host: 'https://ph.invalid' });

    posthog.capture('probe');

    // SDK는 저장소에 비동기로 쓴다. 두 기록이 실제로 저장될 때까지 기다린 뒤에 값을 본다.
    await vi.waitFor(() => {
      const persisted = persistedText();
      expect(persisted).toContain('$initial_person_info');
      expect(persisted).toContain('$client_session_props');
      expect(document.cookie).toContain(`ph_${TOKEN}_posthog=`);
    });
    const persisted = persistedText();
    expect(persisted).toContain('/auth/callback');
    expect(persisted).toContain('scope=email');
    expect(persisted).not.toContain(CODE);
    expect(persisted).not.toContain(STATE);
  });
});
