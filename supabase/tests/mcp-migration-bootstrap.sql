create extension if not exists pgcrypto;

create role anon nologin;
create role authenticated nologin;
create role service_role nologin;
create role supabase_auth_admin nologin;

create schema auth;
create table auth.users (id uuid primary key);

create function auth.uid()
returns uuid
language sql stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

create function auth.jwt()
returns jsonb
language sql stable
as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb)
$$;

create schema storage;
create table storage.buckets (
  id text primary key,
  name text not null,
  public boolean not null default false,
  file_size_limit bigint,
  allowed_mime_types text[]
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text not null,
  name text not null,
  unique (bucket_id, name)
);
alter table storage.objects enable row level security;

create function storage.foldername(name text)
returns text[]
language sql immutable
as $$
  select string_to_array(name, '/')
$$;

create table public.gallery_items (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id),
  asset_id text not null,
  media_type text not null check (media_type = any (array['image','video'])),
  display_name text,
  mime_type text,
  taken_at timestamptz,
  duration_ms bigint,
  width integer,
  height integer,
  labels text[] not null default '{}',
  indexed_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_id, asset_id)
);
alter table public.gallery_items enable row level security;

create policy "Users can read their own gallery items"
  on public.gallery_items for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy "Users can add their own gallery items"
  on public.gallery_items for insert to authenticated
  with check ((select auth.uid()) = owner_id);
create policy "Users can update their own gallery items"
  on public.gallery_items for update to authenticated
  using ((select auth.uid()) = owner_id)
  with check ((select auth.uid()) = owner_id);
create policy "Users can delete their own gallery items"
  on public.gallery_items for delete to authenticated
  using ((select auth.uid()) = owner_id);

grant usage on schema auth, storage to authenticated;
grant select, insert, update, delete on public.gallery_items to authenticated;
grant select, insert, update, delete on storage.objects to authenticated;
