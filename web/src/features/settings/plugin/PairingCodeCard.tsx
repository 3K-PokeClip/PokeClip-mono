import { Check } from 'lucide-react';
import { Button, Skeleton } from '@/ui';
import type { PairingCodeStatus } from './useStreamKeyState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 연동 코드 카드. 발급됨/미발급 두 상태를 갖는다.
// 코드 원문은 여기 없다 — 발급 직후 모달(IssuedCodeDialog)에서만 1회 노출된다 (ADR-019).
// 발급과 재발급은 같은 동작이다(rotate 없음 — 새 코드만 발급) — 라벨만 상태에 맞게 다르다.
export function PairingCodeCard({
  code,
  loading,
  error,
  busy,
  onIssue,
  onRetry,
}: {
  code: PairingCodeStatus;
  /** 상태 조회 전 — 발급/재발급 어느 쪽도 아직 모른다. */
  loading?: boolean;
  /** 상태를 한 번도 못 읽음 — 미발급으로 오인시키면 안 된다 (리뷰 #73). */
  error?: boolean;
  /** 발급 진행 중 — 이중 클릭 방지. */
  busy?: boolean;
  onIssue: () => void;
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
        /* 상태 조회 전 자리 — 코드 박스와 같은 높이(64u)라 로드 후 레이아웃이 튀지 않는다.
           Skeleton은 height·radius를 항상 인라인 style로 적용해 클래스 값은 덮이므로
           (기본 16px로 렌더됐다) props/style로 넘겨야 실제로 먹는다 (리뷰 #73) */
        <Skeleton
          height="calc(64 * var(--pc-u))"
          style={{ borderRadius: 'calc(10 * var(--pc-u))' }}
        />
      ) : error ? (
        <div className={styles.codeBoxEmpty}>
          <div className={styles.codeEmptyText}>연동 코드 상태를 불러오지 못했어요.</div>
          <Button variant="soft" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        </div>
      ) : code.issued ? (
        <>
          <div className={styles.codeBox}>
            <Check size={16} strokeWidth={2} className={styles.codeCheck} aria-hidden />
            <div className={styles.codeBody}>
              {/* 디자인 개정: 날짜가 힌트 줄로 내려가고 보안 문구·하단 경고줄은 삭제 —
                  재발급이 키를 건드리지 않게 되면서(rotate 미사용) 경고할 것도 없어졌다.
                  라벨은 "최초 발급일" — 서버가 주는 시각이 키 생성일(=첫 코드 발급일)뿐이라,
                  "발행일"이라 쓰면 재발급 후 과거 날짜가 거짓말이 된다. (리뷰 #74) */}
              <div className={styles.codeTitle}>코드가 발급되어 있어요</div>
              <div className={styles.codeHint}>최초 발급일 {code.issuedAt}</div>
            </div>
            {/* 디자인 ②-1: 요청 중엔 인라인 스피너만 — 라벨은 자리만 지켜 폭이 튀지 않게
                시각만 숨긴다(.busyButton). 접근 이름은 aria-label이 대신한다 */}
            <Button
              variant="soft"
              size="sm"
              loading={busy}
              aria-label={busy ? '발급 요청 중' : undefined}
              className={styles.busyButton}
              onClick={onIssue}
            >
              재발급
            </Button>
          </div>
        </>
      ) : (
        <div className={styles.codeBoxEmpty}>
          <div className={styles.codeEmptyText}>
            아직 발급된 코드가 없어요. 코드를 발급하면 OBS 플러그인과 연결할 수 있습니다.
          </div>
          {/* 첫 발급도 같은 요청 상태다 — ②-1과 동일하게 스피너만 보이되 폭은 유지 */}
          <Button
            variant="solid"
            size="sm"
            loading={busy}
            aria-label={busy ? '발급 요청 중' : undefined}
            className={styles.busyButton}
            onClick={onIssue}
          >
            코드 발급
          </Button>
        </div>
      )}
    </section>
  );
}
