# FoodBridge Backend

Team 1 TeSA Cohort 6 capstone — **independent Git repository**.

FoodBridge is a food-sharing application that connects donors with recipients to help coordinate surplus food listings and food requests.

## Technology Stack

- Java 25
- Spring Boot 3.5.7
- Maven
- PostgreSQL
- Flyway database migrations
- Spring Security
- OpenAPI for API contracts and documentation
- Cloudinary for listing image uploads

The backend follows a **modular monolith** architecture and maintains its own PostgreSQL database.

## Run Locally

1. Install JDK 25 and Maven.
2. Confirm your installations:

   ```bash
   java -version
   mvn -version
   ```

3. Start a separate PostgreSQL database for this application.
4. Configure the environment variables listed in `.env.example`. Do not commit passwords, API keys, or other secrets.
5. Run the application:

   ```bash
   mvn spring-boot:run
   ```

6. Run the tests:

   ```bash
   mvn test
   ```

7. Run full Maven verification:

   ```bash
   mvn -B verify
   ```

Full verification requires Docker because the integration tests use disposable PostgreSQL containers. The tests should not require production database credentials.

## Module Rules

Each business module follows the package structure:

- `api` — contracts exposed to other modules.
- `domain` — business entities and rules.
- `repository` — data access.
- `service` — application and business operations.
- `web` — HTTP controllers and endpoint handling.

Other modules should use only a module's `api` package rather than accessing its internal implementation directly.

Lombok is not used.

## API and Access

The backend API includes work for the following areas:

- **Authentication:** Login and current-user profile endpoints.
- **Listings:** Create, browse, filter, and retrieve food listings.
- **Image uploads:** Listing image upload through Cloudinary.
- **Access control:** Spring Security authentication and role-based restrictions.
- **Request handling:** API error responses and validation.
- **API contract:** OpenAPI specification in `contracts/openapi.yaml`.

The API contract should match the actual endpoint paths, request fields, response fields, and authentication requirements implemented by the backend.

## API Documentation

The OpenAPI contract is located at:

`contracts/openapi.yaml`

To validate it with Redocly CLI:

```bash
npx --yes @redocly/cli lint contracts/openapi.yaml
```

The production backend URL is:

`https://foodbridge-backend-rg9f.onrender.com`

The local development server uses port `8080`, unless overridden by configuration.

## Database and Migrations

PostgreSQL is the backend's database, and Flyway manages database schema migrations.

Migration files are maintained under:

`src/main/resources/db/migration`

Database schema changes and business rules must remain consistent with the team's agreed module ownership and integration contracts.

## Team Branches and Responsibilities

- **Adedayo — `dayo/data-rules`:** Schema, Flyway migrations, database rules, seed data, and database/concurrency tests.
- **Esther — API, access, and deployment work:** OpenAPI, controllers, authentication, role checks, CI, and deployment.

Confirm the active Git branch before making commits or opening pull requests.

## Continuous Integration

The GitHub Actions CI workflow runs Maven verification. It checks the build and configured tests; it does not itself deploy the application.

The CI database configuration should use a temporary test database rather than production credentials.

## Verification Status

The following verification has been reported during development:

- The OpenAPI contract passed Redocly validation, with warnings.
- `mvn -B verify` completed successfully after the listing naming-convention changes.
- 20 tests passed, 0 failures, 0 errors, 0 skipped; Maven verification successful on 29 October 2026.
