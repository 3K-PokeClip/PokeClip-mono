'use client';

import { useEffect, useRef, useState, type AnchorHTMLAttributes } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { LinkButton, Spinner, useToast } from '@/ui';
import { ApiError } from '@/api/client';
import {
  chzzkLinkFailureMessage,
  chzzkLinkQueryOptions,
  completeChzzkLink,
  type ChzzkLinkState,
} from '@/api/chzzkLink';
import { restoreReturnPath } from '@/components/app-shell/AuthGuard';
import { useAuthHydration, useAuthStore } from '@/stores/auth';
import { CHANNEL_SETTINGS_PATH } from './chzzkOAuth';
import styles from './ChzzkCallbackScreen.module.css';

// 치지직 동의 복귀 처리 (POK-205) — ?code·?state를 연동 한 건으로 바꾼다.
//
// 실패해도 이 화면에 가두지 않고 채널 연동 화면으로 되돌려보낸다. 구글 콜백이 막다른
// 실패 화면을 쓰는 건 돌아갈 셸이 없어서인데, 여기는 사용자가 방금 떠나온 화면이 확정돼
// 있다. 오류 토스트는 자동으로 닫히지 않으므로(ADR-044) 결과를 놓칠 위험도 없다.
// 예외는 세션이 죽은 경우뿐 — 그때만 화면으로 막는다.

/**
 * 히스토리를 남기지 않는 링크. 이 콜백 URL은 code를 한 번 쓰고 버린 주소라, 뒤로 가기로
 * 돌아오면 소모된 code로 재교환을 시도하게 된다. (구글 콜백과 같은 이유·같은 래퍼)
 */
function ReplaceLink(props: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  return <Link {...props} replace />;
}

export function ChzzkCallbackScreen() {
  useAuthHydration();
  const hydrated = useAuthStore((s) => s.hydrated);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [signedOut, setSignedOut] = useState(false);
  const startedRef = useRef(false);

  useEffect(() => {
    // hydrate 전에는 로그인 여부를 판단할 재료가 없다 — 가드도 세우지 않는다.
    if (!hydrated) return;
    // code는 1회용이다. StrictMode 이중 이펙트가 두 번 POST하면 두 번째가 INVALID_CODE로
    // 떨어져, 성공한 연동이 실패 토스트로 뒤집힌다. ref 가드가 필수다.
    if (startedRef.current) return;
    startedRef.current = true;

    if (refreshToken === null) {
      // 재로그인 후 채널 연동 화면으로 돌아오게 자리를 남긴다 (LoginScreen이 읽는다).
      restoreReturnPath(CHANNEL_SETTINGS_PATH);
      setSignedOut(true);
      return;
    }

    // replace로 나간다 — 주소창과 히스토리 항목에서 code·state가 함께 지워져
    // 뒤로 가기로 소모된 code에 재진입할 수 없다.
    const back = () => router.replace(CHANNEL_SETTINGS_PATH);

    const code = params.get('code');
    const state = params.get('state');
    if (params.get('error') !== null || !code || !state) {
      // 치지직이 사유를 실어 주지만 사용자에겐 취소로 뭉뚱그리는 편이 정확하다.
      toast({
        tone: 'error',
        title: '연동이 취소됐어요',
        description: '치지직 동의가 완료되지 않았어요. 다시 시도해 주세요.',
      });
      back();
      return;
    }

    completeChzzkLink({ code, state })
      .then((linked) => {
        // 재조회가 끝나기 전에도 목적지 화면이 「연동됨」으로 서게 캐시를 먼저 심는다.
        queryClient.setQueryData<ChzzkLinkState>(chzzkLinkQueryOptions.queryKey, {
          linked: true,
          status: 'ACTIVE',
          channelName: linked.channelName,
          linkedAt: linked.linkedAt,
        });
        void queryClient.invalidateQueries({ queryKey: chzzkLinkQueryOptions.queryKey });
        // 온보딩 플래그는 여기서 건드리지 않는다 — 필자는 useChzzkLinkState의 미러링
        // 이펙트 하나뿐이고, 목적지 화면이 마운트되며 서버 진실로 반영한다.
        toast({
          tone: 'success',
          title: '치지직 채널을 연동했어요',
          description: `${linked.channelName} 채널에서 하이라이트를 감지해요`,
        });
        back();
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) {
          // apiFetch가 회전까지 실패해 이미 세션을 접은 상태다.
          restoreReturnPath(CHANNEL_SETTINGS_PATH);
          setSignedOut(true);
          return;
        }
        // 액션(「다시 시도」)을 달지 않는다 — 곧 언마운트될 화면의 클로저를 붙들게 되고,
        // 목적지 화면의 「연동」 버튼이 어차피 한 클릭 거리다.
        toast({ tone: 'error', ...chzzkLinkFailureMessage(e) });
        back();
      });
  }, [hydrated, refreshToken, params, queryClient, router, toast]);

  if (signedOut) {
    return (
      <main className={styles.screen}>
        <h1 className={styles.title}>로그인이 필요해요</h1>
        <p className={styles.description}>
          연동을 마치려면 다시 로그인해 주세요. 로그인하면 채널 연동 화면으로 돌아옵니다.
        </p>
        <LinkButton as={ReplaceLink} href="/login" variant="solid" size="md">
          로그인 화면으로
        </LinkButton>
      </main>
    );
  }

  return (
    <main className={styles.screen}>
      <Spinner size="lg" />
      <p className={styles.working}>치지직 채널을 연동하는 중…</p>
    </main>
  );
}
