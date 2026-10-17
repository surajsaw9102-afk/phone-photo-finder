-- Phone Photo Finder: explicit image sharing + MCP OAuth audience binding.
-- Review this migration before applying it to production.
begin;

alter table public.gallery_items
  add column if not exists camera_origin boolean not null default false;

create table if not exists public.gallery_shared_media (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  asset_id text not null,
  display_name text not null,
  storage_path text not null,
  mime_type text not null default 'image/jpeg' check (mime_type = 'image/jpeg'),
  taken_at timestamptz,
  camera_origin boolean not null default false,
  shared_at timestamptz not null default now(),
  unique (owner_id, asset_id),
  unique (storage_path)
);

alter table public.gallery_shared_media enable row level security;

-- Expose this table to authenticated REST clients; RLS below remains the row-level boundary.
grant select, insert, update, delete on public.gallery_shared_media to authenticated;

-- The OAuth access-token hook only changes the audience for client IDs explicitly
-- registered here. Resource URI must exactly match the deployed MCP endpoint.
create schema if not exists mcp_private;
revoke all on schema mcp_private from public, anon, authenticated;
create table if not exists mcp_private.oauth_clients (
  client_id text primary key,
  resource_uri text not null check (
    resource_uri = 'https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp'
  ),
  enabled boolean not null default true,
  created_at timestamptz not null default now()
);
revoke all on mcp_private.oauth_clients from public, anon, authenticated, service_role;

create or replace function public.custom_access_token_hook(event jsonb)
returns jsonb
language plpgsql
stable
security definer
set search_path = pg_catalog, mcp_private
as $$
declare
  claims jsonb := coalesce(event -> 'claims', '{}'::jsonb);
  oauth_client_id text := coalesce(event ->> 'client_id', claims ->> 'client_id');
  resource_uri text;
begin
  -- client_id is present on Supabase OAuth tokens, including refreshed tokens.
  if oauth_client_id is not null then
    select c.resource_uri
      into resource_uri
      from mcp_private.oauth_clients c
     where c.client_id = oauth_client_id
       and c.enabled = true;

    if resource_uri is not null then
      -- Keep Supabase's "authenticated" audience for PostgREST/Storage while
      -- also binding this OAuth token to the MCP resource.
      claims := jsonb_set(claims, '{aud}', jsonb_build_array(resource_uri, 'authenticated'), true);
    end if;
  end if;

  return jsonb_build_object('claims', claims);
end;
$$;

revoke all on function public.custom_access_token_hook(jsonb) from public, anon, authenticated, service_role;
grant usage on schema public to supabase_auth_admin;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;

-- Direct Android sessions (no OAuth client_id) may manage their own metadata.
-- OAuth clients may only read metadata when their token audience includes this MCP.
drop policy if exists "Users can read their own gallery items" on public.gallery_items;
create policy "Users and Phone Photo Finder MCP can read own gallery items"
  on public.gallery_items for select to authenticated
  using (
    (select auth.uid()) = owner_id
    and (
      (auth.jwt() ->> 'client_id') is null
      or (auth.jwt() -> 'aud') @> '["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp"]'::jsonb
    )
  );

drop policy if exists "Users can add their own gallery items" on public.gallery_items;
create policy "Direct sessions can add own gallery items"
  on public.gallery_items for insert to authenticated
  with check ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

drop policy if exists "Users can update their own gallery items" on public.gallery_items;
create policy "Direct sessions can update own gallery items"
  on public.gallery_items for update to authenticated
  using ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null)
  with check ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

drop policy if exists "Users can delete their own gallery items" on public.gallery_items;
create policy "Direct sessions can delete own gallery items"
  on public.gallery_items for delete to authenticated
  using ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

drop policy if exists "Direct users and MCP can read own shared media" on public.gallery_shared_media;
create policy "Direct users and MCP can read own shared media"
  on public.gallery_shared_media for select to authenticated
  using (
    (select auth.uid()) = owner_id
    and (
      (auth.jwt() ->> 'client_id') is null
      or (auth.jwt() -> 'aud') @> '["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp"]'::jsonb
    )
  );

create policy "Direct sessions can share own media"
  on public.gallery_shared_media for insert to authenticated
  with check ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

create policy "Direct sessions can update own shared media"
  on public.gallery_shared_media for update to authenticated
  using ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null)
  with check ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

create policy "Direct sessions can remove own shared media"
  on public.gallery_shared_media for delete to authenticated
  using ((select auth.uid()) = owner_id and (auth.jwt() ->> 'client_id') is null);

-- Private, thumbnail-only bucket. Files are uploaded only after a user taps
-- "Share with ChatGPT" in the Android app.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('gallery-shares', 'gallery-shares', false, 2097152, array['image/jpeg'])
on conflict (id) do update
set public = false, file_size_limit = 2097152, allowed_mime_types = array['image/jpeg'];

drop policy if exists "Direct users and MCP can read own shared images" on storage.objects;
create policy "Direct users and MCP can read own shared images"
  on storage.objects for select to authenticated
  using (
    bucket_id = 'gallery-shares'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (
      (auth.jwt() ->> 'client_id') is null
      or (auth.jwt() -> 'aud') @> '["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp"]'::jsonb
    )
  );

create policy "Direct sessions can upload own shared images"
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'gallery-shares'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (auth.jwt() ->> 'client_id') is null
  );

create policy "Direct sessions can replace own shared images"
  on storage.objects for update to authenticated
  using (
    bucket_id = 'gallery-shares'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (auth.jwt() ->> 'client_id') is null
  )
  with check (
    bucket_id = 'gallery-shares'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (auth.jwt() ->> 'client_id') is null
  );

create policy "Direct sessions can delete own shared images"
  on storage.objects for delete to authenticated
  using (
    bucket_id = 'gallery-shares'
    and (storage.foldername(name))[1] = (select auth.uid())::text
    and (auth.jwt() ->> 'client_id') is null
  );

commit;
