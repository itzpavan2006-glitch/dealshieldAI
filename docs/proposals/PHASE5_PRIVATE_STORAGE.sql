-- Phase 5 proposal for dealshield-ai-fresh (umdmxoegwccerfzhpruc) only.
-- Creates one private, size- and MIME-restricted bucket.
-- Object names must start with the authenticated user's UUID.

insert into storage.buckets
  (id, name, public, file_size_limit, allowed_mime_types)
values
  ('dealshield-quotations', 'dealshield-quotations', false, 10485760,
   array['application/pdf', 'image/jpeg', 'image/png']::text[]);

create policy "dealshield quotation upload own folder"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'dealshield-quotations'
  and (storage.foldername(name))[1] = (select auth.uid()::text)
);

create policy "dealshield quotation read own folder"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'dealshield-quotations'
  and owner_id = (select auth.uid()::text)
  and (storage.foldername(name))[1] = (select auth.uid()::text)
);

-- Deliberately no UPDATE, DELETE, public, anon, or service-role policy.
-- Uploads use unique object paths, so overwrite/upsert is not required.
