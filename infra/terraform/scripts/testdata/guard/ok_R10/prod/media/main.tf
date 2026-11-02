# R10 을 지키는 꼴: 루트도 SSM Core 밖의 관리형 정책을 붙이지 않는다.
module "media_host" {
  source = "../../modules/media_host"

  rds_resource_id = "db-EXAMPLE"
}
