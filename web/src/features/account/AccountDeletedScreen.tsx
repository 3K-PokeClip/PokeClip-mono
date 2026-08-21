'use client';

import { useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { logoutSession } from '@/api/auth';
import { markIntentionalLogout } from '@/components/app-shell/AuthGuard';
import { useAuthStore } from '@/stores/auth';
import { Button } from '@/ui';
import { consumeWithdrawn } from './withdrawHandoff';
import styles from './AccountDeletedScreen.module.css';

// 탈퇴 완료 안내 (디자인 1p ④) — 로그아웃 뒤에 보이는 화면이라 (dock) 밖에 산다.
// AuthGuard는 토큰이 비는 순간 /login으로 보내므로 가드 안에 두면 뜰 틈이 없다.
//
// 세션을 접는 것도 여기서 한다. 탈퇴 화면에서 접으면 그 리렌더로 깨어난 가드가 /login으로
// 보내 이 화면이 뜨지 못한다 — 가드 밖으로 나온 지금이 접을 수 있는 첫 지점이다.
// 자세한 사정은 withdrawHandoff.ts에 적어 뒀다.
//
// ⚠ 아래 문구는 시안 1p ④ 그대로다. 탈퇴 백엔드(POK-171)가 아직 「할 일」이라 실제로는
// 계정도 보관함도 삭제되지 않았고 세션만 접힌 상태다 — 같은 계정으로 다시 로그인하면
// 데이터가 그대로 돌아온다. POK-171이 붙기 전까지 이 문장은 사실이 아니다.
export function AccountDeletedScreen() {
  const router = useRouter();
  const queryClient = useQueryClient();
  // StrictMode는 이펙트를 두 번 태운다 — 표식은 이미 첫 번에 소비됐다
  const settled = useRef(false);

  useEffect(() => {
    if (settled.current) return;
    settled.current = true;
    // 탈퇴로 온 것이 아니면(주소창 직접 진입) 남의 세션을 접지 않는다
    if (!consumeWithdrawn()) return;

    const { refreshToken, clearTokens } = useAuthStore.getState();
    // 서버 refresh 세션도 폐기한다 — 안 하면 탈퇴가 일반 로그아웃보다 덜 정리하는 꼴이 되어
    // 「탈퇴했다」고 믿는 사용자의 토큰이 만료(14일)까지 회전 가능한 채 남는다.
    // 실패는 무시한다(useLogout과 같은 계약) — 로컬 정리는 이미 끝났다.
    if (refreshToken !== null) {
      void logoutSession(refreshToken).catch(() => {
        /* 서버 폐기 실패 — 남은 refresh는 만료로 수렴한다 */
      });
    }
    markIntentionalLogout(); // 뒤로 가기로 가드에 걸려도 복원 경로를 남기지 않게
    clearTokens();
    queryClient.clear(); // 이전 계정의 me·스트림키가 다음 로그인에 새면 안 된다
  }, [queryClient]);

  return (
    <main className={styles.screen}>
      <section className={styles.card}>
        <h1 className={styles.title}>탈퇴가 완료되었어요</h1>
        <p className={styles.lead}>
          계정과 보관함 데이터가 삭제되었습니다. 같은 Google 계정으로 다시 가입할 수 있어요.
        </p>
        <div className={styles.actions}>
          <Button variant="soft" size="md" onClick={() => router.replace('/login')}>
            로그인 화면으로
          </Button>
        </div>
      </section>
    </main>
  );
}
