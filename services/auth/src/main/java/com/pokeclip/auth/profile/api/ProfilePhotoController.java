package com.pokeclip.auth.profile.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.api.dto.MeResponse;
import com.pokeclip.auth.profile.PhotoToken;
import com.pokeclip.auth.profile.PhotoUrls;
import com.pokeclip.auth.profile.ProfilePhotoService;
import com.pokeclip.auth.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 사진은 파일이라 이름 수정({@code PATCH /api/auth/me})과 창구를 나눴다 — 본문 형식이 다르고,
 * 한쪽만 실패했을 때 무엇이 저장됐는지가 응답 하나로는 말해지지 않는다.
 *
 * <p>PUT인 이유: 회원마다 사진이 하나이고 같은 이름에 덮어쓴다. 여러 번 눌러도 결과가 같다.
 *
 * <p><b>주소 두 개가 클래스 하나에 있다.</b> 넣는 곳은 토큰의 주인만 부르는 {@code /api/auth/me/...}이고,
 * 내보내는 곳은 그림 태그가 부르는 공개 경로라 뿌리가 다를 수밖에 없다. 그래서 클래스 수준
 * {@code @RequestMapping}을 두지 않고 메서드마다 전체 주소를 적는다 — 한 기능(사진 in/out)이
 * 한 파일에 있는 편이 다음 사람이 두 쪽을 나란히 보게 한다.
 */
@RestController
@RequiredArgsConstructor
public class ProfilePhotoController {

    private final ProfilePhotoService service;
    private final PhotoUrls photoUrls;

    /** 회원 번호를 받지 않는다 — 토큰의 주인만 자기 것을 고친다. */
    @PutMapping("/api/auth/me/photo")
    public MeResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
        // 올린 직후 응답이 바로 새 주소를 실어야 화면이 한 번 더 묻지 않고 새 그림을 건다.
        User user = service.upload(userId(jwt), file);
        return MeResponse.from(user, photoUrls.of(user, Instant.now()));
    }

    /**
     * 창고를 공개하지 않고 auth가 직접 내보낸다 — 어느 편집자가 어느 스트리머와 일하는지가
     * 주소 하나로 새면 안 되기 때문이다(PRD).
     *
     * <p><b>거절은 전부 404다.</b> 표가 틀렸든 만료됐든 그런 사진이 없든 그런 회원이 없든
     * 여기 오는 것은 같은 빈손이고, 나가는 응답도 <b>글자 그대로 같은 한 줄</b>에서 만들어진다.
     * 갈라 주면 「그 회원이 사진을 올렸는가」가 표 없이도 새어 나간다 —
     * 없는 토큰으로 불러도 204인 로그아웃과 같은 방식이다.
     */
    @GetMapping("/api/profile-photos/{userId}")
    public ResponseEntity<byte[]> photo(@PathVariable long userId,
                                        @RequestParam(required = false) String token) {
        return service.read(userId, token)
                .map(photo -> ResponseEntity.ok()
                        // 올린 쪽이 밝힌 이름표가 아니라 우리가 판정해 넣어 둔 형식이다.
                        .contentType(MediaType.parseMediaType(photo.contentType()))
                        // 이름표 말고 내용으로 추측하지 마라 — 우리가 판정한 형식으로만 해석되게 한다.
                        .header("X-Content-Type-Options", "nosniff")
                        // 표 수명과 맞춘다. private라 중간 캐시가 남의 사진을 들고 있지 않는다.
                        .cacheControl(CacheControl.maxAge(PhotoToken.SLOT_SECONDS, TimeUnit.SECONDS).cachePrivate())
                        .body(photo.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * {@code AuthController.userId}와 같은 모양으로 감싼다 — 한쪽만 감싸면 같은 입력이 한쪽에서는
     * 401이고 다른 쪽에서는 500이 된다. {@code TokenSubjectRejectionTest}가 <b>셋</b>을 나란히 잰다
     * (POK-171이 {@code WithdrawalController.userId}를 감싼 쪽에 더했다).
     *
     * <p>오늘은 닿지 않는다 — 우리 발급기는 {@code sub}에 항상 회원 번호를 넣고 서명 검증을 통과한
     * 토큰만 여기까지 온다. <b>아무도 안 밟기 때문에 더 갈라지기 쉬운 자리다.</b>
     *
     * <p>🔴 <b>이 모양인 것은 열 중 셋뿐이다.</b> auth에서 {@code Long.valueOf(jwt.getSubject())}를
     * 하는 자리를 전수로 세면 <b>열 자리(아홉 파일)</b>이고, 나머지 <b>일곱 자리(여섯 파일)</b>는
     * 아직 안 감쌌다 —
     * {@code ChzzkLinkController:60} · {@code YoutubeLinkController:70} ·
     * <b>{@code StreamKeyController:27}·{@code :38}(한 파일에 두 자리다)</b> ·
     * {@code PairingCodeController:30} · {@code EditorDelegationController:43} ·
     * {@code EditorInvitationController:74}.
     * <b>파일 수로 세지 마라</b> — 이 문장을 처음 쓸 때 여섯 파일을 여섯 자리로 세어 하나를 빠뜨렸다.
     * POK-207이 자기가 만든 창구 둘만, POK-171이 자기가 만든 창구 하나만 맞춘 것이고
     * <b>「쌍둥이를 다 맞췄다」가 아니다.</b> <b>안 감싼 일곱은 그대로다</b> —
     * 새 창구가 늘어도 그 일곱은 안 줄어든다. auth의 「알려진 구멍」 22에 적어 뒀다.
     * {@code sub} 규약을 바꿀 일이 생기면 열을 함께 본다.
     */
    private static Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new AuthException(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID, "토큰의 주체를 읽을 수 없다", e);
        }
    }
}
