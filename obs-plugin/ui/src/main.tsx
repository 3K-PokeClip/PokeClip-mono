import { render } from 'preact';
import { App } from './app';
import { createHttpBridge, readToken } from './lib/bridge';
import './styles.css';

async function boot() {
  const token = readToken();
  const root = document.getElementById('app')!;

  if (token) {
    render(<App bridge={createHttpBridge(token)} />, root);
    return;
  }
  if (import.meta.env.DEV) {
    // 토큰 없이 개발 서버로 열면 목 브리지 (?mock=unpaired|idle|live|reconnecting|error|no_key|encoder|audio_overflow|audio_manual)
    const { createMockBridge } = await import('./lib/bridge-mock');
    const scenario = new URLSearchParams(location.search).get('mock') ?? 'unpaired';
    render(<App bridge={createMockBridge(scenario)} />, root);
    return;
  }
  root.textContent = 'PokeClip 독은 OBS 안에서만 열립니다.';
}

void boot();
