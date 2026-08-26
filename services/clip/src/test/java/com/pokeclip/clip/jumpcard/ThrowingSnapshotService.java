package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@code snapshotsOf}만 던지는 {@link JumpCardService}. 「연결을 잡은 뒤 실패하면 자리가 새는가」를
 * 재려고 있다.
 *
 * <p>이 패키지에 두는 이유: {@code JumpCardService}의 생성자가 package-private이라
 * 다른 패키지에서는 상속할 수 없다. <b>운영 코드를 열지 않으려고 시험을 이쪽으로 옮겼다</b>
 * (러너가 package-private이라 관통 시험을 옮긴 것과 같은 처리).
 */
public class ThrowingSnapshotService extends JumpCardService {

    public ThrowingSnapshotService(JumpCardRepository cards, BroadcastRepository broadcasts,
                                   JumpCardProperties properties, ObjectMapper mapper,
                                   CardStreamRegistry registry, BroadcastAccessGuard guard) {
        super(cards, broadcasts, properties, mapper, registry, guard);
    }

    /** DB 커넥션 고갈·쿼리 타임아웃·jsonb 직렬화 실패를 대신한다 — 셋 다 여기서 예외로 나온다. */
    @Override
    public List<JumpCardSnapshot> snapshotsOf(String streamId) {
        throw new IllegalStateException("스냅샷 읽기 실패(시험용)");
    }
}
