create extension if not exists pgcrypto;

create table if not exists tenants (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  created_at timestamptz not null default now()
);

create table if not exists accounts (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  email text not null unique,
  password_hash text not null,
  role text not null default 'user',
  subscription_status text not null default 'active',
  subscription_expires_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists posts (
  tenant_id uuid not null references tenants(id) on delete cascade,
  id text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, id)
);

create table if not exists comments (
  tenant_id uuid not null references tenants(id) on delete cascade,
  id text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, id)
);

create table if not exists users (
  tenant_id uuid not null references tenants(id) on delete cascade,
  profile text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, profile)
);

create table if not exists analysis_scopes (
  tenant_id uuid not null references tenants(id) on delete cascade,
  id text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, id)
);

create table if not exists email_verification_codes (
  email text not null,
  purpose text not null check (purpose in ('login', 'register')),
  code_hash text not null,
  attempts integer not null default 0,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  primary key (email, purpose)
);

create table if not exists app_settings (
  setting_key text primary key,
  setting_value jsonb not null,
  updated_at timestamptz not null default now()
);
