# Security

Supabase Auth is integrated server-side for email/password and Google PKCE. The browser receives only an HttpOnly `JSESSIONID`; access tokens remain in the server session, are capped at 30 minutes or the access-token lifetime (whichever is shorter), and are revalidated through Supabase `/user` on authenticated requests. Session fixation protection, SameSite=Lax, CSRF tokens, POST-only logout and local-session invalidation are enabled. Production cookies default to Secure; local HTTP must explicitly opt out.

Every buyer query must use `BuyerTransaction`, which sets `dealshield.buyer_id` transaction-locally on the Spring-bound JDBC connection. Forced PostgreSQL RLS is the final data boundary. The single-connection disposable test proved that identity disappears after commit/rollback and that a reused pooled connection cannot see the previous buyer's rows.

Authentication fails closed until a verified Supabase subject can be provisioned as the same UUID in `dealshield.buyers`. The required INSERT policy is in `docs/proposals/V2__buyer_provisioning.sql`; it passed disposable PostgreSQL testing but is not live. No service-role key is used. The restricted database login is still a trusted backend credential: anyone who obtains it could set the transaction setting, so it must never be exposed to the browser or untrusted services.
