import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createObserverClient } from '../diagnostics/background-client.mjs';

const owner = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const config = { key: 'sb_publishable_fixture', citizen: { uid: owner, email: 'fixture@example.invalid', password: 'fixture-only' }, label: 'CP25-FIXTURE-ONLY' };
function mock({ mismatch = false, badPhoto = false } = {}) {
  const calls = [];
  const request = async (url, options) => {
    calls.push({ url, options });
    if (url.includes('/auth/')) return Response.json({ user: { id: mismatch ? id : owner }, access_token: 'fixture-token' });
    if (url.includes('/rest/')) return Response.json([{ id, created_at: '2026-11-22T00:00:00Z', photo_path: `${owner}/${id}/photo.jpg` }]);
    return new Response(new Uint8Array(badPhoto ? [0, 1, 2, 3] : [255, 216, 255, 217]));
  };
  return { calls, request };
}
test('observer uses citizen authentication, owner/label-scoped reads and the exact private photo path', async () => {
  const fixture = mock();
  const client = await createObserverClient({ ...config, request: fixture.request });
  const rows = await client.reports();
  assert.equal((await client.photo(rows[0])).length, 4);
  assert.equal(fixture.calls.length, 3);
  const [login, reports, photo] = fixture.calls;
  assert.equal(login.options.method, 'POST');
  assert.equal(new URL(login.url).pathname, '/auth/v1/token');
  assert.equal(reports.options.method ?? 'GET', 'GET');
  assert.equal(new URL(reports.url).searchParams.get('owner_id'), `eq.${owner}`);
  assert.equal(new URL(reports.url).searchParams.get('description'), `like.${config.label}*`);
  assert.equal(new URL(reports.url).searchParams.get('limit'), '2');
  assert.equal(photo.options.method ?? 'GET', 'GET');
});
test('refuses server keys, unsafe labels and account mismatches before report access', async () => {
  let calls = 0;
  for (const invalid of [{ key: 'sb_secret_fixture' }, { label: 'unsafe,*' }, { citizen: { ...config.citizen, uid: 'invalid' } }]) {
    await assert.rejects(createObserverClient({ ...config, ...invalid, request: () => { calls++; } }), /Invalid observer configuration/);
  }
  assert.equal(calls, 0);
  const fixture = mock({ mismatch: true });
  await assert.rejects(createObserverClient({ ...config, request: fixture.request }), /account mismatch/);
  assert.equal(fixture.calls.length, 1);
});
test('refuses unrelated photo paths and non-JPEG responses', async () => {
  const fixture = mock({ badPhoto: true });
  const client = await createObserverClient({ ...config, request: fixture.request });
  await assert.rejects(client.photo({ id, photo_path: '../somewhere' }), /Unexpected photo path/);
  assert.equal(fixture.calls.length, 1);
  await assert.rejects(client.photo({ id, photo_path: `${owner}/${id}/photo.jpg` }), /Invalid JPEG response/);
});
