'use client';

import { useState } from 'react';
import { Download, ExternalLink, TriangleAlert } from 'lucide-react';
import { LinkButton, Tabs, VisuallyHidden } from '@/ui';
import { track } from '@/lib/analytics';
import type { DetectedOs } from '@/lib/detectOs';
import {
  PLUGIN_DOWNLOADS,
  PLUGIN_VERSION,
  RELEASES_PAGE_URL,
  type PluginOs,
} from '@/lib/pluginDownload';
import styles from './PluginSettingsScreen.module.css';

// OS별 설치 파일 받기 (POK-379) — 다운로드 창(모달)의 본문. 같은 조각을 다른 배치(카드·공개 페이지)에도 꽂는다.
// 주소는 고정 상수(lib/pluginDownload)다 — 웹은 Release를 조회하지 않는다.
// 파일 링크는 target 없이 그대로 연다: GitHub가 Content-Disposition: attachment로 주므로 페이지가 바뀌지 않는다
// (2026-10-21 실측). 새 창은 「모든 버전 보기」 같은 페이지 링크에만 쓴다.
// 「설치됨·연결됨」은 그리지 않는다 — 받았는지·깔았는지 웹은 모른다(POK-377).

const OS_LABEL: Record<PluginOs, string> = { windows: 'Windows', macos: 'macOS' };
const HINT: Record<PluginOs, string> = {
  windows: '설치 파일(.exe) · 64비트 · OBS 32 이상',
  macos: '설치 파일(.pkg) · Apple Silicon·Intel 공용',
};

export function PluginDownloadPanel({ detectedOs }: { detectedOs: DetectedOs }) {
  // 감지는 첫 탭만 정한다 — 사용자가 고른 탭을 늦게 온 감지 결과가 덮지 않는다. 모르면 Windows
  const [chosen, setChosen] = useState<PluginOs | null>(null);
  const os: PluginOs = chosen ?? (detectedOs === 'macos' ? 'macos' : 'windows');

  return (
    <div className={styles.downloadPanel}>
      <Tabs value={os} onValueChange={(value) => setChosen(value as PluginOs)}>
        <Tabs.List aria-label="설치할 PC의 운영체제" className={styles.downloadTabs}>
          <Tabs.Trigger value="windows">Windows</Tabs.Trigger>
          <Tabs.Trigger value="macos">macOS</Tabs.Trigger>
        </Tabs.List>
        <Tabs.Panel value="windows">
          <OsPanel os="windows" />
        </Tabs.Panel>
        <Tabs.Panel value="macos">
          <OsPanel os="macos" />
        </Tabs.Panel>
      </Tabs>
      <p className={styles.downloadMeta}>
        <span>버전 {PLUGIN_VERSION}</span>
        <ExternalPageLink href={RELEASES_PAGE_URL}>모든 버전 보기</ExternalPageLink>
      </p>
    </div>
  );
}

function OsPanel({ os }: { os: PluginOs }) {
  const { kind, url } = PLUGIN_DOWNLOADS[os];

  return (
    <div className={styles.downloadOsPanel}>
      <LinkButton
        href={url}
        variant="solid"
        size="md"
        fullWidth
        iconStart={<Download size={16} aria-hidden />}
        onClick={() => track('plugin_download_clicked', { os, kind })}
      >
        다운로드
        <VisuallyHidden>
          {' '}
          — {OS_LABEL[os]} 설치 파일, 버전 {PLUGIN_VERSION}
        </VisuallyHidden>
      </LinkButton>
      <p className={styles.downloadHint}>{HINT[os]}</p>

      {/* flex 자식은 아이콘·span 둘뿐 — 텍스트 노드를 직접 두면 줄이 갈라진다(.issueWarning 규칙) */}
      <div className={styles.issueWarning}>
        <TriangleAlert aria-hidden className={styles.issueWarningIcon} />
        {os === 'windows' ? (
          <span>
            아직 서명이 없어 받을 때·열 때 <b>경고가 뜰 수 있어요</b>. Chrome이 막으면 다운로드
            목록에서 「위험한 파일 다운로드」, Windows가 막으면 「추가 정보 › 실행」을 눌러 주세요.
          </span>
        ) : (
          <span>
            아직 Apple 서명이 없어 처음 열 때 <b>「확인되지 않은 개발자」</b>로 막힐 수 있어요.
            시스템 설정 › 개인정보 보호 및 보안에서 「그래도 열기」를 눌러 주세요.
          </span>
        )}
      </div>

      <ol className={styles.downloadSteps}>
        <li>OBS를 끕니다</li>
        <li>{os === 'windows' ? '받은 파일을 실행해 설치합니다' : '받은 pkg를 열어 설치합니다'}</li>
        <li>OBS를 열고 「독 › PokeClip」에 이 화면의 연동 코드를 넣습니다</li>
      </ol>
    </div>
  );
}

function ExternalPageLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <ExternalLink size={11} aria-hidden className={styles.downloadLinkIcon} />
      <VisuallyHidden> (새 창)</VisuallyHidden>
    </a>
  );
}
