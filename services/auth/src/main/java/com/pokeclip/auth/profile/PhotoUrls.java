package com.pokeclip.auth.profile;

import com.pokeclip.auth.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 회원 → 화면이 그림 태그에 그대로 넣을 주소.
 *
 * <p><b>응답 형식은 안 바꾼다</b> — 칸은 지금처럼 하나이고, 사진을 올렸으면 우리 주소가,
 * 안 올렸으면 구글 주소가 들어간다(PRD). 늘리는 것은 언제든 되지만 박아 둔 칸을 빼는 것은
 * 웹을 깨뜨린다.
 *
 * <p><b>표를 부를 때마다 새로 만들지만 글자는 10분 동안 안 바뀐다</b> — {@link PhotoToken}이
 * 만료를 10분 경계에 맞추기 때문이다. 회원 정보는 60초마다·탭 복귀마다 다시 불리므로,
 * 이 성질이 없으면 화면이 같은 그림을 계속 다시 받는다.
 */
@Component
@RequiredArgsConstructor
public class PhotoUrls {

    private static final Logger log = LoggerFactory.getLogger(PhotoUrls.class);

    private final PhotoProperties properties;

    public String of(User user, Instant now) {
        if (!user.hasUploadedPhoto()) {
            return user.getProfileImageUrl();
        }
        if (!properties.enabled()) {
            // 🔴 여기서 나가는 것은 대개 null이다. 사진을 올릴 때 구글 주소를 비웠기 때문이다
            // (User.attachPhoto). 화면은 이니셜을 그리고 <b>에러는 아무 데도 안 난다</b> —
            // 결함이 아니라 의도다: 꺼진 창고의 사진은 꺼낼 수가 없으니 있는 척하지 않는다.
            // 데이터는 안 잃는다(설정을 되돌리면 그대로 복구된다).
            //
            // 그 상태를 아무도 모르는 것이 유일한 문제라 줄을 하나 남긴다.
            // <b>건수로 알람을 걸지 마라</b> — 사진을 올린 회원 수 × 폴링 빈도에 비례해 늘어난다.
            // 한 줄이라도 뜨는 것 자체가 신호다(auth.failed·reuse_detected와 같은 원칙).
            log.warn("auth.profile.photo.unreachable userId={}", user.getId());
            return user.getProfileImageUrl();
        }
        // 사진을 바꾸면 이 값이 달라지고, 그래야 브라우저가 들고 있던 옛 그림을 버린다.
        long version = user.getProfilePhotoUpdatedAt().getEpochSecond();
        String token = PhotoToken.issue(properties.tokenSecret(), user.getId(), version, now);
        // 화면과 서버가 다른 주소에 있어 절대 주소여야 한다.
        return properties.baseUrl() + "/api/profile-photos/" + user.getId() + "?token=" + token;
    }
}
