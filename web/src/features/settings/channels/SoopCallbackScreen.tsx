'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { LinkButton, Spinner, useToast } from '@/ui';
import { ApiError } from '@/api/client';
import {
  completeSoopLink,
  soopLinkFailureMessage,
  soopLinkMessageOf,
  soopLinkQueryOptions,
} from '@/api/soopLink';
import { ReplaceLink } from '@/components/ReplaceLink';
import { restoreReturnPath } from '@/components/app-shell/AuthGuard';
import { track } from '@/lib/analytics';
import { captureSession, useAuthHydration, useAuthStore } from '@/stores/auth';
import { CHANNEL_SETTINGS_PATH } from './chzzkOAuth';
import styles from './ChzzkCallbackScreen.module.css';

// SOOP 동의 복귀 처리 (POK-396). ChzzkCallbackScreen의 쌍둥이다(1회용 code의 ref 가드, replace로
// 주소창 code 지우기, 캐시를 심지 않고 비우기, 토큰이 실제로 비었을 때만 로그인 안내).
// 다른 점 하나: state가 없으면 1회용 code를 서버에 보내지 않고 돌려보낸다(SOOP은 state를 돌려준다, soopOAuth.ts).

export function SoopCallbackScreen() {
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
    if (!hydrated) return;
    if (startedRef.current) return;
    startedRef.current = true;

    if (refreshToken === null) {
      restoreReturnPath(CHANNEL_SETTINGS_PATH);
      setSignedOut(true);
      return;
    }

    const back = () => router.replace(CHANNEL_SETTINGS_PATH);

    const code = params.get('code');
    const state = params.get('state');
    if (params.get('error') !== null || !code) {
      toast({
        tone: 'error',
        title: '연동이 취소됐어요',
        description: 'SOOP 동의가 완료되지 않았어요. 다시 시도해 주세요.',
      });
      back();
      return;
    }
    if (!state) {
      // 서버에 물어도 INVALID_STATE다. 1회용 code를 헛되이 보내지 않는다.
      toast({ tone: 'error', ...soopLinkMessageOf('INVALID_STATE') });
      back();
      return;
    }

    // 응답을 기다리는 사이 다른 탭이 계정을 바꿀 수 있다 (POK-222): 시작한 세션을 적어 둔다.
    const isSameSession = captureSession();
    completeSoopLink({ code, state })
      .then((linked) => {
        // 계정이 바뀌었으면 결과를 알리지 않고 돌아가기만 한다: 토스트의 채널명이 다음 사람에게
        // 보이고 집계도 그 사람에게 붙는다. 캐시는 크로스탭 핸들러가 이미 비웠다.
        if (!isSameSession()) {
          back();
          return;
        }
        // 치지직과 같은 이유로 낙관 갱신 대신 캐시를 비운다(다른 탭의 계정 전환 뒤 늦은 응답).
        queryClient.removeQueries({ queryKey: soopLinkQueryOptions.queryKey });
        track('platform_linked', { platform: 'soop' });
        toast({
          tone: 'success',
          title: 'SOOP 방송국을 연동했어요',
          description: `${linked.nickname} 방송국에서 하이라이트를 감지해요`,
        });
        back();
      })
      .catch((e: unknown) => {
        if (
          e instanceof ApiError &&
          e.status === 401 &&
          useAuthStore.getState().refreshToken === null
        ) {
          restoreReturnPath(CHANNEL_SETTINGS_PATH);
          setSignedOut(true);
          return;
        }
        // 앞 계정의 실패도 다음 사람에게는 엉뚱한 안내다. 위의 「로그인 필요」는 지금 상태라 그대로 둔다.
        if (!isSameSession()) {
          back();
          return;
        }
        toast({ tone: 'error', ...soopLinkFailureMessage(e) });
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
      <p className={styles.working}>SOOP 방송국을 연동하는 중…</p>
    </main>
  );
}
