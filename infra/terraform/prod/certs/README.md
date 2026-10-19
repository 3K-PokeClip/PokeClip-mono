# prod/certs

prod 인증서 두 벌을 만드는 루트입니다(설계 3-1 의 ⑥). 둘 다
`pokeclip.com` + `*.pokeclip.com` 이고, 서울 것은 ALB(platform), us-east-1
것은 CloudFront(edge)가 씁니다. us-east-1 은 provider 별칭 `aws.use1` 입니다.

## 검증 레코드

*   공개 존 `pokeclip.com` 은 `nonprod/dns-legacy` 가 소유합니다. 이 루트는
    존을 이름으로 조회합니다(`data "aws_route53_zone"` · 공개 존). 같은 이름의
    공개 존이 둘이면 조회가 실패합니다.
*   검증 레코드는 그 존 안의 별도 `aws_route53_record` 라 dns-legacy 의
    레코드를 건드리지 않습니다. 인증서 · 검증 레코드 · 검증 대기가 이 루트의
    한 apply 에 묶입니다.
*   같은 계정 · 같은 도메인의 인증서는 리전이 달라도 같은 검증 레코드를
    씁니다. 그래서 두 인증서의 검증 옵션을 정규화한 도메인 이름(`*.` 를 뗀
    이름) 키로 합쳐 레코드를 **한 벌만** 만들고, 두 검증 대기 자원이 그
    레코드를 씁니다.
*   같은 키로 모인 원소의 이름 · 유형 · 값이 하나라도 다르면 레코드의
    precondition 이 apply 를 멈춥니다(값은 인증서가 만들어진 뒤에 알려지므로
    첫 apply 때 평가됩니다).
*   `allow_overwrite` 는 쓰지 않습니다 — 공유 존의 기존 레코드를 덮는 길이
    되기 때문입니다. 같은 이름의 레코드가 이미 있으면 apply 가 「이미 있음」
    으로 멈춥니다. 그래서 O7b 선행 0 에서 공유 존에 `_` 로 시작하는 CNAME(옛
    ACM 검증 이름)이 있는지 먼저 읽어 봅니다.

provider 두 개(서울 · `aws.use1`) 모두 `default_tags` 네 키
(`TerraformRoot = prod/certs`)를 둡니다. 태그를 받는 자원(`tags_all` 이 있는
자원 — 인증서 두 벌)에 붙고, mock 시험은 태그를 보지 못하므로 plan 의
`tags_all` 로 확인합니다.

## 적용

`terraform.tfvars` 가 필요 없습니다(도메인은 고정). 적용은 O7b 의
1(kty 승인)입니다. 백엔드는 `backend.hcl.example` 을 `backend.hcl` 로 복사하고
`bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로
고친 뒤 `init -backend-config=backend.hcl` 로 엽니다(운영 단계).

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다. 검증 대기 자원을
거친 값이라 발급이 끝난 인증서만 넘어갑니다.

| 출력 | 쓰는 곳 |
|---|---|
| `cert_arn_apne2` | platform — 공개 ALB · 내부 ALB 리스너 |
| `cert_arn_use1` | edge — apex · media CloudFront 배포 |
