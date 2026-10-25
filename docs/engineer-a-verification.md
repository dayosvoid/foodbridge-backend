# Engineer A verification — 2026-10-25

Executed on Windows with Oracle JDK 25.0.4.1, Maven 3.9.16, unchanged Spring Boot 3.5.7,
Docker Desktop engine 29.8.0 and disposable PostgreSQL 17.11 containers (`postgres:17-alpine`).

`mvn -B verify`: **BUILD SUCCESS — 13 tests, 0 failures, 0 errors, 0 skipped**.
Both migrations applied successfully and Hibernate schema validation initialized all five entities.
Spring Boot's executable JAR was built. Full output: `target/verification-final.log`.
The initial sandbox cache/access failure and Docker API mismatch were resolved; test-only
`docker-java.properties` selects API 1.44, so the final full run needed no command-line override.
No dependency or Spring Boot version changes were needed. JDK native-access/Mockito dynamic-agent
warnings were nonfatal; no functional compilation or runtime compatibility failure remains.

| Suite | Tests | Verified |
| --- | ---: | --- |
| ConstraintEvidenceTest | 1 | Index removed: 3 approvals; index restored: 1 approval, 2 SQLSTATE 23505 conflicts |
| PostgresRulesTest | 10 | Three service approvals, atomic handover/activity/listing state, duplicate approval, expiry, stale deadline after lock wait, donor/recipient/admin access, quantity checks, idempotency, downstream rollback, repeatable demo seed |
| ModuleBoundaryTest | 1 | No cross-module internals or module cycles |
| RequestIdFilterTest | 1 | Incoming/generated IDs, response header, MDC cleanup including exceptions |

[Slide-4 output excerpt](concurrency-evidence.txt) preserves actual command output.
The unprotected phase is an intentionally demonstrated **business invariant failure**, asserted by a
passing counterexample test. It is not a red JUnit test. The protected phase repeats the same concurrent
database writes and proves at-most-one using PostgreSQL alone. The service suite separately verifies
transactional behavior. Neither experiment accepts a shared or production database URL.

A final request completion logging change is verified separately with
`mvn -B '-Dtest=RequestIdFilterTest' verify`: **BUILD SUCCESS — 1 test, 0 failures/errors/skips**;
its output is `target/request-id-final.log` (finished 03:33:49 +01:00).
The full database run precedes only this log statement change; the final focused run recompiles and
packages the final production sources.

No shared `foodbridge-db`, PayBridge or Render data was accessed or modified. Fixtures were exercised
only on the disposable test database. No controllers/authentication were added and no HTTP journey is
claimed complete. Remaining Monday integration tasks and configuration are in
[the handoff](engineer-a-handoff.md); the full changed-file inventory is
[engineer-a-files.txt](engineer-a-files.txt).

After review, these are the commit/push commands; none were executed:

```powershell
git status --short --branch
# Confirm the branch is dayo/data-rules before staging.
git add -- .env.example README.md docs src
git commit -m "Implement Monday MVP data rules and PostgreSQL concurrency protection"
# Push only after explicit approval:
git push origin dayo/data-rules
```

The branch was clean and one commit ahead of origin before this task. No branch switch, commit,
push or merge was performed. The push above would include that existing commit too.
