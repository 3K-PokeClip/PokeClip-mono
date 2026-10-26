# auth · clip · chat 작업 역할 정책 — 코드가 실제로 부르는 AWS 동작만(결정
# 135). 대상 자원은 런타임 1단이 만들고 ARN 을 app_task_resource_arns(chat 큐는
# broadcast_queue_arns)로 낸다. 키가 없으면(기능 꺼짐) 그 문장을 만들지 않는다.
# 근거(파일:행)는 README 「작업 역할」.
#
# 동작을 바꾸는 코드 변경(새 AWS 호출)은 이 표도 함께 고친다 — 빠지면 그
# 기능이 운영에서 권한 거부로 실패한다.

locals {
  auth_arns = local.task_resource_arns.auth
  clip_arns = local.task_resource_arns.clip

  # 채팅 원본 보관 창고(런타임 출력 — collector 의 S3_BUCKET 과 같은 버킷).
  chat_archive_bucket_arn = try(local.runtime.chat_archive_bucket_arn, "")

  # 스트림 키 비밀 이름 = 접두 + UUID(auth 가 만든다) — 접두 와일드카드만 쓴다.
  stream_key_secret_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:${var.stream_key_secret_prefix}*"

  db_user_arns = {
    for service, user in local.db_users :
    service => "arn:aws:rds-db:${local.region}:${local.account_id}:dbuser:${local.runtime.rds_resource_id}/${user}"
  }

  # 서비스마다 후보 문장. resources 가 빈 문장은 아래에서 뺀다.
  task_role_candidates = {
    auth = [
      {
        sid       = "StreamKeySecrets"
        actions   = ["secretsmanager:CreateSecret", "secretsmanager:DeleteSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        resources = [local.stream_key_secret_arn]
      },
      {
        sid       = "ProfilePhotoObjects"
        actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        resources = [for arn in compact([try(local.auth_arns.profile_photo_bucket_arn, "")]) : "${arn}/*"]
      },
    ]
    clip = [
      {
        sid       = "BroadcastIntake"
        actions   = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = compact([try(local.clip_arns.broadcast_queue_arn, "")])
      },
      {
        sid     = "OrderQueues"
        actions = ["sqs:SendMessage"]
        resources = sort(compact([
          try(local.clip_arns.render_queue_arn, ""),
          try(local.clip_arns.upload_queue_arn, ""),
          try(local.clip_arns.thumbnail_queue_arn, ""),
        ]))
      },
      {
        # 일꾼이 포기한 주문을 DLQ 에서 읽어 정리한다(DlqReconciler).
        sid       = "DeadLetterQueues"
        actions   = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = sort(compact([try(local.clip_arns.render_dlq_arn, ""), try(local.clip_arns.upload_dlq_arn, "")]))
      },
      {
        # GetObject 는 서명 URL(파일 받기 · 썸네일)이 이 역할 자격으로 서명되기 때문이다.
        sid       = "ClipObjects"
        actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        resources = [for arn in compact([try(local.clip_arns.clips_bucket_arn, "")]) : "${arn}/*"]
      },
      {
        sid       = "ClipList"
        actions   = ["s3:ListBucket"]
        resources = compact([try(local.clip_arns.clips_bucket_arn, "")])
      },
      {
        # 탈퇴 정리가 회원의 녹화 조각을 지운다(S3PurgeStorage).
        sid       = "SegmentDelete"
        actions   = ["s3:DeleteObject"]
        resources = [for arn in compact([try(local.clip_arns.segment_bucket_arn, "")]) : "${arn}/*"]
      },
      {
        # 트랙별 미리 듣기의 조각 서명 URL(SegmentAudioSigner)이 이 역할 자격으로 서명된다(POK-389).
        sid       = "SegmentRead"
        actions   = ["s3:GetObject"]
        resources = [for arn in compact([try(local.clip_arns.segment_bucket_arn, "")]) : "${arn}/*"]
      },
    ]
    "chat-collector" = [
      {
        # 방송 편지 받기 — 부팅 때 가시성 시한을 한 번 읽는다(GetQueueAttributes).
        sid       = "ChatBroadcastIntake"
        actions   = ["sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ReceiveMessage"]
        resources = compact([try(local.runtime.broadcast_queue_arns.chat, "")])
      },
      {
        # 채팅 원본 1분 파일 올리기 · 탈퇴 정리(DeleteObjects 는 객체마다
        # s3:DeleteObject 로 인가된다). 키는 모두 chat/ 아래다(ArchiveKey).
        sid       = "ChatArchiveObjects"
        actions   = ["s3:DeleteObject", "s3:PutObject"]
        resources = [for arn in compact([local.chat_archive_bucket_arn]) : "${arn}/chat/*"]
      },
      {
        # 탈퇴 정리가 채널 접두(chat/<플랫폼>/<채널>/)를 훑는다. ecs_service 의
        # 문장에 조건(s3:prefix)이 없어 버킷 전체 목록이다 — 창고는 chat 전용.
        sid       = "ChatArchiveList"
        actions   = ["s3:ListBucket"]
        resources = compact([local.chat_archive_bucket_arn])
      },
    ]
    "chat-detector" = []
  }

  db_connect_statements = {
    for service, arn in local.db_user_arns : service => {
      sid       = "DbConnect"
      actions   = ["rds-db:connect"]
      resources = var.app_db_auth == "iam" ? [arn] : []
    }
  }

  task_role_statements = merge(
    { next = [] },
    {
      for service, candidates in local.task_role_candidates : service => [
        for statement in concat(candidates, [local.db_connect_statements[service]]) :
        statement if length(statement.resources) > 0
      ]
    },
  )
}
