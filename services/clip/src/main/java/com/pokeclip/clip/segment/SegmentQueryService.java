package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.ResolveResult;
import com.pokeclip.clip.segment.SegmentErrors.AuthUnavailableException;
import com.pokeclip.clip.segment.SegmentErrors.InvalidRangeException;
import com.pokeclip.clip.segment.SegmentErrors.NotViewableException;
import com.pokeclip.clip.segment.SegmentErrors.VodExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「방송이 있나 → 볼 자격이 있나 → 기한이 안 지났나 → 조각을 읽어 창을 조립」.
 * <b>이 순서 자체가 계약이다</b>(PRD 결정 표 「처리 순서」) — 바꾸면 정보가 샌다.
 *
 * <p>소비자가 둘이다. 편집기 미리보기(태스크 6의 컨트롤러)와 <b>렌더 잡(POK-125)</b>인데,
 * 뒤쪽은 컨트롤러를 거치지 않고 이 메서드를 직접 부른다. 그래서 <b>거절 판정을 하나도
 * 컨트롤러에 두지 않는다</b> — 구간 검증까지 여기 있는 이유다(감사 1회차).
 */
@Service
public class SegmentQueryService {

    private static final Logger log = LoggerFactory.getLogger(SegmentQueryService.class);

    /**
     * 한 번에 볼 수 있는 구간의 상한(30분, PRD 결정).
     *
     * <p><b>서비스가 갖는다 — 컨트롤러가 아니다.</b> 계약의 주인이 여기라서다. 컨트롤러에 두면
     * 렌더 잡 경로에는 상한이 없어지고, 그쪽이 8시간 방송 전체(4초 조각 약 7,200행)를
     * 한 번에 당긴다.
     */
    public static final long MAX_RANGE_MS = 1_800_000L;

    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader reader;
    private final DelegationResolveClient delegation;

    SegmentQueryService(BroadcastRepository broadcasts, StreamSegmentReader reader,
                        DelegationResolveClient delegation) {
        this.broadcasts = broadcasts;
        this.reader = reader;
        this.delegation = delegation;
    }

    /**
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @throws SegmentErrors.InvalidRangeException 구간이 형식부터 틀렸다 (400)
     * @throws SegmentErrors.NotViewableException 방송이 없거나 이 사람이 볼 자격이 없다 (404, 같은 본문)
     * @throws SegmentErrors.AuthUnavailableException 자격을 물어보지 못했다 (503)
     * @throws SegmentErrors.VodExpiredException 보관 기한이 지났다 (410)
     */
    public SegmentWindow previewWindow(String requesterSubject, String streamId, long startMs, long endMs) {
        // 구간 검증이 맨 앞이다. 뒤로 밀면 형식 오류 하나가 DB 조회와 auth 왕복(최대 7초)을
        // 태우고, 그러고도 나가는 응답이 400이 아니라 404가 된다.
        //
        // 조립기는 뒤집힌 구간을 스스로 못 막는다 — 감사 1회차 실측:
        // assemble(조각들, 9000, 5000) → complete=true. 빈 결과만 막혀 있고 비지 않은 결과는
        // 그대로 통과한다. 즉 여기가 그 방어의 유일한 자리다.
        //
        // 뺄셈이 넘치지 않는다: 위 두 검사를 지나면 startMs >= 0 이고 endMs > startMs 이므로
        // endMs - startMs <= endMs <= Long.MAX_VALUE 다.
        if (startMs < 0) {
            throw new InvalidRangeException("startMs");
        }
        if (endMs <= startMs) {
            throw new InvalidRangeException("endMs");
        }
        if (endMs - startMs > MAX_RANGE_MS) {
            throw new InvalidRangeException("endMs");
        }

        // ① 방송이 있나. 여기가 먼저인 것은 auth에 물어볼 스트리머 번호가 이 줄에 있기 때문이다.
        Broadcast broadcast = broadcasts.findByStreamId(streamId)
                .orElseThrow(() -> new NotViewableException("broadcast_not_found"));

        // ② 우리 토큰인데 sub가 숫자가 아니면 데이터가 이상한 것이다.
        long userId = parseNumeric(requesterSubject, "subject_not_numeric", streamId);
        // ③ 명부의 streamer_id는 문자열 칸이고 auth의 회원 번호는 숫자다. 이 변환이 그 다리다.
        long streamerUserId = parseNumeric(broadcast.getStreamerId(), "streamer_id_not_numeric", streamId);

        // ④ 자격. 인자 순서가 (요청자, 스트리머)다 — 바꿔 넘기면 스트리머가 자기 방송을 못 보고
        //    증상은 「권한 없음」으로 나온다.
        ResolveResult relation = delegation.resolve(userId, streamerUserId);
        if (relation == ResolveResult.UNAVAILABLE) {
            // 판정 불가는 거절이다(PRD). 통과로 접으면 auth가 죽은 동안 남남이 남의 방송을 본다.
            throw new AuthUnavailableException();
        }
        if (relation == ResolveResult.NONE) {
            throw new NotViewableException("relation_none");
        }

        // ⑤ 만료. 🔴 자격 확인 뒤여야 한다 — 410은 「있었는데 없어졌다」는 뜻이라 그 자체로
        //    방송의 실재를 말한다. 앞으로 옮기면 남남에게도 410이 나가고, ①과 NONE을 같은
        //    404로 합쳐 둔 것이 통째로 무의미해진다.
        if (Boolean.TRUE.equals(broadcasts.isVodExpired(streamId))) {
            throw new VodExpiredException();
        }

        return SegmentWindowAssembler.assemble(reader.findOverlapping(streamId, startMs, endMs), startMs, endMs);
    }

    /**
     * 식별자를 회원 번호로 바꾼다. 못 바꾸면 <b>ERROR를 남기고</b> 「볼 수 없다」로 접는다.
     *
     * <p>ERROR인 이유는 이것이 <b>조용한 장애</b>이기 때문이다 — 주인이 자기 방송을 못 보는데
     * 화면에는 「없는 방송」이라고 나온다. 응답으로는 영영 구분이 안 되므로 이 로그가 유일한
     * 발견 수단이다(PRD 성공 기준).
     *
     * <p><b>값 자체는 안 찍는다.</b> 어떤 쓰레기가 왔는지가 아니라 어느 방송이 아픈지가
     * 진단이다. {@code streamId}는 싣는데, 그것은 <b>둘 다 방송 조회가 성공한 뒤에만</b>
     * 불리므로 명부에 실재하는 값이다(자유 입력이 그대로 로그로 가는 것이 아니다).
     */
    private long parseNumeric(String value, String reason, String streamId) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.error("clip.segment.identity_not_numeric reason={} streamId={}", reason, streamId);
            throw new NotViewableException(reason);
        }
    }
}
