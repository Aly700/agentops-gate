# ADR 0002: Use AWS SDK v2 directly

## Context

Spring Cloud AWS 3.4 reaches its S3 auto-configuration under Spring Boot 4.1, then fails at startup with `NoSuchMethodError` for `PropertyMapper$Source.whenNonNull()`. It was compiled against a Spring Boot 3 API that Boot 4 removed.

The application needs SQS publishing and consumption, S3 writes, LocalStack endpoint overrides, and task-role credentials in AWS.

## Decision

Remove Spring Cloud AWS and build `SqsClient` and `S3Client` beans with AWS SDK v2. Use a scheduled SQS long-poll worker instead of `@SqsListener`. Configuration owns the region, optional endpoint, local static credentials, and S3 path-style access.

## Consequences

- Startup no longer depends on the incompatible Spring Cloud AWS auto-configuration.
- AWS client behavior and LocalStack differences are explicit in application configuration.
- The application owns polling, message deletion, retry behavior, and scheduling.
- There is one fewer abstraction between the transport code and AWS SDK responses.
