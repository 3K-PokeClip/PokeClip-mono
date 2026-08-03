package com.pokeclip.auth.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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
            // 동시 요청이 먼저 만들었다. 위 트랜잭션은 롤백됐으니 여기서 새로 읽는다.
            return userRepository.findByGoogleSub(googleSub)
                    .orElseThrow(() -> e);
        }
    }
}
