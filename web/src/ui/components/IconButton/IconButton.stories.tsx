import type { Meta, StoryObj } from '@storybook/react-vite';
import { IconButton } from './IconButton';
import { HStack } from '../Stack';

const Heart = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
    <path d="M8 14s-5-3.2-5-7a3 3 0 0 1 5-2 3 3 0 0 1 5 2c0 3.8-5 7-5 7z" />
  </svg>
);

const meta: Meta<typeof IconButton> = {
  title: 'Components/IconButton',
  component: IconButton,
  parameters: { layout: 'centered' },
  args: { 'aria-label': '좋아요', variant: 'soft', size: 'md' },
  argTypes: {
    variant: { control: 'select', options: ['solid', 'soft', 'outline', 'ghost', 'danger'] },
    size: { control: 'inline-radio', options: ['sm', 'md', 'lg'] },
  },
};
export default meta;
type Story = StoryObj<typeof IconButton>;

export const Playground: Story = {
  render: (args) => (
    <IconButton {...args}>
      <Heart />
    </IconButton>
  ),
};

export const Variants: Story = {
  render: () => (
    <HStack gap={2} align="center">
      <IconButton aria-label="좋아요" variant="soft">
        <Heart />
      </IconButton>
      <IconButton aria-label="좋아요" variant="solid">
        <Heart />
      </IconButton>
      <IconButton aria-label="좋아요" variant="ghost" size="lg">
        <Heart />
      </IconButton>
    </HStack>
  ),
};
