import type { Meta, StoryObj } from '@storybook/react-vite';
import { HStack, VStack } from './Stack';
import { Text } from './Text';
import { Badge } from './Badge';
import { Avatar, AvatarGroup } from './Avatar';
import { Card } from './Card';
import { Spinner } from './Spinner';
import { Skeleton } from './Skeleton';

const meta: Meta = { title: 'Components/Data Display', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

export const Typography: Story = {
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

export const Badges: Story = {
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

export const Avatars: Story = {
  render: () => (
    <HStack gap={4} align="center">
      <Avatar name="김민준" size="lg" />
      <Avatar name="Poke Clip" size="lg" />
      <AvatarGroup max={3} size="md">
        <Avatar name="가" />
        <Avatar name="나" />
        <Avatar name="다" />
        <Avatar name="라" />
        <Avatar name="마" />
      </AvatarGroup>
    </HStack>
  ),
};

export const Cards: Story = {
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

export const Loading: Story = {
  render: () => (
    <HStack gap={6} align="center">
      <HStack gap={3} align="center">
        <Spinner size="sm" />
        <Spinner size="md" />
        <Spinner size="lg" />
      </HStack>
      <VStack gap={2} style={{ width: 200 }}>
        <Skeleton height={14} width="70%" />
        <Skeleton height={14} />
        <Skeleton height={14} width="90%" />
      </VStack>
    </HStack>
  ),
};
