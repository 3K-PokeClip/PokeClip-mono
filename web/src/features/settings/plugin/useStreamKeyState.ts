'use client';

import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  issuePairingCode,
  PAIRING_CODE_TTL_MS,
  streamKeyStatusQueryOptions,
  type StreamKeyStatus,
} from '@/api/streamKeys';
import { useOnboardingStore } from '@/stores/onboarding';
import { useToast } from '@/ui';

// 연동 코드 실상태 훅 (POK-102) — usePluginMockState의 교체 주석이 약속한 자리.
// 화면 용어 "연동 코드"는 사용자 모델이고, 실제로는 스트림키 유무(GET /api/stream-keys)와
// 페어링 코드 발급(POST pairing-codes)의 합성이다. 발급·재발급은 같은 동작 —
// 새 일회용 코드를 찍는 1콜이고(키가 없으면 서버 ensureKey가 만든다), 키 회전(rotate)은
// 프론트 흐름에서 쓰지 않으므로 재발급이 방송 키를 건드리지 않는다.

export interface PairingCodeStatus {
  issued: boolean;
  /**
   * 최초 발급일(서버 키 createdAt) — 재발급해도 바뀌지 않는다. 이미 포맷된 표시용
   * 날짜로, 서버 ISO를 클라 수신 후에만 변환한다(SSR 시 쿼리 데이터가 없어 하이드레이션 안전).
   */
  issuedAt?: string;
}

/** 발급 직후 모달(IssuedCodeDialog)이 소비하는 표시용 코드. */
export interface DisplayedPairingCode {
  code: string;
  /**
   * 만료 마감(클라 시계 epoch ms) = 발급 응답 수신 시각 + PAIRING_CODE_TTL_MS.
   * 서버 expiresAt을 쓰지 않는 이유: 서버 시각을 클라 시계와 직접 비교하면 시계가
   * 어긋난 기기에서 정상 코드가 발급 즉시 만료로 보인다. (리뷰 #74)
   */
  deadline: number;
}

export interface StreamKeyState {
  /** 최초 상태 조회 중 — 카드가 스켈레톤을 그린다. */
  loading: boolean;
  /** 상태를 한 번도 못 읽었다 — 미발급으로 오인시키지 말고 오류·재시도를 그린다. */
  error: boolean;
  retryStatus: () => void;
  code: PairingCodeStatus;
  /** 발급 진행 중 — 버튼 잠금. */
  busy: boolean;
  /** 발급 직후 1회 표시용 원문+만료 마감 (ADR-019) — 모달(IssuedCodeDialog)만 소비한다. */
  justIssued: DisplayedPairingCode | null;
  /** 모달을 닫는 지점 — 이 뒤로 원문은 다시 볼 수 없다. */
  clearJustIssued: () => void;
  /** 새 일회용 코드 발급 — 첫 발급·재발급·만료 후 재발급 전부 이 한 경로다. */
  issue: () => void;
}

export function useStreamKeyState(): StreamKeyState {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const markPluginLinked = useOnboardingStore((s) => s.markPluginLinked);
  const status = useQuery(streamKeyStatusQueryOptions);
  const [justIssued, setJustIssued] = useState<DisplayedPairingCode | null>(null);

  const mutation = useMutation({
    mutationFn: issuePairingCode,
    onSuccess: (issued) => {
      // 마감은 서버 expiresAt이 아니라 "수신 순간 + TTL"로 앵커한다 — 클라 시계가
      // 서버와 어긋나 있어도 카운트다운은 같은 시계끼리 비교하게 된다. (리뷰 #74)
      setJustIssued({ code: issued.code, deadline: Date.now() + PAIRING_CODE_TTL_MS });
      // 발급 성공 = 키 존재 확정(서버 ensureKey). 재조회가 끝나기 전이나 실패한 채로
      // 모달을 닫아도(justIssued가 비워져도) 카드가 "미발급"으로 되돌아가지 않게
      // 캐시를 먼저 낙관 갱신한다. 키가 이미 있었으면 createdAt(최초 발급일)은 그대로
      // 둔다 — 재발급이 날짜를 오늘로 튀게 하면 재조회가 과거로 되돌릴 때 어긋난다. (리뷰 #74)
      queryClient.setQueryData<StreamKeyStatus>(streamKeyStatusQueryOptions.queryKey, (prev) => ({
        issued: true,
        createdAt: prev?.createdAt ?? new Date().toISOString(),
      }));
      // 코드 발급 = 온보딩 2단계(플러그인) 완료 (POK-113 시작 가이드 체크)
      markPluginLinked();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 429) {
        // ADR-019: 계정당 분당 3회 — 문구도 그 제한값 그대로 안내한다 (POK-103 완료조건)
        toast({
          tone: 'error',
          title: '코드 발급이 잠시 제한됐어요',
          description: '1분에 3회까지만 발급할 수 있어요. 잠시 후 다시 시도해 주세요.',
        });
        return;
      }
      toast({
        tone: 'error',
        title: '코드 발급에 실패했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: streamKeyStatusQueryOptions.queryKey });
    },
  });

  const { mutate } = mutation;
  const issue = useCallback(() => mutate(), [mutate]);
  const clearJustIssued = useCallback(() => setJustIssued(null), []);
  const { refetch } = status;
  const retryStatus = useCallback(() => {
    void refetch();
  }, [refetch]);

  // 카드의 날짜는 서버 createdAt(최초 발급일) 하나만 쓴다 — 발급 직후엔 위 낙관 갱신이
  // 값을 보장한다. justIssued로 "오늘"을 따로 만들면 재발급 시 재조회가 과거 날짜로
  // 되돌리면서 화면이 튄다. (리뷰 #74)
  const createdAt = status.data?.createdAt;
  const issuedAt = createdAt ? new Date(createdAt).toLocaleDateString('ko-KR') : undefined;

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
  };
}
