// Diagnostic only: host-side sampling, no instrumentation, app launch or radio changes.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { parseArgs } from 'node:util';
import { readFile, realpath, open } from 'node:fs/promises';
import { resolve, dirname, basename, relative, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { BackgroundObservation, parseConnectivity } from '../diagnostics/background-observation.mjs';
import { createObserverClient } from '../diagnostics/background-client.mjs';

const execute = promisify(execFile);
let archive;
let stopped = false;
process.once('SIGINT', () => { stopped = true; });
try {
  const { values: args } = parseArgs({ options: {
    serial: { type: 'string' }, output: { type: 'string' }, credentials: { type: 'string' },
    label: { type: 'string' }, transport: { type: 'string', default: 'Any' },
    seconds: { type: 'string', default: '300' }, 'system-only': { type: 'boolean' },
    'allow-physical-device': { type: 'boolean' },
  } });
  const seconds = Number(args.seconds);
  if (!/^[A-Za-z0-9._:-]+$/.test(args.serial ?? '') || !args.output ||
      !Number.isFinite(seconds) || seconds < 1 || seconds > 600 ||
      !['Any', 'WiFi', 'Cellular'].includes(args.transport)) throw new Error('Invalid arguments');
  if (!/^emulator-\d+$/.test(args.serial) && !args['allow-physical-device']) {
    throw new Error('Physical-device observation requires owner approval and --allow-physical-device');
  }
  const repo = await realpath(fileURLToPath(new URL('../../', import.meta.url)));
  const output = join(await realpath(dirname(resolve(args.output))), basename(args.output));
  const insideRepo = path => { const part = relative(repo, path); return part === '' || (!part.startsWith('..') && !isAbsolute(part)); };
  if (insideRepo(output)) throw new Error('Keep diagnostic output outside Git');
  const adb = join(process.env.ANDROID_HOME ?? join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk'), 'platform-tools', 'adb.exe');
  async function shell(...command) {
    const { stdout } = await execute(adb, ['-s', args.serial, 'shell', ...command],
      { timeout: 5000, maxBuffer: 4 * 1024 * 1024, windowsHide: true });
    return stdout;
  }
  const initial = parseConnectivity(await shell('dumpsys', 'connectivity'));
  let client;
  if (!args['system-only']) {
    if (initial.state !== 'offline') throw new Error('Arm while the selected Android device is offline');
    const credentialPath = await realpath(resolve(args.credentials ?? ''));
    if (insideRepo(credentialPath)) throw new Error('Keep credentials outside Git');
    const citizen = JSON.parse(await readFile(credentialPath, 'utf8')).citizen;
    const properties = await readFile(join(repo, 'local.properties'), 'utf8');
    const url = properties.match(/^SUPABASE_URL=(.*)$/m)?.[1].trim();
    const key = properties.match(/^SUPABASE_PUBLISHABLE_KEY=(.*)$/m)?.[1].trim();
    if (url !== 'https://jaysxjjdvaitgrbqjlhe.supabase.co') throw new Error('Unexpected project configuration');
    client = await createObserverClient({ key, citizen, label: args.label });
    if ((await client.reports()).length) throw new Error('Label already exists; use a new label');
  }
  archive = await open(output, 'wx');
  async function record(event) { await archive.writeFile(`${JSON.stringify(event)}\n`); }
  const observation = new BackgroundObservation({ expectedTransport: args.transport });
  const started = performance.now();
  const elapsed = () => Math.round(performance.now() - started);
  await record({ type: 'start', utc: new Date().toISOString(), mode: client ? 'ordinary-app-external' : 'system-only',
    expected_transport: args.transport, limit_seconds: seconds,
    timing_method: 'Host monotonic poll-completion times; not app acknowledgement or exact reconnect latency' });
  console.log(client ? 'ARMED: ordinary-app observer; no app or radio commands will be sent.' : 'Observing system connectivity only; no cloud authentication.');
  let rows = [];
  while (!stopped && elapsed() < seconds * 1000) {
    try {
      const network = parseConnectivity(await shell('dumpsys', 'connectivity'));
      const at = elapsed(); observation.network(network, at);
      await record({ type: 'network', at_ms: at, ...network });
    } catch {
      const at = elapsed(); observation.failed('system', at);
      await record({ type: 'read-error', source: 'system', at_ms: at });
    }
    if (client && !stopped && elapsed() < seconds * 1000) {
      try {
        rows = await client.reports(); const at = elapsed(); observation.reports(rows, at);
        await record({ type: 'server', at_ms: at, copies: rows.length,
          reports: rows.map(row => ({ id: row.id, created_at: row.created_at, photo_attached: !!row.photo_path })) });
        if (rows.length) break;
      } catch {
        const at = elapsed(); observation.failed('backend', at);
        await record({ type: 'read-error', source: 'backend', at_ms: at });
      }
    }
    await delay(Math.max(0, Math.min(1000, seconds * 1000 - elapsed())));
  }
  let photo;
  if (client && rows.length === 1) {
    try {
      const bytes = await client.photo(rows[0]);
      photo = { downloaded: true, jpeg_envelope: true, bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex'), local_hash_compared: false };
    } catch { photo = { downloaded: false }; }
  }
  const summary = { type: 'summary', utc: new Date().toISOString(), elapsed_ms: elapsed(), interrupted: stopped,
    scope: client ? 'server-report-and-system-connectivity' : 'system-only', ...observation.result(), ...(photo ? { photo } : {}) };
  await record(summary);
  console.log(JSON.stringify(summary));
  if (client && (summary.copies !== 1 || !summary.timing_valid || !photo?.downloaded)) process.exitCode = 1;
} catch (error) {
  // Never echo raw ADB, HTTP, JSON or filesystem errors: they can contain private values.
  const allowed = /^(Invalid arguments|Physical-device observation requires owner approval and --allow-physical-device|Keep diagnostic output outside Git|Arm while the selected Android device is offline|Keep credentials outside Git|Unexpected project configuration|Label already exists; use a new label|Invalid observer configuration|Observer account mismatch)$/;
  console.error(allowed.test(error.message) ? error.message : 'Observation failed; check device, private configuration and exclusive output path. No app data was changed.');
  process.exitCode = 1;
} finally {
  await archive?.close();
}
