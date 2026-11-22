const project = 'https://jaysxjjdvaitgrbqjlhe.supabase.co';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Only an ordinary citizen session; never use a server/service-role credential. */
export async function createObserverClient({ key, citizen, label, request = fetch }) {
  if (!/^sb_publishable_[A-Za-z0-9_-]+$/.test(key ?? '') || !uuid.test(citizen?.uid ?? '') ||
      !citizen.email || !citizen.password || !/^[A-Za-z0-9-]{8,64}$/.test(label ?? '')) {
    throw new Error('Invalid observer configuration');
  }
  let token;
  async function call(path, options = {}) {
    const response = await request(`${project}/${path}`, {
      ...options, redirect: 'error', signal: AbortSignal.timeout(5000),
      headers: { apikey: key, ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    });
    if (!response.ok) throw new Error(`Observer HTTP ${response.status}`);
    return response;
  }
  const session = await (await call('auth/v1/token?grant_type=password', {
    method: 'POST', body: JSON.stringify({ email: citizen.email, password: citizen.password }),
  })).json();
  if (session.user?.id !== citizen.uid || !session.access_token) throw new Error('Observer account mismatch');
  token = session.access_token;
  return {
    async reports() {
      const query = new URLSearchParams({ select: 'id,created_at,photo_path', owner_id: `eq.${citizen.uid}`,
        description: `like.${label}*`, order: 'created_at.asc', limit: '2' });
      const rows = await (await call(`rest/v1/reports?${query}`)).json();
      if (!Array.isArray(rows) || rows.some(row => !uuid.test(row.id))) throw new Error('Invalid report response');
      return rows;
    },
    async photo(row) {
      if (!uuid.test(row.id) || row.photo_path !== `${citizen.uid}/${row.id}/photo.jpg`) throw new Error('Unexpected photo path');
      const response = await call(`storage/v1/object/authenticated/report-images/${row.photo_path}`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (bytes.length < 4 || bytes.length > 256000 || bytes[0] !== 0xff || bytes[1] !== 0xd8 ||
          bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9) throw new Error('Invalid JPEG response');
      return bytes;
    },
  };
}
