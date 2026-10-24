import { useId } from 'react';
import { Switch } from '@/ui';
import styles from './AccountSettingsScreen.module.css';

// 설정 › 계정 「이용 분석」 카드 — 처리방침 7·9절이 적은 거부 수단 「이용 분석 참여」. 기본은 켬(동의 창 없음, POK-354).
// 끄면 이 계정으로 로그인한 동안 분석 도구(PostHog)로 보내지 않는다. 브라우저 추적 금지는 따로 그대로 지킨다.
export function AnalyticsCard({
  participating,
  saving,
  onChange,
}: {
  /** me를 아직 못 읽었으면 undefined — 잠근다 */
  participating: boolean | undefined;
  saving: boolean;
  onChange: (next: boolean) => void;
}) {
  const descId = useId();
  return (
    <section className={styles.card} aria-labelledby="account-analytics-title">
      <h2 id="account-analytics-title" className={styles.cardTitle}>
        이용 분석
      </h2>
      <div className={styles.loginRow}>
        <div className={styles.loginBody}>
          <div className={styles.loginName}>이용 분석 참여</div>
          <div id={descId} className={styles.loginEmail}>
            서비스를 고치는 데 쓰는 방문·사용 통계예요. 끄면 이 계정으로 로그인한 동안 보내지
            않아요.
          </div>
        </div>
        {/* 이름은 aria-label로 직접 준다 — 위 글을 aria-labelledby로만 가리키면 axe가 label-title-only로 잡는다 */}
        <Switch
          size="sm"
          aria-label="이용 분석 참여"
          aria-describedby={descId}
          checked={participating ?? true}
          disabled={participating === undefined || saving}
          onChange={(e) => onChange(e.target.checked)}
        />
      </div>
    </section>
  );
}
