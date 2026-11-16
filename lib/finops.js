// JavaScript port of the Java engine (src/*.java) for Vercel serverless functions.
// Same live sources, same formulas, same JSON shape as the Java WebServer.
'use strict';

const UA = 'FinOpsPricingSimulator/2.0 (university cloud-computing project)';
const AZURE_API = 'https://prices.azure.com/api/retail/prices';
const AWS_SPOT_ADVISOR = 'https://spot-bid-advisor.s3.amazonaws.com/spot-advisor-data.json';
const WIKI = 'https://wikimedia.org/api/rest_v1/metrics/pageviews';
const TTL_MS = 6 * 3600 * 1000;
const DAYS = 30;

const REGIONS = {
  eastus: ['East US (Virginia)', 'us-east-1'],
  westus2: ['West US 2 (Washington)', 'us-west-2'],
  centralindia: ['Central India (Pune)', 'ap-south-1'],
  southindia: ['South India (Chennai)', 'ap-south-1'],
  northeurope: ['North Europe (Ireland)', 'eu-west-1'],
  westeurope: ['West Europe (Netherlands)', 'eu-central-1'],
  uksouth: ['UK South (London)', 'eu-west-2'],
  southeastasia: ['Southeast Asia (Singapore)', 'ap-southeast-1'],
  japaneast: ['Japan East (Tokyo)', 'ap-northeast-1'],
  australiaeast: ['Australia East (Sydney)', 'ap-southeast-2'],
};
const SKUS = {
  Standard_D2s_v5: ['D2s v5 - general purpose', '2', '8', 'm6i.large'],
  Standard_D4s_v5: ['D4s v5 - general purpose', '4', '16', 'm6i.xlarge'],
  Standard_D8s_v5: ['D8s v5 - general purpose', '8', '32', 'm6i.2xlarge'],
  Standard_E2s_v5: ['E2s v5 - memory optimised', '2', '16', 'r6i.large'],
  Standard_E4s_v5: ['E4s v5 - memory optimised', '4', '32', 'r6i.xlarge'],
  Standard_F2s_v2: ['F2s v2 - compute optimised', '2', '4', 'c5.large'],
  Standard_F4s_v2: ['F4s v2 - compute optimised', '4', '8', 'c5.xlarge'],
  Standard_B2s: ['B2s - burstable (no Spot)', '2', '4', 't3.medium'],
};
const CURRENCIES = ['USD', 'INR', 'EUR', 'GBP'];
const PRESETS = {
  STEADY: ['Steady', 'Whole English Wikipedia - total daily user traffic'],
  SPIKY: ['Spiky', "Today's most-spiky trending Wikipedia article"],
  SEASONAL: ['Seasonal', "Weekday business cycle - 'LinkedIn' article traffic"],
  ARTICLE: ['Custom Wikipedia article', 'Any English Wikipedia article you name'],
  CSV: ['My usage CSV', 'Your own daily usage export (date,value)'],
};

// ---------------------------------------------------------------- HTTP + cache (per warm instance)
const cache = new Map();
async function get(url) {
  const hit = cache.get(url);
  if (hit && Date.now() - hit.at < TTL_MS) return hit;
  let lastErr;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
      if (res.status === 200) {
        const entry = { body: await res.json(), at: Date.now() };
        cache.set(url, entry);
        return entry;
      }
      if (res.status === 404) throw Object.assign(new Error('HTTP 404 (not found)'), { notFound: true });
      if (res.status === 429 || res.status >= 500) { await new Promise(r => setTimeout(r, 1500 * (attempt + 1))); lastErr = new Error('HTTP ' + res.status); continue; }
      throw new Error('HTTP ' + res.status);
    } catch (e) {
      if (e.notFound) throw e;
      lastErr = e;
    }
  }
  if (hit) return { ...hit, stale: true };
  throw new Error('Could not reach ' + new URL(url).host + ': ' + (lastErr && lastErr.message));
}
const enc = s => encodeURIComponent(s);
const iso = ms => new Date(ms).toISOString();

const symbol = c => ({ INR: '₹', EUR: '€', GBP: '£' }[c] || '$');
const fmt = (v, c) => (v == null || Number.isNaN(v)) ? 'n/a' : symbol(c) + (Math.abs(v) < 1 ? v.toFixed(5) : v.toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 }));

// ---------------------------------------------------------------- prices
async function azureQuery(p, filter) {
  const items = [];
  let url = `${AZURE_API}?currencyCode=${enc("'" + p.currency + "'")}&$filter=${enc(filter)}`;
  for (let pages = 0; url && pages < 10; pages++) {
    const f = await get(url);
    p.fetchedAt = Math.min(p.fetchedAt, f.at);
    if (f.stale) p.stale = true;
    items.push(...f.body.Items);
    url = f.body.NextPageLink;
  }
  return items;
}

async function loadPrices(region, sku, currency) {
  if (!REGIONS[region]) throw new Error('Unsupported region ' + region);
  if (!SKUS[sku]) throw new Error('Unsupported VM size ' + sku);
  if (!CURRENCIES.includes(currency)) throw new Error('Unsupported currency ' + currency);
  const p = {
    region, regionLabel: REGIONS[region][0], sku, skuLabel: SKUS[sku][0], currency,
    awsRegion: REGIONS[region][1], awsType: SKUS[sku][3],
    payg: NaN, reserved1y: NaN, reserved3y: NaN, spot: NaN, storagePerGbMonth: NaN, egressTiers: [],
    spotBand: 'unknown', spotMonthlyInterruptionRate: NaN, fetchedAt: Date.now(), stale: false, sources: [], warnings: [],
  };
  const link = f => `${AZURE_API}?$filter=${enc(f)}`;

  const vmFilter = `serviceName eq 'Virtual Machines' and armRegionName eq '${region}' and armSkuName eq '${sku}'`;
  const stFilter = `serviceName eq 'Storage' and armRegionName eq '${region}' and productName eq 'General Block Blob v2' and skuName eq 'Hot LRS' and meterName eq 'Hot LRS Data Stored'`;
  const bwFilter = `serviceName eq 'Bandwidth' and armRegionName eq '${region}' and meterName eq 'Standard Data Transfer Out' and productName eq 'Rtn Preference: MGN'`;
  const [vm, st, bw, spotRisk] = await Promise.all([
    azureQuery(p, vmFilter), azureQuery(p, stFilter), azureQuery(p, bwFilter), get(AWS_SPOT_ADVISOR).catch(e => e),
  ]);
  const when = iso(p.fetchedAt), src = 'Azure Retail Prices API';

  for (const it of vm) {
    if (it.productName.includes('Windows') || it.meterName.includes('Low Priority')) continue;
    const price = it.unitPrice;
    if (it.type === 'Consumption' && it.meterName.endsWith('Spot')) {
      p.spot = price;
      p.sources.push(source(`Spot VM (${it.meterName})`, fmt(price, currency), 'per VM-hour', `${src} - meterId ${it.meterId}`, when, link(vmFilter)));
    } else if (it.type === 'Consumption') {
      p.payg = price;
      p.sources.push(source(`Pay-as-you-go VM (${it.meterName})`, fmt(price, currency), 'per VM-hour', `${src} - meterId ${it.meterId}`, when, link(vmFilter)));
    } else if (it.type === 'Reservation') {
      const three = String(it.reservationTerm).startsWith('3');
      const hours = three ? 3 * 8760 : 8760, hourly = price / hours;
      if (three) p.reserved3y = hourly; else p.reserved1y = hourly;
      p.sources.push(source(`Reserved ${it.reservationTerm} (${it.meterName})`, fmt(hourly, currency),
        `per VM-hour (${fmt(price, currency)} upfront / ${hours} h)`, src, when, link(vmFilter)));
    }
  }
  if (Number.isNaN(p.spot)) p.warnings.push(`Azure does not sell ${sku} as a Spot VM in ${region}, so the Spot strategies are unavailable.`);
  if (Number.isNaN(p.reserved1y)) p.warnings.push(`No reserved-instance price was published for ${sku} in ${region}.`);
  if (Number.isNaN(p.payg)) throw new Error(`Azure returned no pay-as-you-go price for ${sku} in ${region}`);

  const tier0 = st.find(it => it.tierMinimumUnits === 0);
  if (tier0) {
    p.storagePerGbMonth = tier0.unitPrice;
    p.sources.push(source('Blob storage, Hot LRS (first 50 TB)', fmt(tier0.unitPrice, currency), 'per GB-month', src, when, link(stFilter)));
  } else { p.storagePerGbMonth = 0; p.warnings.push('No storage price returned; storage cost set to 0.'); }

  bw.sort((a, b) => a.tierMinimumUnits - b.tierMinimumUnits);
  p.egressTiers = bw.map(it => [it.tierMinimumUnits, it.unitPrice]);
  if (p.egressTiers.length) p.sources.push(source('Internet egress (tiered)',
    p.egressTiers.map(([f, pr]) => `>${f} GB: ${fmt(pr, currency)}`).join('; '), 'per GB', src, when, link(bwFilter)));
  else p.warnings.push('No egress price returned; egress cost set to 0.');

  try {
    if (spotRisk instanceof Error) throw spotRisk;
    const root = spotRisk.body;
    const entry = root.spot_advisor[p.awsRegion].Linux[p.awsType];
    if (!entry) throw new Error('no entry for ' + p.awsType);
    p.spotBand = root.ranges[entry.r].label;
    p.spotMonthlyInterruptionRate = [0.025, 0.075, 0.125, 0.175, 0.25][Math.min(entry.r, 4)];
    p.sources.push(source(`Spot interruption frequency (${p.awsType}, ${p.awsRegion})`,
      `${p.spotBand} per month (model uses ${Math.round(p.spotMonthlyInterruptionRate * 1000) / 10}%)`,
      'share of Spot instances interrupted per month', 'AWS Spot Instance Advisor dataset', iso(spotRisk.at), AWS_SPOT_ADVISOR));
  } catch (e) {
    p.spotMonthlyInterruptionRate = 0.075; p.spotBand = '5-10% (fallback)';
    p.warnings.push(`Spot interruption data unavailable (${e.message}); assuming 7.5% per month.`);
  }
  return p;
}
const source = (item, value, unit, src, retrieved, url) => ({ item, value, unit, source: src, retrieved, url });

function egressCost(p, gb) {
  const t = p.egressTiers;
  let cost = 0;
  for (let k = 0; k < t.length; k++) {
    const from = t[k][0], to = k + 1 < t.length ? t[k + 1][0] : Infinity;
    if (gb <= from) break;
    cost += (Math.min(gb, to) - from) * t[k][1];
  }
  return cost;
}

// ---------------------------------------------------------------- workloads
const ymd = d => d.toISOString().slice(0, 10).replace(/-/g, '');
function range() {
  const end = new Date(); end.setUTCHours(0, 0, 0, 0); end.setUTCDate(end.getUTCDate() - 2);
  const start = new Date(end); start.setUTCDate(start.getUTCDate() - (DAYS - 1));
  return { start, end };
}

async function fromWiki(preset, name, url, start) {
  const f = await get(url);
  const byDay = {};
  for (const it of f.body.items) byDay[it.timestamp.slice(0, 8)] = it.views;
  const w = { preset, name, sourceUrl: url, source: 'Wikimedia Pageviews REST API (real measured user traffic)',
    fetchedAt: iso(f.at), stale: !!f.stale, dates: [], values: [] };
  for (let d = 0; d < DAYS; d++) {
    const day = new Date(start); day.setUTCDate(day.getUTCDate() + d);
    w.dates.push(day.toISOString().slice(0, 10));
    w.values.push(byDay[ymd(day)] || 0);
  }
  classify(w);
  return w;
}

async function article(preset, title, start, end) {
  const url = `${WIKI}/per-article/en.wikipedia/all-access/user/${enc(title)}/daily/${ymd(start)}/${ymd(end)}`;
  try { return await fromWiki(preset, 'Wikipedia article: ' + title.replace(/_/g, ' '), url, start); }
  catch (e) {
    if (e.notFound) throw new Error(`Wikipedia has no traffic data for '${title.replace(/_/g, ' ')}'. Check the exact article title.`);
    throw e;
  }
}

async function mostSpikyTrending(start, end) {
  const url = `${WIKI}/top/en.wikipedia/all-access/${end.getUTCFullYear()}/${String(end.getUTCMonth() + 1).padStart(2, '0')}/${String(end.getUTCDate()).padStart(2, '0')}`;
  const f = await get(url);
  const titles = f.body.items[0].articles.map(a => a.article)
    .filter(t => !t.includes(':') && t !== 'Main_Page' && !t.startsWith('.')).slice(0, 6);
  const ws = await Promise.all(titles.map(t => article('SPIKY', t, start, end).catch(() => null)));
  let best = null, bestRatio = -1;
  ws.forEach((w, i) => { if (!w) return; const r = Math.max(...w.values) / Math.max(1, median(w.values)); if (r > bestRatio) { bestRatio = r; best = titles[i]; } });
  if (!best) throw new Error('Could not read trending Wikipedia articles');
  return best;
}

async function loadWorkload(preset, articleTitle, csv) {
  const { start, end } = range();
  switch (preset) {
    case 'STEADY': return fromWiki(preset, 'en.wikipedia.org (all pages)', `${WIKI}/aggregate/en.wikipedia/all-access/user/daily/${ymd(start)}/${ymd(end)}`, start);
    case 'SEASONAL': return article(preset, 'LinkedIn', start, end);
    case 'SPIKY': return article(preset, await mostSpikyTrending(start, end), start, end);
    case 'ARTICLE':
      if (!articleTitle || !articleTitle.trim()) throw new Error('Enter a Wikipedia article title, e.g. Cricket');
      return article(preset, articleTitle.trim().replace(/ /g, '_'), start, end);
    case 'CSV': return fromCsv(csv);
    default: throw new Error('Unknown workload ' + preset);
  }
}

function fromCsv(csv) {
  if (!csv || !csv.trim()) throw new Error('Paste or load a CSV with rows of: date,value');
  const w = { preset: 'CSV', name: 'Uploaded usage data', sourceUrl: '', source: 'User-supplied usage export (CSV)',
    fetchedAt: new Date().toISOString(), stale: false, dates: [], values: [] };
  for (const line of csv.split(/\r?\n/)) {
    const parts = line.trim().split(/[,;\t]/);
    if (parts.length < 2) continue;
    const v = Number(parts[parts.length - 1].trim().replace(/"/g, ''));
    if (parts[parts.length - 1].trim() === '' || Number.isNaN(v)) continue;
    w.dates.push(parts[0].trim().replace(/"/g, '')); w.values.push(v);
  }
  if (w.values.length < 7) throw new Error(`CSV needs at least 7 daily rows of: date,value (found ${w.values.length})`);
  classify(w);
  return w;
}

const mean = v => v.reduce((a, b) => a + b, 0) / v.length;
function median(v) { const s = [...v].sort((a, b) => a - b), n = s.length; return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2; }
function stdDev(v) { const m = mean(v); return Math.sqrt(mean(v.map(x => (x - m) ** 2))); }
function autocorrelation(v, lag) {
  if (v.length <= lag) return 0;
  const m = mean(v); let num = 0, den = 0;
  for (let i = 0; i < v.length; i++) { den += (v[i] - m) ** 2; if (i + lag < v.length) num += (v[i] - m) * (v[i + lag] - m); }
  return den === 0 ? 0 : num / den;
}
function classify(w) {
  const v = w.values, m = mean(v), cv = m === 0 ? 0 : stdDev(v) / m;
  const spike = Math.max(...v) / Math.max(1e-9, median(v)), weekly = autocorrelation(v, 7);
  if (spike >= 2) { w.detectedPattern = 'Spiky'; w.patternReason = `Peak day is ${spike.toFixed(1)}x the median day.`; }
  else if (weekly >= 0.35 || cv >= 0.12) { w.detectedPattern = 'Seasonal'; w.patternReason = `Repeating weekly cycle (lag-7 autocorrelation ${weekly.toFixed(2)}, variation ${Math.round(cv * 100)}%).`; }
  else { w.detectedPattern = 'Steady'; w.patternReason = `Low day-to-day variation (${Math.round(cv * 100)}%), peak only ${spike.toFixed(2)}x the median.`; }
}

// ---------------------------------------------------------------- engine
const STRATEGIES = [['PAYG', 'Pay-as-you-go'], ['RESERVED', 'Reserved instances'], ['SPOT', 'Spot (interruptible)'], ['HYBRID', 'Hybrid: Reserved + Spot']];

function demandHours(w, inp) {
  const v = w.values.length > 30 ? w.values.slice(-30) : w.values;
  const m = mean(v);
  if (m <= 0) throw new Error('The selected workload has no traffic in this period.');
  return v.map(x => 24 * inp.avgFleet * (x / m) * inp.demandMultiplier);
}
function sharedCost(p, inp, days) { const f = days / 30; return inp.storageGb * p.storagePerGbMonth * f + egressCost(p, inp.egressGb * f); }

function optimalReservation(hours, payg, res, spot) {
  const over = Number.isNaN(spot) ? payg : spot, maxVms = Math.ceil(Math.max(...hours) / 24);
  let best = 0, bestCost = Infinity;
  for (let c = 0; c <= maxVms; c++) {
    let cost = 0;
    for (const h of hours) cost += c * 24 * res + Math.max(0, h - c * 24) * over;
    if (cost < bestCost - 1e-9) { bestCost = cost; best = c; }
  }
  return best;
}
function applyReserved(r, hours, c, payg, res, spot, extra) {
  const total = hours.reduce((a, b) => a + b, 0), perDay = c * 24, over = Number.isNaN(spot) ? payg : spot;
  let used = 0, compute = 0;
  hours.forEach((h, i) => {
    const o = Math.max(0, h - perDay);
    used += Math.min(h, perDay);
    r.daily[i] = perDay * res + o * over + (total === 0 ? 0 : extra * h / total);
    compute += perDay * res + o * over;
  });
  r.compute = compute; r.reservedVms = c;
  r.reservationUtilisation = c === 0 ? NaN : used / (perDay * hours.length);
}

function simulate(p, key, label, hours, inp) {
  const days = hours.length, pm = inp.priceMultiplier;
  const payg = p.payg * pm, res = (inp.termYears === 3 ? p.reserved3y : p.reserved1y) * pm, spot = p.spot * pm;
  const shared = sharedCost(p, inp, days), total = hours.reduce((a, b) => a + b, 0);
  const r = { key, label, available: true, eligible: true, unavailableReason: '', total: 0, compute: 0, shared, rework: 0, impact: 0,
    variability: 0, interruptions: 0, savingsVsPayg: 0, reservedVms: 0, reservationUtilisation: NaN, daily: new Array(days).fill(0), effect: '', reason: '' };
  if ((key === 'RESERVED' || key === 'HYBRID') && Number.isNaN(res)) { r.available = false; r.unavailableReason = 'No reserved price published for this VM size/region.'; }
  if ((key === 'SPOT' || key === 'HYBRID') && Number.isNaN(spot)) { r.available = false; r.unavailableReason = 'Azure does not offer this VM size as Spot in this region.'; }
  if ((key === 'SPOT' || key === 'HYBRID') && !inp.interruptionTolerant) { r.eligible = false; r.unavailableReason = 'Workload marked as NOT interruption-tolerant.'; }
  if (!r.available) { r.total = NaN; r.effect = r.unavailableReason; return r; }
  const reworkPer = (inp.restartMinutes + inp.checkpointMinutes / 2) / 60;

  if (key === 'PAYG') {
    hours.forEach((h, i) => { r.daily[i] = h * payg; });
    r.compute = total * payg;
    r.effect = 'No commitment, no interruptions';
    r.reason = 'Maximum flexibility, but every hour is billed at the full on-demand rate.';
  } else if (key === 'RESERVED') {
    const c = optimalReservation(hours, payg, res, NaN);
    applyReserved(r, hours, c, payg, res, NaN, 0);
    if (c === 0) {
      r.effect = 'Demand too variable - no VM is busy enough to justify a reservation';
      r.reason = `A reservation only pays off when it is used more than ${Math.round(100 * res / payg)}% of the time.`;
    } else {
      const u = r.reservationUtilisation;
      r.effect = `${c} VMs reserved for ${inp.termYears}-yr term, ${Math.round(u * 100)}% utilised; peaks on pay-as-you-go`;
      r.reason = `A reservation pays off when it is used more than ${Math.round(100 * res / payg)}% of the time (reserved/on-demand price ratio). The optimiser reserved the baseline that is used ${Math.round(u * 100)}% of the time.`;
    }
  } else if (key === 'SPOT') {
    const avgFleet = total / (24 * days);
    r.interruptions = avgFleet * p.spotMonthlyInterruptionRate * days / 30;
    r.rework = r.interruptions * reworkPer * spot;
    r.impact = r.interruptions * inp.impactPerInterruption;
    hours.forEach((h, i) => { r.daily[i] = h * spot + (r.rework + r.impact) * h / total; });
    r.compute = total * spot;
    r.effect = `~${r.interruptions.toFixed(1)} evictions/month (${p.spotBand} rate); ${(r.interruptions * reworkPer).toFixed(1)} VM-hours of rework`;
    r.reason = 'Spot capacity is the cheapest rate but can be reclaimed at short notice; rework from evictions is included.';
  } else {
    const c = optimalReservation(hours, payg, res, spot);
    let overflow = 0; for (const h of hours) overflow += Math.max(0, h - c * 24);
    r.interruptions = (overflow / (24 * days)) * p.spotMonthlyInterruptionRate * days / 30;
    r.rework = r.interruptions * reworkPer * spot;
    r.impact = r.interruptions * inp.impactPerInterruption;
    applyReserved(r, hours, c, payg, res, spot, r.rework + r.impact);
    const share = total === 0 ? 0 : overflow / total;
    if (c === 0) {
      r.effect = `Spot is cheaper than any reservation, so hybrid = 100% Spot; ~${r.interruptions.toFixed(1)} evictions/month`;
      r.reason = 'Spot already undercuts the reserved rate, so reserving a baseline would only add cost.';
    } else {
      r.effect = `${c} VMs reserved (${Math.round(r.reservationUtilisation * 100)}% utilised) + ${Math.round(share * 100)}% of hours on Spot; ~${r.interruptions.toFixed(1)} evictions/month`;
      r.reason = 'Reserve the always-on baseline, burst above it on Spot - the standard FinOps pattern for variable demand.';
    }
  }
  r.daily = r.daily.map(d => d + shared / days);
  r.total = r.compute + r.shared + r.rework + r.impact;
  r.variability = stdDev(r.daily);
  return r;
}

function simulateAll(p, hours, inp) {
  const out = STRATEGIES.map(([k, l]) => simulate(p, k, l, hours, inp));
  out.forEach(r => { r.savingsVsPayg = out[0].total - r.total; });
  return out;
}
const best = rs => rs.filter(r => r.available && r.eligible).reduce((b, r) => (!b || r.total < b.total ? r : b), null);
const bestGuaranteed = rs => (rs[1].available && rs[1].total < rs[0].total ? rs[1] : rs[0]);

function sensitivity(p, w, inp) {
  const demandFactors = [0.8, 1, 1.2], priceFactors = [0.8, 1, 1.2];
  const grid = priceFactors.map(pf => demandFactors.map(df => {
    const x = { ...inp, demandMultiplier: inp.demandMultiplier * df, priceMultiplier: inp.priceMultiplier * pf };
    const b = best(simulateAll(p, demandHours(w, x), x));
    return { strategy: b.label, total: b.total };
  }));
  const spotRisk = [0.025, 0.075, 0.125, 0.175, 0.25, 0.5].map(rate => {
    const rs = simulateAll({ ...p, spotMonthlyInterruptionRate: rate }, demandHours(w, inp), inp);
    return { rate, spot: num(rs[2].total), hybrid: num(rs[3].total), reserved: num(rs[1].total), payg: rs[0].total };
  });
  return { demandFactors, priceFactors, grid, spotRisk };
}
const num = v => (Number.isNaN(v) ? null : v);

const DEPARTMENTS = [['Engineering', 'SPIKY', 0.45], ['Analytics', 'SEASONAL', 0.30], ['Marketing', 'STEADY', 0.25]];
async function chargeback(p, inp) {
  const ws = await Promise.all(DEPARTMENTS.map(d => loadWorkload(d[1])));
  return DEPARTMENTS.map(([department, , share], i) => {
    const w = ws[i];
    const di = { ...inp, avgFleet: Math.max(1, inp.avgFleet * share), storageGb: inp.storageGb * share, egressGb: inp.egressGb * share };
    const rs = simulateAll(p, demandHours(w, di), di), b = best(rs);
    return { department, workload: w.name, pattern: w.detectedPattern, share, fleet: di.avgFleet, strategy: b.label,
      cost: b.total, paygCost: rs[0].total, savings: rs[0].total - b.total };
  });
}

function recommendationText(p, w, inp, rs, b) {
  const s = symbol(p.currency), m = v => s + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  let t = `For the ${PRESETS[w.preset][0]} workload (${w.name} - detected as ${w.detectedPattern}), choose ${b.label} at about ${m(b.total)} for ${Math.min(30, w.values.length)} days.`;
  if (b.savingsVsPayg > 0.005) t += ` That saves ${m(b.savingsVsPayg)} (${(100 * b.savingsVsPayg / rs[0].total).toFixed(1)}%) against pay-as-you-go.`;
  t += ' ' + b.reason;
  if (!inp.interruptionTolerant) t += ' Spot options were excluded because the workload is marked as not interruption-tolerant.';
  else if (b.key === 'SPOT' || b.key === 'HYBRID') {
    const g = bestGuaranteed(rs);
    t += ` Only use Spot if the application can checkpoint and survive VM evictions. If capacity must be guaranteed, the best choice is ${g.label} at ${m(g.total)}.`;
  }
  return t;
}

const clamp = (v, lo, hi, def) => { const n = Number(v); return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : def; };

async function report(q, csv) {
  const inp = {
    avgFleet: clamp(q.fleet, 1, 5000, 10), demandMultiplier: clamp(q.demand, 0.1, 10, 1), priceMultiplier: clamp(q.price, 0.1, 10, 1),
    storageGb: clamp(q.storage, 0, 1e7, 500), egressGb: clamp(q.egress, 0, 1e7, 300), termYears: q.term === '3' ? 3 : 1,
    interruptionTolerant: q.tolerant !== 'false', restartMinutes: clamp(q.restart, 0, 600, 5),
    checkpointMinutes: clamp(q.checkpoint, 0, 1440, 30), impactPerInterruption: clamp(q.impact, 0, 1e7, 0),
  };
  const preset = q.workload || 'STEADY';
  const [p, w] = await Promise.all([
    loadPrices(q.region || 'eastus', q.sku || 'Standard_D2s_v5', q.currency || 'USD'),
    loadWorkload(preset, q.article, csv),
  ]);
  const hours = demandHours(w, inp), rs = simulateAll(p, hours, inp), b = best(rs), g = bestGuaranteed(rs);
  const out = {
    generatedAt: new Date().toISOString(),
    prices: {
      region: p.region, regionLabel: p.regionLabel, sku: p.sku, skuLabel: p.skuLabel, currency: p.currency, symbol: symbol(p.currency),
      payg: p.payg, reserved1y: num(p.reserved1y), reserved3y: num(p.reserved3y), spot: num(p.spot), storagePerGbMonth: p.storagePerGbMonth,
      spotBand: p.spotBand, spotMonthlyInterruptionRate: p.spotMonthlyInterruptionRate, awsEquivalent: `${p.awsType} in ${p.awsRegion}`,
      fetchedAt: iso(p.fetchedAt), stale: p.stale, sources: p.sources, warnings: p.warnings,
    },
    workload: { name: w.name, preset: PRESETS[w.preset][0], source: w.source, sourceUrl: w.sourceUrl, fetchedAt: w.fetchedAt, stale: w.stale,
      detectedPattern: w.detectedPattern, patternReason: w.patternReason, dates: w.dates, rawValues: w.values, vmHours: hours },
    results: rs.map(r => ({ ...r, total: num(r.total), reservationUtilisation: num(r.reservationUtilisation) })),
    recommendation: { strategy: b.label, key: b.key, total: b.total, savingsVsPayg: b.savingsVsPayg,
      savingsPct: rs[0].total === 0 ? 0 : 100 * b.savingsVsPayg / rs[0].total, text: recommendationText(p, w, inp, rs, b),
      guaranteedStrategy: g.label, guaranteedTotal: g.total, guaranteedSavingsVsPayg: g.savingsVsPayg },
    sensitivity: sensitivity(p, w, inp),
  };
  if (q.chargeback !== 'false') out.chargeback = await chargeback(p, inp);
  return out;
}

function meta() {
  return {
    regions: Object.entries(REGIONS).map(([id, v]) => ({ id, label: v[0] })),
    skus: Object.entries(SKUS).map(([id, v]) => ({ id, label: v[0], vcpu: v[1], ramGb: v[2] })),
    currencies: CURRENCIES,
    presets: Object.entries(PRESETS).map(([id, v]) => ({ id, label: v[0], description: v[1] })),
  };
}

module.exports = { report, meta };
