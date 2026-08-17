import { Check } from 'lucide-react';
import { Button } from '@/ui';
import type { PairingCodeStatus } from './usePluginMockState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 연동 코드 카드. 발급됨/미발급 두 상태를 갖는다.
// 코드 원문은 발급 직후 1회만 노출되고 그 뒤로는 다시 뜨지 않는다 (ADR-019).
export function PairingCodeCard({
  code,
  onIssue,
}: {
  code: PairingCodeStatus;
  onIssue: () => void;
}) {
  return (
    <section className={styles.card} aria-labelledby="pairing-code-title">
      <h2 className={styles.cardTitle} id="pairing-code-title">
        연동 코드
      </h2>
      <p className={styles.codeCardDesc}>
        코드를 발급받아 OBS 플러그인 설정에 붙여넣으면 이 계정으로 연결돼요
      </p>

      {code.issued ? (
        <>
          <div className={styles.codeBox}>
            <Check size={16} strokeWidth={2} className={styles.codeCheck} aria-hidden />
            <div className={styles.codeBody}>
              {code.justIssuedCode ? (
                <>
                  <div className={styles.codeTitle}>
                    <span className={styles.codeValue}>{code.justIssuedCode}</span> · 발행일{' '}
                    {code.issuedAt}
                  </div>
                  <div className={styles.codeHint}>
                    이 코드는 지금만 보여요 — OBS 플러그인에 붙여넣은 뒤에는 다시 표시되지 않아요
                  </div>
                </>
              ) : (
                <>
                  <div className={styles.codeTitle}>
                    코드가 발급되어 있어요 · 발행일 {code.issuedAt}
                  </div>
                  <div className={styles.codeHint}>
                    보안을 위해 코드는 다시 표시되지 않아요 — 잃어버렸다면 재발급하세요
                  </div>
                </>
              )}
            </div>
            <Button variant="soft" size="sm" onClick={onIssue}>
              재발급
            </Button>
          </div>
          <div className={styles.metaText}>재발급하면 기존 코드는 즉시 무효화됩니다</div>
        </>
      ) : (
        <div className={styles.codeBoxEmpty}>
          <div className={styles.codeEmptyText}>
            아직 발급된 코드가 없어요. 코드를 발급하면 OBS 플러그인과 연결할 수 있습니다.
          </div>
          <Button variant="solid" size="sm" onClick={onIssue}>
            코드 발급
          </Button>
        </div>
      )}
    </section>
  );
}
