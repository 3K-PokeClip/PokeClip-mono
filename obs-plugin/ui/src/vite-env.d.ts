/// <reference types="vite/client" />

declare module '*.module.css' {
  const classes: Record<string, string>;
  export default classes;
}

declare module '*.svg?raw' {
  const content: string;
  export default content;
}

// 시험에서 Qt 폴백 로케일(data/locale/*.ini)을 읽어 독 문구와 대조한다.
declare module '*.ini?raw' {
  const content: string;
  export default content;
}
