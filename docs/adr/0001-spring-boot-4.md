# ADR 0001: Use Spring Boot 4

## Context

Spring Initializr no longer offers a Spring Boot 3.x project, and Spring Boot 3.5 left open-source support in mid-2026. The service is built with Java 21, while the build machine may use a newer JDK.

## Decision

Use Spring Boot 4.1.1 and compile for Java 21. Treat earlier references to “Spring Boot 3” as requirements for the current supported Spring Boot line, not as a reason to pin an unsupported project baseline.

## Consequences

- The application uses current Spring Boot APIs and still produces Java 21 bytecode.
- Libraries compiled against Spring Boot 3 must be checked for binary compatibility rather than assumed to work.
- Spring Cloud AWS 3.4 failed that check, which led to the separate decision to use AWS SDK v2 directly.
- Upgrades may require code or configuration changes because this project is on a newer framework line.
