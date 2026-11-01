# Demo recipient authentication — review handoff for Esther

This small authentication-only patch follows donor-login commit f5702f9. Please
review the additions in ApiUserDetailsService, AuthController, and
UserProfileService. There are no changes to ActorFactory, SecurityConfig,
bearer-token handling, seed processes, migrations, or business operations.
The patch is intentionally uncommitted for review; no deployment is performed.

## Cause and identities

Authentication uses an in-memory credential registry, not Neon password_hash.
Recipient 1 already had credential, login-email, and profile entries. Recipients
2 and 3 were seeded in PostgreSQL but missing from all three authentication maps.
They now use the existing DelegatingPasswordEncoder with salted BCrypt hashes
for the assessment password recipient123, under demo & !prod & !production.

| Email | Existing UUID | Existing requests at read-only check |
|---|---|---:|
| recipient1@demo.foodbridge.invalid | c8840d79-f188-3367-a470-d3dc9609a640 | 23 |
| recipient2@demo.foodbridge.invalid | 668cefe8-0c95-34f2-9823-92ac376b8cf8 | 1 |
| recipient3@demo.foodbridge.invalid | 034f933b-74f1-3e77-b2ac-a069fe91c637 | 1 |

A read-only Neon transaction on 2026-11-01 confirmed all three existing users,
RECIPIENT roles, deterministic UUID matches, and request relationships. It did
not select password hashes or modify any rows. DemoSeed and the saved presentation
execution records use these same identities. No Neon password update, duplicate
account, seeding, or migration is required for this authentication fix.

## Verification and deployment

SecurityHttpTest covers all three email/password logins, distinct bearer tokens,
/api/auth/me identity and role, request creation under the authenticated recipient
UUID, rejection of recipient approval/deletion attempts, and invalid passwords.
Existing donor login/ownership tests remain in that suite. Config tests cover
encoded recipient passwords and exclusion under no profile, prod, production,
demo+prod and demo+production. The full Maven suite includes concurrency checks.
The full mvn clean test run passed on 2026-11-01: 53 tests, zero failures, errors or skips; Maven exit code 0.

The disposable Render demo service needs the same settings as the donor fix:

- SPRING_PROFILES_ACTIVE=demo (without prod or production).
- FOODBRIDGE_DEMO_SEED_ENABLED=false for the existing Neon dataset.
- Preserve existing DB/Cloudinary credentials; no DEMO_PASSWORD_HASH change.

If these settings already exist, no additional environment change is needed.
After Esther's review and a separately authorized deployment of this patch,
log in with each exact email above and password recipient123 through
POST /api/auth/login. Send the returned token in Authorization: Bearer <token>
to GET /api/auth/me and verify the UUID/role above. Restarting the service clears
existing process-local tokens, so obtain new ones after deployment.
