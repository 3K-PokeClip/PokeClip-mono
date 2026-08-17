import type { Meta, StoryObj } from '@storybook/react-vite';
import { Badge } from './Badge';
import { HStack, VStack } from '../Stack';

const meta: Meta<typeof Badge> = {
  title: 'Components/Badge',
  component: Badge,
  parameters: { layout: 'centered' },
  args: { children: 'LIVE', tone: 'accent', variant: 'soft' },
  argTypes: {
    tone: {
      control: 'select',
      options: ['neutral', 'accent', 'point', 'success', 'warning', 'danger', 'info'],
    },
    variant: { control: 'inline-radio', options: ['solid', 'soft', 'outline'] },
    size: { control: 'inline-radio', options: ['sm', 'md'] },
  },
};
export default meta;
type Story = StoryObj<typeof Badge>;

export const Playground: Story = {};

export const Tones: Story = {
  render: () => (
    <VStack gap={3} align="start">
      <HStack gap={2} wrap>
        {(['neutral', 'accent', 'point', 'success', 'warning', 'danger', 'info'] as const).map(
          (t) => (
            <Badge key={t} tone={t} variant="soft">
              {t}
            </Badge>
          ),
        )}
      </HStack>
      <HStack gap={2} wrap>
        {(['solid', 'soft', 'outline'] as const).map((v) => (
          <Badge key={v} tone="accent" variant={v}>
            LIVE
          </Badge>
        ))}
      </HStack>
    </VStack>
  ),
};
