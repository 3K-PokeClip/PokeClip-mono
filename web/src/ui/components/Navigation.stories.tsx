import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { Tabs } from './Tabs';
import { RadioGroup } from './RadioGroup';
import { Breadcrumb } from './Breadcrumb';
import { Pagination } from './Pagination';
import { Progress } from './Progress';
import { VStack } from './Stack';
import { Text } from './Text';

const meta: Meta = { title: 'Components/Navigation', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

export const TabsExample: Story = {
  name: 'Tabs',
  render: () => (
    <Tabs defaultValue="live" style={{ maxWidth: 480 }}>
      <Tabs.List aria-label="콘텐츠 종류">
        <Tabs.Trigger value="live">라이브</Tabs.Trigger>
        <Tabs.Trigger value="clip">클립</Tabs.Trigger>
        <Tabs.Trigger value="vod">영상</Tabs.Trigger>
        <Tabs.Trigger value="soon" disabled>
          예정
        </Tabs.Trigger>
      </Tabs.List>
      <Tabs.Panel value="live">
        <Text tone="secondary">지금 라이브 중인 채널 목록입니다.</Text>
      </Tabs.Panel>
      <Tabs.Panel value="clip">
        <Text tone="secondary">인기 클립 모음입니다.</Text>
      </Tabs.Panel>
      <Tabs.Panel value="vod">
        <Text tone="secondary">다시보기 영상입니다.</Text>
      </Tabs.Panel>
    </Tabs>
  ),
};

export const Radios: Story = {
  name: 'RadioGroup',
  render: function Radios() {
    const [value, setValue] = useState('follow');
    return (
      <RadioGroup value={value} onValueChange={setValue} aria-label="구독 유형">
        <RadioGroup.Item value="follow" label="팔로우 (무료)" />
        <RadioGroup.Item value="tier1" label="구독 티어 1" />
        <RadioGroup.Item value="tier2" label="구독 티어 2" />
        <RadioGroup.Item value="tier3" label="구독 티어 3 (준비 중)" disabled />
      </RadioGroup>
    );
  },
};

export const Crumbs: Story = {
  name: 'Breadcrumb',
  render: () => (
    <Breadcrumb>
      <Breadcrumb.Item href="#">홈</Breadcrumb.Item>
      <Breadcrumb.Item href="#">클립</Breadcrumb.Item>
      <Breadcrumb.Item href="#">버추얼</Breadcrumb.Item>
      <Breadcrumb.Item current>여기는 비키니 시티</Breadcrumb.Item>
    </Breadcrumb>
  ),
};

export const Pages: Story = {
  name: 'Pagination',
  render: function Pages() {
    const [page, setPage] = useState(4);
    return <Pagination page={page} count={12} onPageChange={setPage} />;
  },
};

export const Progresses: Story = {
  name: 'Progress',
  render: () => (
    <VStack gap={4} style={{ width: 320 }}>
      <Progress value={30} label="업로드 30%" />
      <Progress value={72} label="업로드 72%" />
      <Progress value={null} label="처리 중" />
    </VStack>
  ),
};
