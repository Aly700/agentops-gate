# ADR 0004: Run on Fargate instead of Lambda

## Context

The approval worker continuously long-polls SQS. Approval expiry and nightly audit export are schedulers that also expect a resident process. The HTTP API, relay, worker, and schedulers already run together in one Spring Boot image.

## Decision

Run that image as one ECS Fargate task. Use the same image locally in Compose and in Fargate.

## Consequences

- The worker and schedulers keep their in-process timing and lifecycle model.
- Local and AWS runs exercise the same application image.
- The task has an always-on cost even when request volume is zero.
- The demo has one task and no Application Load Balancer, so it does not provide horizontal availability.
- Lambda could suit isolated event handlers, but it would split this resident process into different deployment and scheduling models.
