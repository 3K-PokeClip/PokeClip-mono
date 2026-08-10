// Inlines @import statements into standalone CSS bundles for the published package.
// - dist/tokens.css : primitives + semantic tokens only (pure CSS variables, overridable)
// - dist/global.css : tokens + fonts + reset (convenience bundle for apps)
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const IMPORT_RE = /@import\s+(?:url\()?["']([^"')]+)["']\)?\s*;/g;

function inline(entryPath, seen = new Set()) {
  const abs = resolve(entryPath);
  if (seen.has(abs)) return '';
  seen.add(abs);
  const dir = dirname(abs);
  const css = readFileSync(abs, 'utf8');
  return css.replace(IMPORT_RE, (_m, spec) => inline(resolve(dir, spec), seen));
}

mkdirSync('dist', { recursive: true });

writeFileSync('dist/tokens.css', inline('src/styles/tokens.css'), 'utf8');

let globalCss = inline('src/styles/global.css');
const fontSrc = 'src/assets/fonts/PretendardVariable.woff2';
if (existsSync(fontSrc)) {
  mkdirSync('dist/fonts', { recursive: true });
  copyFileSync(fontSrc, 'dist/fonts/PretendardVariable.woff2');
}
globalCss = globalCss.replace(
  /url\((["']?)[^)]*PretendardVariable\.woff2\1\)/g,
  'url("./fonts/PretendardVariable.woff2")',
);
writeFileSync('dist/global.css', globalCss, 'utf8');

// dist/design-system.css : global (tokens + fonts + reset) + component styles,
// a single self-contained stylesheet. Lives in dist/ so the @font-face
// url("./fonts/…") resolves. Consumed by design-sync (cfg.cssEntry).
const componentCss = existsSync('dist/styles.css') ? readFileSync('dist/styles.css', 'utf8') : '';
writeFileSync(
  'dist/design-system.css',
  `${globalCss}\n/* component styles */\n${componentCss}`,
  'utf8',
);

console.log('[build:css] wrote dist/tokens.css, dist/global.css and dist/design-system.css');
