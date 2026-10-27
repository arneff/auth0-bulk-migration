# Auth0 Bulk User Migration PoC — Acme Corp

Proof-of-concept scripts demonstrating Auth0's [Bulk User Import API](https://auth0.com/docs/manage-users/user-migration/bulk-user-imports) for migrating users from a legacy system. Includes 10 seeded test users and examples in **Node.js**, **Python**, and **Java**.

## How it works

1. Loads `users.json` (10 test users with sha256 password hashes and MFA enrollments)
2. Submits a batch to `POST /api/v2/jobs/users-imports`
3. Polls `GET /api/v2/jobs/{id}` with exponential backoff until the job completes
4. Enforces Auth0's **2 concurrent job limit** — waits for a slot before submitting the next batch
5. Logs the job summary (`inserted`, `updated`, `failed`) and any per-record errors

## Prerequisites

- An [Auth0](https://auth0.com) tenant (free tier works)
- A **Database Connection** in your tenant (note its Connection ID: `con_xxxxxxxx`)
- A **Machine-to-Machine (M2M) application** authorized on the Auth0 Management API with the following scopes:
  - `create:users`
  - `read:users` (for polling job status)

  Create one at: **Auth0 Dashboard → Applications → Applications → Create Application → Machine to Machine → Auth0 Management API**

  The scripts use the [Client Credentials flow](https://auth0.com/docs/get-started/authentication-and-authorization-flow/client-credentials-flow) to fetch a short-lived token at runtime — no manual token generation required.

## Environment variables

Copy `.env.example` to `.env` and fill in your values:

```
AUTH0_DOMAIN=your-tenant.us.auth0.com
AUTH0_CLIENT_ID=your-m2m-client-id
AUTH0_CLIENT_SECRET=your-m2m-client-secret
AUTH0_CONNECTION_ID=con_xxxxxxxxxx
AUTH0_UPSERT=false
AUTH0_SEND_EMAIL=false
```

| Variable | Required | Description |
|---|---|---|
| `AUTH0_DOMAIN` | Yes | Your Auth0 tenant domain |
| `AUTH0_CLIENT_ID` | Yes | M2M application client ID |
| `AUTH0_CLIENT_SECRET` | Yes | M2M application client secret |
| `AUTH0_CONNECTION_ID` | Yes | Target database connection ID |
| `AUTH0_UPSERT` | No | `true` to update existing users (default: `false`) |
| `AUTH0_SEND_EMAIL` | No | `true` to email you on job completion (default: `false`) |

## Test users

All 10 users in `users.json` share the password **`Password1!`**, stored as `sha256(salt + password)`. Each user also has one or more MFA factors pre-enrolled (TOTP, email, and/or phone), demonstrating that MFA enrollments are migrated alongside credentials.

| Email | Legacy ID | MFA factors |
|---|---|---|
| alice.johnson@acme-corp.com | ACM-001 | TOTP |
| bob.smith@acme-corp.com | ACM-002 | TOTP |
| carol.white@acme-corp.com | ACM-003 | TOTP |
| david.brown@acme-corp.com | ACM-004 | Email |
| eve.davis@acme-corp.com | ACM-005 | Email |
| frank.miller@acme-corp.com | ACM-006 | Phone (SMS) |
| grace.wilson@acme-corp.com | ACM-007 | Phone (SMS) |
| henry.moore@acme-corp.com | ACM-008 | TOTP, Email |
| irene.taylor@acme-corp.com | ACM-009 | TOTP, Phone (SMS) |
| james.anderson@acme-corp.com | ACM-010 | TOTP, Email, Phone (SMS) |

## Running the scripts

### Node.js
```bash
cd node
npm install
cp ../.env.example .env   # then fill in values
npm start
```
Requires Node 18+.

### Python
```bash
cd python
pip install -r requirements.txt
cp ../.env.example .env   # then fill in values
python migrate.py
```
Requires Python 3.9+.

### Java
```bash
cd java
cp ../.env.example .env   # then fill in values
mvn compile exec:java -Dexec.mainClass="com.acme.auth0.BulkMigrate"
```
Requires Java 17+ and Maven 3.8+.

## Verifying the import

1. Go to **Auth0 Dashboard → User Management → Users**
2. You should see 10 new users with `migrated_from: acme_legacy` in their metadata
3. The script output will show `inserted: 10, failed: 0`
4. Test a login with `alice.johnson@acme-corp.com` / `Password1!`

## Project structure

```
auth0-bulk-migration/
├── README.md
├── .env.example
├── users.json              # 10 test users (sha256 hashed passwords)
├── node/
│   ├── package.json
│   └── migrate.js
├── python/
│   ├── requirements.txt
│   └── migrate.py
└── java/
    ├── pom.xml
    └── src/main/java/com/acme/auth0/
        └── BulkMigrate.java
```

## Auth0 API limits

| Limit | Value |
|---|---|
| Max concurrent import jobs | 2 per tenant |
| Max file size per job | 500 KB |
| Job data retention | 24 hours |
| Job timeout | 2 hours |

The scripts respect the 2-concurrent-job limit automatically.

## References

- [Bulk User Imports — Auth0 Docs](https://auth0.com/docs/manage-users/user-migration/bulk-user-imports)
- [Bulk Import JSON Schema — Auth0 Docs](https://auth0.com/docs/manage-users/user-migration/bulk-user-import-schema)
- [How to Migrate Users to Auth0 — Technical Guide](https://auth0.com/blog/how-to-migrate-users-to-auth0-a-technical-guide/)
