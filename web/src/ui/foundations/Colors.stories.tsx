import type { Meta, StoryObj } from '@storybook/react-vite';
import { Canvas, Grid, Section, Swatch } from './preview-parts';

const meta: Meta = {
  title: 'Foundations/Colors',
  parameters: { layout: 'fullscreen' },
};
export default meta;
type Story = StoryObj;

const ramp = (prefix: string, steps: Array<number | string>) =>
  steps.map((s) => <Swatch key={s} name={`${prefix}-${s}`} value={`var(--pc-${prefix}-${s})`} />);

export const Palette: Story = {
  render: () => (
    <Canvas>
      <Section title="Primary — 메인 컬러 (인디고)">
        <Grid>{ramp('primary', [50, 100, 200, 300, 400, 500, 600, 700, 800, 900])}</Grid>
      </Section>
      <Section title="Point — 포인트 컬러 (마젠타)">
        <Grid>{ramp('point', [50, 100, 200, 300, 400, 500, 600, 700, 800, 900])}</Grid>
      </Section>
      <Section title="Gray — 뉴트럴 (쿨톤)">
        <Grid>{ramp('gray', [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950, 1000])}</Grid>
      </Section>
      <Section title="Accents — 액센트 / 상태 색">
        <Grid>
          <Swatch name="cyan-500" value="var(--pc-cyan-500)" />
          <Swatch name="teal-500" value="var(--pc-teal-500)" />
          <Swatch name="pink-500" value="var(--pc-pink-500)" />
          <Swatch name="pink-400" value="var(--pc-pink-400)" />
          <Swatch name="purple-500" value="var(--pc-purple-500)" />
          <Swatch name="yellow-500" value="var(--pc-yellow-500)" />
          <Swatch name="green-400" value="var(--pc-green-400)" />
          <Swatch name="green-600" value="var(--pc-green-600)" />
          <Swatch name="red-500" value="var(--pc-red-500)" />
          <Swatch name="red-400" value="var(--pc-red-400)" />
          <Swatch name="brown-500" value="var(--pc-brown-500)" />
        </Grid>
      </Section>
    </Canvas>
  ),
};

export const SemanticTokens: Story = {
  render: () => (
    <Canvas>
      <Section title="Background — 배경/표면">
        <Grid>
          <Swatch name="bg-canvas" value="var(--pc-color-bg-canvas)" />
          <Swatch name="bg-surface" value="var(--pc-color-bg-surface)" />
          <Swatch name="bg-surface-raised" value="var(--pc-color-bg-surface-raised)" />
          <Swatch name="bg-inset" value="var(--pc-color-bg-inset)" />
        </Grid>
      </Section>
      <Section title="Text — 텍스트 (surface 위)">
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
            padding: 20,
            background: 'var(--pc-color-bg-surface)',
            borderRadius: 'var(--pc-radius-lg)',
            border: '1px solid var(--pc-color-border-subtle)',
          }}
        >
          <span style={{ color: 'var(--pc-color-text-primary)' }}>
            text-primary — 기본 본문 텍스트
          </span>
          <span style={{ color: 'var(--pc-color-text-secondary)' }}>
            text-secondary — 보조 텍스트
          </span>
          <span style={{ color: 'var(--pc-color-text-muted)' }}>text-muted — 흐린 메타 텍스트</span>
        </div>
      </Section>
      <Section title="Accent / Status — 역할 색">
        <Grid>
          <Swatch name="accent" value="var(--pc-color-accent)" />
          <Swatch name="accent-hover" value="var(--pc-color-accent-hover)" />
          <Swatch name="accent-subtle" value="var(--pc-color-accent-subtle)" />
          <Swatch name="point" value="var(--pc-color-point)" />
          <Swatch name="point-subtle" value="var(--pc-color-point-subtle)" />
          <Swatch name="danger" value="var(--pc-color-danger)" />
          <Swatch name="success" value="var(--pc-color-success)" />
          <Swatch name="warning" value="var(--pc-color-warning)" />
          <Swatch name="info" value="var(--pc-color-info)" />
        </Grid>
      </Section>
      <p style={{ marginTop: 8, color: 'var(--pc-color-text-muted)', fontSize: 13 }}>
        상단 툴바의 Theme 토글로 Dark ↔ Light 전환 시 semantic 토큰이 리테마됩니다.
      </p>
    </Canvas>
  ),
};
