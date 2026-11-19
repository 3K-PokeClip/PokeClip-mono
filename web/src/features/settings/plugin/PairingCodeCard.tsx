import { Check } from 'lucide-react';
import { Button, Skeleton } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 연동 코드 카드 — 플러그인을 연결한 회원과, 상태를 아직 모르는 동안(읽는 중·못 읽음)의 자리다.
// 연결한 적 없는 회원은 이 카드 대신 연결 순서 카드(ConnectStepsCard)를 본다(POK-609): 예전의 「미발급 · 코드 발급」
// 「발급됨 · 재발급」 상태는 설치 전에 코드부터 받게 해서 걷었다.
// 코드 원문은 여기 없다 — 발급 직후 모달(IssuedCodeDialog)에서만 1회 노출된다 (ADR-019).
// 재발급은 새 코드만 찍는다(rotate 없음) — 방송 키는 그대로라 이미 붙은 OBS는 끊기지 않는다.
export function PairingCodeCard({
  pairedAt,
  loading,
  error,
  retrying,
  busy,
  onIssue,
  onRetry,
}: {
  /** 플러그인에 코드를 넣은 마지막 날(표시용) — 읽는 중·못 읽음이면 없다. */
  pairedAt?: string;
  /** 상태 조회 전 — 연결했는지 아직 모른다. */
  loading?: boolean;
  /** 상태를 한 번도 못 읽음 — 「연결 전」으로 오인시키면 안 된다 (리뷰 #73). */
  error?: boolean;
  /** 「다시 시도」로 다시 읽는 중 — 오류 카드를 둔 채 그 버튼만 돈다(POK-446). */
  retrying?: boolean;
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
          {/* 다시 읽는 동안 이 버튼만 돈다(POK-446) — 발급 버튼과 같이 라벨은 자리만 지켜 폭이 튀지 않는다(.busyButton) */}
          <Button
            variant="soft"
            size="sm"
            loading={retrying}
            aria-label={retrying ? '다시 불러오는 중' : undefined}
            className={styles.busyButton}
            onClick={onRetry}
          >
            다시 시도
          </Button>
        </div>
      ) : (
        /* 서버가 코드가 쓰인 시각을 준다(POK-608) — 그때만 「연결」을 말한다. 지금도 붙어 있는지는 웹이 모르므로
           「연결됨」 배지가 아니라 「연결했어요 + 날짜」로 적는다. 버튼은 다른 PC를 더 붙이거나 다시 붙일 때 쓴다 */
        <div className={styles.codeBox}>
          <Check size={16} strokeWidth={2} className={styles.codeCheck} aria-hidden />
          <div className={styles.codeBody}>
            <div className={styles.codeTitle}>OBS 플러그인을 연결했어요</div>
            {pairedAt ? <div className={styles.codeHint}>마지막 연결 {pairedAt}</div> : null}
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
      )}
    </section>
  );
}
