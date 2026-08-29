# AgentOps Gate Operations Runbook

Related documentation: [project status](../README.md#status), [architecture](../README.md#architecture), [operations and metrics](../README.md#operations), [admin API](../README.md#api), and [first AWS smoke record](evidence/2026-08-29-aws-smoke.md).

Use the `AgentOpsGate` CloudWatch dashboard first, then correlate the alarm window with the ECS application log stream. Never delete an SQS message manually: either correct the payload/source or replay it through the audited admin endpoint.

## HTTP 5xx alarm

The alarm fires when `gate.http.responses{status_class=5xx}` reaches five responses in five minutes.

1. Check the dashboard's p99 latency, request rate, ECS CPU/memory, outbox backlog, and DLQ depth for the same window.
2. Inspect application exceptions and PostgreSQL/SQS/S3 failures in the ECS log stream.
3. Check ECS deployment events and RDS health before restarting anything. Fargate's circuit breaker already rolls back a failed deployment.
4. If the failures came from approval messages and the DLQ is non-empty, inspect and replay them using the DLQ procedure below.

## Approval DLQ-depth alarm

The alarm fires as soon as `ApproximateNumberOfMessagesVisible >= 1`.

1. Receive without deleting one message from `$APPROVAL_DLQ_URL` and inspect its body and receive count:

   ```bash
   aws sqs receive-message \
     --queue-url "$APPROVAL_DLQ_URL" \
     --max-number-of-messages 1 \
     --attribute-names All \
     --visibility-timeout 0
   ```

2. Correlate its stable `messageId`, `approvalId`, and `decisionId` with application logs and the [`GET /audit` API](../README.md#api).
3. Fix a poison payload or the underlying service failure before replaying. A replay of an already-processed stable message id is an audited no-op at the consumer.
4. Replay up to ten messages through the API:

   ```bash
   curl --fail-with-body -X POST "$BASE_URL/admin/dlq/replay?limit=10" \
     -H "X-API-Key: $AGENTOPS_API_KEY"
   ```

5. Confirm the DLQ returns to zero, `gate.worker.processed` or `gate.worker.duplicates` advances, and the audit stream contains `DLQ_REPLAYED`.

For a local rehearsal of the authenticated endpoint, use the [Compose walkthrough](../README.md#compose-walkthrough). An empty DLQ returns `{"replayed":0}` and changes no state.
