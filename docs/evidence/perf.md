# Performance — POST /decisions on ECS Fargate (us-east-1), k6 `load/decisions.js`

Script: ramping arrival rate 5 → 20 → 50 rps over 80 s, up to 100 VUs, mixed ALLOW/DENY/REQUIRE_APPROVAL
traffic, run from a workstation in Toronto (adds ~20–30 ms RTT per request). Same RDS db.t4g.micro throughout.

| Build | Task size | Sustained rps | median | p90 | p95 | p99 | max | errors | ECS CPU avg/max per min (%) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| baseline (commit 0275a65: default JVM flags, Hikari 10, policy + rules read per request) | 0.25 vCPU / 0.5 GB | 21.0 | 3.13 s | 4.92 s | 5.38 s | 6.79 s | 9.6 s | 0 / 1,736 | 50/100, 100/100, 61/100, 78/100 |
| tuned (commit 4dd7b9d: SerialGC + TieredStopAtLevel=1, Hikari 5, in-memory policy cache, single insert per decision) | 0.25 vCPU / 0.5 GB | 34.5 | 173 ms | 409 ms | 567 ms | 1.09 s | 1.82 s | 0 / 2,774 | 25/99, 46/100, 3/4, 3/3, 2/2, 42/91 |
| tuned | 0.5 vCPU / 1 GB | _pending_ | | | | | | | |

ECS memory utilization during the tuned run (per-minute averages): 40, 56, 53, 53, 53, 55 %.
RDS during both runs: CPU ≤ 7%, 5–10 connections — the database was never the bottleneck.

Reading: the baseline task was CPU-bound in the JVM (100% CPU with the DB idle). Dropping the C2 JIT
compiler (TieredStopAtLevel=1) and the parallel GC on a quarter-core, and removing the per-request
policy/rules reads, cut median latency 18× and raised sustained throughput 64% at identical cost.
The p99 above 1 s at 50 rps is queueing: the arrival-rate executor keeps pushing while the single
task is saturated; the honest capacity of one 0.25 vCPU task is ~35 rps.
