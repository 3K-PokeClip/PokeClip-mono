import { useId } from 'react';
import type { AnalyticsChoice } from '@/lib/analytics';
import { Switch } from '@/ui';
import styles from './AccountSettingsScreen.module.css';

// 설정 › 계정 「이용 분석」 카드 — 처리방침 7·9절이 적은 거부 수단 「이용 분석 참여」. 기본은 켬(동의 창 없음, POK-354).
// 선택은 이 브라우저에만 둔다 — 끄면 이 브라우저에서는 분석 도구(PostHog)로 보내지 않고, 다른 기기는 따로 끈다.

const DESCRIPTIONS: Record<AnalyticsChoice, string> = {
  participating:
    '서비스를 고치는 데 쓰는 방문·사용 통계예요. 끄면 이 브라우저에서는 보내지 않아요.',
  opted_out: '이 브라우저에서는 보내지 않아요. 다른 기기는 그 기기에서 따로 꺼 주세요.',
  dnt: '브라우저의 추적 금지 설정이 켜져 있어 보내지 않아요.',
  unavailable: '지금 이 브라우저는 이용 분석을 보내지 않아요.',
};

export function AnalyticsCard({
  choice,
  onChange,
}: {
  /** 이 브라우저의 상태. 아직 모르면 undefined — 잠근다 */
  choice: AnalyticsChoice | undefined;
  onChange: (next: boolean) => void;
}) {
  const descId = useId();
  const switchable = choice === 'participating' || choice === 'opted_out';
  return (
    <section className={styles.card} aria-labelledby="account-analytics-title">
      <h2 id="account-analytics-title" className={styles.cardTitle}>
        이용 분석
      </h2>
      <div className={styles.loginRow}>
        <div className={styles.loginBody}>
          <div className={styles.loginName}>이용 분석 참여</div>
          <div id={descId} className={styles.loginEmail}>
            {DESCRIPTIONS[choice ?? 'participating']}
          </div>
        </div>
        {/* 이름은 aria-label로 직접 준다 — 위 글을 aria-labelledby로만 가리키면 axe가 label-title-only로 잡는다 */}
        <Switch
          size="sm"
          aria-label="이용 분석 참여"
          aria-describedby={descId}
          // 읽는 동안은 기본(켬)으로 보인다 — 추적 금지·SDK 없음은 보내지 않으니 꺼짐으로 보인다
          checked={choice === undefined || choice === 'participating'}
          disabled={!switchable}
          onChange={(e) => onChange(e.target.checked)}
        />
      </div>
    </section>
  );
}
