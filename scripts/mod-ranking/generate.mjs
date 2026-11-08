#!/usr/bin/env node
// Generates remote/mod-ranking.json from MDTCopper/mindustry-data-index.
//
// The launcher fetches the generated file; weights live in config.json so a
// ranking tweak does not require a launcher release. The output is intentionally
// deterministic: the same input data and config always produce the same order.

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const CONFIG_PATH = resolve(HERE, 'config.json');
const FETCH_TIMEOUT_MS = 60_000;
const DAY_MS = 86_400_000;

// CI reaches raw.githubusercontent.com directly; local Windows networks often
// do not. Try direct first, then the same mirrors the launcher uses.
const FETCH_MIRRORS = [
  (url) => url,
  (url) => `https://gh-proxy.net/${url}`,
  (url) => `https://ghfast.top/${url}`,
  (url) => `https://gh.llkk.cc/${url}`,
];

/** Reads and validates the generator config. */
async function loadConfig() {
  const config = JSON.parse(await readFile(CONFIG_PATH, 'utf8'));
  const weights = config.weights ?? {};
  const sum = ['popularity', 'activity', 'freshness', 'compatibility']
    .reduce((total, key) => total + numberOr(weights[key], 0), 0);
  if (Math.abs(sum - 1) > 0.0001) {
    throw new Error(`weights must sum to 1.0, got ${sum}`);
  }
  return config;
}

/** Fetches one JSON document with a bounded timeout and mirror fallback. */
async function fetchJson(url, label) {
  let lastError = null;
  for (const mirror of FETCH_MIRRORS) {
    const candidate = mirror(url);
    try {
      const response = await fetch(candidate, {
        headers: { 'User-Agent': 'Xenon-mod-ranking/1' },
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      return await response.json();
    } catch (error) {
      lastError = error;
      if (candidate !== url) {
        console.warn(`${label}: retrying through ${candidate.split('/')[2]}`);
      }
    }
  }
  throw new Error(`${label}: ${lastError instanceof Error ? lastError.message : lastError}`);
}

/** Reads one JSON document from an explicit input directory. */
async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

/** Loads the four source documents, optionally from a local input directory. */
async function loadInputs(config) {
  const inputDir = process.env.MOD_RANKING_INPUT_DIR;
  if (inputDir) {
    const directory = resolve(inputDir);
    return await Promise.all([
      readJson(resolve(directory, 'index-full.json')),
      readJson(resolve(directory, 'repo-created.json')),
      readJson(resolve(directory, 'commit-stats.json')),
      readJson(resolve(directory, 'versions.json')),
    ]);
  }
  return await Promise.all([
    fetchJson(config.source.index, 'mod index'),
    fetchJson(config.source.repoCreated, 'repo-created'),
    fetchJson(config.source.commitStats, 'commit stats'),
    fetchJson(config.source.versions, 'mindustry versions'),
  ]);
}

/** Coerces a JSON value to a finite number. */
function numberOr(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

/** Lower-cases a repo slug for cross-source joins. */
function repoKey(repo) {
  return String(repo ?? '').trim().toLowerCase();
}

/** Parses `v160.7` / `160` / `160.10` into a comparable tuple. */
function parseVersion(value) {
  const match = /^v?(\d+)(?:\.(\d+))?/i.exec(String(value ?? '').trim());
  if (!match) return null;
  return [Number(match[1]), Number(match[2] ?? 0)];
}

/** Compares two version tuples. */
function compareVersions(left, right) {
  if (left[0] !== right[0]) return left[0] - right[0];
  return left[1] - right[1];
}

/** Returns the newest stable version advertised by the version dataset. */
function latestStableVersion(versions) {
  for (const release of Array.isArray(versions?.releases) ? versions.releases : []) {
    const parsed = parseVersion(release?.tag_name);
    if (parsed) return parsed;
  }
  return null;
}

/** Returns whether one mod's ranges cover the latest stable version. */
function supportsVersion(ranges, latest) {
  if (!Array.isArray(ranges) || latest == null) return false;
  return ranges.some((range) => {
    const from = parseVersion(range?.from);
    const to = parseVersion(range?.to);
    return from != null && to != null
      && compareVersions(from, latest) <= 0
      && compareVersions(latest, to) <= 0;
  });
}

/** Builds a value -> percentile map; ties share their average rank. */
function percentileMap(values) {
  const count = values.length;
  const sorted = values
    .map((value, index) => ({ value, index }))
    .sort((left, right) => left.value - right.value || left.index - right.index);
  const result = new Map();
  let position = 0;
  while (position < count) {
    let end = position;
    while (end + 1 < count && sorted[end + 1].value === sorted[position].value) {
      end += 1;
    }
    const percentile = count > 1 ? ((position + end) / 2) / (count - 1) : 0.5;
    result.set(sorted[position].value, percentile);
    position = end + 1;
  }
  return result;
}

/** Sums the newest N weekly commit counts, accepting sparse arrays. */
function commitsInWindow(weeks, windowWeeks) {
  if (!Array.isArray(weeks)) return 0;
  let total = 0;
  const from = Math.max(0, weeks.length - windowWeeks);
  for (let index = from; index < weeks.length; index += 1) {
    total += numberOr(weeks[index], 0);
  }
  return total;
}

/** Returns days since an ISO timestamp, or `null` when it cannot be parsed. */
function daysSince(iso, now) {
  if (!iso) return null;
  const time = Date.parse(iso);
  return Number.isFinite(time) ? Math.max(0, (now - time) / DAY_MS) : null;
}

/** Rounds to four decimals for stable JSON output. */
function round(value) {
  return Math.round(value * 10_000) / 10_000;
}

/** Creates a case-insensitive lookup for the repo-created cache. */
function repoCreatedMap(repoCreated) {
  const map = new Map();
  for (const [repo, createdAt] of Object.entries(repoCreated?.repos ?? {})) {
    map.set(repoKey(repo), createdAt);
  }
  return map;
}

/** Creates a case-insensitive lookup for the weekly commit cache. */
function commitStatsMap(commitStats) {
  const map = new Map();
  for (const [repo, weeks] of Object.entries(commitStats?.stats ?? {})) {
    const key = repoKey(repo);
    if (!map.has(key)) map.set(key, weeks);
  }
  return map;
}

/** Adds one badge once, preserving order. */
function addBadge(badges, badge) {
  if (badge && !badges.includes(badge)) badges.push(badge);
}

/** Compares two documents while ignoring their `generatedAt` timestamps. */
function sameContent(next, previous) {
  if (previous == null || typeof previous !== 'object') return false;
  const strip = (value) => {
    const { generatedAt: _ignored, ...rest } = value;
    return JSON.stringify(rest);
  };
  return strip(next) === strip(previous);
}

/** Computes one score row before percentiles are known. */
function buildRow(mod, config, createdByRepo, commitsByRepo, latestVersion, now) {
  const key = repoKey(mod?.repo);
  const stars = Math.max(0, numberOr(mod?.stars, 0));
  const downloads = Math.max(0, numberOr(mod?.totalDownloads, 0));
  const releaseCount = Math.max(0, numberOr(mod?.releaseCount, 0));

  const createdAt = createdByRepo.get(key);
  const ageMonths = createdAt
    ? Math.max(0.5, (now - Date.parse(createdAt)) / DAY_MS / 30)
    : 24;
  const releaseRate = Math.min(
    numberOr(config.activity?.releaseRateCapPerMonth, 20),
    releaseCount / ageMonths,
  );
  const commits = commitsInWindow(
    commitsByRepo.get(key),
    numberOr(config.activity?.commitWindowWeeks, 13),
  );

  const releaseUpdatedAt = mod?.releaseUpdatedAt ?? mod?.commitUpdatedAt ?? null;
  const releaseDays = daysSince(releaseUpdatedAt, now);
  const freshness = releaseDays == null ? 0 : Math.exp(-releaseDays / 90);
  const compatibility = supportsVersion(mod?.gameVersionRanges, latestVersion)
    ? 1
    : numberOr(config.compatibility?.unsupportedScore, 0.6);
  const heatRank = Math.max(1, numberOr(mod?.ranks?.heat, Number.MAX_SAFE_INTEGER));

  return {
    repo: String(mod?.repo ?? '').trim(),
    key,
    stars,
    downloads,
    releaseCount,
    releaseRate,
    commits,
    releaseDays,
    freshness,
    compatibility,
    heatRank,
    score: 0,
    rank: 0,
    popularRank: 0,
  };
}

/** Adds a badge set to one row using the configured owner/bundle rules. */
function badgesFor(row, config, latestVersion) {
  const badges = [];
  const owner = row.key.includes('/') ? row.key.slice(0, row.key.indexOf('/')) : '';
  const officialOwners = Array.isArray(config.officialOwners)
    ? config.officialOwners.map((value) => String(value).toLowerCase())
    : [];
  if (owner && officialOwners.includes(owner)) addBadge(badges, 'official');

  const bundles = config.badges?.bundles ?? {};
  for (const [repo, badge] of Object.entries(bundles)) {
    if (repoKey(repo) === row.key) addBadge(badges, String(badge));
  }
  if (row.compatibility >= 1 && latestVersion != null) addBadge(badges, 'latest-compatible');
  return badges;
}

/** Runs the generator and writes the ranking file. */
async function main() {
  const config = await loadConfig();
  const [index, repoCreated, commitStats, versions] = await loadInputs(config);

  const mods = Array.isArray(index?.mods) ? index.mods : [];
  if (mods.length === 0) throw new Error('mod index returned no mods');

  const latestVersion = latestStableVersion(versions);
  const latestVersionText = latestVersion == null
    ? null
    : `${latestVersion[0]}.${latestVersion[1]}`;
  const createdByRepo = repoCreatedMap(repoCreated);
  const commitsByRepo = commitStatsMap(commitStats);
  const now = Date.now();

  const rows = mods.map((mod) => buildRow(
    mod, config, createdByRepo, commitsByRepo, latestVersion, now,
  ));

  const downloadPercentile = percentileMap(rows.map((row) => row.downloads));
  const starPercentile = percentileMap(rows.map((row) => row.stars));
  // log1p keeps a single hyperactive repo from flattening everyone else's
  // release-rate percentile; the cap already bounds the raw input.
  const releaseRatePercentile = percentileMap(rows.map((row) => Math.log1p(row.releaseRate)));
  const commitPercentile = percentileMap(rows.map((row) => row.commits));
  const freshnessPercentile = percentileMap(rows.map((row) => row.freshness));

  const weights = config.weights;
  const releaseRateWeight = numberOr(config.activity?.releaseRateWeight, 0.6);
  const commitWeight = numberOr(config.activity?.commitWeight, 0.4);
  const activityWeightSum = releaseRateWeight + commitWeight;
  if (Math.abs(activityWeightSum - 1) > 0.0001) {
    throw new Error(`activity weights must sum to 1.0, got ${activityWeightSum}`);
  }

  for (const row of rows) {
    const popularity = 0.6 * downloadPercentile.get(row.downloads)
      + 0.4 * starPercentile.get(row.stars);
    const activity = releaseRateWeight * releaseRatePercentile.get(Math.log1p(row.releaseRate))
      + commitWeight * commitPercentile.get(row.commits);
    row.components = {
      popularity,
      activity,
      freshness: freshnessPercentile.get(row.freshness),
      compatibility: row.compatibility,
    };
    row.score = numberOr(weights.popularity, 0) * popularity
      + numberOr(weights.activity, 0) * activity
      + numberOr(weights.freshness, 0) * freshnessPercentile.get(row.freshness)
      + numberOr(weights.compatibility, 0) * row.compatibility;
  }

  const eligibility = config.eligibility ?? {};
  const minStars = numberOr(eligibility.minStars, 10);
  const minDownloads = numberOr(eligibility.minDownloads, 1000);
  const maxDaysSinceRelease = numberOr(eligibility.maxDaysSinceRelease, 365);
  for (const row of rows) {
    row.eligible = (row.stars >= minStars || row.downloads >= minDownloads)
      && row.releaseDays != null
      && row.releaseDays <= maxDaysSinceRelease;
  }

  const ranked = rows
    .filter((row) => row.eligible)
    .sort((left, right) => right.score - left.score
      || right.components.activity - left.components.activity
      || left.key.localeCompare(right.key));
  ranked.forEach((row, indexValue) => {
    row.rank = indexValue + 1;
  });

  const popular = [...rows].sort((left, right) => left.heatRank - right.heatRank
    || right.score - left.score
    || left.key.localeCompare(right.key));
  popular.forEach((row, indexValue) => {
    row.popularRank = indexValue + 1;
  });

  const ordered = [...ranked, ...rows
    .filter((row) => !row.eligible)
    .sort((left, right) => right.score - left.score || left.key.localeCompare(right.key))];

  const entries = ordered.map((row) => ({
    repo: row.repo,
    rank: row.rank,
    score: round(row.score),
    popularRank: row.popularRank,
    downloads: row.downloads,
    stars: row.stars,
    components: {
      popularity: round(row.components.popularity),
      activity: round(row.components.activity),
      freshness: round(row.components.freshness),
      compatibility: round(row.components.compatibility),
    },
    badges: badgesFor(row, config, latestVersion),
  }));

  const output = {
    formatVersion: numberOr(config.formatVersion, 1),
    methodology: {
      name: 'Xenon recommendation',
      axis: 'maintenance-first',
      latestGameVersion: latestVersionText,
      weights: {
        popularity: numberOr(weights.popularity, 0),
        activity: numberOr(weights.activity, 0),
        freshness: numberOr(weights.freshness, 0),
        compatibility: numberOr(weights.compatibility, 0),
      },
      activity: {
        releaseRateWeight,
        commitWeight,
        releaseRateCapPerMonth: numberOr(config.activity?.releaseRateCapPerMonth, 20),
        commitWindowWeeks: numberOr(config.activity?.commitWindowWeeks, 13),
      },
      eligibility: { minStars, minDownloads, maxDaysSinceRelease },
      source: config.source,
    },
    entries,
  };

  const outputPath = resolve(ROOT, config.output ?? 'remote/mod-ranking.json');
  // Keep the previous timestamp when only the clock changed, so the daily
  // workflow does not create empty commits.
  let generatedAt = new Date().toISOString();
  try {
    const previous = JSON.parse(await readFile(outputPath, 'utf8'));
    if (sameContent(output, previous)) {
      generatedAt = String(previous.generatedAt ?? generatedAt);
    }
  } catch {
    // First run or an unreadable previous file: use the fresh timestamp.
  }
  const document = {
    formatVersion: output.formatVersion,
    generatedAt,
    methodology: output.methodology,
    entries: output.entries,
  };
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${JSON.stringify(document, null, 2)}\n`, 'utf8');

  const top = entries.filter((entry) => entry.rank > 0).slice(0, 10);
  console.log(`Wrote ${entries.length} entries to ${outputPath}`);
  console.log(`Latest game version: ${latestVersionText ?? 'unknown'}`);
  for (const entry of top) {
    console.log(`  #${entry.rank} ${entry.repo} score=${entry.score}`);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack ?? error.message : error);
  process.exitCode = 1;
});
