# Design: AgentOps Gate to production grade, and Teller

Owner goal: pass resume screens, get OAs and interviews at banks/fintech and at
big tech. Recruiters scan for stack keywords and one link that looks shipped;
engineers spend five minutes on the README looking for architecture, numbers,
tests, failure handling, and a live demo. Both projects are built to survive
that five minutes.

## Project 1 — AgentOps Gate (this repo)

A policy and approval service for AI tool calls. Java 21, Spring Boot 4.1,
PostgreSQL 16, Flyway, AWS SDK v2 (SQS, S3), ECS Fargate, RDS, Secrets
Manager, CloudWatch, CDK (TypeScript), GitHub Actions with OIDC. Status at
design time: domain, rules engine, API, approvals state machine, audit, SQS
worker, S3 export, Compose, CI, seven CDK stacks; 48 tests green; first live
deploy to account REDACTED-ACCOUNT in progress.

### What "production grade" adds

1. Exactly-once plumbing
   - `Idempotency-Key` on `POST /decisions`: same key + same body returns the
     stored response; same key + different body returns 409.
   - Transactional outbox: the approval message row is written in the same
     transaction as the decision; a relay publishes to SQS and marks rows
     sent; publishing is at-least-once by construction.
   - Idempotent consumer: a `processed_messages` table keyed by message id
     makes duplicate deliveries no-ops; DLQ after five receives; a replay
     tool moves DLQ messages back with an audit row.
2. Deterministic simulation testing (DST)
   - A seeded simulator drives the real domain services against an in-memory
     store and a fault-injecting bus and clock: crash before/after commit,
     duplicate, reorder, delay, drop-then-redeliver, clock jumps.
   - Invariants checked after every step: every REQUIRE_APPROVAL decision has
     exactly one approval; every approval ends APPROVED, DENIED or EXPIRED;
     an idempotent replay never creates a second decision; the audit log is
     append-only and totally ordered per aggregate; outbox rows are eventually
     all sent.
   - CI runs a fixed seed range; any failure prints the seed and the minimal
     event trace; `--seed` reproduces it exactly.
3. Property-based tests (jqwik) for the rules engine: first-match-wins,
   default deny, precedence monotonicity, glob/regex matcher algebra.
4. Live proof on AWS
   - k6 load test against the Fargate task: p50/p99 for `POST /decisions`
     and sustained rps on 0.25 vCPU / 0.5 GB, with one tuning note.
   - Chaos: kill the task mid-batch, poison a message into the DLQ, replay
     it; screenshots of CloudWatch and the DLQ in the README.
5. Operations: OpenTelemetry traces to CloudWatch (X-Ray exporter or OTLP
   via the CloudWatch agent sidecar is out; use the AWS Distro for OTel
   Java agent only if it fits the task memory, else structured logs +
   Micrometer metrics to CloudWatch), one dashboard, alarms on 5xx rate and
   DLQ depth, a runbook.
6. Presentation: README as a case study, architecture diagram, five ADRs
   (Boot 4 over 3; SDK v2 over Spring Cloud AWS; outbox over direct publish;
   Fargate over Lambda; no-NAT networking), 90-second demo GIF, cost table
   from the pricing calculator, `cdk destroy` to zero after capture.

### Out of scope (stays cut)

Kubernetes, Kafka, OAuth, multi-region, ALB, NAT. Frontend for Gate (the
approvals console lives in Teller).

## Project 2 — Teller (new repo, built after Gate)

A payments core with a policy gate. Same stack. Reuses Gate's rules engine
as its policy module, extended with money-aware matchers.

### Domain

- Account: id, currency, ledger balance, available balance, status.
- Transfer: idempotency key, from, to, amount, currency, state machine
  `PENDING -> AUTHORIZED | HELD -> POSTED | REVERSED`, reason codes.
- Entry: double-entry rows; every posted transfer produces balanced
  debit/credit entries; balances are derived and cached with optimistic
  locking.
- Policy: rules with matchers for amount limits, velocity (n per window),
  counterparty allow/deny lists, four-eyes threshold, currency; effects
  ALLOW / DENY / REQUIRE_APPROVAL; first match wins; default deny.
- Approval: as in Gate, over SQS; expiry reverses the hold.
- Audit and nightly export: as in Gate, plus a reconciliation job that
  proves the S3 export sums to the ledger.

### Proof layer

DST as in Gate with money invariants: conservation (sum of all entries is
zero per currency), no negative available balance without an overdraft rule,
every HELD transfer ends POSTED or REVERSED, idempotent replays are no-ops
under any interleaving of crashes and duplicate deliveries. Property tests on
ledger arithmetic and on the policy module. Live k6 numbers and chaos as in
Gate.

### Approvals console

Small React + TypeScript (Vite) app: queue of held transfers, approve/deny
with reason, audit view, served from S3 + CloudFront only if it stays under
the cost ceiling, else from the API's static resources. This makes the demo
visual and makes the React keyword true.

### Out of scope

Real payment rails, KYC, FX, multi-currency conversion, Kafka, OAuth,
multi-region.

## Order of work

1. Gate: exactly-once plumbing (outbox, idempotency, processed messages,
   replay tool) with tests.
2. Gate: DST harness + invariants + CI seed sweep; jqwik properties.
3. Gate: live capture on AWS (smoke, k6, chaos, screenshots), README case
   study, ADRs, diagram, GIF; destroy.
4. Teller: scaffold from Gate, ledger core with tests, policy module
   extension, outbox/consumer reuse.
5. Teller: DST with money invariants, property tests.
6. Teller: approvals console; CDK stacks; live capture; README; destroy.
7. Publish both when the owner says so; exercise the OIDC pipeline; final
   report to the applications agent.

## Constraints

- Nothing from any employer. No real names or data.
- Every README claim runs. Cut features are listed as cut.
- Cost ceiling $10/month across both; Budgets alarm exists; deploy for
  capture and demos, destroy between.
- Repos stay local until the owner says publish.
