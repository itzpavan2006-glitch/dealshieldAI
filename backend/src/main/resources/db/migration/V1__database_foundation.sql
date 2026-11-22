-- Apply only with the designated Flyway migration account after explicit approval.
-- Flyway creates the private schema and keeps its history there.
CREATE ROLE dealshield_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE ALL ON SCHEMA dealshield FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA dealshield TO dealshield_app;

CREATE TABLE dealshield.buyers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  auth_user_id uuid NOT NULL UNIQUE,
  display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 200),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE dealshield.comparisons (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL REFERENCES dealshield.buyers(id),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200), description text, category text,
  purchasing_priority text NOT NULL DEFAULT 'BALANCED' CHECK (purchasing_priority IN ('LOWEST_COST','FASTEST_DELIVERY','BEST_WARRANTY','BALANCED')),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','IN_REVIEW','ANALYZED','DECIDED','ARCHIVED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE (id,buyer_id)
);
CREATE TABLE dealshield.suppliers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL REFERENCES dealshield.buyers(id),
  supplier_name text NOT NULL CHECK (length(btrim(supplier_name)) BETWEEN 1 AND 200), contact_email text, contact_phone text, address text,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (id,buyer_id)
);
CREATE TABLE dealshield.quotations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL REFERENCES dealshield.buyers(id), comparison_id uuid NOT NULL, supplier_id uuid NOT NULL,
  slot smallint NOT NULL CHECK (slot BETWEEN 1 AND 3), quotation_number text, quotation_date date, currency char(3) CHECK (currency ~ '^[A-Z]{3}$'),
  subtotal numeric(19,4) CHECK (subtotal >= 0), tax_amount numeric(19,4) CHECK (tax_amount >= 0),
  shipping_amount numeric(19,4) CHECK (shipping_amount >= 0), additional_charges numeric(19,4) CHECK (additional_charges >= 0),
  grand_total numeric(19,4) CHECK (grand_total >= 0), delivery_days integer CHECK (delivery_days >= 0), warranty_terms text, payment_terms text,
  validity_terms text, extraction_status text NOT NULL DEFAULT 'PENDING' CHECK (extraction_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
  review_status text NOT NULL DEFAULT 'UNREVIEWED' CHECK (review_status IN ('UNREVIEWED','CONFIRMED')),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id,buyer_id), UNIQUE (id,comparison_id,buyer_id), UNIQUE (comparison_id,slot),
  FOREIGN KEY (comparison_id,buyer_id) REFERENCES dealshield.comparisons(id,buyer_id) ON DELETE CASCADE,
  FOREIGN KEY (supplier_id,buyer_id) REFERENCES dealshield.suppliers(id,buyer_id)
);
CREATE TABLE dealshield.quotation_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, quotation_id uuid NOT NULL,
  description text NOT NULL CHECK (length(btrim(description)) > 0), quantity numeric(19,4) CHECK (quantity > 0), unit text,
  unit_price numeric(19,4) CHECK (unit_price >= 0), line_total numeric(19,4) CHECK (line_total >= 0),
  FOREIGN KEY (quotation_id,buyer_id) REFERENCES dealshield.quotations(id,buyer_id) ON DELETE CASCADE
);
CREATE TABLE dealshield.quotation_files (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, quotation_id uuid NOT NULL,
  storage_path text NOT NULL UNIQUE, original_filename text NOT NULL, mime_type text NOT NULL,
  file_size bigint NOT NULL CHECK (file_size > 0), checksum char(64) NOT NULL, uploaded_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (quotation_id,buyer_id) REFERENCES dealshield.quotations(id,buyer_id) ON DELETE CASCADE
);
CREATE TABLE dealshield.extraction_results (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, quotation_id uuid NOT NULL,
  extracted_json jsonb NOT NULL DEFAULT '{}'::jsonb, confidence_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  extraction_method text NOT NULL, model_version text, processing_status text NOT NULL CHECK (processing_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
  created_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY (quotation_id,buyer_id) REFERENCES dealshield.quotations(id,buyer_id) ON DELETE CASCADE
);
CREATE TABLE dealshield.risk_findings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, quotation_id uuid NOT NULL, finding_type text NOT NULL,
  severity text NOT NULL CHECK (severity IN ('INFORMATIONAL','LOW','MEDIUM','HIGH')), description text NOT NULL,
  evidence text NOT NULL, source_field text, recommendation text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (quotation_id,buyer_id) REFERENCES dealshield.quotations(id,buyer_id) ON DELETE CASCADE
);
CREATE TABLE dealshield.comparison_results (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, comparison_id uuid NOT NULL, ranking_json jsonb NOT NULL,
  priority_snapshot text NOT NULL, calculation_version text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (comparison_id,buyer_id) REFERENCES dealshield.comparisons(id,buyer_id) ON DELETE CASCADE
);
CREATE TABLE dealshield.buyer_decisions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL, comparison_id uuid NOT NULL UNIQUE, selected_quotation_id uuid NOT NULL,
  decision_notes text, decided_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (comparison_id,buyer_id) REFERENCES dealshield.comparisons(id,buyer_id) ON DELETE CASCADE,
  FOREIGN KEY (selected_quotation_id,comparison_id,buyer_id) REFERENCES dealshield.quotations(id,comparison_id,buyer_id)
);
CREATE TABLE dealshield.audit_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), buyer_id uuid NOT NULL REFERENCES dealshield.buyers(id), action text NOT NULL,
  entity_type text NOT NULL, entity_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX comparisons_buyer_created_idx ON dealshield.comparisons(buyer_id,created_at DESC);
CREATE INDEX suppliers_buyer_idx ON dealshield.suppliers(buyer_id);
CREATE INDEX quotations_supplier_owner_idx ON dealshield.quotations(supplier_id,buyer_id);
CREATE INDEX quotations_comparison_owner_idx ON dealshield.quotations(comparison_id,buyer_id);
CREATE INDEX quotation_items_parent_idx ON dealshield.quotation_items(quotation_id,buyer_id);
CREATE INDEX quotation_files_parent_idx ON dealshield.quotation_files(quotation_id,buyer_id);
CREATE INDEX extraction_results_parent_idx ON dealshield.extraction_results(quotation_id,buyer_id);
CREATE INDEX risk_findings_parent_idx ON dealshield.risk_findings(quotation_id,buyer_id);
CREATE INDEX comparison_results_parent_idx ON dealshield.comparison_results(comparison_id,buyer_id);
CREATE INDEX buyer_decisions_quote_idx ON dealshield.buyer_decisions(selected_quotation_id,comparison_id,buyer_id);
CREATE INDEX audit_events_buyer_created_idx ON dealshield.audit_events(buyer_id,created_at DESC);

-- The authenticated backend must SET LOCAL dealshield.buyer_id per transaction.
-- Missing or empty context denies access. Never expose this database role to clients.
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['comparisons','suppliers','quotations','quotation_items','quotation_files','extraction_results','risk_findings','comparison_results','buyer_decisions','audit_events'] LOOP
    EXECUTE format('ALTER TABLE dealshield.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE dealshield.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY buyer_isolation ON dealshield.%I TO dealshield_app USING (buyer_id = nullif(current_setting(''dealshield.buyer_id'', true), '''')::uuid) WITH CHECK (buyer_id = nullif(current_setting(''dealshield.buyer_id'', true), '''')::uuid)', t);
    EXECUTE format('REVOKE ALL ON dealshield.%I FROM PUBLIC, anon, authenticated', t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON dealshield.%I TO dealshield_app', t);
  END LOOP;
END $$;
ALTER TABLE dealshield.buyers ENABLE ROW LEVEL SECURITY;
ALTER TABLE dealshield.buyers FORCE ROW LEVEL SECURITY;
CREATE POLICY buyer_isolation ON dealshield.buyers TO dealshield_app USING (id = nullif(current_setting('dealshield.buyer_id',true),'')::uuid) WITH CHECK (id = nullif(current_setting('dealshield.buyer_id',true),'')::uuid);
REVOKE ALL ON dealshield.buyers FROM PUBLIC, anon, authenticated;
GRANT SELECT, UPDATE ON dealshield.buyers TO dealshield_app;
REVOKE UPDATE ON dealshield.buyers FROM dealshield_app;
GRANT UPDATE (display_name, updated_at) ON dealshield.buyers TO dealshield_app;
REVOKE UPDATE, DELETE ON dealshield.audit_events FROM dealshield_app;
-- Buyer provisioning is deliberately deferred to the authenticated onboarding design.
