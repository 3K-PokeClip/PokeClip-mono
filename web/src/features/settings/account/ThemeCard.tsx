import { ModeSegment, useTheme, type ModeOption, type Theme } from '@/ui';
import { track } from '@/lib/analytics';
import styles from './AccountSettingsScreen.module.css';

// 설정 › 계정 「화면 테마」. 고르는 즉시 바뀌고(저장 버튼 없음) 이 브라우저(localStorage pc-theme)에만
// 남는다 — 서버에 사용자 설정 자리가 없어 기기마다 따로다. 편집자도 계정 화면에 오므로 누구나 쓴다.
// 기본은 다크(다크 퍼스트). 「시스템」은 기기 설정을 따라간다.

const OPTIONS: readonly ModeOption<Theme>[] = [
  { key: 'system', label: '시스템' },
  { key: 'light', label: '라이트' },
  { key: 'dark', label: '다크' },
];

export function ThemeCard() {
  const { theme, setTheme } = useTheme();
  return (
    <section className={styles.card} aria-labelledby="account-theme-title">
      <h2 id="account-theme-title" className={styles.cardTitle}>
        화면 테마
      </h2>
      <ModeSegment
        className={styles.themeSegment}
        options={OPTIONS}
        value={theme}
        onChange={(next) => {
          if (next === theme) return;
          setTheme(next);
          track('theme_changed', { theme: next });
        }}
        labelledBy="account-theme-title"
        describedBy="account-theme-desc"
      />
      <p id="account-theme-desc" className={styles.themeNote}>
        이 브라우저에만 저장돼요. 시스템을 고르면 기기 설정을 따라가요.
      </p>
    </section>
  );
}
