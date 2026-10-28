import type { MouseEvent } from 'react';
import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import { hasInAppHistory } from '@/lib/inAppHistory';
import styles from './editorShared.module.css';

// 편집기 헤더의 「이전 화면으로」(POK-491) — 들어온 화면(라이브·지난 방송·보관함)으로 돌아간다. 이 탭에 앱 안 앞 화면이
// 없으면(새 탭·주소 직접·새로고침) 링크 그대로 보관함으로 간다. 보관함은 /clips가 아니라 /clips/library를 바로 가리킨다
// — /clips는 리다이렉트라 한 홉을 더 탄다(POK-235).
//
// 뒤로 가기는 window.history.back()이다: Next의 router.back()이 하는 일이 바로 이것이라(app-router-instance) 라우터
// 문맥 없이 그리는 곳(첫 읽기 뼈대·목업 편집기)에서도 같은 길이다.

function onBack(event: MouseEvent<HTMLAnchorElement>) {
  // 가운데·보조키 클릭은 새 탭·새 창으로 보관함을 여는 링크 그대로 둔다
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)
    return;
  if (!hasInAppHistory()) return;
  event.preventDefault();
  window.history.back();
}

export function EditorBackLink() {
  return (
    <Link
      href="/clips/library"
      className={styles.backLink}
      aria-label="이전 화면으로"
      onClick={onBack}
    >
      <ChevronLeft size={17} aria-hidden />
    </Link>
  );
}
