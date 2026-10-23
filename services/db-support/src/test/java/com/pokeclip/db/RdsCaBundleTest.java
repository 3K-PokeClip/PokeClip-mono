package com.pokeclip.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 넷 이미지에 넣는 RDS 통합 CA 번들({@code db-support/certs})을 잰다(POK-407). iam이면 verify-full이 이것으로 RDS
 * 서버 인증서를 검증한다. 깨진 파일이면 운영에서만 접속이 전부 실패한다.
 *
 * <p>사이드카 번들({@code media/certs}, 1번 관리)을 복사한 쌍둥이다. 두 사본이 갈리면 같은 RDS를 두고 한쪽만 붙는
 * 날이 온다. 그쪽이 갱신되면 이 시험이 빨개져 알려 준다. 고치는 법은 그 파일을 다시 복사하는 것이다.
 */
class RdsCaBundleTest {

    /** 작업 디렉터리는 services/db-support다. */
    private static final Path OURS = Path.of("certs", "rds-global-bundle.pem");
    private static final Path SIDECAR = Path.of("..", "..", "media", "certs", "rds-global-bundle.pem");

    @Test
    void 번들은_읽히는_CA_인증서들이고_서울_리전_루트가_들어_있다() throws Exception {
        Collection<X509Certificate> certs;
        try (InputStream in = Files.newInputStream(OURS)) {
            certs = CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                    .map(X509Certificate.class::cast).toList();
        }

        assertThat(certs).isNotEmpty()
                .allSatisfy(cert -> assertThat(cert.getBasicConstraints()).as("CA 인증서여야 한다").isNotNegative());
        assertThat(certs).anySatisfy(cert ->
                assertThat(cert.getSubjectX500Principal().getName()).contains("ap-northeast-2"));
    }

    @Test
    void 사이드카_번들과_바이트가_같다() throws Exception {
        assertThat(Files.mismatch(OURS, SIDECAR)).as("media/certs 번들과 갈렸다. 그 파일을 다시 복사하라").isEqualTo(-1L);
    }
}
