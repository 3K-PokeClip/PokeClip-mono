import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Overview',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

export const Introduction: Story = {
  render: () => (
    <Canvas>
      <div style={{ maxWidth: 720 }}>
        <h1 style={{ fontSize: 'var(--pc-font-size-3xl)', marginBottom: 16 }}>
          PokeClip 디자인 시스템
        </h1>
        <p
          style={{
            fontSize: 'var(--pc-font-size-lg)',
            lineHeight: 'var(--pc-line-relaxed)',
            color: 'var(--pc-color-text-secondary)',
            marginBottom: 24,
          }}
        >
          다크 우선(dark-first), 접근성을 갖춘 React 컴포넌트 라이브러리. 3계층 토큰
          아키텍처(primitive → semantic → component)와 CSS 변수 기반 테마 위에 구축됩니다.
        </p>
        <ul
          style={{
            lineHeight: 'var(--pc-line-relaxed)',
            color: 'var(--pc-color-text-primary)',
            paddingLeft: 20,
          }}
        >
          <li>
            <strong>Colors</strong> — 메인 인디고 · 포인트 마젠타 · 쿨톤 그레이 · semantic 토큰
          </li>
          <li>
            <strong>Typography</strong> — Pretendard, 15px 기준 타입 스케일
          </li>
          <li>
            <strong>Spacing / Radius / Elevation / Motion</strong> — 스케일 토큰
          </li>
        </ul>
        <p style={{ marginTop: 24, color: 'var(--pc-color-text-muted)', fontSize: 13 }}>
          상단 툴바의 Theme 토글로 Dark ↔ Light를 전환해 보세요.
        </p>
      </div>
    </Canvas>
  ),
};
