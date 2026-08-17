'use client';

import { useEffect } from 'react';
import Image from 'next/image';
import { useRouter } from 'next/navigation';
import { consumeReturnPath } from '@/components/app-shell/AuthGuard';
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

  // 역가드 — 이미 세션이 있으면 로그인 화면에 머물 이유가 없다.
  useEffect(() => {
    if (hydrated && refreshToken !== null) router.replace('/home');
  }, [hydrated, refreshToken, router]);

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
          <button
            type="button"
            className={styles.googleButton}
            onClick={() => startGoogleLogin(consumeReturnPath() ?? undefined)}
          >
            <GoogleGIcon />
            Google로 시작하기
          </button>
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
