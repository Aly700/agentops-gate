#!/bin/sh
set -eu

DLQ_URL="$(awslocal sqs create-queue --queue-name agentops-gate-approvals-dlq --query QueueUrl --output text)"
DLQ_ARN="$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)"

awslocal sqs create-queue \
  --queue-name agentops-gate-approvals \
  --attributes "ReceiveMessageWaitTimeSeconds=20,VisibilityTimeout=30,RedrivePolicy={\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"5\"}" \
  >/dev/null

awslocal s3api head-bucket --bucket agentops-gate-audit 2>/dev/null \
  || awslocal s3api create-bucket --bucket agentops-gate-audit >/dev/null
