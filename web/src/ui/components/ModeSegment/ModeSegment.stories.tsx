import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { ModeSegment, type ModeOption } from './ModeSegment';
import { VStack } from '../Stack';

const meta: Meta<typeof ModeSegment> = {
  title: 'Components/ModeSegment',
  component: ModeSegment,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof ModeSegment>;

type Notify = 'off' | 'on' | 'important';
const NOTIFY: readonly ModeOption<Notify>[] = [
  { key: 'off', label: '끔' },
  { key: 'on', label: '받기' },
  { key: 'important', label: '중요', accent: true },
];

type ThemeChoice = 'system' | 'light' | 'dark';
const THEMES: readonly ModeOption<ThemeChoice>[] = [
  { key: 'system', label: '시스템' },
  { key: 'light', label: '라이트' },
  { key: 'dark', label: '다크' },
];

/** 알림 설정 줄 — 「중요」만 골라졌을 때 브랜드 색 글자 */
export const Notification: Story = {
  render: function Notification() {
    const [value, setValue] = useState<Notify>('on');
    return (
      <VStack gap={2} align="start">
        <span id="story-notify">하이라이트 감지</span>
        <ModeSegment options={NOTIFY} value={value} onChange={setValue} labelledBy="story-notify" />
      </VStack>
    );
  },
};

/** 설정 › 계정 「화면 테마」 */
export const Theme: Story = {
  render: function Theme() {
    const [value, setValue] = useState<ThemeChoice>('system');
    return (
      <VStack gap={2} align="start">
        <span id="story-theme">화면 테마</span>
        <ModeSegment options={THEMES} value={value} onChange={setValue} labelledBy="story-theme" />
      </VStack>
    );
  },
};
