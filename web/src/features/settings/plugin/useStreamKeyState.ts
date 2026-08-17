'use client';

import { useCallback, useState } from 'react';
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
  /** 발급 직후 1회만 존재하는 코드 원문 (ADR-019) — 서버가 발급 응답에만 실어준다. */
  justIssuedCode?: string;
}

export interface StreamKeyState {
  /** 최초 상태 조회 중 — 카드가 스켈레톤을 그린다. */
  loading: boolean;
  code: PairingCodeStatus;
  /** 발급·재발급 진행 중 — 버튼 잠금. */
  busy: boolean;
  /** 미발급 상태의 첫 발급. */
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

  const mutation = useMutation({
    mutationFn: async (mode: 'issue' | 'reissue') => {
      if (mode === 'reissue') {
        try {
          await rotateStreamKey();
        } catch (e) {
          // 404 = 폐기할 키가 없다 — 화면이 낡은(stale) 상태였을 뿐이고,
          // 이어지는 발급의 ensureKey가 키를 만들며 자연 복구된다. 오류로 끊지 않는다.
          if (!(e instanceof ApiError && e.status === 404)) throw e;
        }
      }
      return issuePairingCode();
    },
    onSuccess: (issued) => {
      setJustIssued(issued);
      // 코드 발급 = 온보딩 2단계(플러그인) 완료 (POK-113 시작 가이드 체크)
      markPluginLinked();
      void queryClient.invalidateQueries({ queryKey: streamKeyStatusQueryOptions.queryKey });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 429) {
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
  });

  const { mutate } = mutation;
  const issue = useCallback(() => mutate('issue'), [mutate]);
  const reissue = useCallback(() => mutate('reissue'), [mutate]);

  const createdAt = status.data?.createdAt;
  const issuedAt = justIssued
    ? new Date().toLocaleDateString('ko-KR')
    : createdAt
      ? new Date(createdAt).toLocaleDateString('ko-KR')
      : undefined;

  return {
    loading: status.isPending,
    code: {
      // 발급 직후엔 status 재조회가 끝나기 전에도 발급됨으로 보여야 한다
      issued: (status.data?.issued ?? false) || justIssued !== null,
      issuedAt,
      justIssuedCode: justIssued?.code,
    },
    busy: mutation.isPending,
    issue,
    reissue,
  };
}
