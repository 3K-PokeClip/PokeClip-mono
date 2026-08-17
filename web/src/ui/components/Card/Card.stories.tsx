import type { Meta, StoryObj } from '@storybook/react-vite';
import { Card } from './Card';
import { Text } from '../Text';
import { HStack } from '../Stack';

const meta: Meta<typeof Card> = {
  title: 'Components/Card',
  component: Card,
  parameters: { layout: 'padded' },
  args: { variant: 'surface' },
  argTypes: {
    variant: { control: 'inline-radio', options: ['surface', 'outline', 'ghost'] },
    interactive: { control: 'boolean' },
  },
};
export default meta;
type Story = StoryObj<typeof Card>;

export const Playground: Story = {
  render: (args) => (
    <Card {...args} style={{ width: 240 }}>
      <Text weight="semibold">카드 제목</Text>
      <Text tone="muted" size="sm">
        토큰 기반 표면 컴포넌트
      </Text>
    </Card>
  ),
};

export const Variants: Story = {
  render: () => (
    <HStack gap={3} wrap>
      <Card variant="surface" style={{ width: 220 }}>
        <Text weight="semibold">Surface</Text>
        <Text tone="muted" size="sm">
          기본 카드 표면
        </Text>
      </Card>
      <Card variant="outline" interactive style={{ width: 220 }}>
        <Text weight="semibold">Outline · interactive</Text>
        <Text tone="muted" size="sm">
          hover 해보세요
        </Text>
      </Card>
    </HStack>
  ),
};
