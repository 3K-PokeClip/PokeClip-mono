// @/ui 배럴은 쓰지 않는다 — 서버 컴포넌트인 그룹 레이아웃이 부르는데, 배럴이 createContext를 쓰는 DS 컴포넌트까지 끌고
// 오면 서버 컴포넌트 빌드가 깨진다(NotFoundScreen과 같은 이유). 서버에서 쓸 DS는 모듈 경로로 직접 집는다.
import { LoadingRegion, Skeleton } from '@/ui/components/Skeleton';
import styles from './ScreenSkeleton.module.css';

/** 행 수 — 화면 위쪽을 채울 만큼만 */
const ROWS = 4;

const u = (n: number) => `calc(${n} * var(--pc-u))`;

// 사이드바 화면(방송·클립·설정) 본문의 첫 로딩(POK-442). 새로고침 직후엔 채널(me·as-editor)이 서기 전이라 어느 화면도
// 목록을 못 부른다: 그동안 ChannelScope가 화면 대신 이것을 그린다. 사이드바는 채널 범위 밖이라 그대로 서 있다.
// 아직 어느 화면 데이터도 없어 화면별 모양을 알 수 없으므로 공통 뼈대만 둔다: 다른 화면 h1과 같은 자리의 제목 막대와
// 행 몇 줄. 개수 배지·필터처럼 데이터가 있어야 서는 자리는 그리지 않는다.
export function ScreenSkeleton({ padded = true }: { padded?: boolean }) {
  const region = (
    <LoadingRegion label="화면을 불러오는 중">
      {/* h1(21u · 줄 높이 1.2)의 줄 상자만큼 */}
      <Skeleton width={u(120)} height={u(25)} className={styles.title} />
      <div className={styles.rows}>
        {Array.from({ length: ROWS }, (_, i) => (
          <Skeleton key={i} height={u(64)} radius="md" />
        ))}
      </div>
    </LoadingRegion>
  );
  // 방송·클립은 본문 패딩과 <main>을 화면이 세운다: 화면 대신 서는 동안 둘 다 여기서 세워 h1 자리가 화면과 같게 한다.
  // 설정은 레이아웃의 <main>이 이미 패딩을 주므로 뼈대만 그린다(main 안에 main을 두지 않는다)
  return padded ? <main className={styles.padded}>{region}</main> : region;
}
