import Image from 'next/image';
import Link from 'next/link';
// @/ui 배럴은 쓰지 않는다 — 배럴이 createContext를 쓰는 DS 컴포넌트까지 끌고 와
// 서버 컴포넌트 빌드가 깨진다. 서버에서 쓸 DS는 모듈 경로로 직접 집는다.
import { LinkButton } from '@/ui/components/LinkButton';
import styles from './NotFoundScreen.module.css';

// 시안 1t — 없는 주소와 만료·삭제된 자원이 함께 떨어지는 화면 (POK-204).
// 문구를 가르지 않는다: 「없음」과 「만료」를 다르게 답하기로 정해진 바 없다 (ADR-045).
// 훅도 상태도 없는 서버 컴포넌트다 — 화면을 저절로 옮길 JS 자체가 없으므로
// 「자동 리다이렉트 없음」이 구조로 보장된다.
export function NotFoundScreen() {
  return (
    <main className={styles.screen}>
      <div className={styles.wash} aria-hidden />
      <div className={styles.content}>
        <div className={styles.art}>
          {/* 숫자는 장식이다 — 의미는 아래 제목이 전달한다 */}
          <span className={styles.numeral} aria-hidden>
            404
          </span>
          <Image
            src="/brand/poki-404.webp"
            alt="클립을 잃어버린 포키 캐릭터"
            width={500}
            height={333}
            priority
            className={styles.poki}
          />
        </div>
        <h1 className={styles.title}>원하시는 페이지를 찾을 수 없어요</h1>
        <p className={styles.description}>
          주소가 바뀌었거나 삭제된 페이지예요.
          <br />
          홈으로 돌아가면 새 하이라이트가 기다리고 있어요.
        </p>
        {/* 복귀 동선은 하나다 — 로그인 여부로 가르지 않는다 */}
        <div className={styles.action}>
          <LinkButton as={Link} href="/home" variant="solid" size="md">
            홈으로 가기
          </LinkButton>
        </div>
      </div>
    </main>
  );
}
