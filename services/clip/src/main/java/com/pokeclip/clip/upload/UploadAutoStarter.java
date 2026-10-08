package com.pokeclip.clip.upload;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.render.Clip;
import com.pokeclip.clip.render.RenderJob;
import com.pokeclip.clip.render.RenderJobRepository;
import com.pokeclip.clip.render.RenderProperties;
import com.pokeclip.clip.upload.UploadRequestService.Video;
import com.pokeclip.clip.upload.UploadRequestStore.Stored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 렌더가 성공하면 저장된 의도대로 업로드 줄을 만든다(POK-291). <b>업로드 줄을 넣는 길이 전부 여기를 지난다</b>
 * ({@link #enqueue}): 옛 문·다시 시도·렌더 성공 자동·「영상 만들기」의 이미 완성 갈래. 길이 갈리면 한쪽만 판 단위 중복 검사나
 * 커밋 뒤 발행을 빠뜨린다.
 *
 * <p>🔴 <b>{@link #startFromRequest}는 부른 쪽 트랜잭션 안에서 돈다.</b> 렌더 성공 보고({@code JobEventService})는 「영상 완성」과
 * 「업로드 줄」을 한 트랜잭션으로 쓴다. 그래야 같은 성공 보고의 재전송이 보고 장부에 걸려 줄을 또 안 만들고, 롤백되면 둘 다 안 남아
 * 일꾼이 다시 보내도 안전하다. 커밋 뒤에 따로 만들면 재전송이 부분 UNIQUE 하나에만 기대고, 그 사이 서버가 죽으면 영상은 완성인데
 * 업로드 줄은 영영 없다.
 *
 * <p>잠금 순서: 부른 쪽이 {@code render_jobs → clips}를 잡은 뒤 여기서 {@code upload_requests}(FOR UPDATE) → 판 권고 잠금 순서로
 * 잡는다. 탈퇴 정리는 {@code render_jobs → clips}를 잡고 {@code upload_requests}는 잠그지 않고 지우기만 하므로(삭제가 줄 잠금을
 * 기다릴 뿐) 순서가 안 뒤집힌다.
 */
@Component
public class UploadAutoStarter {

    private static final Logger log = LoggerFactory.getLogger(UploadAutoStarter.class);

    private static final String VERTICAL = "VERT_9_16";

    private final UploadRequestStore requests;
    private final ClipUploadRepository uploads;
    private final UploadInserter inserter;
    private final UploadPublisher publisher;
    private final ObjectProvider<UploadQueueClient> queue;
    private final BroadcastRepository broadcasts;
    private final RenderJobRepository jobs;
    private final RenderProperties render;
    private final ObjectMapper mapper;

    UploadAutoStarter(UploadRequestStore requests, ClipUploadRepository uploads, UploadInserter inserter,
                      UploadPublisher publisher, ObjectProvider<UploadQueueClient> queue, BroadcastRepository broadcasts,
                      RenderJobRepository jobs, RenderProperties render, ObjectMapper mapper) {
        this.requests = requests;
        this.uploads = uploads;
        this.inserter = inserter;
        this.publisher = publisher;
        this.queue = queue;
        this.broadcasts = broadcasts;
        this.jobs = jobs;
        this.render = render;
        this.mapper = mapper;
    }

    /** 만들지 않은 이유. {@code ALREADY_ACTIVE}면 그 업로드 번호가 같이 온다. */
    public enum Skip { NO_REQUEST, DISABLED, ALREADY_ACTIVE }

    /** @param uploadId 만들었으면 새 번호, {@code ALREADY_ACTIVE}면 살아 있는 번호 */
    public record Started(Skip skipped, long uploadId) {
        public boolean created() {
            return skipped == null;
        }
    }

    /**
     * 완성된 영상에 그 판의 의도가 있으면 업로드 줄을 만들고 커밋 뒤 싣는다. 부른 쪽 트랜잭션 안에서만 부른다.
     *
     * @param clip 방금 완성된(또는 이미 완성인) 영상. 산출물({@code outputs})이 채워져 있어야 한다
     */
    public Started startFromRequest(Clip clip) {
        Optional<Stored> request = requests.findForUpdate(clip.getRecipeId(), clip.getRecipeVersion());
        if (request.isEmpty()) {
            return new Started(Skip.NO_REQUEST, 0);
        }
        if (queue.getIfAvailable() == null) {
            // 줄이 꺼진 채로 렌더만 켜진 배포다. 줄을 만들면 아무도 안 실어 보관함이 영원히 「올리는 중」이다.
            log.warn("clip.upload.auto_skipped reason=disabled clipId={}", clip.getId());
            return new Started(Skip.DISABLED, 0);
        }
        Stored stored = request.get();
        String channelOwner = broadcasts.findStreamerIdByStreamId(clip.getStreamId()).orElseThrow();
        Video video = pickForAuto(clip);
        Started started = enqueue(clip, video, stored.requestedBy(), channelOwner, stored.info());
        if (started.created()) {
            log.info("clip.upload.auto_started uploadId={} clipId={} outputId={}", started.uploadId(), clip.getId(),
                    video.outputId());
        } else {
            log.info("clip.upload.auto_skipped reason=already_active clipId={} uploadId={}", clip.getId(), started.uploadId());
        }
        return started;
    }

    /**
     * 업로드 줄 한 벌을 넣고 주문서를 채우고 <b>커밋 뒤</b> 싣는다. 부른 쪽 트랜잭션 안에서만 부른다.
     *
     * <p>판 단위 중복: 같은 영상 같은 벌에 살아 있는 줄이 있으면 그것(부분 UNIQUE가 가린다), 같은 판의 다른 영상·다른 벌에 있으면
     * {@code ALREADY_ACTIVE}다. 둘 다 새로 안 만든다.
     */
    Started enqueue(Clip clip, Video video, String requestedBy, String channelOwner, UploadInfo info) {
        inserter.lockVersion(clip.getRecipeId(), clip.getRecipeVersion());
        Optional<ClipUpload> active = uploads.findActiveForVersion(clip.getRecipeId(), clip.getRecipeVersion());
        if (active.isPresent()) {
            return new Started(Skip.ALREADY_ACTIVE, active.get().getId());
        }
        OptionalLong inserted = inserter.insertIfNoActive(clip.getId(), video.outputId(), requestedBy, channelOwner, info);
        if (inserted.isEmpty()) {
            // 판 잠금을 잡았으니 여기 올 일이 없다. 와도 새로 안 만든다(색인이 가린 줄을 돌려준다).
            return new Started(Skip.ALREADY_ACTIVE, uploads.findActive(clip.getId(), video.outputId()).orElseThrow().getId());
        }
        long uploadId = inserted.getAsLong();
        inserter.fillPayload(uploadId, UploadPayload.build(mapper, uploadId, clip.getId(), clip.getStreamId(), channelOwner,
                new UploadPayload.Source(render.outputBucket(), video.s3Key(), video.outputId()), info,
                render.outputBucket(), Instant.now()));
        publishAfterCommit(uploadId);
        return new Started(null, uploadId);
    }

    /**
     * 커밋 뒤에 싣는다. 커밋 전에 실으면 일꾼이 아직 없는 줄을 보고 404를 받고, 롤백되면 유령 주문이 남는다.
     * 못 실으면 예외를 안 올린다: 줄은 있고 outbox가 다시 싣는다.
     */
    private void publishAfterCommit(long uploadId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publishNow(uploadId);
            }
        });
    }

    /**
     * 자동 업로드가 올릴 벌. 영상이 하나면 그것, 여럿이면 세로(VERT_9_16), 세로가 없으면 벌 번호 사전순 첫째(ADR-080 규칙을
     * 서버도 똑같이). 비율은 이 영상을 만든 주문서의 편집본에서 읽는다: 편집본 표는 그 뒤에 고쳐졌을 수 있다.
     */
    Video pickForAuto(Clip clip) {
        List<Video> videos = new ArrayList<>();
        for (JsonNode output : mapper.readTree(clip.getOutputs())) {
            if ("video".equals(output.path("kind").asString(null))) {
                videos.add(new Video(output.path("outputId").asString(), output.path("s3Key").asString()));
            }
        }
        if (videos.size() == 1) {
            return videos.getFirst();
        }
        Optional<String> vertical = jobs.findByClipId(clip.getId()).map(RenderJob::getPayload).flatMap(this::verticalOutputId);
        return videos.stream().filter(v -> vertical.isPresent() && v.outputId().equals(vertical.get())).findFirst()
                .orElseGet(() -> videos.stream().min(Comparator.comparing(Video::outputId)).orElseThrow());
    }

    private Optional<String> verticalOutputId(String renderPayload) {
        for (JsonNode output : mapper.readTree(renderPayload).path("recipe").path("outputs")) {
            if (VERTICAL.equals(output.path("aspect").asString(null))) {
                return Optional.ofNullable(output.path("outputId").asString(null));
            }
        }
        return Optional.empty();
    }
}
