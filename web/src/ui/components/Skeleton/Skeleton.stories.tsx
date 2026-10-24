import type { Meta, StoryObj } from '@storybook/react-vite';
import { Skeleton } from './Skeleton';
import { LoadingRegion } from './LoadingRegion';
import { VStack } from '../Stack';

const meta: Meta<typeof Skeleton> = {
  title: 'Components/Skeleton',
  component: Skeleton,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Skeleton>;

export const Lines: Story = {
  render: () => (
    <VStack gap={2} style={{ width: 240 }}>
      <Skeleton height={14} width="70%" />
      <Skeleton height={14} />
      <Skeleton height={14} width="90%" />
    </VStack>
  ),
};

export const Circle: Story = {
  render: () => <Skeleton circle height={48} />,
};

/** 스켈레톤 묶음을 LoadingRegion으로 감싸 낭독기에 status 한 줄만 들린다 */
export const InLoadingRegion: Story = {
  render: () => (
    <LoadingRegion label="목록을 불러오는 중" style={{ width: 320 }}>
      <VStack gap={2}>
        <Skeleton height={56} radius="lg" />
        <Skeleton height={56} radius="lg" />
        <Skeleton height={56} radius="lg" />
      </VStack>
    </LoadingRegion>
  ),
};
