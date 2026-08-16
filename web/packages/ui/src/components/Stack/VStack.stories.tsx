import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';
import { VStack } from './Stack';

const meta: Meta<typeof VStack> = {
  title: 'Components/VStack',
  component: VStack,
  parameters: { layout: 'padded' },
  args: { gap: 2, align: 'stretch' },
};
export default meta;
type Story = StoryObj<typeof VStack>;

const Tile = ({ children }: { children?: ReactNode }) => (
  <div
    style={{
      display: 'grid',
      placeItems: 'center',
      minHeight: 48,
      padding: '8px 12px',
      background: 'var(--pc-color-accent-subtle)',
      color: 'var(--pc-color-accent-text)',
      borderRadius: 'var(--pc-radius-md)',
      fontSize: 13,
    }}
  >
    {children}
  </div>
);

export const Default: Story = {
  render: (args) => (
    <VStack {...args} style={{ width: 200 }}>
      <Tile>V1</Tile>
      <Tile>V2</Tile>
      <Tile>V3</Tile>
    </VStack>
  ),
};
