# Chaos capture — live Fargate deployment (2026-08-29, 05:15–05:19 UTC)

Task size during the run: 0.5 vCPU / 1 GB (the comparison variant was deployed at the time; the
behaviour below does not depend on task size). Driver: a shell script against the public task IP and
the real SQS queues; API key from Secrets Manager. Timestamps are UTC.

## 1. Poison message → dead-letter queue → replay

| Time | Event |
|---|---|
| 05:15:28 | `{"not":"an approval message"` (malformed JSON) sent to the approval queue |
| +15 s, +30 s | main queue: 0 visible / 2 in flight — the worker received it, `codec.decode` threw, the message was left for redrive |
| +45 s | dead-letter queue: 1 visible (redrive policy `maxReceiveCount=5`, visibility 60 s; an earlier poison message from a first, mis-instrumented attempt had already accumulated receives, which is why the DLQ filled before the theoretical 5 minutes) |
| 05:16:20 | `POST /admin/dlq/replay?max=10` → `{"replayed":1}`; audit row `DLQ_REPLAYED` with the SQS message id; DLQ back to 0 |
| later | the replayed poison message fails again and returns to the DLQ after five more receives — expected and documented |

Worker log line for the failure: `event=approval_queue_message_failed message_id=… error_type=…` (WARN).

## 2. Kill the task with unsent outbox rows

| Time | Event |
|---|---|
| 05:16:41 | policy with a single `REQUIRE_APPROVAL` rule; 20 `POST /decisions` with distinct `Idempotency-Key`s → 20 approvals created |
| 05:16:46 | `gate.outbox.backlog` = **7** (seven approval messages committed but not yet relayed to SQS); `aws ecs stop-task` issued |
| 05:18:02 | replacement task healthy (new public IP) after **76 s** |
| 05:18:xx | `POST /approvals/{id}/approve` on all 20 ids against the replacement: **20/20 → 200 APPROVED** |
| +45 s | `gate.outbox.backlog` = **0** on the replacement; queues drained |

What this shows: the decision, approval and outbox row are one transaction, so killing the process
between commit and publish loses nothing — the relay on the next instance sends the seven pending rows.
Without the outbox (the pre-Task-2 design published directly after commit) those seven approvals
would have existed in the database with no message ever reaching the queue.

## Driver mistakes worth recording

A first attempt checked the DLQ 12 seconds after sending (too early), looked up the DLQ URL by a
substring the queue name does not contain, and verified approvals through `GET /approvals/{id}`, which
did not exist at the time (it was added afterwards). The second run above corrected all three; both
runs' raw output are kept in the session scratchpad, not in the repo.
