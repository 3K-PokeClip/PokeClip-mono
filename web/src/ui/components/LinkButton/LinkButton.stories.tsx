import type { Meta, StoryObj } from '@storybook/react-vite';
import { LinkButton } from './LinkButton';
import { HStack } from '../Stack';

const ArrowRight = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
    <path d="M8.5 3.5 13 8l-4.5 4.5-1-1L10 9H3V7h7L7.5 4.5z" />
  </svg>
);

const meta: Meta<typeof LinkButton> = {
  title: 'Components/LinkButton',
  component: LinkButton,
  args: { children: '홈으로 가기', href: '#', variant: 'solid', size: 'md' },
  argTypes: {
    variant: { control: 'select', options: ['solid', 'soft', 'outline', 'ghost', 'danger'] },
    size: { control: 'inline-radio', options: ['sm', 'md', 'lg'] },
    fullWidth: { control: 'boolean' },
    as: { table: { disable: true } },
  },
};
export default meta;
type Story = StoryObj<typeof LinkButton>;

export const Playground: Story = {};

export const Variants: Story = {
  render: (args) => (
    <HStack gap={2} wrap>
      {(['solid', 'soft', 'outline', 'ghost', 'danger'] as const).map((v) => (
        <LinkButton key={v} {...args} variant={v}>
          {v}
        </LinkButton>
      ))}
    </HStack>
  ),
};

export const Sizes: Story = {
  render: (args) => (
    <HStack gap={2} align="center" wrap>
      {(['sm', 'md', 'lg'] as const).map((s) => (
        <LinkButton key={s} {...args} size={s}>
          {s}
        </LinkButton>
      ))}
    </HStack>
  ),
};

export const WithIcon: Story = {
  args: { iconEnd: <ArrowRight /> },
};
