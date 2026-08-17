import type { Meta, StoryObj } from '@storybook/react-vite';
import { Popover } from './Popover';
import { Button } from '../Button';
import { VStack } from '../Stack';
import { Text } from '../Text';

const meta: Meta<typeof Popover> = {
  title: 'Components/Popover',
  component: Popover,
  parameters: { layout: 'centered' },
};
export default meta;
type Story = StoryObj<typeof Popover>;

export const Default: Story = {
  render: () => (
    <Popover>
      <Popover.Trigger>
        <Button variant="outline">필터 ▾</Button>
      </Popover.Trigger>
      <Popover.Content>
        <VStack gap={2} align="start">
          <Text weight="semibold">정렬 기준</Text>
          <Text tone="secondary" size="sm">
            인기순 · 최신순 · 시청자순
          </Text>
          <Button size="sm" variant="soft">
            적용
          </Button>
        </VStack>
      </Popover.Content>
    </Popover>
  ),
};
