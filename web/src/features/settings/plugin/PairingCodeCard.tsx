import { Check } from 'lucide-react';
import { Button, Skeleton } from '@/ui';
import type { PairingCodeStatus } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 연동 코드 카드. 발급됨/미발급 두 상태를 갖는다.
// 코드 원문은 여기 없다 — 발급 직후 모달(IssuedCodeDialog)에서만 1회 노출된다 (ADR-019).
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
              {/* 디자인 개정: 발행일이 힌트 줄로 내려가고 보안 문구·하단 경고줄은 삭제 —
                  재발급 경고는 RotateConfirmDialog가 담당한다 (POK-102 완료조건 유지) */}
              <div className={styles.codeTitle}>코드가 발급되어 있어요</div>
              <div className={styles.codeHint}>발행일 {code.issuedAt}</div>
            </div>
            <Button variant="soft" size="sm" loading={busy} onClick={onReissue}>
              재발급
            </Button>
          </div>
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
