'use client';

import { useCallback, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { meQueryOptions, type Me } from '@/api/auth';
import {
  displayNameFailureMessage,
  displayNameTooLong,
  NAME_MAX_CODE_POINTS,
  normalizeDisplayName,
  updateDisplayName,
  uploadProfilePhoto,
} from '@/api/profile';
import { useMe } from '@/features/auth/useSession';
import { useToast } from '@/ui';

// 설정 · 계정의 실상태 훅 (POK-208 ← 서버 POK-207) — 표시 이름·프로필 사진 저장이 auth 서버로
// 간다. 탈퇴는 아직 목업이라 useAccountMockState에 남아 있다(usePluginMockState 선례처럼 실·목업을
// 파일로 가른다 — 한 파일에 섞으면 안에 가짜가 있다는 걸 다음 사람이 못 알아챈다).
//
// 두 창구 모두 GET /api/auth/me와 같은 모양으로 답하므로 재조회 없이 me 캐시를 통째로 덮는다 —
// 헤더·사이드바가 같은 캐시를 읽어 즉시 따라온다. setQueryData가 dataUpdatedAt을 지금으로 갱신해
// staleTime 60초 안의 포커스 재조회에도 되감기지 않는다.

const TOO_LONG_MESSAGE = `${NAME_MAX_CODE_POINTS}자 이내로 입력해 주세요`;

export interface AccountViewState {
  me: Me | undefined;
  /** 입력 중인 표시 이름. 저장 전까지 me와 갈린다. */
  draftName: string;
  /** 저장 버튼이 살아 있는지 — 비었거나 그대로거나 상한을 넘으면 누를 것이 없다. */
  dirty: boolean;
  /** me가 도착해 프로필을 고칠 수 있는 상태인지. 아니면 갈아 끼울 대상이 없다. */
  editable: boolean;
  /** 이름 저장 왕복 중 — 버튼이 잠기고 스피너가 돈다. */
  saving: boolean;
  /** 입력 아래 한 줄 — 화면이 먼저 거른 30자 초과와 서버가 거절한 400 사유가 같은 자리를 쓴다. */
  nameError: string | null;
  setDraftName: (next: string) => void;
  saveName: () => void;
  /** 잘라낸 사진을 올리고 me 캐시를 응답으로 덮는다. 진행 상태는 호출부(상태 기계)가 갖는다. */
  uploadPhoto: (blob: Blob, filename: string, signal: AbortSignal) => Promise<void>;
  /** 업로드를 취소·중단한 뒤 진실을 다시 읽는다 — 서버는 창고에 먼저 쓰므로 이미 커밋했을 수 있다. */
  refetchMe: () => void;
}

export function useAccountState(): AccountViewState {
  const { data: me } = useMe();
  const queryClient = useQueryClient();
  const { toast } = useToast();

  // me가 늦게 오므로 입력값은 「아직 손대지 않음(null)」과 「비웠음('')」을 갈라 둔다 —
  // 하나로 합치면 이름을 지운 순간 서버 이름이 되살아나 입력이 되감긴다.
  const [typed, setTyped] = useState<string | null>(null);
  // 서버가 거절한 사유. 입력을 고치는 순간 걷는다 — 다음 판정은 서버가 다시 한다.
  const [serverNameError, setServerNameError] = useState<string | null>(null);
  const draftName = typed ?? me?.name ?? '';
  // 저장이 쓰는 값(서버와 같은 규칙으로 양끝을 자른 값)으로 판정한다 — 자르기 전 값으로 재면
  // 끝에 공백만 붙여도 저장이 풀리고, 눌러도 값은 그대로인데 「변경했습니다」가 뜬다.
  const normalizedName = normalizeDisplayName(draftName);
  const tooLong = displayNameTooLong(normalizedName);
  // 뮤테이션 콜백은 mutate() 시점의 옵션을 붙들고 돌아 클로저의 draftName이 낡는다 —
  // 응답이 왔을 때의 입력값을 봐야 하는 자리(onError)를 위해 최신 값을 ref로 든다.
  const normalizedNameRef = useRef(normalizedName);
  normalizedNameRef.current = normalizedName;

  /**
   * me 캐시에 **이 창구가 소유한 필드만** 얹는다. 응답 전체로 덮지 않는 이유가 둘이다.
   *
   * ① 이름·사진 요청이 겹치면 각 응답이 상대가 바꾸기 전의 스냅샷을 담고 있어, 나중에 도착한
   *    쪽이 상대의 변경을 캐시에서 되돌린다 — 서버에는 둘 다 반영돼 있는데도.
   * ② `prev`가 없으면 아무것도 쓰지 않는다. `queryClient.clear()`(로그아웃·계정 교체)는 진행 중
   *    뮤테이션을 끊지 않아 늦게 온 응답이 비운 캐시를 **되살린다** — 그 사이 다른 계정이
   *    로그인했다면 그 화면에 이전 계정의 이름·사진이 신선한 값으로 걸린다.
   */
  const patchMe = useCallback(
    (patch: Partial<Me>) => {
      queryClient.setQueryData<Me>(meQueryOptions.queryKey, (prev) =>
        prev === undefined ? prev : { ...prev, ...patch },
      );
    },
    [queryClient],
  );

  const save = useMutation({
    mutationFn: updateDisplayName,
    onMutate: async () => {
      setServerNameError(null);
      // PATCH 전에 이미 날아가 있던 GET me가 뒤늦게 도착하면 옛 이름으로 캐시를 되돌린다 — 끊는다.
      await queryClient.cancelQueries({ queryKey: meQueryOptions.queryKey });
    },
    onSuccess: (next, submitted) => {
      patchMe({ name: next.name });
      // 왕복 중 더 친 입력은 살린다 — 제출한 값 그대로일 때만 me를 다시 따라가게 되돌린다.
      setTyped((cur) => (cur !== null && normalizeDisplayName(cur) === submitted ? null : cur));
      toast({ tone: 'success', title: '표시 이름을 변경했습니다' });
    },
    onError: (e, submitted) => {
      // 입력을 고쳐 풀 수 있는 실패(400)는 입력 아래에 그린다 — 폼 오류는 폼이 갖는다(ADR-044).
      const message = displayNameFailureMessage(e);
      if (message !== null) {
        // 🔴 제출한 값이 아직 입력에 그대로 있을 때만 붙인다. 입력은 왕복 중에도 잠기지 않으므로
        // (잠기는 것은 버튼뿐) 사용자가 그 사이 고쳐 놓았을 수 있는데, 그 새 값은 아직 서버의
        // 심판을 받지 않았다 — 거기에 옛 제출의 사유를 붙이면 멀쩡한 이름이 빨갛게 선다.
        // onSuccess의 setTyped 가드와 같은 경쟁이다. 어긋나면 조용히 버린다: 저장 버튼이
        // 살아 있는 것(dirty)이 「아직 저장 안 됨」의 신호라 다시 누르면 새 판정을 받는다.
        if (normalizedNameRef.current === submitted) setServerNameError(message);
        return;
      }
      // 입력 탓이 아닌 실패는 지금 입력이 무엇이든 「저장이 실패했다」가 참이라 그대로 알린다.
      toast({
        tone: 'error',
        title: '표시 이름을 저장하지 못했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
  });
  const { mutate: mutateName, isPending: saving } = save;

  // me가 아직 없으면 잠근다: 갈아 끼울 대상이 없어 저장이 no-op인데 입력만 비워진다.
  const dirty =
    me !== undefined && normalizedName.length > 0 && !tooLong && normalizedName !== me.name;

  const saveName = useCallback(() => {
    if (!dirty || saving) return; // 버튼이 잠겨 있어도 호출부가 늘면 여기가 마지막 방어선이다
    mutateName(normalizedName);
  }, [dirty, saving, mutateName, normalizedName]);

  const setDraftName = useCallback((next: string) => {
    setTyped(next);
    setServerNameError(null);
  }, []);

  const uploadPhoto = useCallback(
    async (blob: Blob, filename: string, signal: AbortSignal) => {
      await queryClient.cancelQueries({ queryKey: meQueryOptions.queryKey });
      const next = await uploadProfilePhoto(blob, filename, signal);
      patchMe({ profileImageUrl: next.profileImageUrl });
    },
    [queryClient, patchMe],
  );

  const refetchMe = useCallback(() => {
    // 🔴 이 조회는 진실을 보장하지 못한다. 취소는 서버를 멈추지 못하고(창고 먼저·표 나중),
    // 우리가 응답을 버렸으므로 서버가 커밋했는지 알 길이 없다 — 이 GET이 커밋을 앞지르면
    // **옛 사진을 신선한 값으로** 붙들게 된다.
    //
    // 그래서 읽은 뒤 곧바로 낡음 표시를 남긴다: 다음 포커스·마운트가 staleTime 60초를
    // 기다리지 않고 한 번 더 확인한다. 여기서 보장하는 것은 「맞춘다」가 아니라
    // 「틀린 값을 오래 붙들지 않는다」까지다. refetchType:'none'이라 이 표시가 다시
    // 조회를 부르지 않는다 — 순환이 생기지 않는다.
    void queryClient.invalidateQueries({ queryKey: meQueryOptions.queryKey }).then(() =>
      queryClient.invalidateQueries({
        queryKey: meQueryOptions.queryKey,
        refetchType: 'none',
      }),
    );
  }, [queryClient]);

  return {
    me,
    draftName,
    dirty,
    editable: me !== undefined,
    saving,
    // 상한 초과는 요청을 보내지 않고 화면이 먼저 말한다 — 서버가 같은 사유(NAME_TOO_LONG)로
    // 거절할 것이 확실한 유일한 규칙이라서다. 제어문자·보이지 않는 글자 판정은 서버에 맡긴다.
    nameError: tooLong ? TOO_LONG_MESSAGE : serverNameError,
    setDraftName,
    saveName,
    uploadPhoto,
    refetchMe,
  };
}
