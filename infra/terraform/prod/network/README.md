# prod/network

prod 전용 VPC 를 만드는 루트입니다(설계 3-1 의 ④). 런타임 1단(RDS) ·
platform · media · edge 가 이 루트의 출력을 씁니다.

*   AZ 두 개에 퍼블릭 · 프라이빗 서브넷을 하나씩 둡니다. VPC CIDR 을 네
    등분해 앞 둘은 퍼블릭, 뒤 둘은 프라이빗(`azs` 순서)에 줍니다.
*   NAT 게이트웨이는 `nat_az` 에 하나만 둡니다. 프라이빗 라우트 테이블은 AZ
    마다 두되 모두 그 NAT 로 나갑니다.
*   S3 Gateway 엔드포인트를 프라이빗 라우트 테이블에만 붙입니다.
*   피어링 · TGW 는 없습니다 — dev VPC 와 SG 참조가 생길 수 없습니다(설계
    2-3 「네트워크」).
*   VPC 기본 SG 는 다루지 않습니다. 기본 SG 는 같은 SG 끼리 전부 허용하므로,
    이 VPC 를 쓰는 후속 루트의 모든 자원은 전용 SG 를 지정하고 기본 SG 를
    쓰지 않습니다([런타임 트랙 계약](/infra/terraform/CONTRACT-runtime.md)
    「SG 규칙 규약」).

자원 Name 태그는 `pokeclip-prod-<용도>` 입니다(계획 3절 「한 계정 규약」).
provider 의 `default_tags` 네 키(`TerraformRoot = prod/network`)는 태그를
받는 자원(`tags_all` 이 있는 자원)에 붙습니다. mock 시험은 태그를 보지
못하므로 plan 의 `tags_all` 로 확인합니다.

## 값과 적용

`cidr` · `azs` · `media_az` · `nat_az` 는 기본값이 없습니다.
`terraform.tfvars.example` 을 `terraform.tfvars`(추적 안 함)로 복사해 채웁니다.
`cidr` 는 `/16` 부터 `/25` 까지의 네트워크 주소입니다 — 네 등분한 서브넷이
ALB 서브넷 최소인 `/27` 이상이 되게 합니다. dev 상자 VPC 와 겹치지 않게
고르고, O7a 선행 0(단계 직전 점검)에서 dev VPC CIDR 을 읽어 확인합니다.
적용은 O7a 의 1(kty 승인)입니다.

백엔드는 `backend.hcl.example` 을 `backend.hcl` 로 복사하고 `bucket`
자리표시자 `<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로 고친 뒤
`init -backend-config=backend.hcl` 로 엽니다(운영 단계).

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `vpc_id` · `private_subnet_ids` | 런타임 1단 — RDS 서브넷 그룹 · RDS SG |
| `vpc_id` · `public_subnet_ids` · `private_subnet_ids` | platform — 공개 ALB · 내부 ALB · ECS |
| `media_az` · `media_public_subnet_id` | platform — NLB |
| `media_private_subnet_id` · `s3_prefix_list_id` | media — Media 상자 · SG 이그레스 |
| `vpc_id` | edge — 서울 별칭 data source |
| `vpc_cidr` | 참고용 |
