import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BackgroundObservation, parseConnectivity } from '../diagnostics/background-observation.mjs';

const online = { state: 'connected', internet: true, validated: true, transport: 'Cellular' };
const row = { id: 'test-report', photo_attached: true };
const dump = (id, agents) => `Active default network: ${id}\nCurrent Networks:\n${agents}\nNetwork Requests:\nNetworkAgentInfo{network{999} nc{[Transports: WIFI Capabilities: INTERNET&VALIDATED]}}`;
const agent = (id, transport, caps) => `NetworkAgentInfo{network{${id}} ni{CONNECTED} nc{[Transports: ${transport} Capabilities: ${caps} LinkUpBandwidth>=100]}}`;

test('connectivity parser selects the active network and excludes history/private fields', () => {
  const parsed = parseConnectivity(dump(101, `${agent(100, 'WIFI', 'INTERNET&VALIDATED')}\n${agent(101, 'CELLULAR', 'INTERNET&VALIDATED&TRUSTED')}`));
  assert.deepEqual(parsed, { state: 'connected', internet: true, validated: true, metered: true, transport: 'Cellular' });
  assert.equal(JSON.stringify(parsed).includes('101'), false);
});
test('unrecognized dumps fail closed instead of pretending to be offline or connected', () => {
  assert.equal(parseConnectivity('different Android output').state, 'unknown');
  assert.equal(parseConnectivity(dump(100, '')).state, 'unknown');
  assert.deepEqual(parseConnectivity(dump('none', '')), { state: 'offline' });
});
test('network score text does not fabricate a validated capability', () => {
  const parsed = parseConnectivity(dump(100, agent(100, 'WIFI', 'INTERNET&NOT_METERED').replace('ni{CONNECTED}', 'Score(IS_VALIDATED) ni{CONNECTED}')));
  assert.equal(parsed.validated, false);
  assert.equal(parsed.metered, false);
});
test('a simulated run-8 observation gap preserves system connectivity without fabricating delivery', () => {
  const observation = new BackgroundObservation({ expectedTransport: 'Cellular' });
  // Device system says validated; app callbacks and server report are absent.
  observation.network(online, 1000); observation.reports([], 1100);
  const result = observation.result();
  assert.equal(result.status, 'network-observed-report-missing');
  assert.equal(result.timing_valid, false);
  assert.equal(result.available_to_server_observed_ms, null);
});
test('availability and validation intervals remain separate on one host clock', () => {
  const observation = new BackgroundObservation();
  observation.network({ ...online, validated: false }, 1000);
  observation.network(online, 4000); observation.reports([row], 5000);
  assert.equal(observation.result().available_to_server_observed_ms, 4000);
  assert.equal(observation.result().validated_to_server_observed_ms, 1000);
});
test('a report arriving before observed validation is retained with no made-up validation time', () => {
  const observation = new BackgroundObservation();
  observation.network({ ...online, validated: false }, 1000); observation.reports([row], 2000);
  assert.equal(observation.result().status, 'report-observed');
  assert.equal(observation.result().validated_to_server_observed_ms, null);
});
test('missing network evidence, errors and host pauses prevent positive timing claims', () => {
  const beforeNetwork = new BackgroundObservation(); beforeNetwork.reports([row], 1000);
  assert.equal(beforeNetwork.result().status, 'recovered-timing-unavailable');
  const gap = new BackgroundObservation(); gap.network(online, 0); gap.reports([row], 12000);
  assert.equal(gap.result().timing_valid, false);
  const failed = new BackgroundObservation(); failed.network(online, 0); failed.failed('backend', 50); failed.reports([row], 100);
  assert.equal(failed.result().timing_valid, false);
});
test('duplicate rows, wrong transport and backward clocks remain visible failures', () => {
  const observation = new BackgroundObservation({ expectedTransport: 'Cellular' });
  observation.network({ ...online, transport: 'WiFi' }, 100); observation.reports([row, { ...row, id: 'other' }], 200);
  assert.equal(observation.result().status, 'duplicate-reports');
  assert.deepEqual(observation.result().issues.sort(), ['duplicate-reports', 'unexpected-transport']);
  assert.throws(() => observation.network(online, 50), /Non-monotonic/);
});
