import { Check } from 'lucide-react';
import { Button, Skeleton } from '@/ui';
import type { PairingCodeStatus } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 연동 코드 카드. 발급됨/미발급 두 상태를 갖는다.
// 코드 원문은 발급 직후 1회만 노출되고 그 뒤로는 다시 뜨지 않는다 (ADR-019).
// 발급(1콜)과 재발급(rotate 경유 — 확인 모달 필수)은 흐름이 달라 핸들러를 나눈다.
export function PairingCodeCard({
  code,
  loading,
  error,
  busy,
  onIssue,
  onReissue,
  onRetry,
}: {
  code: PairingCodeStatus;
  /** 상태 조회 전 — 발급/재발급 어느 쪽도 아직 모른다. */
  loading?: boolean;
  /** 상태를 한 번도 못 읽음 — 미발급으로 오인시키면 안 된다 (리뷰 #73). */
  error?: boolean;
  /** 발급·재발급 진행 중 — 이중 클릭 방지. */
  busy?: boolean;
  onIssue: () => void;
  onReissue: () => void;
  onRetry: () => void;
}) {
  return (
    <section className={styles.card} aria-labelledby="pairing-code-title">
      <h2 className={styles.cardTitle} id="pairing-code-title">
        연동 코드
      </h2>
      <p className={styles.codeCardDesc}>
        코드를 발급받아 OBS 플러그인 설정에 붙여넣으면 이 계정으로 연결돼요
      </p>

      {loading ? (
        <Skeleton className={styles.codeSkeleton} />
      ) : error ? (
        <div className={styles.codeBoxEmpty}>
          <div className={styles.codeEmptyText}>
            연동 코드 상태를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
          </div>
          <Button variant="soft" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
      ) : code.issued ? (
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
            <Button variant="soft" size="sm" loading={busy} onClick={onReissue}>
              재발급
            </Button>
          </div>
          {/* "기존 코드도 무효화"라고 말하면 거짓 보장이다 — 백엔드 rotate는 미사용
              페어링 코드를 죽이지 않는다(교환은 현재 키를 준다). 키 만료만 약속한다. (리뷰 #73) */}
          <div className={styles.metaText}>재발급하면 기존 스트림 키가 즉시 만료됩니다</div>
        </>
      ) : (
        <div className={styles.codeBoxEmpty}>
          <div className={styles.codeEmptyText}>
            아직 발급된 코드가 없어요. 코드를 발급하면 OBS 플러그인과 연결할 수 있습니다.
          </div>
          <Button variant="solid" size="sm" loading={busy} onClick={onIssue}>
            코드 발급
          </Button>
        </div>
      )}
    </section>
  );
}
