-- REVIEW PROPOSAL ONLY. Do not apply without explicit approval.
-- Target: dealshield-ai-fresh (umdmxoegwccerfzhpruc).

create table dealshield.comparison_requirements (
  id uuid primary key default gen_random_uuid(),
  buyer_id uuid not null,
  comparison_id uuid not null,
  product_name text not null check (length(btrim(product_name)) between 1 and 200),
  category text,
  required_quantity numeric(19,4) not null check (required_quantity > 0),
  budget numeric(19,4) check (budget >= 0),
  currency char(3) not null check (currency ~ '^[A-Z]{3}$'),
  minimum_warranty_months integer check (minimum_warranty_months >= 0),
  maximum_delivery_days integer check (maximum_delivery_days >= 0),
  preferred_payment_terms text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (comparison_id),
  unique (id, buyer_id),
  foreign key (comparison_id, buyer_id)
    references dealshield.comparisons(id, buyer_id) on delete cascade
);

create table dealshield.requirement_specs (
  id uuid primary key default gen_random_uuid(),
  buyer_id uuid not null,
  requirements_id uuid not null,
  spec_key text not null check (length(btrim(spec_key)) between 1 and 100),
  operator text not null check (operator in ('EXACT','MINIMUM','MAXIMUM','CONTAINS')),
  expected_value text not null check (length(btrim(expected_value)) between 1 and 500),
  expected_numeric numeric(19,4),
  unit text,
  mandatory boolean not null default true,
  created_at timestamptz not null default now(),
  foreign key (requirements_id, buyer_id)
    references dealshield.comparison_requirements(id, buyer_id) on delete cascade
);

alter table dealshield.quotation_items
  add column product_name text,
  add column model text,
  add column specifications jsonb not null default '{}'::jsonb,
  add column extraction_confidence jsonb not null default '{}'::jsonb;

create index comparison_requirements_buyer_idx
  on dealshield.comparison_requirements(buyer_id, comparison_id);
create index requirement_specs_parent_idx
  on dealshield.requirement_specs(requirements_id, buyer_id);

alter table dealshield.comparison_requirements enable row level security;
alter table dealshield.comparison_requirements force row level security;
create policy buyer_isolation on dealshield.comparison_requirements
  to dealshield_app
  using (buyer_id = nullif(current_setting('dealshield.buyer_id', true), '')::uuid)
  with check (buyer_id = nullif(current_setting('dealshield.buyer_id', true), '')::uuid);

alter table dealshield.requirement_specs enable row level security;
alter table dealshield.requirement_specs force row level security;
create policy buyer_isolation on dealshield.requirement_specs
  to dealshield_app
  using (buyer_id = nullif(current_setting('dealshield.buyer_id', true), '')::uuid)
  with check (buyer_id = nullif(current_setting('dealshield.buyer_id', true), '')::uuid);

revoke all on dealshield.comparison_requirements, dealshield.requirement_specs
  from public, anon, authenticated;
grant select, insert, update, delete
  on dealshield.comparison_requirements, dealshield.requirement_specs
  to dealshield_app;
