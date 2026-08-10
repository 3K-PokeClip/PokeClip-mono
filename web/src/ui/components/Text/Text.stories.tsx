import type { Meta, StoryObj } from '@storybook/react-vite';
import { Text } from './Text';
import { VStack } from '../Stack';

const meta: Meta<typeof Text> = {
  title: 'Components/Text',
  component: Text,
  parameters: { layout: 'padded' },
  args: { children: '스트리밍 클립 제목', size: 'md', tone: 'primary' },
  argTypes: {
    size: { control: 'select', options: ['xs', 'sm', 'md', 'lg', 'xl', '2xl', '3xl'] },
    weight: { control: 'select', options: ['normal', 'medium', 'semibold', 'bold'] },
    tone: {
      control: 'select',
      options: ['primary', 'secondary', 'muted', 'accent', 'danger', 'success'],
    },
  },
};
export default meta;
type Story = StoryObj<typeof Text>;

export const Playground: Story = {};

export const Tones: Story = {
  render: () => (
    <VStack gap={2} align="start">
      <Text as="h2" size="2xl" weight="bold">
        스트리밍 클립 제목
      </Text>
      <Text tone="secondary">보조 설명 텍스트 (secondary)</Text>
      <Text tone="muted" size="sm">
        메타 정보 · 3시간 전 (muted)
      </Text>
      <Text tone="accent" weight="semibold">
        액센트 강조 텍스트
      </Text>
    </VStack>
  ),
};
