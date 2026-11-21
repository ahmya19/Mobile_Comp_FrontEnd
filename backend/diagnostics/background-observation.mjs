/** Extract only connectivity facts. Never retain network names, addresses or IDs. */
export function parseConnectivity(text) {
  const active = text.match(/(?:Current active default network|Active default network):\s*([^\r\n]+)/i)?.[1].trim();
  if (/^(none|null|-1)$/i.test(active ?? '')) return { state: 'offline' };
  if (!/^\d+$/.test(active ?? '')) return { state: 'unknown', reason: 'default-network-unparsed' };
  const section = text.split(/Current Networks:\s*/i)[1]?.split(/Network Requests:/i)[0];
  const agents = section?.split(/(?=NetworkAgentInfo\{)/) ?? [];
  const agent = agents.find(value => value.startsWith(`NetworkAgentInfo{network{${active}}`));
  const caps = agent?.match(/Capabilities:\s*([A-Z_&|]+)/)?.[1].split(/[&|]/);
  const transports = agent?.match(/Transports:\s*(.*?)\s+Capabilities:/s)?.[1].trim().split(/[|\s]+/);
  if (!caps || !transports) return { state: 'unknown', reason: 'active-capabilities-unparsed' };
  return {
    state: 'connected', internet: caps.includes('INTERNET'), validated: caps.includes('VALIDATED'),
    metered: !caps.includes('NOT_METERED'),
    transport: transports.includes('VPN') ? 'VPN' : transports.includes('WIFI') ? 'WiFi' :
      transports.includes('CELLULAR') ? 'Cellular' : 'Other',
  };
}

/** Host-clock observations do not require callbacks or code running in the app. */
export class BackgroundObservation {
  constructor({ expectedTransport = 'Any', maxGapMs = 10000 } = {}) {
    this.expectedTransport = expectedTransport;
    this.maxGapMs = maxGapMs;
    this.availableAt = null;
    this.validatedAt = null;
    this.reportAt = null;
    this.lastAt = null;
    this.transports = new Set();
    this.issues = new Set();
    this.rows = [];
  }
  tick(at) {
    if (!Number.isFinite(at) || at < 0 || (this.lastAt !== null && at < this.lastAt)) throw new Error('Non-monotonic observation time');
    if (this.lastAt !== null && at - this.lastAt > this.maxGapMs) this.issues.add('host-observation-gap');
    this.lastAt = at;
  }
  network(system, at) {
    this.tick(at);
    if (system.state === 'unknown') this.issues.add('system-network-unparsed');
    if (system.state === 'connected' && system.internet) {
      this.availableAt ??= at;
      this.transports.add(system.transport);
      if (system.validated) this.validatedAt ??= at;
      if (this.expectedTransport !== 'Any' && system.transport !== this.expectedTransport) this.issues.add('unexpected-transport');
    }
  }
  reports(rows, at) {
    this.tick(at);
    this.rows = rows;
    if (rows.length) {
      this.reportAt ??= at;
      if (this.availableAt === null) this.issues.add('report-before-network-observation');
      if (rows.length > 1) this.issues.add('duplicate-reports');
    }
  }
  failed(source, at) { this.tick(at); this.issues.add(`${source}-read-failed`); }
  result() {
    const recovered = this.rows.length === 1;
    const valid = recovered && this.availableAt !== null && this.issues.size === 0;
    return {
      status: this.rows.length > 1 ? 'duplicate-reports' : recovered ?
        (valid ? 'report-observed' : 'recovered-timing-unavailable') :
        this.availableAt !== null ? 'network-observed-report-missing' : 'awaiting-network',
      copies: this.rows.length, timing_valid: valid, transports: [...this.transports], issues: [...this.issues],
      available_to_server_observed_ms: valid ? this.reportAt - this.availableAt : null,
      validated_to_server_observed_ms: valid && this.validatedAt !== null && this.validatedAt <= this.reportAt ? this.reportAt - this.validatedAt : null,
    };
  }
}
