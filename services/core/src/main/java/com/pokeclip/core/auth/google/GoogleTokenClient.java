package com.pokeclip.core.auth.google;

import com.pokeclip.core.auth.AuthException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class GoogleTokenClient {

    private final RestClient restClient;
    private final GoogleAuthProperties properties;

    public GoogleTokenClient(RestClient.Builder builder, GoogleAuthProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    public String exchangeCodeForIdToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("grant_type", "authorization_code");

        // Map으로 받는다. ParameterizedTypeReference는 생성자에서 자기 제네릭
        // 슈퍼타입을 리플렉션으로 읽는데, diamond(<>)로 만든 익명 클래스는 그
        // 타입이 기록된다는 보장이 없다. Map이면 리플렉션에 의존하지 않는다.
        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            // RestClient는 4xx·5xx에서 RestClientResponseException을 던진다.
            // 그것이 RestClientException의 하위 타입이라 여기서 잡힌다.
            throw new AuthException("구글 토큰 교환 실패", e);
        }

        if (response == null || !(response.get("id_token") instanceof String idToken)) {
            throw new AuthException("구글 응답에 id_token이 없다");
        }
        return idToken;
    }
}
