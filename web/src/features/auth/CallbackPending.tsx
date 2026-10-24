// @/ui 배럴은 쓰지 않는다 — 콜백 page(서버 컴포넌트)의 Suspense 대기 화면으로도 쓰여, 배럴이 createContext를 쓰는 DS
// 컴포넌트까지 끌고 오면 서버 컴포넌트 빌드가 깨진다(NotFoundScreen과 같은 이유). 서버에서 쓸 DS는 모듈 경로로 직접 집는다.
import { Spinner } from '@/ui/components/Spinner';
import styles from './CallbackPending.module.css';

// 로그인·채널 연동 콜백의 대기 화면(POK-442). 구글 로그인(OAuthCallbackScreen)과 치지직·SOOP·유튜브 연동 콜백이 같은
// 모양을 CSS째 복제해 쓰던 것을 모았다. 콜백 page의 Suspense 대기 화면도 이것이다: 콜백은 로그인 가드 밖이라 하이드레이션
// 전에 아무것도 없으면 진짜 빈 화면이 된다.
// 낭독은 문장 한 줄(status)만: 스피너는 문장 옆이라 aria-hidden이다(같은 말이 두 번 읽힌다).

/** 콜백마다의 대기 문장. 화면과 page의 Suspense 대기 화면이 같은 문장을 쓰게 한 자리에 둔다 */
export const CALLBACK_PENDING_LABEL = {
  login: '로그인하는 중…',
  chzzk: '치지직 채널을 연동하는 중…',
  soop: 'SOOP 방송국을 연동하는 중…',
  youtube: '유튜브 채널을 연동하는 중…',
} as const;

export function CallbackPending({ label }: { label: string }) {
  return (
    <main className={styles.screen}>
      <Spinner size="lg" aria-hidden />
      <p role="status" className={styles.working}>
        {label}
      </p>
    </main>
  );
}
