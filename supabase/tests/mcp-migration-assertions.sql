-- These assertions run against a disposable PostgreSQL instance, never production.
insert into auth.users (id) values
  ('11111111-1111-4111-8111-111111111111'),
  ('22222222-2222-4222-8222-222222222222');

insert into public.gallery_items (owner_id, asset_id, media_type, display_name, labels)
values
  ('11111111-1111-4111-8111-111111111111', 'content://owner-a/photo', 'image', 'beach.jpg', array['beach']),
  ('22222222-2222-4222-8222-222222222222', 'content://owner-b/photo', 'image', 'private.jpg', array['private']);

insert into storage.objects (bucket_id, name)
values ('gallery-shares', '11111111-1111-4111-8111-111111111111/photo.jpg');

insert into public.gallery_shared_media
  (owner_id, asset_id, display_name, storage_path, mime_type, camera_origin)
values
  ('11111111-1111-4111-8111-111111111111', 'content://owner-a/photo', 'beach.jpg',
   '11111111-1111-4111-8111-111111111111/photo.jpg', 'image/jpeg', true);

insert into mcp_private.oauth_clients (client_id, resource_uri, enabled)
values ('chatgpt-test-client', 'https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp', true);

do $$
declare
  hook_result jsonb;
begin
  hook_result := public.custom_access_token_hook(jsonb_build_object(
    'client_id', 'chatgpt-test-client',
    'claims', jsonb_build_object('aud', 'authenticated', 'client_id', 'chatgpt-test-client')
  ));
  if hook_result #> '{claims,aud}' <>
     '["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp","authenticated"]'::jsonb then
    raise exception 'OAuth hook did not bind the allowlisted client to the MCP audience: %', hook_result;
  end if;

  hook_result := public.custom_access_token_hook(jsonb_build_object(
    'client_id', 'not-allowlisted',
    'claims', jsonb_build_object('aud', 'authenticated', 'client_id', 'not-allowlisted')
  ));
  if hook_result #> '{claims,aud}' <> '"authenticated"'::jsonb then
    raise exception 'Unlisted OAuth client audience unexpectedly changed: %', hook_result;
  end if;
end;
$$;

-- A normal Android session sees only its own row and its own shared preview.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","aud":"authenticated"}', true);
do $$
declare n integer;
begin
  select count(*) into n from public.gallery_items;
  if n <> 1 then raise exception 'Direct user should see exactly their own gallery row, got %', n; end if;
  select count(*) into n from public.gallery_shared_media;
  if n <> 1 then raise exception 'Direct user should see their explicit share, got %', n; end if;
  select count(*) into n from storage.objects;
  if n <> 1 then raise exception 'Direct user should read their active shared preview, got %', n; end if;
end;
$$;
rollback;

-- An OAuth token without the MCP audience must not read or mutate gallery data.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","aud":"authenticated","client_id":"chatgpt-test-client"}', true);
do $$
declare n integer; affected integer;
begin
  select count(*) into n from public.gallery_items;
  if n <> 0 then raise exception 'OAuth client without MCP audience read % gallery rows', n; end if;
  select count(*) into n from public.gallery_shared_media;
  if n <> 0 then raise exception 'OAuth client without MCP audience read % share rows', n; end if;
  select count(*) into n from storage.objects;
  if n <> 0 then raise exception 'OAuth client without MCP audience read % storage objects', n; end if;
  update public.gallery_items set display_name = 'tampered.jpg';
  get diagnostics affected = row_count;
  if affected <> 0 then raise exception 'OAuth client unexpectedly updated % gallery rows', affected; end if;
  begin
    insert into public.gallery_shared_media
      (owner_id, asset_id, display_name, storage_path, mime_type)
    values ('11111111-1111-4111-8111-111111111111', 'content://bad', 'bad.jpg',
      '11111111-1111-4111-8111-111111111111/bad.jpg', 'image/jpeg');
    raise exception 'OAuth client unexpectedly inserted a share row';
  exception when insufficient_privilege then
    null;
  end;
end;
$$;
rollback;

-- The allowlisted OAuth client may read its owner's rows and only currently shared objects.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","aud":["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp","authenticated"],"client_id":"chatgpt-test-client"}', true);
do $$
declare n integer; affected integer;
begin
  select count(*) into n from public.gallery_items;
  if n <> 1 then raise exception 'Allowlisted MCP client should see only its owner gallery row, got %', n; end if;
  select count(*) into n from public.gallery_shared_media;
  if n <> 1 then raise exception 'Allowlisted MCP client should see its explicitly shared row, got %', n; end if;
  select count(*) into n from storage.objects;
  if n <> 1 then raise exception 'Allowlisted MCP client should read its shared preview, got %', n; end if;
  update public.gallery_items set display_name = 'tampered.jpg';
  get diagnostics affected = row_count;
  if affected <> 0 then raise exception 'Allowlisted MCP client unexpectedly updated % gallery rows', affected; end if;
end;
$$;
rollback;

-- Removing the explicit share row revokes Storage reads even if an object lingers.
delete from public.gallery_shared_media where asset_id = 'content://owner-a/photo';
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select set_config('request.jwt.claims',
  '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","aud":["https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp","authenticated"],"client_id":"chatgpt-test-client"}', true);
do $$
declare n integer;
begin
  select count(*) into n from storage.objects;
  if n <> 0 then raise exception 'Unshared preview remained readable through Storage RLS'; end if;
end;
$$;
rollback;

select 'MCP migration RLS checks passed' as result;
