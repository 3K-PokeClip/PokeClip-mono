'use client';

import { useCallback, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { meQueryOptions, type Me } from '@/api/auth';
import { markWithdrawn } from '@/features/account/withdrawHandoff';
import { useMe } from '@/features/auth/useSession';
import { useToast } from '@/ui';

// 디자인 1p 설정 · 계정의 상태 (POK-206).
//
// 프로필 수정·아바타 업로드·탈퇴·결제 API가 하나도 없다 — auth 서버의 공개 면은
// google·me·refresh·logout 넷뿐이고 탈퇴 백엔드(POK-171)는 아직 「할 일」이다.
// 그래서 이 화면의 저장은 전부 이 훅 안에서 끝난다: 이름·사진은 me 쿼리 캐시에만
// 쓰고, 탈퇴는 아무것도 지우지 않고 로컬 세션만 접는다.
//
// ⚠ 되돌아가는 시점은 새로고침만이 아니다 — me는 staleTime 60초(providers.tsx)에
// 기본 refetchOnWindowFocus·refetchOnMount가 걸려 있어, 저장 60초 뒤 탭을 갔다 오거나
// 다른 설정 화면을 거쳐 돌아오면 서버값으로 조용히 되감긴다. 헤더·사이드바도 함께다.
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
  /** me가 도착해 프로필을 고칠 수 있는 상태인지. 아니면 갈아 끼울 대상이 없다. */
  editable: boolean;
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
  // 저장이 쓰는 값(트림 후)으로 판정한다 — 트림 전 값으로 재면 끝에 공백만 붙여도 저장이
  // 풀리고, 눌러도 값은 그대로인데 「변경했습니다」가 뜬다.
  // me가 아직 없으면 잠근다: 갈아 끼울 대상이 없어 저장이 no-op인데 입력만 비워진다.
  const trimmedName = draftName.trim();
  const dirty = me !== undefined && trimmedName.length > 0 && trimmedName !== me.name;

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
    if (!dirty) return; // 버튼이 잠겨 있어도 호출부가 늘면 여기가 마지막 방어선이다
    patchMe({ name: trimmedName });
    setTyped(null); // me를 다시 따라가게 되돌린다 — 저장한 값과 입력값이 같아진다
    toast({ tone: 'success', title: '표시 이름을 변경했습니다' });
  }, [dirty, trimmedName, patchMe, toast]);

  const applyPhoto = useCallback(
    (dataUrl: string) => {
      // me가 없으면 patchMe가 no-op이라 사진이 조용히 버려진다 — 성공 토스트만 뜨는 것을
      // 막으려면 여기서 끊는 것으로는 부족하고 「사진 수정」 자체가 잠겨야 한다(editable).
      if (me === undefined) return;
      patchMe({ profileImageUrl: dataUrl });
    },
    [me, patchMe],
  );

  const completeWithdraw = useCallback(() => {
    // POK-171 백엔드가 붙으면 여기서 탈퇴를 부르고, 성공한 뒤에 아래로 넘어간다.
    // 지금은 아무것도 지우지 않는다 — 같은 계정으로 다시 로그인하면 그대로 돌아온다.
    //
    // 세션은 여기서 접지 않는다. clearTokens()가 도는 순간 AuthGuard가 리렌더되고 그
    // 이펙트가 의도적 종료 표식과 무관하게 /login으로 보내는데, 그것이 바로 아래 이동보다
    // 나중에 호출돼 이긴다 — 완료 화면이 뜰 틈이 없다. 가드 밖으로 나간 뒤 /goodbye가 접는다.
    markWithdrawn();
    router.replace('/goodbye');
  }, [router]);

  return {
    me,
    draftName,
    dirty,
    editable: me !== undefined,
    facts: WITHDRAW_FACTS,
    // 개발에서만 켠다. 프로덕션 번들에서는 이 항이 통째로 죽어(NODE_ENV 치환) 주소를
    // 쳐도 없는 미결제 금액이 뜨지 않는다
    blocked: process.env.NODE_ENV !== 'production' && searchParams.get('mock') === 'blocked',
    setDraftName: setTyped,
    saveName,
    applyPhoto,
    completeWithdraw,
  };
}
