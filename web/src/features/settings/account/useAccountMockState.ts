'use client';

import { useCallback, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { meQueryOptions, type Me } from '@/api/auth';
import { markIntentionalLogout } from '@/components/app-shell/AuthGuard';
import { useMe } from '@/features/auth/useSession';
import { useAuthStore } from '@/stores/auth';
import { useToast } from '@/ui';

// 디자인 1p 설정 · 계정의 상태 (POK-206).
//
// 프로필 수정·아바타 업로드·탈퇴·결제 API가 하나도 없다 — auth 서버의 공개 면은
// google·me·refresh·logout 넷뿐이고 탈퇴 백엔드(POK-171)는 아직 「할 일」이다.
// 그래서 이 화면의 저장은 전부 이 훅 안에서 끝난다: 이름·사진은 me 쿼리 캐시에만
// 쓰고, 탈퇴는 아무것도 지우지 않고 로컬 세션만 접는다. 새로고침하면 전부 되돌아온다.
// 백엔드가 붙으면 이 훅만 실제 왕복으로 갈아끼운다 (usePluginMockState 선례).

/** 시안 1p 탈퇴 모달의 표기값. 보관함·구독 API가 없어 목업으로 둔다. */
export const WITHDRAW_FACTS = {
  savedBroadcasts: 42,
  archivedClips: 128,
  remainingDays: 23,
  unpaidAmount: 12900,
} as const;

export interface AccountViewState {
  me: Me | undefined;
  /** 입력 중인 표시 이름. 저장 전까지 me와 갈린다. */
  draftName: string;
  /** 저장 버튼이 살아 있는지 — 비어 있거나 그대로면 누를 것이 없다. */
  dirty: boolean;
  facts: typeof WITHDRAW_FACTS;
  /** 미결제 잔액이 있어 탈퇴가 막힌 상태. 결제 도메인이 없어 `?mock=blocked`로만 켜진다. */
  blocked: boolean;
  setDraftName: (next: string) => void;
  saveName: () => void;
  applyPhoto: (dataUrl: string) => void;
  completeWithdraw: () => void;
}

export function useAccountMockState(): AccountViewState {
  const { data: me } = useMe();
  const queryClient = useQueryClient();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { toast } = useToast();

  // me가 늦게 오므로 입력값은 「아직 손대지 않음(null)」과 「비웠음('')」을 갈라 둔다 —
  // 하나로 합치면 이름을 지운 순간 서버 이름이 되살아나 입력이 되감긴다.
  const [typed, setTyped] = useState<string | null>(null);
  const draftName = typed ?? me?.name ?? '';
  const dirty = draftName.trim().length > 0 && draftName !== me?.name;

  /** me 캐시를 갈아 헤더 아바타까지 함께 바뀌게 한다 — 서버에는 가지 않는다. */
  const patchMe = useCallback(
    (patch: Partial<Me>) => {
      queryClient.setQueryData<Me>(meQueryOptions.queryKey, (prev) =>
        prev ? { ...prev, ...patch } : prev,
      );
    },
    [queryClient],
  );

  const saveName = useCallback(() => {
    const next = draftName.trim();
    patchMe({ name: next });
    setTyped(null); // me를 다시 따라가게 되돌린다 — 저장한 값과 입력값이 같아진다
    toast({ tone: 'success', title: '표시 이름을 변경했습니다' });
  }, [draftName, patchMe, toast]);

  const applyPhoto = useCallback(
    (dataUrl: string) => {
      patchMe({ profileImageUrl: dataUrl });
    },
    [patchMe],
  );

  const completeWithdraw = useCallback(() => {
    // POK-171 백엔드가 붙으면 여기서 탈퇴를 부르고, 성공한 뒤에 아래 세션 정리를 한다.
    // 지금은 아무것도 지우지 않는다 — 같은 계정으로 다시 로그인하면 그대로 돌아온다.
    markIntentionalLogout(); // 가드가 「탈퇴한 사람이 보던 화면」을 복원 경로로 남기지 않게
    useAuthStore.getState().clearTokens();
    queryClient.clear();
    router.replace('/goodbye');
  }, [queryClient, router]);

  return {
    me,
    draftName,
    dirty,
    facts: WITHDRAW_FACTS,
    blocked: searchParams.get('mock') === 'blocked',
    setDraftName: setTyped,
    saveName,
    applyPhoto,
    completeWithdraw,
  };
}
