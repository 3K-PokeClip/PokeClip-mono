'use client';

import { useRouter } from 'next/navigation';
import { Button } from '@/ui';
import styles from './AccountDeletedScreen.module.css';

// 탈퇴 완료 안내 (디자인 1p ④) — 로그아웃 뒤에 보이는 화면이라 (dock) 밖에 산다.
// AuthGuard는 토큰이 비는 순간 /login으로 보내므로 가드 안에 두면 뜰 틈이 없다.
//
// ⚠ 아래 문구는 시안 1p ④ 그대로다. 탈퇴 백엔드(POK-171)가 아직 「할 일」이라
// 실제로는 아무것도 삭제되지 않았고 로컬 세션만 접힌 상태다 — 같은 계정으로 다시
// 로그인하면 데이터가 그대로 돌아온다. POK-171이 붙기 전까지 이 문장은 사실이 아니다.
export function AccountDeletedScreen() {
  const router = useRouter();

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
