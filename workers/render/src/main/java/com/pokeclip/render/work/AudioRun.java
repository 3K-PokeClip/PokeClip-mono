package com.pokeclip.render.work;

import java.util.ArrayList;
import java.util.List;

/**
 * 소리 묶음 하나(PDT 설계 r7 3.3 · 7.4 소리 묶음). 같은 녹화로 이어진 조각들을 concat demuxer 한 입력으로 한 흐름에 디코드하고,
 * 결과에 필요한 표본만 그 흐름에서 자른다. 표본 값은 모두 {@code aresample=48000} 출력 단위(1/48000)다.
 *
 * <p>입력 파일 순서는 {@link #fileSeqs()} — 준비 조각(있으면 맨 앞) · 묶음 조각 · 확보 조각(마지막 묶음이고 있으면 맨 끝).
 * 준비 조각의 소리는 디코더 상태만 채우고 자를 구간 앞이라 결과에 한 표본도 들지 않는다.
 *
 * @param index             결과 순서 번호(0부터). 최종 단계 입력 번호 = 1 + index(설계 13.5)
 * @param primeSeq          묶음 머리 앞 디코더 준비 조각 seq. 없으면 null
 * @param seqs              결과에 드는 조각(영상 0장 · 소리만 조각 포함)
 * @param captureSeq        마지막 묶음 끝 확보 조각 seq. 없으면 null
 * @param concatDurationsUs {@code ffconcat} 줄마다 {@code duration}(µs 정수 · 파일 수 − 1개). 누적 정확값을 µs 로 반올림한 차다
 * @param fromSample        흐름 안 자를 구간 시작({@code atrim start_pts})
 * @param toSample          흐름 안 자를 구간 끝({@code atrim end_pts})
 * @param tracks            고른 트랙마다 앞 무음 · 무음 대체 여부(고른 순서 그대로)
 * @param resultStartSample 이 묶음이 결과에 놓이는 자리(결과 축 정확값을 내림)
 * @param resultEndSample   다음 묶음 자리 또는 결과 끝(묶음 길이 = 끝 − 시작)
 */
public record AudioRun(int index, Long primeSeq, List<Long> seqs, Long captureSeq, List<Long> concatDurationsUs,
                       long fromSample, long toSample, List<TrackStart> tracks, long resultStartSample,
                       long resultEndSample) {

    public AudioRun {
        seqs = List.copyOf(seqs);
        concatDurationsUs = List.copyOf(concatDurationsUs);
        tracks = List.copyOf(tracks);
    }

    /**
     * 고른 트랙 하나의 묶음 머리 상태(설계 7.4 소리 묶음 5 · 6).
     *
     * @param trackId             트랙 번호 N(N번째 소리 스트림)
     * @param silent              묶음의 모든 파일에 이 트랙 패킷이 없다 — {@code [r:a:N]} 대신 정확 길이 무음({@code anullsrc})
     * @param frontSilenceSamples 자를 구간 시작보다 트랙 첫 표본이 늦은 만큼({@code adelay}). 무음 대체면 0
     */
    public record TrackStart(int trackId, boolean silent, long frontSilenceSamples) {
    }

    /** {@code ffconcat} 에 적는 파일 순서. */
    public List<Long> fileSeqs() {
        List<Long> files = new ArrayList<>();
        if (primeSeq != null) {
            files.add(primeSeq);
        }
        files.addAll(seqs);
        if (captureSeq != null) {
            files.add(captureSeq);
        }
        return List.copyOf(files);
    }

    /** 결과 안 길이(표본 · {@code atrim end_sample}). */
    public long lengthSamples() {
        return resultEndSample - resultStartSample;
    }
}
