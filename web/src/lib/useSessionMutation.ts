'use client';

import { useEffect } from 'react';
// 이 파일만 react-query의 useMutation을 직접 쓴다(eslint.config.js의 막이에서 뺐다)
import {
  useMutation,
  type DefaultError,
  type QueryClient,
  type UseMutationOptions,
  type UseMutationResult,
} from '@tanstack/react-query';
import { captureSession } from '@/stores/auth';

/**
 * 요청을 보낸 뒤 이 브라우저의 세션이 바뀌었다(다른 탭이나 같은 탭의 로그인 · 로그아웃). 응답은 앞 사람 몫이라
 * 성공이든 실패든 버린다. 부른 쪽의 onSuccess · onError에는 오지 않는다.
 */
export class StaleSessionError extends Error {
  constructor() {
    super('session changed while the request was in flight');
    this.name = 'StaleSessionError';
  }
}

/**
 * 세션 세대를 확인하는 useMutation (POK-424). 성공하면 캐시를 고치거나 토스트 · 화면을 띄우는 뮤테이션은 전부 이것을 쓴다.
 *
 * 응답을 기다리는 사이 계정이 바뀌면 Providers가 캐시를 비우는데, 앞 계정의 응답이 그 뒤에 도착해 캐시에 값을 되심거나
 * 토스트 · 페어링 코드를 띄우면 다음 사람 화면에 남의 것이 그려진다. 그래서 요청을 보낼 때의 세션을 적어 두고, 응답이 왔을
 * 때 세대가 바뀌었으면 결과를 StaleSessionError로 바꿔 onSuccess · onError를 건너뛴다. onSettled는 그대로 부른다: 모달
 * 닫기 · 목록 다시 읽기는 새 세션에서도 맞는 일이다. 같은 세션의 토큰 회전은 세대를 안 올려 걸리지 않는다(stores/auth).
 *
 * 기능마다 확인을 흩뿌리지 않으려고 한 곳에 둔다. 새 뮤테이션이 빠지지 않게 eslint가 react-query의 useMutation을 직접
 * 쓰지 못하게 막는다.
 */
export function useSessionMutation<
  TData = unknown,
  TError = DefaultError,
  TVariables = void,
  TOnMutateResult = unknown,
>(
  options: UseMutationOptions<TData, TError, TVariables, TOnMutateResult>,
  queryClient?: QueryClient,
): UseMutationResult<TData, TError, TVariables, TOnMutateResult> {
  const { mutationFn, onError } = options;
  const result = useMutation<TData, TError, TVariables, TOnMutateResult>(
    {
      ...options,
      mutationFn:
        mutationFn &&
        (async (variables, context) => {
          const isSameSession = captureSession();
          let data: TData;
          try {
            data = await mutationFn(variables, context);
          } catch (e) {
            throw isSameSession() ? e : new StaleSessionError();
          }
          if (!isSameSession()) throw new StaleSessionError();
          return data;
        }),
      // 성공 쪽 버림은 위 mutationFn이 한다(StaleSessionError로 바꿔 onSuccess에 오지 않는다).
      // 재시도는 하지 않는다: 시도마다 세션을 새로 적으므로, 첫 시도가 실패한 사이 계정이 바뀌면 다음 시도가 앞 사람이
      // 누른 동작을 다음 사람 토큰으로 보내고 그 결과를 같은 세션으로 알린다(로컬 리뷰 1라운드). 사람이 누른 동작이라 다시
      // 누르면 된다
      retry: false,
      onError: (error, ...rest) => {
        if (error instanceof StaleSessionError) return;
        return onError?.(error, ...rest);
      },
    },
    queryClient,
  );
  // 버린 응답을 오류로 남기지 않는다: 오류 상태를 읽는 화면(초대 폼)이 앞 사람의 결과를 그리지 않게
  const { error, reset } = result;
  useEffect(() => {
    if (error instanceof StaleSessionError) reset();
  }, [error, reset]);
  return result;
}
