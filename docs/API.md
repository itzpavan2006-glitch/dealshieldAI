# API

`GET /api/v1/health` returns JSON with `status: UP` and `service: dealshield-api`. `GET /actuator/health` is the Spring health endpoint.

Authentication endpoints:

- `GET /api/v1/auth/csrf` creates/returns the Spring CSRF token.
- `POST /api/v1/auth/login` exchanges email/password with Supabase Auth and creates a server-managed session.
- `POST /api/v1/auth/google` starts the Google PKCE flow.
- `GET /api/v1/auth/callback` completes the same server-managed flow.
- `GET /api/v1/auth/me` returns the verified buyer subject only.
- `POST /api/v1/auth/logout` revokes the current Supabase session when possible and invalidates the local session.
- `GET /api/v1/buyer/dashboard` runs under transaction-local buyer identity and returns only the active buyer's profile and aggregate counts.

All POST endpoints require the CSRF header. Tokens are never returned to browser JavaScript. Buyer data endpoints require an authenticated, remotely revalidated session.
