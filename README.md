# Distributed Workflow Engine

A distributed workflow engine that runs multi-step workflows defined as Directed Acyclic Graphs (DAGs). It persists state, controls concurrency, and tolerates faults. PostgreSQL is both the authoritative state store and the work-coordination mechanism; there is no message broker.

- Java 17, Spring Boot 3.3, Maven
- PostgreSQL 16, Spring JDBC, Flyway
- Docker and Docker Compose
- JUnit 5, Testcontainers

## Quick Start

### Prerequisites

- Docker with Docker Compose
- For building and testing outside Docker: JDK 17 and Maven 3.9+
- For the demo script: `curl` and `jq`
