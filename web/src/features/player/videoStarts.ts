import type Hls from 'hls.js';
import type { BufferAppendingData, Events, InitPTSFoundData, LevelUpdatedData } from 'hls.js';
import { readTrackStart, readVideoTrack, type VideoTrackInfo } from '../../lib/fmp4Boxes';
import type { StartOf } from './fragmentTiming';

// 조각마다 영상 첫 표본의 재생 위치(v_k)와 구성 시차(c_k)를 hls.js 사건에서 적는다(설계 r7 3.3 · 4.1절).
// 판 2 조각의 영상 축 환산(datedFragments)이 이 기록을 읽는다. 붙이는 재생기는 배선(useHlsPlayback)이 정한다.
//
// 사건 셋은 hls.js 1.7.3 공개 사건이고 주 스레드에서 동기로 난다.
// - BUFFER_APPENDING(parent=main): 붙이는 바이트 그대로다(passthrough 는 tfdt 를 고치지 않는다). init 붙임은
//   frag 가 MAP 조각(sn 'initSegment')이고, DISCONTINUITY 에서 MAP 을 다시 받지 않아도 init 을 다시 붙인다
//   — MAP 조각의 cc 는 MAP 줄의 cc 라 뒤 조각의 cc 와 다를 수 있어 cc 가 아니라 마지막 init 을 쓴다.
//   조각 붙임이 늘 그 조각의 init 붙임 뒤에 오기 때문이다(stream-controller _bufferInitSegment).
// - INIT_PTS_FOUND(id=main): 미디어 조각 cc 별 initPTS. 먼저 나온 트랙(소리일 수 있다)의 눈금이다.
// - LEVEL_UPDATED: 목록에서 빠진 순번(startSN 앞)의 기록을 버린다.
// FRAG_LOADED 는 쓰지 않는다(바이트가 비어 있을 수 있음 — 설계 r6 4.2).

export interface VideoStarts {
  startOf: StartOf;
  /** 처리기를 떼고 기록을 비운다 — 재생기 해체와 함께 부른다 */
  dispose(): void;
}

/** 붙인 조각에서 읽은 영상 트랙 시작(눈금) */
interface TrackRecord {
  baseDecodeTime: number;
  firstCtoTicks: number;
  timescale: number;
}

/** INIT_PTS_FOUND 의 initPTS */
interface InitPts {
  initPTS: number;
  timescale: number;
}

const MAIN = 'main';
const INIT_SEGMENT = 'initSegment';

/**
 * hls 에 처리기를 달아 조각마다 영상 시작을 적는다. LL 파트는 첫 파트(index 0)의 moof 만 그 조각의 시작으로
 * 적는다. 같은 조각을 다시 붙였는데 읽지 못하면 앞 기록을 버린다 — 지금 버퍼에 든 바이트를 모르기 때문이다.
 * events 는 hls.js 의 Events 값이다 — hls.js 를 동적 import 하는 호출부가 넘긴다
 */
export function trackVideoStarts(hls: Hls, events: typeof Events): VideoStarts {
  const records = new Map<number, TrackRecord>();
  const initPtsByCc = new Map<number, InitPts>();
  let videoTrack: VideoTrackInfo | null = null;

  const onAppending = (_event: string, data: BufferAppendingData) => {
    if (data.parent !== MAIN) {
      return;
    }
    const { sn } = data.frag;
    if (sn === INIT_SEGMENT) {
      videoTrack = readVideoTrack(data.data);
      return;
    }
    if (data.part && data.part.index !== 0) {
      return;
    }
    const start = videoTrack && readTrackStart(data.data, videoTrack.trackId);
    if (!videoTrack || !start) {
      records.delete(sn);
      return;
    }
    records.set(sn, { ...start, timescale: videoTrack.timescale });
  };

  const onInitPts = (_event: string, data: InitPTSFoundData) => {
    if (data.id !== MAIN) {
      return;
    }
    initPtsByCc.set(data.frag.cc, { initPTS: data.initPTS, timescale: data.timescale });
  };

  const onLevelUpdated = (_event: string, data: LevelUpdatedData) => {
    const { startSN } = data.details;
    for (const sn of records.keys()) {
      if (sn < startSN) {
        records.delete(sn);
      }
    }
  };

  hls.on(events.BUFFER_APPENDING, onAppending);
  hls.on(events.INIT_PTS_FOUND, onInitPts);
  hls.on(events.LEVEL_UPDATED, onLevelUpdated);

  const startOf: StartOf = (frag) => {
    const record = typeof frag.sn === 'number' ? records.get(frag.sn) : undefined;
    const initPts = initPtsByCc.get(frag.cc);
    if (!record || !initPts) {
      return null;
    }
    return {
      videoStartS: record.baseDecodeTime / record.timescale - initPts.initPTS / initPts.timescale,
      firstCtoS: record.firstCtoTicks / record.timescale,
    };
  };

  const dispose = () => {
    hls.off(events.BUFFER_APPENDING, onAppending);
    hls.off(events.INIT_PTS_FOUND, onInitPts);
    hls.off(events.LEVEL_UPDATED, onLevelUpdated);
    records.clear();
    initPtsByCc.clear();
    videoTrack = null;
  };

  return { startOf, dispose };
}
