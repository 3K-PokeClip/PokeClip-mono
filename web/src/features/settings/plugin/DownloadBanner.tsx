import { Download } from 'lucide-react';
import { Button } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 디자인 1m 하단 배너 (POK-379) — 「플러그인 다운로드」가 OS별 설치 파일 창을 연다.
// 주소가 고정 상수라 불러오는 중·없음·오류 상태가 없다(lib/pluginDownload).
// 시안 문구의 「연동 가이드」는 갈 곳이 없어 뺐다 — 설치 순서는 다운로드 창이 보여 준다.
export function DownloadBanner({ onOpen }: { onOpen: () => void }) {
  return (
    <div className={styles.downloadBanner}>
      <Download size={16} aria-hidden className={styles.downloadIcon} />
      <span className={styles.downloadText}>다른 PC에 설치하나요? 설치 파일을 받아보세요.</span>
      <Button variant="soft" size="sm" aria-haspopup="dialog" onClick={onOpen}>
        플러그인 다운로드
      </Button>
    </div>
  );
}
