import { LoadingRegion, Skeleton } from '@/ui';
import styles from './VodListScreen.module.css';

/** 한 화면 위쪽을 채우는 수 — 두 열이면 두 줄이다(POK-441 시안에서 4줄로 골랐다) */
const ROWS = 4;

const u = (n: number) => `calc(${n} * var(--pc-u))`;

// 첫 읽기가 끝나기 전의 지난 방송 목록(POK-441 시안 A). 로드 뒤 행과 같은 클래스(.list·.row·.thumb·.rowText·.rowActions)를
// 그대로 써서, 데이터가 와도 행 높이·열 배치가 바뀌지 않는다. 헤더는 제목만 둔다: 개수 배지·기간 필터는 방송이 있을 때만
// 서므로 미리 그리면 방송이 0개인 계정에서 거짓이 된다.
export function VodListSkeleton() {
  return (
    <LoadingRegion label="지난 방송을 불러오는 중">
      <ul className={styles.list} aria-hidden="true">
        {Array.from({ length: ROWS }, (_, i) => (
          <li key={i} className={styles.row}>
            {/* Skeleton은 폭·높이·반경을 인라인으로 덮으므로 .thumb의 값(150u·16:9·9u)을 다시 준다 */}
            <Skeleton
              className={styles.thumb}
              width={u(150)}
              height="auto"
              style={{ borderRadius: u(9) }}
            />
            <div className={styles.rowText}>
              <Skeleton width="55%" height={u(14)} />
              <Skeleton width="35%" height={u(11)} style={{ marginTop: u(8) }} />
            </div>
            <div className={styles.rowActions}>
              <Skeleton width={u(54)} height={u(20)} radius="pill" />
              <Skeleton width={u(40)} height={u(20)} radius="pill" />
            </div>
          </li>
        ))}
      </ul>
    </LoadingRegion>
  );
}
