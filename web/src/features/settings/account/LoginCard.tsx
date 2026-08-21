import { Badge } from '@/ui';
import { GoogleGIcon } from '@/features/auth/GoogleGIcon';
import styles from './AccountSettingsScreen.module.css';

// 디자인 1p 「로그인」 카드. 읽기 전용이다 — 로그인 수단을 바꾸는 자리가 아니다.
//
// Me에 provider 필드가 없어 「Google로 로그인」을 문자열로 둔다. 구글이 유일한
// 로그인 경로라(User.googleSub가 nullable=false) 사실과 어긋나지 않는다. 다른 수단이
// 생기면 그때 me가 provider를 실어 오고 이 줄이 그것을 따른다. ADR-012 참조.
export function LoginCard({ email }: { email: string }) {
  return (
    <section className={styles.card} aria-labelledby="account-login-title">
      <h2 id="account-login-title" className={styles.cardTitle}>
        로그인
      </h2>
      <div className={styles.loginRow}>
        <span className={styles.googleTile}>
          {/* 크기는 .googleTile svg가 셸 배율로 정한다 — size prop은 덮어써진다 */}
          <GoogleGIcon />
        </span>
        <div className={styles.loginBody}>
          <div className={styles.loginName}>Google로 로그인</div>
          <div className={styles.loginEmail}>{email}</div>
        </div>
        <Badge tone="success" variant="soft" size="sm">
          연결됨
        </Badge>
      </div>
    </section>
  );
}
