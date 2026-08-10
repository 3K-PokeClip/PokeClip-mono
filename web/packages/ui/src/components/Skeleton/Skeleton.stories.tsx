import type { Meta, StoryObj } from '@storybook/react-vite';
import { Skeleton } from './Skeleton';
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
