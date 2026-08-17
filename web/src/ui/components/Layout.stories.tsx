import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';
import { Box } from './Box';
import { HStack, VStack } from './Stack';
import { Grid } from './Grid';
import { Container } from './Container';
import { Divider } from './Divider';
import { AspectRatio } from './AspectRatio';

const meta: Meta = { title: 'Components/Layout', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

const Tile = ({ children }: { children?: ReactNode }) => (
  <div
    style={{
      display: 'grid',
      placeItems: 'center',
      minWidth: 64,
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

export const Stacks: Story = {
  render: () => (
    <VStack gap={6}>
      <HStack gap={3}>
        <Tile>H1</Tile>
        <Tile>H2</Tile>
        <Tile>H3</Tile>
      </HStack>
      <Divider />
      <VStack gap={2} align="stretch">
        <Tile>V1</Tile>
        <Tile>V2</Tile>
      </VStack>
    </VStack>
  ),
};

export const Grids: Story = {
  render: () => (
    <Grid columns={4} gap={3}>
      {Array.from({ length: 8 }, (_, i) => (
        <Tile key={i}>{i + 1}</Tile>
      ))}
    </Grid>
  ),
};

export const Containers: Story = {
  render: () => (
    <Container size="sm">
      <Box as="p" style={{ color: 'var(--pc-color-text-secondary)' }}>
        Container(size="sm")로 중앙 정렬 + 최대 너비 제한.
      </Box>
    </Container>
  ),
};

export const Ratio: Story = {
  render: () => (
    <div style={{ width: 320 }}>
      <AspectRatio ratio={16 / 9}>
        <div
          style={{
            width: '100%',
            height: '100%',
            background: 'var(--pc-color-bg-surface-raised)',
            display: 'grid',
            placeItems: 'center',
            color: 'var(--pc-color-text-muted)',
          }}
        >
          16 : 9
        </div>
      </AspectRatio>
    </div>
  ),
};
