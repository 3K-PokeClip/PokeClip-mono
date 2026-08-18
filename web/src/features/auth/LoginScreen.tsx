'use client';

import { useEffect, useState } from 'react';
import Image from 'next/image';
import { useRouter } from 'next/navigation';
import { consumeReturnPath, restoreReturnPath } from '@/components/app-shell/AuthGuard';
import { useAuthHydration, useAuthStore } from '@/stores/auth';
import { GoogleGIcon } from './GoogleGIcon';
import { startGoogleLogin } from './googleOAuth';
import styles from './LoginScreen.module.css';

// 디자인 1r — Google 계정 전용 로그인. 버튼이 구글 동의 화면으로 보내고,
// 복귀 처리는 /auth/callback(OAuthCallbackScreen)이 맡는다 (POK-101).
export function LoginScreen() {
  const router = useRouter();
  useAuthHydration();
  const hydrated = useAuthStore((s) => s.hydrated);
  const refreshToken = useAuthStore((s) => s.refreshToken);
  const [startFailed, setStartFailed] = useState(false);

  // 역가드 — 이미 세션이 있으면 로그인 화면에 머물 이유가 없다.
  useEffect(() => {
    if (hydrated && refreshToken !== null) router.replace('/home');
  }, [hydrated, refreshToken, router]);

  // OAuth 진입 실패(NEXT_PUBLIC_GOOGLE_CLIENT_ID 부재 등 배포 설정 오류) — onClick의
  // throw는 에러 바운더리 밖이라 콘솔에만 남고 사용자에겐 "버튼이 안 눌리는" 증상이
  // 된다. 문구로 표면화하고, 이미 소모한 복원 경로는 되돌린다. (리뷰 #72)
  const handleGoogleLogin = () => {
    const returnTo = consumeReturnPath();
    try {
      startGoogleLogin(returnTo ?? undefined);
    } catch {
      if (returnTo !== null) restoreReturnPath(returnTo);
      setStartFailed(true);
    }
  };

  return (
    <main className={styles.split}>
      <section className={styles.left}>
        <div className={styles.column}>
          <div className={styles.brand}>
            <Image src="/brand/pokeclip-symbol.svg" alt="" width={36} height={36} priority />
            <span className={styles.wordmark}>PokeClip</span>
            <span className={styles.beta}>BETA</span>
          </div>
          <div className={styles.intro}>
            <h1 className={styles.headline}>클립 제작, 바로 시작하세요</h1>
            <p className={styles.subtitle}>별도 가입 없이 Google 계정 하나로 로그인됩니다.</p>
          </div>
          <button type="button" className={styles.googleButton} onClick={handleGoogleLogin}>
            <GoogleGIcon />
            Google로 시작하기
          </button>
          {startFailed ? (
            <p role="alert" className={styles.startError}>
              지금은 로그인을 시작할 수 없어요. 잠시 후 다시 시도해 주세요.
            </p>
          ) : null}
          <p className={styles.terms}>
            계속 진행하면 이용약관 및 개인정보 처리방침에 동의하게 됩니다.
          </p>
        </div>
      </section>
      <aside className={styles.hero} aria-hidden>
        <Image
          src="/brand/login-hero.webp"
          alt=""
          fill
          sizes="40vw"
          className={styles.heroImage}
          priority
        />
      </aside>
    </main>
  );
}
