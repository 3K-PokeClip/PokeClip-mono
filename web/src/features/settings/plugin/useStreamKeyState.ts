'use client';

import { useCallback, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSessionMutation } from '@/lib/useSessionMutation';
import { ApiError } from '@/api/client';
import {
  issuePairingCode,
  PAIRING_CODE_TTL_MS,
  streamKeyStatusQueryOptions,
  type StreamKeyStatus,
} from '@/api/streamKeys';
import { track } from '@/lib/analytics';
import { syncPluginPaired } from '@/lib/pluginPairing';
import { useToast } from '@/ui';

// 연동 코드 실상태 훅 (POK-102).
// 화면 용어 "연동 코드"는 사용자 모델이고, 실제로는 스트림키 유무(GET /api/stream-keys)와
// 페어링 코드 발급(POST pairing-codes)의 합성이다. 발급·재발급은 같은 동작 —
// 새 일회용 코드를 찍는 1콜이고(키가 없으면 서버 ensureKey가 만든다), 키 회전(rotate)은
// 프론트 흐름에서 쓰지 않으므로 재발급이 방송 키를 건드리지 않는다.
// 연결 여부는 같은 응답의 연결 시각(lastPairedAt)으로만 안다(POK-608) — 발급은 연결이 아니다.

/**
 * 코드 창을 띄워 둔 동안 상태를 다시 읽는 주기(POK-608) — 코드를 OBS에 넣은 것을 몇 초 안에 알아챈다.
 * 숨은 탭에서는 묻지 않는다(OBS에 가 있는 동안). 돌아오면 다음 주기에 바로 읽는다.
 */
export const PAIRING_WATCH_MS = 3000;

export interface PairingCodeStatus {
  /** 코드를 받은 적이 있다(키 존재) — 연결했다는 뜻이 아니다. 「전에 받은 코드는 다시 볼 수 없어요」를 가르는 데만 쓴다. */
  issued: boolean;
  /**
   * 플러그인에 코드를 넣은 마지막 날(서버 키 lastPairedAt, POK-608) — 없으면 연결한 적이 없다(코드만 받았거나,
   * 이 칸이 생기기 전에 연결했다). 이미 포맷된 표시용 날짜다.
   */
  pairedAt?: string;
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
  /**
   * 이 코드를 발급한 **서버** 시각(epoch ms) = 발급 응답 expiresAt − TTL. 서버의 연결 시각(lastPairedAt)이 이 뒤면
   * 이 코드(또는 그 뒤에 넣은 코드)로 연결한 것이다 — 둘 다 서버 시계라 클라 시계와 캐시 상태에 흔들리지 않는다.
   * 「있다」만 보면 이미 연결한 회원이 다른 PC용 코드를 받자마자 연결됐다고 하고, 발급 순간의 값을 캐시에서 읽으면
   * 캐시가 낡았을 때 새 코드가 쓰이기도 전에 「연결됐어요」가 뜬다(PR #539 리뷰).
   */
  issuedAt: number;
}

/** 지켜보는 코드 — 창과 따로 산다. 창을 닫아도 코드가 살아 있는 동안은 연결을 기다린다(PR #539 리뷰) */
interface WatchedCode {
  issuedAt: number;
  deadline: number;
}

/** 서버가 준 연결 시각이 이 코드의 발급 뒤인가 */
function pairedSince(lastPairedAt: string | undefined, issuedAt: number): boolean {
  return lastPairedAt !== undefined && Date.parse(lastPairedAt) >= issuedAt;
}

export interface StreamKeyState {
  /** 최초 상태 조회 중 — 카드가 스켈레톤을 그린다. */
  loading: boolean;
  /** 상태를 한 번도 못 읽었다 — 미발급으로 오인시키지 말고 오류·재시도를 그린다. */
  error: boolean;
  retryStatus: () => void;
  /** 「다시 시도」로 다시 읽는 중 — 오류 카드를 둔 채 누른 버튼만 돈다(POK-446). */
  retrying: boolean;
  code: PairingCodeStatus;
  /** 발급 진행 중 — 버튼 잠금. */
  busy: boolean;
  /** 발급 직후 1회 표시용 원문+만료 마감 (ADR-019) — 모달(IssuedCodeDialog)만 소비한다. */
  justIssued: DisplayedPairingCode | null;
  /**
   * 띄워 둔 코드가 OBS에 들어갔다(POK-608) — 모달이 「연결됐어요」로 바뀐다. 모달을 닫으면 false로 돌아가고,
   * 그 뒤의 연결 사실은 code.pairedAt이 말한다.
   */
  justPaired: boolean;
  /**
   * 받은 코드가 아직 살아 있고 쓰이기를 기다리는 중이다 — 창을 닫았어도. 연결 순서 카드가 「새로 받아 주세요」 대신
   * 「받은 코드를 넣으면 알려 드려요」라고 하게 한다(PR #539 리뷰: 막 OBS에 넣으려는 사람에게 실패처럼 읽혔다).
   */
  codeWaiting: boolean;
  /** 모달을 닫는 지점 — 이 뒤로 원문은 다시 볼 수 없다. */
  clearJustIssued: () => void;
  /** 새 일회용 코드 발급 — 첫 발급·재발급·만료 후 재발급 전부 이 한 경로다. */
  issue: () => void;
}

export function useStreamKeyState(): StreamKeyState {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [justIssued, setJustIssued] = useState<DisplayedPairingCode | null>(null);
  const [watching, setWatching] = useState<WatchedCode | null>(null);
  // 코드가 죽으면 지켜보기를 거둔다 — 순서 카드 문구도 이때 「새로 받아 주세요」로 돌아간다.
  // 거두기 전에 한 번 더 읽는다: 마지막 주기 조회(최대 3초 전)와 마감 사이에 넣은 코드도 성공이다. 안 읽고 거두면 창은
  // 「코드가 만료됐어요」를 띄우고 화면을 옮기거나 60초(staleTime)가 지나기 전에는 연결을 모른다(PR #539 2차 리뷰)
  useEffect(() => {
    if (watching === null) return;
    let cancelled = false;
    const id = window.setTimeout(
      () => {
        void queryClient
          .refetchQueries({ queryKey: streamKeyStatusQueryOptions.queryKey })
          .finally(() => {
            if (!cancelled) setWatching(null);
          });
      },
      Math.max(0, watching.deadline - Date.now()),
    );
    return () => {
      cancelled = true;
      window.clearTimeout(id);
    };
  }, [watching, queryClient]);
  const status = useQuery({
    ...streamKeyStatusQueryOptions,
    // 받은 코드가 살아 있는 동안 다시 읽는다(POK-608) — 창을 닫아도. 창의 주 버튼이 「확인했어요」라 코드를 복사하고
    // 창부터 닫는 흐름이 자연스러운데, 창에 묶었을 때는 전역 staleTime(60초) 탓에 돌아와도 연결을 못 봤다(PR #539 리뷰).
    // 연결을 봤거나 만료되면 멈춘다 — 읽을 때마다 다시 계산되므로 마지막 한 번을 읽은 뒤에 선다
    refetchInterval: (query) => {
      if (watching === null || Date.now() >= watching.deadline) return false;
      return pairedSince(query.state.data?.lastPairedAt, watching.issuedAt)
        ? false
        : PAIRING_WATCH_MS;
    },
  });

  const mutation = useSessionMutation({
    mutationFn: issuePairingCode,
    onSuccess: (issued) => {
      // 마감은 서버 expiresAt이 아니라 "수신 순간 + TTL"로 앵커한다 — 클라 시계가
      // 서버와 어긋나 있어도 카운트다운은 같은 시계끼리 비교하게 된다. (리뷰 #74)
      // 연결 판정은 거꾸로 서버 시각끼리 견준다 — 발급 시각도 서버 값(expiresAt − TTL)으로 둔다(DisplayedPairingCode.issuedAt)
      const deadline = Date.now() + PAIRING_CODE_TTL_MS;
      const issuedAt = Date.parse(issued.expiresAt) - PAIRING_CODE_TTL_MS;
      setJustIssued({ code: issued.code, deadline, issuedAt });
      setWatching({ issuedAt, deadline });
      // 발급 성공 = 키 존재 확정(서버 ensureKey). 재조회가 끝나기 전이나 실패한 채로
      // 모달을 닫아도(justIssued가 비워져도) 「코드를 받은 적 없음」으로 되돌아가지 않게
      // 캐시를 먼저 낙관 갱신한다. 키가 이미 있었으면 createdAt(최초 발급일)은 그대로
      // 둔다 — 홈도 같은 캐시를 읽으니 재조회가 과거로 되돌릴 때 어긋나지 않게 한다. (리뷰 #74)
      // 연결 시각도 그대로 둔다 — 발급은 연결이 아니라서 만들지도 지우지도 않는다(POK-608)
      queryClient.setQueryData<StreamKeyStatus>(streamKeyStatusQueryOptions.queryKey, (prev) => ({
        ...prev,
        issued: true,
        createdAt: prev?.createdAt ?? new Date().toISOString(),
      }));
      // 둘러보기 2단계 체크는 여기서 올리지 않는다 — 코드를 받은 것이지 OBS에 넣은 것이 아니다(아래 syncPluginPaired)
      track('pairing_code_issued', {}); // 코드 값은 싣지 않는다 (POK-357)
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
  const [retrying, setRetrying] = useState(false);
  const retryStatus = useCallback(() => {
    // 응답까지 누른 「다시 시도」를 돌린다(POK-446). 데이터가 없는 쿼리는 다시 읽는 동안 pending으로 돌아가 오류가
    // 지워진다 — 그대로 두면 오류 카드가 스켈레톤으로 바뀌어 누른 버튼이 사라진다
    setRetrying(true);
    void refetch().finally(() => setRetrying(false));
  }, [refetch]);
  // 데이터가 오면 바로 끝이다: 그 뒤의 다시 읽기는 받아 둔 상태를 그린다
  const retryingFirstLoad = retrying && status.data === undefined;

  // 카드의 날짜는 서버가 준 연결 시각 하나만 쓴다 — 서버 ISO를 받은 뒤에만 바꾸므로(SSR 때는 쿼리 데이터가 없다)
  // 하이드레이션이 어긋나지 않는다. 발급일(createdAt)은 이제 화면에 적지 않는다: 연결한 적 없는 회원은 날짜 대신
  // 연결 순서를 본다(ConnectStepsCard)
  const lastPairedAt = status.data?.lastPairedAt;
  const pairedAt = lastPairedAt ? new Date(lastPairedAt).toLocaleDateString('ko-KR') : undefined;
  // 띄워 둔 코드가 쓰였나 — 서버의 연결 시각이 그 코드의 발급 뒤일 때만이다(DisplayedPairingCode.issuedAt)
  const justPaired = justIssued !== null && pairedSince(lastPairedAt, justIssued.issuedAt);
  const codeWaiting = watching !== null && !pairedSince(lastPairedAt, watching.issuedAt);

  // 읽은 연결 사실을 둘러보기 체크·분석 이벤트로 올린다. 코드 창이 떠 있을 때 본 연결과 창을 닫은 뒤에 본 연결을 가른다
  const statusData = status.data;
  useEffect(() => {
    if (statusData !== undefined)
      syncPluginPaired(statusData, justPaired ? 'code_dialog' : 'later');
  }, [statusData, justPaired]);

  return {
    loading: status.isPending && !retryingFirstLoad,
    // 데이터를 한 번이라도 읽었으면 그것을 그대로 보여준다 — 재조회 실패에 오류 화면으로
    // 갈아끼우는 쪽이 더 헷갈린다. "아무것도 모르는" 최초 실패만 오류로 그린다. (리뷰 #73)
    error: (status.isError && status.data === undefined) || retryingFirstLoad,
    retryStatus,
    retrying: retryingFirstLoad,
    code: {
      // 발급 직후엔 status 재조회가 끝나기 전에도 발급됨으로 보여야 한다
      issued: (status.data?.issued ?? false) || justIssued !== null,
      pairedAt,
    },
    busy: mutation.isPending,
    justIssued,
    justPaired,
    codeWaiting,
    clearJustIssued,
    issue,
  };
}
