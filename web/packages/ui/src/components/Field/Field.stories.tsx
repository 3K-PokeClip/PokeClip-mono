import type { Meta, StoryObj } from '@storybook/react-vite';
import { Field } from './Field';
import { Input } from '../Input';
import { Textarea } from '../Textarea';
import { VStack } from '../Stack';

const meta: Meta<typeof Field> = {
  title: 'Components/Field',
  component: Field,
  parameters: { layout: 'padded' },
};
export default meta;
type Story = StoryObj<typeof Field>;

export const Default: Story = {
  render: () => (
    <VStack gap={5} style={{ maxWidth: 360 }}>
      <Field>
        <Field.Label>스트리머 이름</Field.Label>
        <Input placeholder="예: 마준" />
        <Field.Description>공개 프로필에 표시됩니다.</Field.Description>
      </Field>
      <Field required invalid>
        <Field.Label>이메일</Field.Label>
        <Input defaultValue="not-an-email" />
        <Field.Error>올바른 이메일 형식이 아닙니다.</Field.Error>
      </Field>
      <Field>
        <Field.Label>채널 소개</Field.Label>
        <Textarea placeholder="채널 소개를 입력하세요" />
      </Field>
    </VStack>
  ),
};
