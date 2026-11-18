import { Download } from 'lucide-react';
import { Button } from '@/ui';
import styles from './PluginSettingsScreen.module.css';

// 코드를 한 번도 받지 않은 회원에게만 맨 위에 두는 설치 안내(2026-11-18 시안 C).
// 새 회원 둘이 플러그인 없이 코드부터 받고 떠났다 — 코드 카드가 맨 위, 다운로드가 맨 아래였고
// 배너 문구(「다른 PC에 설치하나요?」)가 이미 설치한 사람용이라 처음 온 사람에게 자기 일로 안 읽혔다.
// 눌렀을 때 여는 창은 맨 아래 배너와 같다(PluginDownloadDialog).
export function InstallGuideCard({ onOpen }: { onOpen: () => void }) {
  return (
    <section className={styles.installGuide} aria-labelledby="install-guide-title">
      <div className={styles.installGuideBody}>
        <h2 className={styles.installGuideTitle} id="install-guide-title">
          먼저 OBS 플러그인을 설치하세요
        </h2>
        <p className={styles.installGuideText}>설치한 뒤 아래에서 연동 코드를 받으면 돼요</p>
      </div>
      <Button
        variant="solid"
        size="sm"
        iconStart={<Download size={14} aria-hidden />}
        aria-haspopup="dialog"
        onClick={onOpen}
      >
        플러그인 다운로드
      </Button>
    </section>
  );
}
