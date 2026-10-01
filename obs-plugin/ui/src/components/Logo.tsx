import symbolSvg from '@pc-brand/pokeclip-symbol.svg?raw';

// web/public/brand/pokeclip-symbol.svg 정본. 재생 마크가 흰색 고정(다크 배경 전용)이라
// 라이트 테마에서 사라지지 않게 테마 글자색을 따르게 바꾼다. 리본 색(브랜드)은 그대로 둔다.
const markup = symbolSvg
  .replace(/<svg([^>]*?)\s(width|height)="\d+"/g, '<svg$1')
  .replace(/<svg([^>]*?)\s(width|height)="\d+"/g, '<svg$1')
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace('fill="#FFFFFF"', 'style="fill:var(--pc-color-text-primary)"')
  .replace('<svg', '<svg width="22" height="22" aria-hidden="true" focusable="false"');

export function Logo({ className }: { className?: string }) {
  return <span class={className} dangerouslySetInnerHTML={{ __html: markup }} />;
}
