## Cost (us-east-1, on-demand, AWS Pricing API 2026-08-29)

| Resource | Unit price | Always-on / month (730 h) | Per day while deployed |
|---|---:|---:|---:|
| ECS Fargate task, 0.25 vCPU + 0.5 GB (Linux/x86) | $0.040478/vCPU-h + $0.004446/GB-h = $0.01234/h | $9.01 | $0.30 |
| RDS db.t4g.micro, PostgreSQL, Single-AZ | $0.016/h | $11.68 | $0.38 |
| RDS storage, 20 GB gp3 | $0.115/GB-mo | $2.30 | $0.08 |
| Secrets Manager, 2 secrets | $0.40/secret-mo | $0.80 | $0.03 |
| SQS, S3, CloudWatch, ECR at demo volumes | metered, ~free-tier scale | ≈ $0.50 | ≈ $0.02 |
| **Total** | | **≈ $24.3** | **≈ $0.80** |

The $10/month ceiling therefore holds only under the project's operating rule:
deploy for capture and demos, `cdk destroy --all` between (≈ 12 deployed
days/month before the Budgets alarm fires). A NAT gateway alone would add
$32/month, an ALB $16/month — both deliberately absent.
