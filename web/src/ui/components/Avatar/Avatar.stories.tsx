import type { Meta, StoryObj } from '@storybook/react-vite';
import { Avatar } from './Avatar';
import { HStack } from '../Stack';

const meta: Meta<typeof Avatar> = {
  title: 'Components/Avatar',
  component: Avatar,
  parameters: { layout: 'centered' },
  args: { name: '김민준', size: 'lg' },
  argTypes: { size: { control: 'select', options: ['xs', 'sm', 'md', 'lg', 'xl'] } },
};
export default meta;
type Story = StoryObj<typeof Avatar>;

export const Playground: Story = {};

export const Sizes: Story = {
  render: () => (
    <HStack gap={3} align="center">
      {(['xs', 'sm', 'md', 'lg', 'xl'] as const).map((s) => (
        <Avatar key={s} name="Poke Clip" size={s} />
      ))}
    </HStack>
  ),
};
