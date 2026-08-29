# ADR 0005: Use public subnets without a NAT gateway

## Context

The cost ceiling is $10 per month. A NAT gateway alone would cost about $32 per month before data processing. The Fargate task needs outbound access to ECR, Secrets Manager, SQS, and S3.

The VPC has public subnets only. The RDS instance is placed in those subnets but has `publiclyAccessible: false`; its security group accepts PostgreSQL traffic only from the Fargate task security group. Network access is controlled by that security group, and the database has no public endpoint. Public-subnet placement does not make it public or private.

## Decision

Assign the Fargate task a public IP in a public subnet so it can reach AWS service endpoints directly. Do not create a NAT gateway. Open the API port on the task security group for the demo.

## Consequences

- The architecture avoids the NAT gateway's fixed monthly cost.
- The task has direct internet ingress and egress, so the API key and security-group rules are security boundaries.
- There is no centralized NAT egress path or private endpoint layer.
- RDS is not publicly reachable even though its subnets are public.
