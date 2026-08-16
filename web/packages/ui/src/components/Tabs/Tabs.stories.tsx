import type { Meta, StoryObj } from '@storybook/react-vite';
import { Tabs } from './Tabs';
import { Text } from '../Text';

const meta: Meta<typeof Tabs> = {
  title: 'Components/Tabs',
  component: Tabs,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Tabs>;

export const Default: Story = {
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
