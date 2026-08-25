package com.pokeclip.auth.user;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import com.pokeclip.auth.profile.ProfileUpdateException;
import com.pokeclip.auth.profile.ProfileUpdateFailure;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * V101이 {@code google_sub VARCHAR(255) NOT NULL UNIQUE}로 선언해 Postgres가 붙인 기본
     * 이름이다(실물 확인: {@code pg_constraint}에 {@code users_google_sub_key}).
     * <b>마이그레이션에서 이 제약 이름을 바꾸면 여기도 같이 바꿔야 한다 — V101과 쌍이다.</b>
     * ({@code uq_users_email}이 V108과 쌍인 것과 같다.)
     */
    private static final String GOOGLE_SUB_CONSTRAINT = "users_google_sub_key";

    /**
     * 코드 포인트로 센다 — 표(VARCHAR(255))가 세는 단위와 같아야 「검증 통과 → 저장 거부」가 안 생긴다.
     * String.length()로 세면 이모지 하나가 2로 잡혀 30자 상한이 사람마다 다른 뜻이 된다.
     */
    public static final int NAME_MAX_CODE_POINTS = 30;

    private final UserRepository userRepository;
    private final UserCreator userCreator;

    /**
     * 조회 후 없으면 생성한다. 두 요청이 동시에 들어오면 둘 다 "없다"를 보고
     * 둘 다 만들려 한다. google_sub의 UNIQUE 제약이 하나를 거부하고, 거부당한
     * 쪽은 상대가 만든 행을 다시 읽는다.
     *
     * 이 메서드에 @Transactional을 걸지 않는다. 걸면 재조회가 생성 실패와 같은
     * 트랜잭션에 묶여, 오염된 세션에서 쿼리를 돌리게 된다.
     *
     * 두 번째 로그인에서 프로필(email·name·picture)을 갱신하지 않는다. 그래서
     * users.updated_at은 created_at과 같은 값으로 남는다. 프로필 동기화는 별도
     * 요구가 서면 그때 넣는다.
     */
    public User findOrCreate(String googleSub, String email, String name, String profileImageUrl) {
        return userRepository.findByGoogleSub(googleSub)
                .orElseGet(() -> createOrRead(googleSub, email, name, profileImageUrl));
    }

    private User createOrRead(String googleSub, String email, String name, String profileImageUrl) {
        try {
            // 경합에 져서 남의 행을 다시 읽은 경우는 우리가 만든 것이 아니다.
            // 그래서 성공한 경로에서만 찍는다.
            User created = userCreator.create(googleSub, email, name, profileImageUrl);
            log.info("auth.user.created userId={}", created.getId());
            return created;
        } catch (DataIntegrityViolationException e) {
            return recoverOrFail(googleSub, e);
        }
    }

    /**
     * 어느 제약에 걸렸는지 갈라낸다(DelegationExceptionHandler와 같은 방식).
     * 잡는 자리가 create 트랜잭션 <b>밖</b>이라 여기서 재조회를 해도 된다 —
     * UserCreator 주석이 경고한 오염된 세션 문제를 피하는 조건이 이것이다.
     *
     * <p><b>uq_users_email은 재조회로 회수되지 않는다.</b> 새 sub + 기존 email 조합이라
     * findByGoogleSub이 빈손이고, 그대로 흘리면 예외 메시지에 이메일이 평문으로 실려
     * 나간다(authz-auditor 라운드 1 중대 1). 사유 코드만 남기고 이메일이 없는 예외로 바꾼다.
     * <b>원인을 달지 않는다</b> — 체인에 남으면 스택트레이스를 찍는 곳 하나로 다시 샌다.
     *
     * <p>Hibernate가 던지기 전에 찍는 WARN은 이 catch로 안 막힌다.
     * application.yml에서 org.hibernate.orm.jdbc.error를 낮춰 같이 닫았다.
     *
     * <p>모르는 제약은 다시 던진다. 조용히 삼키면 진짜 버그가 정상 응답에 숨는다.
     */
    private User recoverOrFail(String googleSub, DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (cause.contains("uq_users_email")) {
            throw new AuthException(AuthFailure.EMAIL_ALREADY_REGISTERED, "이미 가입된 이메일이다");
        }
        if (cause.contains(GOOGLE_SUB_CONSTRAINT)) {
            // 동시 요청이 먼저 만들었다. 위 트랜잭션은 롤백됐으니 새로 읽는다.
            return userRepository.findByGoogleSub(googleSub).orElseThrow(() -> e);
        }
        throw e;
    }

    /**
     * 표시 이름을 바꾼다. <b>findOrCreate는 건드리지 않는다</b> — 재로그인이 프로필을 안 덮는 성질이
     * 이미 참이고(POK-53), 이 카드는 그것을 규칙으로 굳히기만 한다(ProfileSurvivesReloginTest).
     *
     * <p>같은 회원의 동시 수정을 직렬화하지 않는다. 토큰 회전·스트림키 재발급이 users 행 락을 쓰는
     * 이유는 <b>아직 없는 행</b>을 막기 위해서인데, 이름은 한 컬럼을 덮어쓰는 것이라 마지막 요청이
     * 이기면 그만이다. 락을 얹으면 그 경로들의 대기만 늘어난다.
     */
    @Transactional
    public User updateName(Long userId, String rawName) {
        String trimmed = stripInvisible(rawName);
        if (trimmed.isEmpty()) {
            throw new ProfileUpdateException(ProfileUpdateFailure.NAME_BLANK, "이름이 비어 있다");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > NAME_MAX_CODE_POINTS) {
            throw new ProfileUpdateException(ProfileUpdateFailure.NAME_TOO_LONG, "이름이 너무 길다");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));
        user.changeName(trimmed, Instant.now());
        return user;
    }

    /**
     * 앞뒤의 <b>보이지 않는 문자</b>를 자른다.
     *
     * <p>🔴 <b>{@code trim()}으로는 부족했다</b>(PR #127 codex, 실측). 그것은 U+0020 이하만
     * 자르므로 전각 공백(U+3000)·NBSP(U+00A0)·EM SPACE(U+2003)·ZWSP(U+200B)로만 이루어진
     * 이름이 <b>그대로 저장됐다</b> — 화면에는 이름이 없는 것처럼 보이고, 편집자 목록이
     * 누가 누군지 말해 주지 못한다. 창구를 직접 부르는 쪽에서만 닿지만 막을 자리는 여기다.
     *
     * <p><b>{@code strip()}으로 바꾸는 것도 답이 아니다</b> — U+3000·U+2003은 잡지만
     * U+00A0·U+200B는 {@code Character.isWhitespace}가 false라 그대로 남는다.
     * 그래서 공백 분류({@code SPACE_SEPARATOR})와 형식 문자({@code FORMAT})까지 본다.
     *
     * <p><b>자르는 것은 앞뒤뿐이다.</b> 가운데까지 접으면 "김 태현"이 "김태현"이 되고,
     * 이모지를 잇는 ZWJ(U+200D, FORMAT)가 사라져 <b>이모지 이름이 깨진다</b> —
     * 그 문자는 항상 글자 사이에 오므로 앞뒤만 자르면 안전하다.
     */
    private static String stripInvisible(String raw) {
        if (raw == null) {
            return "";
        }
        int[] cps = raw.codePoints().toArray();
        int start = 0;
        int end = cps.length;
        while (start < end && isInvisible(cps[start])) {
            start++;
        }
        while (end > start && isInvisible(cps[end - 1])) {
            end--;
        }
        return new String(cps, start, end - start);
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || type == Character.SPACE_SEPARATOR
                || type == Character.FORMAT
                || type == Character.CONTROL;
    }
}
