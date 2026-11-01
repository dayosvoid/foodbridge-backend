# Assessment demo donor login

Authentication currently uses Spring Security's in-memory demo identities, not
`users.password_hash` in PostgreSQL. Equal Neon password hashes therefore do not
make both users login-capable. Donor 2 was absent from the credential registry,
login email mapping, and response profile mapping.

The demo registry now includes both donors. Each uses `donor123`; Spring's
DelegatingPasswordEncoder stores a salted `{bcrypt}` hash in UserDetails, rather
than a plaintext or `{noop}` password. Recipient/admin demo passwords remain
`recipient123` and `admin123`, and are encoded too.

Demo authentication requires the `demo` Spring profile and refuses activation
when `prod` or `production` is also active. Outside that environment the empty
credential registry fails closed; this application does not yet implement a
production database authentication provider.

The existing deterministic identity mapping is unchanged:

| Email | UUID |
|---|---|
| donor1@demo.foodbridge.invalid | 487c47b3-65e8-3ca1-bccb-7ee2a44172d6 |
| donor2@demo.foodbridge.invalid | 0b512802-54ad-333b-9757-977db2c5deff |

These IDs agree with DemoSeed and the replacement dataset listing owners. The
saved presentation verification reports 40 exact donor/image matches. This is
repository/saved-report evidence, not a fresh query against the live database.

## Enable on the disposable Render assessment service

1. Deploy this backend change to the intended assessment service when ready.
2. Set `SPRING_PROFILES_ACTIVE=demo`. Do not activate `prod` or `production` on
   this disposable demo service; do not enable demo authentication on a real
   production service.
3. Set `FOODBRIDGE_DEMO_SEED_ENABLED=false` because Neon already contains the
   accounts/listings. Preserve existing DB and Cloudinary environment variables.
   No `DEMO_PASSWORD_HASH` change is needed for login; it belongs to fixture
   insertion and is not used by this authentication provider.
4. Restart/redeploy the service with those settings. Existing in-memory tokens
   do not survive a restart; log in again.
5. POST JSON to `https://<backend-host>/api/auth/login` separately for each donor:

   ```json
   {"email":"donor1@demo.foodbridge.invalid","password":"donor123"}
   ```

   ```json
   {"email":"donor2@demo.foodbridge.invalid","password":"donor123"}
   ```

6. Use each returned token as `Authorization: Bearer <token>` on
   `GET /api/auth/me`. Verify its UUID and `DONOR` role against the table above.
   Use that same bearer token for listing and request-management endpoints.
   The frontend must call this deployed backend rather than its mock data path.

No Neon user/password updates, duplicate accounts, data resets, seeding, or new
migrations are required for this fix. Deployment still runs the application's
normal Flyway startup checks; any pending existing migrations are separate from
this authentication change and should be reviewed before deployment.

Bearer tokens retain the existing 30-minute lifetime and process-local storage.
For a demo, keep one application instance so a token reaches its issuing process.
No token-storage or production authentication redesign is included here.
