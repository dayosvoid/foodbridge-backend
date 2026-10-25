# Foodbridge backend

Team 1 TeSA capstone — **independent Git repository**. Java **25**, Spring Boot **3.5.7**, Maven, PostgreSQL, Flyway, modular monolith.

## Run locally

1. Install JDK 25 and Maven; check `java -version` and `mvn -version`.
2. Start a separate PostgreSQL database for this application.
3. Set environment variables listed in `.env.example` (do not commit secrets).
4. Run `mvn spring-boot:run` or `mvn test`.

## Module rules

Each business module has `api`, `domain`, `repository`, `service`, `web` packages. Other modules should use only its `api` package. No Lombok.

## Team branches

Adedayo: `dayo/data-rules` — schema, Flyway migrations, database rules, seed data and tests.
Esther: `feat/api-access` — OpenAPI, controllers, authentication, role checks and deployment.

## Status

Data/rules services, Flyway schema, PostgreSQL approval protection, opt-in simulated fixtures,
request-ID logging and real PostgreSQL concurrency tests are implemented. Controllers and authentication
remain integration work; this is not yet an end-to-end API. The CI workflow runs Maven verification,
not deployment. Docker is required for `mvn -B verify`; tests use disposable PostgreSQL 17 containers.
See [Engineer A handoff](docs/engineer-a-handoff.md) for proposed contracts, fixtures, rollback and slide-4 evidence.
