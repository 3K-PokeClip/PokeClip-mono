'use client';

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type Hls from 'hls.js';
import {
  behindFromCurrentTime,
  dvrRange,
  liveEdgePosition,
  mediaTimeAt,
  programDateAt,
  rewindWindowSeconds,
} from './dvrWindow';
import { HLS_DVR_CONFIG } from './hlsConfig';
import { behindFromSeekFraction, isAtEdge } from './playerMath';
import { useControlsAutoHide } from './useControlsAutoHide';
import {
  CLIP_MARK_MS,
  PLAYER_QUALITIES,
  type PlayerQuality,
  type PlayerSimulation,
  type PlayerSimulationOptions,
} from './usePlayerSimulation';

// 실재생 훅 (POK-31) — usePlayerSimulation과 같은 PlayerSimulation 계약을 반환한다.
// hls.js 인스턴스·<video> 바인딩은 전부 여기 있고, UI(GlassPlayer 이하)는 어느 훅인지 모른다.
// jsdom엔 미디어 구현이 없어 이 훅만 따로 시험하지 않는다: src 없는 시험은 시뮬레이션 경로를 탄다. 라이브 화면 시험이
// hls.js를 흉내 내 재생기의 자리(livePosition)·실제 시각으로 옮기기(seekToDate)·사람이 옮김(onSeek)을 잰다(POK-326).

/** 재생 위치에 영상 자료가 있다(HTMLMediaElement.HAVE_CURRENT_DATA) */
const HAVE_CURRENT_DATA = 2;

/**
 * 라이브 재생기의 자리(POK-326). 시각은 조각마다 그 조각의 시각 표시(PDT)로 센 실제 시각(epoch ms)이다. 같은 실제 초 안에서
 * 시차도 같으면 재생기는 그 초에 처음 잰 값을 둔다
 */
export interface LivePosition {
  /** 재생 위치의 실제 시각 */
  atMs: number;
  /** 실시간 끝(재생기가 서는 라이브 끝)의 실제 시각. 라이브 채팅이 표에 다 들어온 자리를 이것으로 센다 */
  edgeAtMs: number;
  /** 실시간 끝에서 몇 초 뒤인가(정수, 재생기 시차 표기와 같은 값) */
  behindSeconds: number;
}

/**
 * @param onSeek 사람이 옮겼다(시크바·키·실시간으로·카드). 재생으로 흐를 때는 부르지 않는다: 라이브 채팅이 맨 아래(되감은 자리,
 *   실시간이면 최신)로 간다(POK-326)
 */
export function useHlsPlayback(
  videoRef: RefObject<HTMLVideoElement | null>,
  src: string,
  options: PlayerSimulationOptions = {},
  onSeek?: () => void,
): PlayerSimulation & { livePosition: LivePosition | null } {
  // playing은 video 이벤트로만 바꾼다 — 자동재생이 거부되면 false로 남아 재생 버튼이 노출된다
  const [playing, setPlaying] = useState(false);
  // 자동재생 정책상 muted로 시작한다 (시뮬레이션의 muted=false와 다른 점)
  const [muted, setMuted] = useState(true);
  const [volume, setVolumeState] = useState(70);
  const [behindSeconds, setBehind] = useState(0);
  // 매니페스트 로드 전엔 되감을 곳이 없다 — 0이면 시크바가 엣지에 붙는다 (POK-32)
  const [windowSeconds, setWindowSeconds] = useState(0);
  const [uptimeSeconds, setUptime] = useState(options.initialUptimeSeconds ?? 0);
  // 재생기의 자리(재생 위치·실시간 끝의 실제 시각, 시차). 라이브 채팅이 되감은 영상의 시각을 따른다(POK-326). 목록에 시각
  // 표시가 없거나 아직 모르면 null. 셋을 한 순간에 같이 잰다(paint): 시크는 시차를 바로 바꾸는데 시각은 다음 paint에 바뀌어,
  // 따로 알리면 새 시차에 옛 시각이 붙어 실시간 끝을 시크 폭만큼 늦게 셈했다(채팅이 아직 표에 없는 구간까지 「다 읽음」으로 뒀다)
  const [livePosition, setLivePosition] = useState<LivePosition | null>(null);
  const [quality, setQuality] = useState<PlayerQuality>(PLAYER_QUALITIES[0]);
  const [lowLatency, setLowLatency] = useState(true);
  const [clipMarked, setClipMarked] = useState(false);
  const { controlsVisible, wake, sleep } = useControlsAutoHide(playing);

  const hlsRef = useRef<Hls | null>(null);
  const clipTimer = useRef<number>(undefined);

  // 방송 경과는 스트림 메타데이터에 없다 — 시뮬레이션처럼 시드+티커로 표시만 유지한다.
  // 방송 상태 API가 생기면 그 티켓에서 교체하며, 그때 아래 src 전환 초기화에 uptime도
  // 넣어야 한다 — 지금은 시드가 스트림과 무관한 단일 상수라 안 넣어도 티가 안 난다.
  useEffect(() => {
    const tick = window.setInterval(() => setUptime((s) => s + 1), 1000);
    return () => window.clearInterval(tick);
  }, []);

  useEffect(() => () => window.clearTimeout(clipTimer.current), []);

  // 초기 볼륨은 마운트 1회만 — src 전환(?stream= 변경) 이펙트에 두면 사용자가 만진
  // 볼륨이 스트림을 바꿀 때마다 70으로 되돌아간다.
  useEffect(() => {
    const video = videoRef.current;
    if (video) video.volume = 0.7;
  }, [videoRef]);

  // 유일한 시크 경로 (계약3 4절 2번) — 마우스·키보드·LIVE 복귀가 전부 이 함수를 탄다.
  // 라이브 재동기화를 호출하지 않는다.
  const seekToBehind = useCallback(
    (behind: number) => {
      const video = videoRef.current;
      if (!video) return;
      const range = dvrRange(video.seekable);
      if (!range) return;
      // 되감기 기준점은 엣지와 같은 지점이어야 한다 — range.end 기준으로 시크하고
      // liveEdgePosition 기준으로 시차를 재면 둘이 그 차이만큼 어긋난다.
      const sync = hlsRef.current?.liveSyncPosition ?? null;
      const live = liveEdgePosition(range, sync);
      // 상한은 시크바 좌측 끝과 같은 함수에서 온다 — 어긋나면 눌러도 안 가는 영역이 생긴다
      const clamped = Math.min(rewindWindowSeconds(range, sync), Math.max(0, behind));
      if (isAtEdge(clamped)) {
        // 창이 라이브 지연폭보다 짧으면(방송 시작 직후) live가 range.start로 붕괴한다 —
        // 스냅할 지점이 없는데 시크하면 재생 중인 위치에서 뒤로 끌려간다. 그땐 두고 본다.
        if (live > range.start) video.currentTime = live;
        setBehind(0);
      } else {
        video.currentTime = Math.max(range.start, live - clamped);
        setBehind(Math.round(clamped));
      }
      onSeek?.();
    },
    [videoRef, onSeek],
  );

  const seekToFraction = useCallback(
    (fraction: number) => seekToBehind(behindFromSeekFraction(fraction, windowSeconds)),
    [seekToBehind, windowSeconds],
  );

  const seekBy = useCallback(
    (deltaSeconds: number) => {
      const video = videoRef.current;
      if (!video) return;
      const range = dvrRange(video.seekable);
      if (!range) return;
      seekToBehind(
        behindFromCurrentTime(range, video.currentTime, hlsRef.current?.liveSyncPosition ?? null) +
          deltaSeconds,
      );
    },
    [videoRef, seekToBehind],
  );

  // 실제 시각으로 옮긴다(라이브 카드, POK-326). 그 시각을 품은 조각의 시각 표시로 영상 시각을 셈하고, 시크는 같은 한 길
  // (seekToBehind)을 탄다: 되감기 창 밖은 가장 오래된 지점으로 자른다. 방송 경과 시계로 옮기면 숨은 탭에서 그 시계의 타이머가
  // 늦춰져 카드보다 몇 분 늦은 자리(심하면 실시간 끝)로 갔다(리뷰 1라운드). 시각 표시가 없으면 false: 부르는 쪽이 경과 시계로 옮긴다
  const seekToDate = useCallback(
    (epochMs: number): boolean => {
      const video = videoRef.current;
      if (!video) return false;
      const range = dvrRange(video.seekable);
      const target = mediaTimeAt(hlsRef.current?.latestLevelDetails?.fragments ?? [], epochMs);
      if (!range || target === null) return false;
      const live = liveEdgePosition(range, hlsRef.current?.liveSyncPosition ?? null);
      seekToBehind(live - target);
      return true;
    },
    [videoRef, seekToBehind],
  );

  // 라이브 복귀는 이 액션(LIVE 버튼)뿐이다 — 플레이어가 스스로 복귀하는 경로는 없다 (계약3 4절 3번)
  const returnToLive = useCallback(() => {
    seekToBehind(0);
    videoRef.current?.play().catch(() => {});
  }, [seekToBehind, videoRef]);

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    // 재개 시 currentTime을 만지지 않는다 — 되감은 위치를 그대로 이어서 튼다 (계약3 4절 1번)
    if (video.paused) video.play().catch(() => {});
    else video.pause();
    wake();
  }, [videoRef, wake]);

  const toggleMute = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !video.muted;
  }, [videoRef]);

  const setVolume = useCallback(
    (value: number) => {
      const video = videoRef.current;
      if (!video) return;
      const v = Math.min(100, Math.max(0, Math.round(value)));
      video.volume = v / 100;
      if (v > 0) video.muted = false;
    },
    [videoRef],
  );

  const markClip = useCallback(() => {
    setClipMarked(true);
    window.clearTimeout(clipTimer.current);
    clipTimer.current = window.setTimeout(() => setClipMarked(false), CLIP_MARK_MS);
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    // StrictMode 이중 이펙트·동적 import 경합 방어 — 클린업 후 도착한 인스턴스를 버린다
    let disposed = false;
    let recoveredNetwork = false;
    let recoveredMedia = false;

    // src 전환(?stream= 변경) 시 이전 스트림의 상태가 남지 않게 초기화 — 첫 마운트에선
    // 같은 값이라 no-op. 새 소스의 자동재생이 거부되면 playing=false로 남아 재생 버튼이 노출된다.
    setPlaying(false);
    setBehind(0);
    // 이전 스트림의 창 폭이 새 스트림 시크바에 남으면 안 된다
    setWindowSeconds(0);
    // 이전 스트림의 자리도 남기지 않는다: 새 스트림 채팅이 앞 방송 시각을 따른다
    setLivePosition(null);

    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onVolumeChange = () => {
      setMuted(video.muted);
      setVolumeState(Math.round(video.volume * 100));
    };
    // 시차는 정수 초로 반올림돼 있어 같은 값이면 setState가 리렌더를 건너뛴다
    const paint = () => {
      const range = dvrRange(video.seekable);
      if (!range) return;
      const sync = hlsRef.current?.liveSyncPosition ?? null;
      // 창 폭도 정수로 반올림한다 — live - range.start는 계속 흐르는 실수라 그대로 세팅하면
      // timeupdate마다 값이 달라져 리렌더가 돈다 (시차를 반올림하는 이유와 같다)
      setWindowSeconds(Math.round(rewindWindowSeconds(range, sync)));
      const behind = behindFromCurrentTime(range, video.currentTime, sync);
      setBehind(behind);
      // 재생 위치에 영상이 없으면(첫 조각이 붙기 전, 옮긴 자리를 받는 중, 미디어 오류 복구로 다시 붙는 중) 자리를 바꾸지 않는다:
      // 그때의 재생 위치(첫 조각 전엔 0초)는 보고 있는 화면이 아니라, 라이브를 여는 순간 1시간 되감은 것처럼 채팅이 따라갔다
      // (POK-326 리뷰 1라운드)
      if (video.readyState < HAVE_CURRENT_DATA) return;
      // 시각은 조각 목록으로 센다(programDateAt). 같은 실제 초 안에서 시차도 같으면 앞 값(같은 객체)을 둔다: timeupdate는 1초에
      // 네 번쯤 온다(시차를 반올림하는 이유와 같다). 정각 초로 내리지 않는다: 카드·채팅 바닥 시계의 0초(서버 기준점)는 ms 자리가
      // 있어, 내리면 카드로 정확히 옮겨도 채팅이 카드보다 그만큼 일찍 잘리고 바닥이 1초 이르게 섰다(리뷰 2라운드)
      const fragments = hlsRef.current?.latestLevelDetails?.fragments ?? [];
      const at = programDateAt(fragments, video.currentTime);
      const edge = programDateAt(fragments, liveEdgePosition(range, sync));
      setLivePosition((prev) => {
        if (at === null || edge === null) return null;
        const atMs = Math.round(at);
        return prev !== null &&
          Math.floor(prev.atMs / 1000) === Math.floor(atMs / 1000) &&
          prev.behindSeconds === behind
          ? prev
          : { atMs, edgeAtMs: Math.round(edge), behindSeconds: behind };
      });
    };
    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('volumechange', onVolumeChange);
    video.addEventListener('timeupdate', paint);
    // 일시정지 중엔 timeupdate가 멎지만 라이브 엣지는 계속 흐른다 — 시차 표시용 보조 틱
    const paintTimer = window.setInterval(paint, 1000);

    void (async () => {
      // hls.js는 클라이언트 전용(월드 오브젝트 참조) — 동적 import로 번들 분리까지 겸한다
      const { default: HlsCtor } = await import('hls.js');
      if (disposed) return;

      if (HlsCtor.isSupported()) {
        const hls = new HlsCtor(HLS_DVR_CONFIG);
        hlsRef.current = hls;
        hls.on(HlsCtor.Events.ERROR, (_event, data) => {
          if (!data.fatal) return;
          // 스파이크 최소 복구 — 각 1회만 시도, 재발 시 정리하고 크게 남긴다
          if (data.type === HlsCtor.ErrorTypes.NETWORK_ERROR && !recoveredNetwork) {
            recoveredNetwork = true;
            hls.startLoad();
            return;
          }
          if (data.type === HlsCtor.ErrorTypes.MEDIA_ERROR && !recoveredMedia) {
            recoveredMedia = true;
            hls.recoverMediaError();
            return;
          }
          // 화면은 마지막 프레임에서 멎고 로그만 남는다 — 사용자에게 보이는 에러 상태는
          // PlayerSimulation 계약을 넓혀야 해 POK-23에서 다룬다
          console.error('[player] 복구 불가 hls.js 오류:', data.type, data.details);
          hls.destroy();
          hlsRef.current = null;
        });
        hls.on(HlsCtor.Events.MANIFEST_PARSED, () => {
          // 자동재생 거부는 reject로 온다 — playing=false로 남아 재생 버튼이 노출된다
          video.play().catch(() => {});
        });
        hls.attachMedia(video);
        // index.m3u8의 302(세션 파라미터)는 기본 로더가 따라간다 — 커스텀 loader 금지 (.env.example 참조)
        hls.loadSource(src);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari 네이티브 HLS — liveSyncPosition이 없어 엣지 스냅은 백오프 경로를 탄다
        video.src = src;
        video.play().catch(() => {});
      } else {
        console.warn('[player] HLS 재생 불가 — hls.js 미지원 + 네이티브 HLS 없음 (Chrome 권장)');
      }
    })();

    return () => {
      disposed = true;
      window.clearInterval(paintTimer);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('volumechange', onVolumeChange);
      video.removeEventListener('timeupdate', paint);
      hlsRef.current?.destroy();
      hlsRef.current = null;
      // src 제거만으로는 네트워크·디코더가 붙어 있다 — load()까지 해야 리소스가 해제된다
      video.removeAttribute('src');
      video.load();
    };
  }, [videoRef, src]);

  return {
    playing,
    muted,
    volume,
    behindSeconds,
    livePosition,
    atEdge: isAtEdge(behindSeconds),
    windowSeconds,
    uptimeSeconds,
    quality,
    lowLatency,
    clipMarked,
    controlsVisible,
    togglePlay,
    toggleMute,
    setVolume,
    seekToFraction,
    seekBy,
    seekToDate,
    returnToLive,
    // 화질은 표시용 — hls.js 레벨 배선은 스파이크 범위 밖 (POK-23 이후). 렌디션이 하나라 메뉴를 안 보인다(POK-253)
    settingsMenu: false,
    replay: false,
    setQuality,
    // 표시 전용 — lowLatencyMode는 Hls 생성자 전용 옵션이라 실반영엔 인스턴스 재생성이
    // 필요하다. 시킹 위치 복원 등 엣지가 커서 스파이크 범위 밖 (POK-23 이후).
    toggleLowLatency: useCallback(() => setLowLatency((v) => !v), []),
    markClip,
    wake,
    sleep,
  };
}
