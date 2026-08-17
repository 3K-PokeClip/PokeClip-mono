import styles from './SettingsPageHeader.module.css';

// 설정 하위 화면 공통 헤더 (디자인 1k~1q 상단 패턴)
export function SettingsPageHeader({ title, description }: { title: string; description?: string }) {
  return (
    <header>
      <h1 className={styles.title}>{title}</h1>
      {description && <p className={styles.description}>{description}</p>}
    </header>
  );
}
