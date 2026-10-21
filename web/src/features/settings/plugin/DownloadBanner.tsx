import { Download, ExternalLink } from 'lucide-react';
import { Button, LinkButton, Skeleton, VisuallyHidden } from '@/ui';
import { RELEASES_PAGE_URL } from '@/lib/pluginRelease/types';
import type { PluginReleaseViewState } from './usePluginReleaseState';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 하단 배너 (POK-379) — 최신 설치 파일 정보의 네 상태를 그린다.
// 받을 게 없으면 눌리지 않는 버튼을 두지 않고 문장만 둔다(LinkButton 규칙: 갈 곳이 없으면 텍스트).
// 시안 문구의 「연동 가이드」는 갈 곳이 없어 뺐다 — 설치 안내는 다운로드 창의 링크가 맡는다.
const TEXT: Record<PluginReleaseViewState['kind'], string> = {
  loading: '다른 PC에 설치하나요? 설치 파일을 받아보세요.',
  ready: '다른 PC에 설치하나요? 설치 파일을 받아보세요.',
  unavailable: '아직 받을 수 있는 설치 파일이 없어요.',
  error: '설치 파일 정보를 불러오지 못했어요.',
};

export function DownloadBanner({
  state,
  onOpen,
}: {
  state: PluginReleaseViewState;
  onOpen: () => void;
}) {
  return (
    <div className={styles.downloadBanner}>
      <Download size={16} aria-hidden className={styles.downloadIcon} />
      <span className={styles.downloadText}>{TEXT[state.kind]}</span>
      {state.kind === 'loading' ? (
        // 버튼 자리만 지킨다 — 눌리지 않는 버튼이 잠깐 보였다 바뀌는 깜빡임을 피한다
        <Skeleton width="calc(112 * var(--pc-u))" height="calc(30 * var(--pc-u))" radius="md" />
      ) : null}
      {state.kind === 'ready' ? (
        <Button variant="soft" size="sm" aria-haspopup="dialog" onClick={onOpen}>
          플러그인 다운로드
        </Button>
      ) : null}
      {state.kind === 'error' ? (
        <div className={styles.downloadActions}>
          {/* 연동 코드 카드도 실패하면 「다시 시도」가 둘이 된다 — 접근 이름으로 가른다 */}
          <Button variant="ghost" size="sm" onClick={state.retry}>
            다시 시도<VisuallyHidden> (설치 파일 정보)</VisuallyHidden>
          </Button>
          <LinkButton
            href={RELEASES_PAGE_URL}
            target="_blank"
            rel="noopener noreferrer"
            variant="soft"
            size="sm"
            iconEnd={<ExternalLink size={12} aria-hidden />}
          >
            GitHub에서 받기
            <VisuallyHidden> (새 창)</VisuallyHidden>
          </LinkButton>
        </div>
      ) : null}
    </div>
  );
}
