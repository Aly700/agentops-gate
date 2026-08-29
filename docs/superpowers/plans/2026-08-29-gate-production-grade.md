# AgentOps Gate — Production Grade Implementation Plan

> **For agentic workers:** Each task below is a whole problem dispatched to
> Codex (offline Maven, no Docker, no network); the lead runs every
> Testcontainers/Compose/AWS gate and commits. Steps use checkbox syntax.

**Goal:** Turn the working AgentOps Gate service into a production-grade,
evidence-backed system: exactly-once plumbing, deterministic simulation
testing, property tests, live load and chaos numbers, and a case-study
README.

**Architecture:** Keep the existing Spring Boot 4.1 / Postgres / AWS SDK v2
service. Add a transactional outbox and idempotent consumer around the
existing decision/approval flow; add a simulation harness that drives the
real domain services through in-memory ports; capture live evidence on the
deployed Fargate task; document decisions as ADRs.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Data JPA, Flyway,
PostgreSQL 16, AWS SDK v2 2.31 (sqs, s3), Testcontainers 2.x (postgres,
localstack), jqwik, k6, CDK 2.267, CloudWatch.

**Spec:** `docs/superpowers/specs/2026-08-29-production-grade-design.md`

## Global Constraints

- Nothing from any employer; no real names or data; no secrets in files.
- Every README claim must run; cut features are listed as cut.
- Cost ceiling $10/month; Budgets alarm exists; destroy after capture.
- Repo stays local until the owner says publish.
- Maven runs offline for Codex (`./mvnw -o`); new artifacts must be warmed
  by the lead first (jqwik is the only planned addition).
- Existing 48 tests stay green; every task adds tests and ends with
  `./mvnw -o -q -DskipTests package` green and the pure unit tests green.

---

### Task 1: Idempotency keys on POST /decisions

**Files:**
- Create: `src/main/resources/db/migration/V2__idempotency.sql`
- Create: `src/main/java/dev/affan/agentopsgate/web/IdempotencyKeyFilter.java`
- Create: `src/main/java/dev/affan/agentopsgate/domain/IdempotencyRecord.java`,
  `IdempotencyRecordRepository.java`, `IdempotencyService.java`
- Modify: `src/main/java/dev/affan/agentopsgate/web/DecisionController.java`
- Test: `src/test/java/dev/affan/agentopsgate/web/IdempotencyIntegrationTest.java`

**Interfaces:**
- Consumes: `DecisionService.evaluate(EvaluateDecisionCommand)`.
- Produces: `IdempotencyService.execute(String key, String requestHash,
  Supplier<StoredResponse>)` returning the stored response on replay; a
  `409 Conflict` ProblemDetail when the same key arrives with a different
  body hash; `idempotency_records(key PK, request_hash, status_code,
  response_body jsonb, created_at)` with a 24h TTL sweep.

- [ ] Schema, entity, service; SHA-256 of the canonical JSON body as the hash.
- [ ] Tests (lead runs with Docker): replay returns identical body and 200;
      different body → 409; concurrent duplicate requests create one
      decision (use a latch); key older than TTL is accepted as new.
- [ ] Commit: `feat: idempotency keys on POST /decisions`.

### Task 2: Transactional outbox for approval messages

**Files:**
- Create: `V3__outbox.sql` (`outbox_messages(id, aggregate_type,
  aggregate_id, payload jsonb, created_at, sent_at, attempts, last_error)`)
- Create: `sqs/OutboxMessage.java`, `OutboxRepository.java`,
  `OutboxRelay.java` (@Scheduled, batch of 50, FOR UPDATE SKIP LOCKED,
  publishes via the existing `ApprovalQueuePublisher`, marks `sent_at`)
- Modify: `domain/DecisionService.java` — write the outbox row in the same
  transaction as the decision + approval instead of publishing directly.
- Test: `sqs/OutboxRelayTest.java` (unit, fake publisher),
  `sqs/OutboxIntegrationTest.java` (LocalStack: crash between commit and
  publish is simulated by a publisher that throws once; the relay retries
  and the message arrives exactly once on the queue).

**Interfaces:**
- Produces: `OutboxRelay.relayOnce(): int` (rows sent); metrics counter
  `gate.outbox.sent` / `gate.outbox.failed`.

- [ ] Implement; keep the old direct-publish path deleted, not flagged.
- [ ] Tests; commit: `feat: transactional outbox for approval messages`.

### Task 3: Idempotent consumer and DLQ replay

**Files:**
- Create: `V4__processed_messages.sql` (`processed_messages(message_id PK,
  processed_at)`)
- Modify: `sqs/ApprovalQueueWorker.java` — insert into processed_messages
  in the same transaction as the state change; duplicate message id → skip
  and delete.
- Create: `sqs/DlqReplayService.java` + `web/AdminController.java`
  endpoint `POST /admin/dlq/replay` (API-key protected) that moves up to N
  messages from the DLQ back to the main queue and writes an audit row
  `DLQ_REPLAYED`.
- Test: `sqs/IdempotentConsumerIntegrationTest.java` (deliver the same
  message twice → one state change, one audit row), `sqs/DlqReplayTest.java`.

- [ ] Implement; commit: `feat: idempotent consumer and dlq replay`.

### Task 4: Deterministic simulation harness

**Files:**
- Create: `src/test/java/dev/affan/agentopsgate/sim/` — `Simulator.java`
  (seeded `SplittableRandom`, virtual clock, event queue),
  `FaultInjectingBus.java` (duplicate p, reorder p, delay p, drop-then-
  redeliver p, crash-before-commit p, crash-after-commit p),
  `InMemoryStores.java` (implementations of the repository ports used by
  the services), `Invariants.java`, `SimulationTest.java` (runs seeds
  1..N from `-Dsim.seeds`, default 200 in CI, 2000 nightly), `Trace.java`
  (prints the minimal event list for a failing seed).
- Modify (only if needed): introduce port interfaces so services can run
  against in-memory stores without Spring — keep production wiring unchanged.

**Invariants (all checked after every step):**
1. Every REQUIRE_APPROVAL decision has exactly one approval.
2. Every approval terminal state ∈ {APPROVED, DENIED, EXPIRED} once the
   simulation quiesces.
3. Idempotent replay of a decision request never creates a second decision.
4. Audit log is append-only and totally ordered per aggregate.
5. Every outbox row is eventually sent exactly once (no duplicate effects
   even if published twice).

- [ ] Harness + invariants; fail with seed + trace; commit:
      `test: deterministic simulation harness with fault injection`.

### Task 5: Property-based tests (jqwik)

**Files:**
- Modify: `pom.xml` (jqwik, test scope — lead warms it first)
- Create: `rules/RulesEngineProperties.java`: for generated policies,
  first-match-wins equals the reference evaluation; default deny holds when
  no rule matches; adding a lower-precedence rule never changes a decision
  already matched by a higher one; glob matcher equals the reference regex
  translation.

- [ ] Commit: `test: property-based rules engine invariants`.

### Task 6: Observability

**Files:**
- Modify: `pom.xml` (micrometer-registry-cloudwatch2 — lead warms),
  `application.yml` (Micrometer CloudWatch export namespace AgentOpsGate,
  disabled in `local`/test), `infra/lib/service-stack.ts` (alarm on
  `DlqDepth` via SQS metric `ApproximateNumberOfMessagesVisible` on the DLQ;
  a CloudWatch dashboard with p99 latency, 5xx, outbox backlog, DLQ depth).
- Create: `docs/runbook.md` (alarm → what to check → replay command).

- [ ] `npx cdk synth` green; commit: `feat: metrics, dlq alarm, dashboard, runbook`.

### Task 6b: Performance on 0.25 vCPU (evidence-driven)

Baseline measured 2026-08-29 against the live task with `load/decisions.js`
(ramp 5→50 rps, 80 s): 0 errors, ~21 rps sustained, latency median 3.1 s,
p99 6.8 s, min 106 ms; ECS CPUUtilization 100% throughout, RDS CPU 5%,
10 DB connections. The task is CPU-bound in the JVM; the database is idle.

**Files:**
- Modify: `Dockerfile` (JVM flags: `-XX:+UseSerialGC -XX:TieredStopAtLevel=1
  -Xshare:auto -XX:MaxRAMPercentage=75`), `application.properties`
  (Hikari `maximum-pool-size=5`, `minimum-idle=1`; structured console
  logging kept but per-request logging filter reduced to WARN under a
  `perf` profile flag), `domain/PolicyService` or a new `rules/PolicyCache`
  (Caffeine is NOT in the cache — use a `ConcurrentHashMap` keyed by
  policy id+version, invalidated on rule creation; policies are immutable
  once evaluated), `domain/DecisionService` (one INSERT for the decision,
  one for the audit row; no re-reads; no N+1 on rules).
- Test: existing suites stay green; add `rules/PolicyCacheTest`.

**Gate (lead):** rerun the same k6 script on the same task size; record
before/after in `docs/evidence/`; then one run at 0.5 vCPU / 1 GB to show
the cost/latency trade (`cdk deploy -c taskCpu=512 -c taskMemory=1024` —
add these context knobs to the service stack with 256/512 defaults).

- [ ] Commit: `perf: jvm flags for small cpu, pool sizing, policy cache`.

### Task 7: Live capture (lead)

- [ ] Deploy with the new image; run the smoke walkthrough against the
      public IP; k6 script `load/decisions.js` (60 s ramp to 50 rps);
      record p50/p99/rps and task CPU/memory; chaos: stop the task while
      messages are in flight, poison a message (invalid JSON) into the
      queue, watch it reach the DLQ after 5 receives, replay it; capture
      CloudWatch dashboard, DLQ, and S3 listing screenshots into
      `docs/evidence/`.
- [ ] `cdk destroy --all`; confirm zero resources; commit evidence.

### Task 8: Case-study README, ADRs, diagram, GIF

- [ ] `docs/adr/0001-spring-boot-4.md`, `0002-aws-sdk-v2.md`,
      `0003-transactional-outbox.md`, `0004-fargate-over-lambda.md`,
      `0005-no-nat-networking.md` (Context / Decision / Consequences).
- [ ] README rewrite: problem → architecture (diagram) → invariants and how
      they are proven → numbers → operations → cost → what was cut.
- [ ] 90-second demo GIF recorded by the lead from the Compose walkthrough +
      console; commit: `docs: case study readme, adrs, evidence`.

## Self-review

- Spec coverage: idempotency (T1), outbox (T2), consumer/DLQ (T3), DST
  (T4), jqwik (T5), observability (T6), live proof (T7), presentation (T8).
  Teller has its own plan after Gate lands.
- Type consistency: `OutboxRelay.relayOnce`, `IdempotencyService.execute`,
  `DlqReplayService` names are used consistently above.
