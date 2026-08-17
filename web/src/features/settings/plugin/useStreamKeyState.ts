'use client';

import { useCallback, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  issuePairingCode,
  rotateStreamKey,
  streamKeyStatusQueryOptions,
  type IssuedPairingCode,
} from '@/api/streamKeys';
import { useOnboardingStore } from '@/stores/onboarding';
import { useToast } from '@/ui';

// 연동 코드 실상태 훅 (POK-102) — usePluginMockState의 교체 주석이 약속한 자리.
// 화면 용어 "연동 코드"는 사용자 모델이고, 실제로는 스트림키 유무(GET /api/stream-keys)와
// 페어링 코드 발급(POST pairing-codes)의 합성이다:
//   최초 발급 = pairing-codes 1콜 (서버 ensureKey가 키를 만든다 — 발급의 유일한 입구)
//   재발급   = rotate(기존 키 즉시 만료, ADR-019 유예 없음) → pairing-codes

export interface PairingCodeStatus {
  issued: boolean;
  /** 이미 포맷된 표시용 날짜 — 서버 ISO를 클라 수신 후에만 변환한다(SSR 시 쿼리 데이터가 없어 하이드레이션 안전). */
  issuedAt?: string;
}

export interface StreamKeyState {
  /** 최초 상태 조회 중 — 카드가 스켈레톤을 그린다. */
  loading: boolean;
  /** 상태를 한 번도 못 읽었다 — 미발급으로 오인시키지 말고 오류·재시도를 그린다. */
  error: boolean;
  retryStatus: () => void;
  code: PairingCodeStatus;
  /** 발급·재발급 진행 중 — 버튼 잠금. */
  busy: boolean;
  /** 발급 직후 1회 표시용 원문+만료 시각 (ADR-019) — 모달(IssuedCodeDialog)만 소비한다. */
  justIssued: IssuedPairingCode | null;
  /** 모달을 닫는 지점 — 이 뒤로 원문은 다시 볼 수 없다. */
  clearJustIssued: () => void;
  /** 미발급 상태의 첫 발급. 만료 후 "새 코드 발급"도 이 경로다(코드 만료는 키와 무관). */
  issue: () => void;
  /** 재발급 — 반드시 확인 모달(RotateConfirmDialog) 뒤에서만 부른다. */
  reissue: () => void;
}

export function useStreamKeyState(): StreamKeyState {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const markPluginLinked = useOnboardingStore((s) => s.markPluginLinked);
  const status = useQuery(streamKeyStatusQueryOptions);
  const [justIssued, setJustIssued] = useState<IssuedPairingCode | null>(null);
  // 재발급은 rotate→발급 비원자 2콜이다. rotate만 성공하고 발급이 실패한 상태를
  // 기억해 두면 ① 재시도가 키를 불필요하게 또 회전하지 않고 ② 오류 안내가
  // "기존 키는 이미 만료됐다"는 실상을 말할 수 있다. (리뷰 #73)
  const rotatedWithoutCodeRef = useRef(false);

  const mutation = useMutation({
    mutationFn: async (mode: 'issue' | 'reissue') => {
      if (mode === 'reissue' && !rotatedWithoutCodeRef.current) {
        try {
          await rotateStreamKey();
          rotatedWithoutCodeRef.current = true;
        } catch (e) {
          // 404 = 폐기할 키가 없다 — 화면이 낡은(stale) 상태였을 뿐이고,
          // 이어지는 발급의 ensureKey가 키를 만들며 자연 복구된다. 오류로 끊지 않는다.
          if (!(e instanceof ApiError && e.status === 404)) throw e;
        }
      }
      const issued = await issuePairingCode();
      rotatedWithoutCodeRef.current = false;
      return issued;
    },
    onSuccess: (issued) => {
      setJustIssued(issued);
      // 코드 발급 = 온보딩 2단계(플러그인) 완료 (POK-113 시작 가이드 체크)
      markPluginLinked();
    },
    onError: (e, mode) => {
      const limited = e instanceof ApiError && e.status === 429;
      if (mode === 'reissue' && rotatedWithoutCodeRef.current) {
        // rotate는 이미 성공 — 방송 중이었다면 이미 끊겼다. 발급 실패만 말하면
        // 사용자가 옛 키가 살아 있다고 믿는다.
        toast({
          variant: 'danger',
          title: '기존 키는 이미 만료됐어요',
          description: limited
            ? '새 코드 발급이 분당 한도(3회)에 걸렸어요. 잠시 후 재발급을 다시 누르면 코드만 발급돼요.'
            : '새 코드 발급에 실패했어요. 재발급을 다시 누르면 키 회전 없이 코드만 발급돼요.',
        });
        return;
      }
      if (limited) {
        // ADR-019: 계정당 분당 3회 — 문구도 그 제한값 그대로 안내한다 (POK-103 완료조건)
        toast({
          variant: 'danger',
          title: '코드 발급이 잠시 제한됐어요',
          description: '1분에 3회까지만 발급할 수 있어요. 잠시 후 다시 시도해 주세요.',
        });
        return;
      }
      toast({
        variant: 'danger',
        title: '코드 발급에 실패했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
    onSettled: () => {
      // 실패해도 재조회한다 — rotate만 성공한 경우에도 발행 시각(createdAt)은 이미 바뀌어 있다
      void queryClient.invalidateQueries({ queryKey: streamKeyStatusQueryOptions.queryKey });
    },
  });

  const { mutate } = mutation;
  const issue = useCallback(() => mutate('issue'), [mutate]);
  const reissue = useCallback(() => mutate('reissue'), [mutate]);
  const { refetch } = status;
  const retryStatus = useCallback(() => {
    void refetch();
  }, [refetch]);
  const clearJustIssued = useCallback(() => setJustIssued(null), []);

  const createdAt = status.data?.createdAt;
  const issuedAt = justIssued
    ? new Date().toLocaleDateString('ko-KR')
    : createdAt
      ? new Date(createdAt).toLocaleDateString('ko-KR')
      : undefined;

  return {
    loading: status.isPending,
    // 데이터를 한 번이라도 읽었으면 그것을 그대로 보여준다 — 재조회 실패에 오류 화면으로
    // 갈아끼우는 쪽이 더 헷갈린다. "아무것도 모르는" 최초 실패만 오류로 그린다. (리뷰 #73)
    error: status.isError && status.data === undefined,
    retryStatus,
    code: {
      // 발급 직후엔 status 재조회가 끝나기 전에도 발급됨으로 보여야 한다
      issued: (status.data?.issued ?? false) || justIssued !== null,
      issuedAt,
    },
    busy: mutation.isPending,
    justIssued,
    clearJustIssued,
    issue,
    reissue,
  };
}
