import Image from 'next/image';
import Link from 'next/link';
import { GoogleGIcon } from './GoogleGIcon';
import styles from './LoginScreen.module.css';

// 디자인 1r — Google 계정 전용 로그인. 실제 OAuth 연결은 POK-101에서.
export function LoginScreen() {
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
          {/* 목업 로그인 — POK-101에서 OAuth 리다이렉트로 교체 */}
          <Link href="/home" className={styles.googleButton}>
            <GoogleGIcon />
            Google로 시작하기
          </Link>
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
