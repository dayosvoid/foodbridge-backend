# Foodbridge backend architecture

Independent Spring Boot modular monolith with its own PostgreSQL database.
Modules own their domain, persistence and service internals. Other modules use the public `api` package.
Flyway migrations live in `src/main/resources/db/migration`.

Ownership: Adedayo — data, rules, migrations, concurrency tests; Esther — API, access, CI and deployments (shared reviews).

Data/rules services use public module API contracts, explicit transactional JDBC writes in their owning
modules, and JPA entity mappings for schema validation. Flyway V1/V2 own the schema and partial unique
index protecting approvals. ArchUnit prevents access to other modules' internals and module cycles.
The demo fixture utility is an explicit opt-in administrative exception that populates multiple tables.
Controllers/authentication remain pending; proposed integration contracts are in engineer-a-handoff.md.
