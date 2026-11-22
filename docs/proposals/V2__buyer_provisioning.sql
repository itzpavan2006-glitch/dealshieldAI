-- PROPOSAL ONLY: do not apply without explicit live migration approval.
-- A trusted backend verifies the Supabase subject and uses the same UUID as buyer ID.
-- Existing records are preserved; this policy governs new rows only.
CREATE POLICY buyer_insert_verified_subject ON dealshield.buyers AS RESTRICTIVE FOR INSERT TO dealshield_app
  WITH CHECK (id = auth_user_id AND id = nullif(current_setting('dealshield.buyer_id', true), '')::uuid);
GRANT INSERT (id, auth_user_id, display_name) ON dealshield.buyers TO dealshield_app;
