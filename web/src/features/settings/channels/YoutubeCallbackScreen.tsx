'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { LinkButton, Spinner, useToast } from '@/ui';
import { ApiError } from '@/api/client';
import {
  completeYoutubeLink,
  youtubeLinkFailureMessage,
  youtubeLinkQueryOptions,
} from '@/api/youtubeLink';
import { ReplaceLink } from '@/components/ReplaceLink';
import { restoreReturnPath } from '@/components/app-shell/AuthGuard';
import { useAuthHydration, useAuthStore } from '@/stores/auth';
import { CHANNEL_SETTINGS_PATH } from './youtubeOAuth';
import styles from './YoutubeCallbackScreen.module.css';

// 구글 동의 복귀 처리 (POK-221) — ?code·?state를 유튜브 연동 한 건으로 바꾼다.
// ChzzkCallbackScreen과 같은 구조다.
//
// 실패해도 이 화면에 가두지 않고 채널 연동 화면으로 되돌려보낸다. 구글 콜백이 막다른
// 실패 화면을 쓰는 건 돌아갈 셸이 없어서인데, 여기는 사용자가 방금 떠나온 화면이 확정돼
// 있다. 오류 토스트는 자동으로 닫히지 않으므로(ADR-044) 결과를 놓칠 위험도 없다.
// 예외는 세션이 죽은 경우뿐 — 그때만 화면으로 막는다.

export function YoutubeCallbackScreen() {
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
      // 구글이 사유(access_denied 등)를 실어 주지만 사용자에겐 취소로 뭉뚱그리는 편이 정확하다.
      toast({
        tone: 'error',
        title: '연동이 취소됐어요',
        description: '구글 동의가 완료되지 않았어요. 다시 시도해 주세요.',
      });
      back();
      return;
    }

    completeYoutubeLink({ code, state })
      .then((linked) => {
        // 낙관 갱신으로 채널을 심지 않는다. 다른 탭이 계정을 바꾸면 크로스탭 핸들러가
        // 쿼리 캐시를 비우는데(Providers), 그 뒤 늦게 도착한 이 응답이 **이전 계정의**
        // 채널명을 되살린다 — 이어지는 재조회가 실패하면 unavailable 판정이
        // `data === undefined`라 그 값이 화면에 그대로 남는다. 대신 캐시를 비워
        // 목적지가 처음부터 다시 읽게 한다(스켈레톤 한 번). 남은 옛 값을 그냥 두면
        // 성공 토스트 직후에 「미연동」이 잠깐 스치므로 invalidate가 아니라 remove다.
        queryClient.removeQueries({ queryKey: youtubeLinkQueryOptions.queryKey });
        toast({
          tone: 'success',
          title: '유튜브 채널을 연동했어요',
          description: `${linked.channelName} 채널로 클립을 업로드할 수 있어요`,
        });
        back();
      })
      .catch((e: unknown) => {
        // 401이 곧 세션 종료는 아니다. refresh가 인프라 장애로 못 돈 경우는 apiFetch가
        // 503으로 던지지만(POK-217), 다른 계정으로 바뀐 레이스 등 토큰이 남은 401이
        // 여전히 있다. 그걸 로그아웃으로 읽으면 로그인 화면 역가드가 살아있는
        // refreshToken을 보고 /home으로 튕겨 막다른 길이 된다 — 안내는 로그인하라는데
        // 로그인 화면에 갈 수 없다. 세션이 실제로 접힌 경우(apiFetch가 clearTokens까지
        // 돌아 토큰이 빈 경우)만 로그인으로 보낸다.
        if (
          e instanceof ApiError &&
          e.status === 401 &&
          useAuthStore.getState().refreshToken === null
        ) {
          restoreReturnPath(CHANNEL_SETTINGS_PATH);
          setSignedOut(true);
          return;
        }
        // 액션(「다시 시도」)을 달지 않는다 — 곧 언마운트될 화면의 클로저를 붙들게 되고,
        // 목적지 화면의 「연동」 버튼이 어차피 한 클릭 거리다.
        toast({ tone: 'error', ...youtubeLinkFailureMessage(e) });
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
      <p className={styles.working}>유튜브 채널을 연동하는 중…</p>
    </main>
  );
}
