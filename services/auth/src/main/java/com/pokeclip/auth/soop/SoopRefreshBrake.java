package com.pokeclip.auth.soop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 연속 거절 브레이크. 갱신기({@link SoopTokenRefresher})가 <b>두 경로 모두</b>(스케줄러 점검 · 수집기 resolve 즉석 갱신)의
 * 거절을 여기서 센다(PR #329 봇 지적 P1: 스케줄러만 세면 첫 틱 전에 resolve가 방송하는 회원마다 파기한다).
 *
 * <p>왜 있나: 치지직·유튜브는 앱 설정 오류(시크릿 오타)를 응답의 오류 코드로 가려 일시로 돌리는데, SOOP은 토큰 주소의 오류 꼴이
 * 문서에 없어 그럴 수 없다. 없으면 비밀값 하나가 틀린 배포가 전 회원 연동을 BROKEN으로 닫고 토큰까지 지운다(되돌릴 수 없다).
 *
 * <p>규칙: 거절마다 {@link #recordReject}가 연속 수를 하나 올린다. 올린 결과가 {@code rejectBrake}(3) 이상이면 서고 그 거절부터
 * 파기하지 않는다(세기와 판정이 {@code incrementAndGet} 한 번이라 동시 거절 다섯이 와도 닫히는 것은 rejectBrake-1명 이하).
 * 0으로 돌리는 것은 SOOP이 실제로 답한 성공(REFRESHED, {@link #recordSuccess})뿐이다. SKIPPED_FRESH·NOT_LINKED·UNAVAILABLE은
 * 셈을 건드리지 않는다: 비밀값이 틀린 배포에서도 신선한 회원의 resolve는 SKIPPED_FRESH라, 그것이 0으로 돌리면 브레이크가 영영
 * 안 선다. 프로세스 수명 동안 서 있고 재시작(재배포)이 푼다. 대가: 선 동안 진짜로 철회한 회원도 재배포 때까지 안 닫힌다.
 */
@Component
public class SoopRefreshBrake {

    private static final Logger log = LoggerFactory.getLogger(SoopRefreshBrake.class);

    private final int limit;
    private final AtomicInteger consecutiveRejects = new AtomicInteger();
    private final AtomicBoolean engaged = new AtomicBoolean();

    public SoopRefreshBrake(SoopProperties properties) {
        this.limit = properties.check().rejectBrake();
    }

    public boolean engaged() {
        return engaged.get();
    }

    /** 거절 하나를 센다. true면 그 연동을 파기해도 되고, false면 브레이크가 서 있으니 파기하지 않는다. */
    boolean recordReject(Long userId) {
        if (engaged.get()) {
            return false;
        }
        int count = consecutiveRejects.incrementAndGet();
        if (count < limit) {
            return true;
        }
        if (engaged.compareAndSet(false, true)) {
            // 서는 순간 한 번. 조치를 로그에 같이 둔다(값은 안 찍는다). 이 줄 하나가 운영자가 받는 신호다.
            log.error("auth.soop.link.refresh_reject_burst consecutive={} lastUserId={} "
                            + "action=check_SOOP_CLIENT_ID_SOOP_CLIENT_SECRET_SOOP_REDIRECT_URI_then_redeploy",
                    count, userId);
        }
        return false;
    }

    /** SOOP이 실제로 새 토큰을 준 성공. 연속 수를 0으로 돌린다(선 뒤에는 의미가 없다). */
    void recordSuccess() {
        if (!engaged.get()) {
            consecutiveRejects.set(0);
        }
    }

    /** 시험 전용. 운영은 재시작만이 세운다·푼다. */
    void engage() {
        engaged.set(true);
    }

    /** 시험 전용. 컨텍스트가 공유되므로 시험마다 비운다(셈까지). */
    void release() {
        engaged.set(false);
        consecutiveRejects.set(0);
    }
}
