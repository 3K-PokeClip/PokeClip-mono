'use client';

import { useAuthStore } from '@/stores/auth';

// 인증 붙은 fetch 래퍼 (POK-101) — Bearer 부착과 401→refresh 회전→1회 재시도를
// 한 곳에서 처리한다. 백엔드 401은 사유를 알려주지 않으므로(만료·서명 오류·계정 없음
// 전부 같은 본문) 여기서도 구분하지 않고 회전 시도 여부로만 나눈다.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 오류 바디에서 사람이 읽을 문구를 꺼낸다 — auth는 {message}, stream-keys는 {reason}. */
async function errorMessage(res: Response): Promise<string> {
  try {
    const body: unknown = await res.json();
    if (typeof body === 'object' && body !== null) {
      const { message, reason } = body as Record<string, unknown>;
      if (typeof message === 'string' && message) return message;
      if (typeof reason === 'string' && reason) return reason;
    }
  } catch {
    /* 바디 없음·JSON 아님 — 상태 코드만으로 처리 */
  }
  return `요청이 실패했다 (${res.status})`;
}

// refresh 회전은 single-flight로 직렬화한다. 동시 401 여러 건이 각자 회전을 부르면
// 첫 성공이 나머지를 전부 "이미 쓴 토큰"으로 만들고, 서버는 재사용을 도난으로 보고
// 전 세션을 끊는다. 같은 이유로 이 경로에는 재시도를 절대 넣지 않는다.
let inflightRefresh: Promise<boolean> | null = null;

/**
 * 회전 401 뒤 옆 탭의 회전 메시지를 기다리는 시간. 두 탭이 같은 refresh로 동시에 회전하면
 * (브라우저가 탭 여러 개를 한꺼번에 복원할 때 흔하다) 서버는 진 쪽에 401을 준다 — 10초 유예
 * 안이라 탈취로 보지 않는다. 이긴 탭의 메시지가 이 401보다 먼저 온다는 보장이 없다: 진 쪽은
 * 본문 없이 status만 보고 즉시 처리하는데 이긴 쪽은 JSON을 읽은 뒤에야 알린다. 진짜 만료·도난
 * 이면 로그인 복귀가 이만큼 늦는다. (POK-211)
 */
const SIBLING_ROTATION_GRACE_MS = 500;

function waitForSessionChange(sent: string, ms: number): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      unsubscribe();
      resolve();
    };
    const unsubscribe = useAuthStore.subscribe((s) => {
      if (s.refreshToken !== sent) done();
    });
    const timer = setTimeout(done, ms);
  });
}

export function refreshSession(): Promise<boolean> {
  inflightRefresh ??= doRefresh().finally(() => {
    inflightRefresh = null;
  });
  return inflightRefresh;
}

async function doRefresh(): Promise<boolean> {
  const { refreshToken } = useAuthStore.getState();
  if (refreshToken === null) return false;
  let res: Response;
  try {
    res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
  } catch {
    // 네트워크 일시 장애 — 토큰을 지우면 오프라인 한 번에 로그아웃된다. 남겨 둔다.
    return false;
  }
  if (res.status === 401 || res.status === 403) {
    // 만료·회전 재사용(도난 감지)이면 세션 종료가 맞다. 다만 옆 탭이 같은 토큰으로 먼저 회전한
    // 것일 수 있으니(동시 회전의 진 쪽) 그 탭의 메시지를 잠깐 기다린다.
    if (useAuthStore.getState().refreshToken === refreshToken)
      await waitForSessionChange(refreshToken, SIBLING_ROTATION_GRACE_MS);
    const now = useAuthStore.getState();
    if (now.refreshToken !== refreshToken) {
      // 응답을 기다리는 사이 세션이 바뀌었다. 옆 탭이 내 토큰을 회전해 이어받았다면 그 access로
      // 재시도한다. 로그아웃·계정 교체·폴백 동기화면 스토어는 건드리지 않고 이 요청만 실패로
      // 끝낸다 — 다른 계정의 Bearer로 이 요청이 완료되면 안 된다. (리뷰 #72)
      return now.rotatedFrom === refreshToken && now.accessToken !== null;
    }
    // 가드가 스토어 변화를 보고 /login으로 보낸다.
    useAuthStore.getState().clearTokens();
    return false;
  }
  if (!res.ok) {
    // 5xx·429 등 백엔드 일시 장애 — 배포 중 재시작 한 번에 유효한 refresh가 파기되면 안 된다.
    // 네트워크 오류(위 catch)와 같게 토큰을 보존한다. (리뷰 #72)
    return false;
  }
  let pair: unknown;
  try {
    pair = await res.json();
  } catch {
    // 200인데 JSON이 아니다(프록시·캡티브 포털의 가로채기 등) — 세션 판정이 아니라
    // 서버 계약 위반이므로 5xx와 같게 토큰을 보존한다. SyntaxError가 그대로 새면
    // apiFetch의 "ApiError만 던진다" 계약도 깨진다. (리뷰 #72)
    return false;
  }
  const { accessToken, refreshToken: nextRefresh } = pair as Record<string, unknown>;
  if (typeof accessToken !== 'string' || typeof nextRefresh !== 'string') {
    // JSON이지만 토큰 쌍이 아니다 — 위와 같은 계약 위반이라 판정도 같게 보존한다.
    return false;
  }
  // 응답을 기다리는 사이 세션이 바뀌었을 수 있다 — 로그아웃(null)이면 setTokens가
  // "로그아웃했는데 세션이 되살아나는" 레이스가 되고, 다른 탭발 교체(로그아웃 후 다른 계정
  // 로그인)면 늦게 도착한 이 회전이 새 세션을 이전 계정으로 되돌린다. 어느 쪽이든 이 새
  // refresh는 서버에 살아 있으므로(로그아웃은 옛 토큰만 폐기했다) 즉시 폐기한다. (리뷰 #72)
  if (useAuthStore.getState().refreshToken !== refreshToken) {
    void fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: nextRefresh }),
    }).catch(() => {
      /* 폐기 실패 — 14일 만료로 수렴하는 것까지만 감수 */
    });
    return false;
  }
  // 같은 세션의 회전 — 다른 탭은 캐시를 유지한 채 이 쌍을 이어받는다. (POK-211)
  useAuthStore.getState().rotateTokens({ accessToken, refreshToken: nextRefresh });
  return true;
}

function send(path: string, init: RequestInit): Promise<Response> {
  const { accessToken } = useAuthStore.getState();
  const headers = new Headers(init.headers);
  if (accessToken !== null) headers.set('Authorization', `Bearer ${accessToken}`);
  if (init.body !== undefined && !headers.has('Content-Type'))
    headers.set('Content-Type', 'application/json');
  return fetch(path, { ...init, headers });
}

/**
 * 보호 API 호출 진입점. 2xx가 아니면 ApiError를 던진다 — 호출부는 status로 분기한다
 * (예: 429 발급 제한 안내). 401은 여기서 refresh 회전 후 딱 한 번 재시도한다.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const res = await send(path, init);
  if (res.status !== 401) {
    if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
    return res;
  }
  const refreshed = await refreshSession();
  if (refreshed) {
    const retry = await send(path, init);
    if (retry.status !== 401) {
      if (!retry.ok) throw new ApiError(retry.status, await errorMessage(retry));
      return retry;
    }
    // 회전 직후에도 401 — 정상 경로가 아니다(서버 측 세션 폐기 등). 세션을 접는다.
    useAuthStore.getState().clearTokens();
  }
  throw new ApiError(401, '세션이 만료됐어요');
}
