#!/usr/bin/env node
// 파형 피크 산출 — stdin의 raw s16le 모노 PCM을 고정 길이 구간(bin)의 최대 진폭으로 줄인다.
// 편집기 타임라인의 오디오 레인이 이 값을 그린다 (POK-238).
//
// 사용: ffmpeg ... -f s16le - | node peaks.mjs --rate 8000 --bin-ms 100 --out peaks_0.json
//
// 왜 스트리밍인가: 10분 8kHz 모노도 9.6MB다. 지금은 통째로 담아도 되지만, 방송 한 벌(1시간)로
// 늘리면 60MB가 된다. 청크 단위로 접어 두면 길이에 상관없이 상주 메모리가 출력 배열뿐이다.
//
// 왜 절대 스케일(32768)인가: 전역 최대로 정규화하면 ① 최대를 알아야 해서 1패스로 못 끝나고
// ② 조용한 구간만 있는 소스를 부풀리며 ③ 나중에 트랙이 여럿(ADR-017 trackId 1~5)이 됐을 때
// 트랙 간 상대 음량이 깨진다. 데이터는 객관값으로 두고, 보기 좋게 키우는 것은 그리는 쪽이 정한다
// (그래서 maxPeak를 함께 낸다).

import { writeFileSync } from 'node:fs';

const SAMPLE_BYTES = 2; // s16le
const FULL_SCALE = 32768; // |s16| 최댓값 +1 — 1.0을 넘지 않게 한다

function die(message) {
  process.stderr.write(`peaks.mjs: ${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const args = { rate: 8000, binMs: 100, out: null };
  for (let i = 0; i < argv.length; i += 1) {
    const value = argv[i + 1];
    if (argv[i] === '--rate') {
      args.rate = Number(value);
      i += 1;
    } else if (argv[i] === '--bin-ms') {
      args.binMs = Number(value);
      i += 1;
    } else if (argv[i] === '--out') {
      args.out = value ?? null;
      i += 1;
    }
  }
  return args;
}

const { rate, binMs, out } = parseArgs(process.argv.slice(2));

if (!Number.isFinite(rate) || rate <= 0) die('--rate 는 양수여야 합니다');
if (!Number.isFinite(binMs) || binMs <= 0) die('--bin-ms 는 양수여야 합니다');
if (out === null) die('--out 이 필요합니다');

/** 한 bin에 들어가는 샘플 수 — 8000Hz · 100ms면 800 */
const samplesPerBin = Math.max(1, Math.round((rate * binMs) / 1000));

const peaks = [];
let binMax = 0; // 현재 bin에서 본 최대 |샘플|
let binCount = 0; // 현재 bin에 넣은 샘플 수
let overallMax = 0;

/**
 * 청크 경계에 홀로 남은 바이트. 16비트 샘플이 두 청크에 걸쳐 오면 이것을 이어 붙여야 한다 —
 * 안 그러면 그 뒤 전체가 한 바이트씩 밀려 파형이 잡음이 된다.
 */
let carry = null;

function closeBin() {
  const value = Math.round((binMax / FULL_SCALE) * 1000) / 1000;
  peaks.push(value);
  if (value > overallMax) overallMax = value;
  binMax = 0;
  binCount = 0;
}

function pushSample(abs) {
  if (abs > binMax) binMax = abs;
  binCount += 1;
  if (binCount === samplesPerBin) closeBin();
}

function consume(buffer) {
  let offset = 0;
  if (carry !== null) {
    // 앞 청크의 마지막 바이트 + 이 청크의 첫 바이트 = 샘플 하나
    pushSample(Math.abs(Buffer.from([carry, buffer[0]]).readInt16LE(0)));
    carry = null;
    offset = 1;
  }
  const usable = buffer.length - offset;
  if (usable <= 0) return;
  const whole = usable - (usable % SAMPLE_BYTES);
  for (let i = offset; i < offset + whole; i += SAMPLE_BYTES) {
    pushSample(Math.abs(buffer.readInt16LE(i)));
  }
  if (usable % SAMPLE_BYTES === 1) carry = buffer[buffer.length - 1];
}

process.stdin.on('data', (chunk) => {
  if (chunk.length > 0) consume(chunk);
});

process.stdin.on('error', (error) => die(`stdin 읽기 실패 — ${error.message}`));

process.stdin.on('end', () => {
  // 마지막 bin이 덜 찼어도 버리지 않는다 — 버리면 파형이 소스보다 최대 한 bin만큼 짧아져
  // 타임라인 오른쪽 끝이 비어 보인다
  if (binCount > 0) closeBin();
  if (peaks.length === 0) die('입력이 비어 있습니다 — ffmpeg 오디오 매핑을 확인하세요');

  writeFileSync(
    out,
    JSON.stringify({
      binMs,
      sampleRate: rate,
      count: peaks.length,
      /** 표시 정규화를 그리는 쪽이 정할 수 있게 함께 낸다 (값 자체는 절대 스케일) */
      maxPeak: overallMax,
      peaks,
    }),
  );
  process.stderr.write(
    `peaks.mjs: bin ${peaks.length}개 (${((peaks.length * binMs) / 1000).toFixed(2)}초), 최대 ${overallMax}\n`,
  );
});
