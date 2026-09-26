package com.pokeclip.clip.render;

import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.RenderErrors.ClipNotFoundException;
import com.pokeclip.clip.render.RenderErrors.ClipNotRenderedException;
import com.pokeclip.clip.render.RenderErrors.RenderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 완성 영상을 보고 받을 주소(POK-247). 창고는 비공개라 화면이 {@code s3Key}로는 못 받는다.
 *
 * <p><b>순서가 계약이다: 자격 → 영상 → 켜짐 → 완성.</b> 자격이 맨 앞이라 없는 방송과 자격 없음이 같은 404·같은 바닥이다
 * (하나 보기 문과 같다). 주소 발급은 표를 안 바꾸므로 트랜잭션이 없다.
 */
@Service
public class ClipFileAccessService {

    private static final Logger log = LoggerFactory.getLogger(ClipFileAccessService.class);

    private final BroadcastAccessGuard guard;
    private final ClipRepository clips;
    private final ObjectProvider<ClipFileSigner> signer;
    private final ObjectMapper mapper;

    ClipFileAccessService(BroadcastAccessGuard guard, ClipRepository clips, ObjectProvider<ClipFileSigner> signer,
                          ObjectMapper mapper) {
        this.guard = guard;
        this.clips = clips;
        this.signer = signer;
        this.mapper = mapper;
    }

    /**
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws ClipNotFoundException 그 방송에 그 번호의 영상이 없다 (404)
     * @throws RenderUnavailableException 주문줄이 꺼져 있어 창고 좌표가 없다 (503)
     * @throws ClipNotRenderedException 아직 완성되지 않았다 (409)
     */
    public ClipFileAccess issue(String requesterSubject, String streamId, long clipId) {
        guard.requireViewable(requesterSubject, streamId);
        Clip clip = clips.findByIdAndStreamId(clipId, streamId).orElseThrow(() -> new ClipNotFoundException(clipId));
        ClipFileSigner available = signer.getIfAvailable();
        if (available == null) {
            throw new RenderUnavailableException();
        }
        if (clip.getStatus() != ClipStatus.RENDERED || clip.getOutputs() == null) {
            throw new ClipNotRenderedException(clipId);
        }
        ClipFileAccess access = available.sign(clipId, mapper.readTree(clip.getOutputs()), Instant.now());
        // 주소는 안 찍는다: 그 값이 곧 출입증이다.
        log.info("clip.file_access.issued clipId={} userId={} files={} expiresAt={}",
                clipId, requesterSubject, access.files().size(), access.expiresAt());
        return access;
    }
}
