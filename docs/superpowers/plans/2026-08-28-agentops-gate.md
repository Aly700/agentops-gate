# AgentOps Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The lead owns commits in this repository.

**Goal:** Build a small, production-shaped policy, approval, audit, and export service for AI tool calls.

**Architecture:** A Spring Boot 4.1.1 REST service evaluates calls with a deterministic plain-Java rules engine, persists immutable decisions and append-only audit records in PostgreSQL, and manages approval state transactionally. AWS SDK v2 adapters publish and consume approval messages through SQS and export date-partitioned JSONL audit files to S3; local adapters target LocalStack.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Data JPA, Flyway, PostgreSQL 16, Spring Cloud AWS 3.4.0/AWS SDK v2, JUnit 5, MockMvc, Testcontainers 2.0.5, Maven, Docker Compose, AWS CDK 2.267 with TypeScript.

---

### Task 1: Database schema

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`

- [ ] Create PostgreSQL enum-constrained tables for `policies`, `rules`, `decisions`, `approvals`, and `audit_records` using UUID primary keys and UTC timestamps.
- [ ] Store proposed arguments and audit details as `jsonb`; keep rule precedence unique per policy and decisions immutable by exposing no update path.
- [ ] Add indexes for rule ordering, approval status/expiry, decision timestamps, and audit time-range queries.
- [ ] Run `./mvnw -o -q -DskipTests package`; expect exit 0 while AWS modules remain excluded.

### Task 2: Rules engine, test first

**Files:**
- Create: `src/test/java/dev/affan/agentopsgate/rules/RulesEngineTest.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/Effect.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/RiskTier.java`
- Create: `src/main/java/dev/affan/agentopsgate/rules/RuleDefinition.java`
- Create: `src/main/java/dev/affan/agentopsgate/rules/RuleEvaluation.java`
- Create: `src/main/java/dev/affan/agentopsgate/rules/RulesEngine.java`

- [ ] Write focused tests proving ascending precedence, first match wins, default deny, `fs.*`, `*`, argument regex, agent-id, and risk-tier matching.
- [ ] Run `./mvnw -o -q -Dtest='RulesEngineTest' -Dsurefire.failIfNoSpecifiedTests=false test`; confirm RED because rule types are absent.
- [ ] Implement immutable records/enums and `RulesEngine.evaluate(List<RuleDefinition>, ProposedCall)`; convert glob syntax to an anchored regex with all non-star characters quoted.
- [ ] Run the same command; expect all rules-engine tests to pass.

### Task 3: Persistence and transactional application services

**Files:**
- Create focused entity/repository files under `src/main/java/dev/affan/agentopsgate/domain/`
- Create: `src/main/java/dev/affan/agentopsgate/domain/PolicyService.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/DecisionService.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/ApprovalService.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/AuditService.java`
- Create: `src/test/java/dev/affan/agentopsgate/domain/ApprovalStateMachineTest.java`
- Create: `src/test/java/dev/affan/agentopsgate/domain/PersistenceIntegrationTest.java`

- [ ] Write state-machine tests for pending-to-approved, pending-to-denied, pending-to-expired, and rejection of terminal-state transitions; confirm RED.
- [ ] Implement UUID entities, package-visible constructors, read-only decision fields, `@Version` on approvals, and Spring Data repositories.
- [ ] Implement constructor-injected services with `@Transactional` boundaries; create audit rows in the same transaction as every state change.
- [ ] Have decision evaluation load one policy version and ordered rules, persist the result, create/publish an approval only for `REQUIRE_APPROVAL`, and default to `DENY` when no rule matches.
- [ ] Write PostgreSQL Testcontainers tests for Flyway, persistence, immutability-facing service behavior, rule ordering, and audit writes. The lead runs these with Docker.
- [ ] Run the pure state-machine tests and offline package checkpoint.

### Task 4: REST API, validation, authentication, and problem responses

**Files:**
- Create DTO records and controllers under `src/main/java/dev/affan/agentopsgate/web/`
- Create: `src/main/java/dev/affan/agentopsgate/config/ApiKeyFilter.java`
- Create: `src/main/java/dev/affan/agentopsgate/config/WebConfiguration.java`
- Create: `src/main/java/dev/affan/agentopsgate/web/ApiExceptionHandler.java`
- Create: `src/test/java/dev/affan/agentopsgate/web/ApiKeyFilterTest.java`
- Create: `src/test/java/dev/affan/agentopsgate/web/ApiIntegrationTest.java`

- [ ] Write filter unit tests for correct key, missing key, wrong key, actuator health exemption, and constant-time byte comparison; confirm RED.
- [ ] Implement `OncePerRequestFilter` returning RFC 9457 `application/problem+json` with 401 and no secret reflection.
- [ ] Implement validated records and the specified policy, rule, decision, approval, audit, and demo export endpoints.
- [ ] Map bean-validation failures and malformed JSON to 400 ProblemDetail, not-found to 404, and invalid approval transitions to 409.
- [ ] Write MockMvc + PostgreSQL integration tests covering endpoints, validation, auth, first-match evaluation, approvals, and audit time ranges for the lead.
- [ ] Run pure web unit tests and offline package checkpoint.

### Task 5: Queue, expiry, and export adapters

**Files:**
- Create queue interfaces/adapters under `src/main/java/dev/affan/agentopsgate/sqs/`
- Create export interfaces/adapters under `src/main/java/dev/affan/agentopsgate/export/`
- Create: `src/main/java/dev/affan/agentopsgate/config/AwsConfiguration.java`
- Create: `src/test/java/dev/affan/agentopsgate/sqs/ApprovalQueueIntegrationTest.java`
- Create: `src/test/java/dev/affan/agentopsgate/export/AuditExportIntegrationTest.java`

- [ ] Add actual dependencies for the already-managed SQS/S3 starters and LocalStack Testcontainers module once the three artifacts are warmed.
- [ ] Write adapter contract tests for message JSON, receipt deletion only after success, idempotent terminal approval handling, stale pending expiry, deterministic JSONL order, and `audit/year=YYYY/month=MM/day=DD/audit.jsonl` object keys; confirm RED with warmed dependencies.
- [ ] Implement `SqsClient` publisher and scheduled long-poll consumer with one-message polling, visibility-timeout retry behavior, and deletion after successful processing.
- [ ] Implement the expiry scheduler using a bulk query for stale pending approvals and one transactional transition per approval.
- [ ] Implement `S3Client` export with an injected `Clock`, stable record ordering, one JSON object per line, nightly schedule, and `POST /admin/exports/audit?date=YYYY-MM-DD` demo trigger.
- [ ] Configure a `local` profile with endpoint override, path-style S3 access, dummy static credentials, and `us-east-1`; use default credential/region providers outside `local`.
- [ ] Write LocalStack module tests for queue publication/consumption and S3 content for the lead.
- [ ] Run package and pure tests after the dependency cache is warmed.

### Task 6: Local runtime and delivery automation

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `docker-compose.yml`
- Create: `localstack/init-aws.sh`
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/deploy.yml`

- [ ] Build with a Maven/JDK 21 stage that tries offline first and falls back online, then copy the layered JAR to a non-root Eclipse Temurin 21 JRE runtime.
- [ ] Compose `app`, `postgres:16-alpine`, and `localstack/localstack:3` with health checks, environment-only secrets, service dependencies, SQS/S3 endpoints, and no checked-in credentials beyond LocalStack dummy values.
- [ ] Initialize a DLQ, source queue with redrive policy, and audit bucket idempotently.
- [ ] Add push CI using Java 21, Maven cache, and Docker-backed `./mvnw -q verify`.
- [ ] Add main-branch OIDC deployment using the repository variable `AWS_ROLE_ARN`, ECR login/build/push, npm/CDK install, and `cdk deploy --all --require-approval never`.

### Task 7: AWS CDK infrastructure

**Files:**
- Create all CDK package/config/source files under `infra/`

- [ ] Define separate Network, Data, Queue, Bucket, Service, and Budget stacks with typed cross-stack properties.
- [ ] Use a two-AZ VPC with public subnets and no NAT; single-AZ `db.t4g.micro` PostgreSQL; generated Secrets Manager secret; SQS/DLQ; private versioned S3 bucket with lifecycle expiration; ECS Fargate 0.25 vCPU/0.5 GB and public IP.
- [ ] Scope the task role to queue send/receive/delete/get-attributes, bucket put/get/list for exactly one bucket, and read exactly one secret; inject DB fields from Secrets Manager and non-secret resource identifiers as environment variables.
- [ ] Add an ECR repository, cluster, task/service, port security group, log group, 5xx metric-filter/alarm, and $10 AWS Budget email alarm parameterized by CDK context.
- [ ] Run `cd infra && npx cdk synth`; expect exit 0 offline and inspect synthesized IAM resources for wildcard-resource regressions.

### Task 8: Documentation and final verification

**Files:**
- Replace: `README.md`

- [ ] Document the system, Mermaid architecture, configuration, decisions, local startup, exact curl walkthrough, test commands, deployment, cost posture, and honest demo-safety boundaries.
- [ ] Include a marked placeholder only for the lead-provided exact interview service table, plus a useful provisional service rationale table.
- [ ] Explain the SDK long-poll choice and rejected listener alternative; list Kubernetes, Kafka, OAuth, frontend, multi-region, ALB, and NAT under deliberately left out.
- [ ] Run fresh pure tests, offline package, `docker compose config`, workflow YAML sanity checks, `npx tsc --noEmit`, and `npx cdk synth`.
- [ ] Count test methods written and tests actually executed from Surefire reports; separate Docker-backed tests that the lead must run.
- [ ] Review the specification line by line and report every blocker without claiming unrun Docker behavior.
