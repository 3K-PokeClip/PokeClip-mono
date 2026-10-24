package com.pokeclip.clip.render;

import com.pokeclip.clip.segment.SegmentSource;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 원본 조각(1번 장부의 {@code s3_key})의 S3 미리서명 주소를 만드는 유일한 자리(POK-389). 자격 판정은 여기 없다:
 * {@link SegmentAudioAccessService}가 먼저 한다.
 *
 * <p>재생 서버 주소가 아니라 조각 파일인 이유: 재생 서버는 영상 하나·소리 하나만 남긴다(ADR-057). 소리 트랙이 전부 든 것은
 * 조각 파일뿐이다(렌더 일꾼도 같은 파일을 읽는다). 로그에 <b>주소를 찍지 않는다</b>: 한 줄 새면 그 조각이 수명 동안 열린다.
 */
public class SegmentAudioSigner {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public SegmentAudioSigner(S3Presigner presigner, String bucket, Duration ttl) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    /** @param segments 올라간({@code uploaded}) 조각만. 거르기는 부르는 쪽이 한다 */
    public SegmentAudioAccess sign(List<SegmentSource> segments, Instant now) {
        List<SegmentAudioAccess.Segment> signed = segments.stream()
                .map(segment -> new SegmentAudioAccess.Segment(segment.seq(), segment.startAtMs(), segment.durationMs(),
                        presigner.presignGetObject(request -> request
                                .signatureDuration(ttl)
                                .getObjectRequest(get -> get.bucket(bucket).key(segment.s3Key())))
                                .url().toString()))
                .toList();
        return new SegmentAudioAccess(now.plus(ttl), signed);
    }
}
