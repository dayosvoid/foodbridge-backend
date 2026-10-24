# Foodbridge backend architecture

Independent Spring Boot modular monolith with its own PostgreSQL database.
Modules own their domain, persistence and service internals. Other modules use the public `api` package.
Flyway migrations live in `src/main/resources/db/migration`.

Ownership: Adedayo — data, rules, migrations, concurrency tests; Esther — API, access, CI and deployments (shared reviews).

This is a skeleton; contracts, schema, endpoints, authentication and concurrency tests are not implemented yet.
