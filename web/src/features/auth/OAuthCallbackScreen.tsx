'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Spinner } from '@/ui';
import { loginWithGoogle } from '@/api/auth';
import { useAuthStore } from '@/stores/auth';
import { consumeOAuthState } from './googleOAuth';
import styles from './OAuthCallbackScreen.module.css';

// 구글 동의 화면 복귀 처리 (POK-101) — ?code를 우리 토큰 한 쌍으로 바꾼다.
// 여기 머무는 시간은 교환 왕복 한 번뿐이라 화면은 스피너와 실패 안내가 전부다.

type Phase = { kind: 'working' } | { kind: 'error'; title: string; description: string };

/**
 * 복원 경로 검증 — returnTo는 같은 오리진 스크립트라면 누구든 쓸 수 있는 sessionStorage에서
 * 온다. 절대 URL이 심기면 로그인 직후 외부로 튕기는 오픈 리다이렉트가 된다. 접두사 검사로
 * 파서를 흉내 내면 정규화 변종에 하나씩 뚫린다 — "//"를 막으면 "\\"가 "/"로 정규화되고
 * ("/\\evil.com"), 그걸 막으면 탭·LF·CR 제거가 남는다("/\n/evil.com" → "//evil.com").
 * 실제 내비게이션과 같은 URL 파서로 도달 오리진을 판정하고, 통과하면 파서가 본 그대로의
 * 내부 경로만 돌려준다. (리뷰 #72)
 */
function sanitizeReturnTo(returnTo: string | null): string {
  if (returnTo !== null) {
    try {
      const url = new URL(returnTo, window.location.origin);
      const path = url.pathname + url.search + url.hash;
      // 반환한 경로 문자열은 내비게이션에서 한 번 더 URL로 해석된다 — 같은 오리진 절대
      // URL("https://우리//evil.com")의 pathname "//evil.com"이 프로토콜 상대 URL로
      // 재해석되는 우회까지 막으려면, 재해석 결과의 오리진으로 최종 판정해야 한다.
      if (
        url.origin === window.location.origin &&
        new URL(path, window.location.origin).origin === window.location.origin
      )
        return path;
    } catch {
      /* 파싱 불가 — 홈으로 */
    }
  }
  return '/home';
}

export function OAuthCallbackScreen() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<Phase>({ kind: 'working' });
  const startedRef = useRef(false);

  useEffect(() => {
    // code는 1회용이다 — StrictMode 이중 이펙트가 두 번 POST하면 두 번째가 곧 401이라
    // 성공한 로그인이 실패 화면으로 뒤집힌다. ref 가드가 필수다.
    if (startedRef.current) return;
    startedRef.current = true;

    const stored = consumeOAuthState(); // 검증 결과와 무관하게 즉시 소모 — 재사용 방지

    if (params.get('error') !== null) {
      // access_denied 등 — 구글이 사유를 실어 주지만 사용자에겐 취소로 뭉뚱그리는 편이 정확하다.
      setPhase({
        kind: 'error',
        title: '로그인이 취소되었어요',
        description: '구글 동의 화면에서 진행이 중단됐어요. 다시 시도해 주세요.',
      });
      return;
    }

    const code = params.get('code');
    const returnedState = params.get('state');
    if (!code || !returnedState || stored === null || stored.state !== returnedState) {
      // state 불일치·부재 — CSRF이거나 새로고침·직접 진입. 어느 쪽이든 처음부터 다시.
      setPhase({
        kind: 'error',
        title: '로그인을 확인할 수 없어요',
        description: '로그인 절차가 어긋났어요. 로그인 화면에서 다시 시작해 주세요.',
      });
      return;
    }

    loginWithGoogle(code)
      .then((pair) => {
        // 자동 로그아웃(401→clearTokens)은 캐시를 못 비운다 — 공용 PC에서 이전 계정의
        // me 캐시가 새 로그인에 새지 않도록, 토큰을 심기 전에 여기서 비운다. (리뷰 #72)
        queryClient.clear();
        useAuthStore.getState().setTokens(pair);
        router.replace(sanitizeReturnTo(stored.returnTo));
      })
      .catch(() => {
        // 401 사유 미공개 계약 + 네트워크 실패 — 문구를 나누지 않는다.
        setPhase({
          kind: 'error',
          title: '로그인에 실패했어요',
          description: '잠시 후 로그인 화면에서 다시 시도해 주세요.',
        });
      });
  }, [params, queryClient, router]);

  if (phase.kind === 'working') {
    return (
      <main className={styles.screen}>
        <Spinner size="lg" />
        <p className={styles.working}>로그인하는 중…</p>
      </main>
    );
  }

  return (
    <main className={styles.screen}>
      <h1 className={styles.title}>{phase.title}</h1>
      <p className={styles.description}>{phase.description}</p>
      <Button variant="solid" size="md" onClick={() => router.replace('/login')}>
        로그인 화면으로
      </Button>
    </main>
  );
}
