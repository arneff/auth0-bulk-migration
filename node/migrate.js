/**
 * Auth0 Bulk User Migration — Node.js
 *
 * Uses the Auth0 Node.js Management SDK (auth0 v4).
 * ManagementClient automatically handles token acquisition and refresh
 * via the OAuth 2.0 Client Credentials grant — no manual token exchange needed.
 *
 * Auth0 limits: max 2 concurrent import jobs per tenant.
 * File limit: 500 KB per import file.
 *
 * Usage:
 *   cp .env.example .env   (fill in your values)
 *   npm install
 *   npm start
 */

'use strict';

require('dotenv').config();
const { ManagementClient } = require('auth0');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
const DOMAIN = process.env.AUTH0_DOMAIN;
const CLIENT_ID = process.env.AUTH0_CLIENT_ID;
const CLIENT_SECRET = process.env.AUTH0_CLIENT_SECRET;
const CONNECTION_ID = process.env.AUTH0_CONNECTION_ID;
const UPSERT = process.env.AUTH0_UPSERT === 'true';
const SEND_EMAIL = process.env.AUTH0_SEND_EMAIL === 'true';

const MAX_CONCURRENT_JOBS = 2;       // Auth0 hard limit per tenant
const BATCH_SIZE = 10;               // Users per file (PoC uses all 10 in one batch)
const POLL_INITIAL_DELAY_MS = 2000;  // Start polling after 2s
const POLL_MAX_DELAY_MS = 30000;     // Cap backoff at 30s
const POLL_BACKOFF_MULTIPLIER = 2;   // Double delay each retry
const POLL_MAX_RETRIES = 20;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function validateConfig() {
  const missing = ['AUTH0_DOMAIN', 'AUTH0_CLIENT_ID', 'AUTH0_CLIENT_SECRET', 'AUTH0_CONNECTION_ID']
    .filter((k) => !process.env[k]);
  if (missing.length) {
    console.error(`Missing required environment variables: ${missing.join(', ')}`);
    console.error('Copy .env.example to .env and fill in your values.');
    process.exit(1);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function chunk(array, size) {
  const chunks = [];
  for (let i = 0; i < array.length; i += size) {
    chunks.push(array.slice(i, i + size));
  }
  return chunks;
}

// ---------------------------------------------------------------------------
// Active job tracking
// ---------------------------------------------------------------------------

/**
 * Returns the number of still-active jobs, pruning finished ones from the set.
 */
async function getActiveJobCount(management, activeJobIds) {
  if (activeJobIds.size === 0) return 0;
  let active = 0;
  for (const jobId of activeJobIds) {
    const job = await management.jobs.get({ id: jobId });
    const status = job.data.status;
    if (status === 'pending' || status === 'processing') {
      active++;
    } else {
      activeJobIds.delete(jobId);
    }
  }
  return active;
}

// ---------------------------------------------------------------------------
// Polling
// ---------------------------------------------------------------------------

/**
 * Polls a job until it reaches a terminal state (completed | failed).
 * Uses exponential backoff to avoid hammering the API.
 */
async function pollUntilDone(management, jobId) {
  let delay = POLL_INITIAL_DELAY_MS;
  let attempt = 0;

  console.log(`  Polling job ${jobId}...`);

  while (attempt < POLL_MAX_RETRIES) {
    await sleep(delay);
    attempt++;

    const job = await management.jobs.get({ id: jobId });
    const { status, percentage_done: pct } = job.data;
    console.log(`  [attempt ${attempt}] job=${jobId} status=${status} progress=${pct ?? 0}%`);

    if (status === 'completed') {
      const s = job.data.summary ?? {};
      console.log(
        `  Job ${jobId} COMPLETED — inserted: ${s.inserted ?? 0}, ` +
          `updated: ${s.updated ?? 0}, failed: ${s.failed ?? 0}`
      );
      if (s.failed > 0) {
        console.warn(`  WARNING: ${s.failed} record(s) failed. Fetching errors...`);
        try {
          const errors = await management.jobs.getErrors({ id: jobId });
          errors.data.forEach((e) => console.error(`    Error: ${JSON.stringify(e)}`));
        } catch (_) {}
      }
      return 'completed';
    }

    if (status === 'failed') {
      console.error(`  Job ${jobId} FAILED.`);
      try {
        const errors = await management.jobs.getErrors({ id: jobId });
        errors.forEach((e) => console.error(`    Error: ${JSON.stringify(e)}`));
      } catch (_) {}
      return 'failed';
    }

    delay = Math.min(delay * POLL_BACKOFF_MULTIPLIER, POLL_MAX_DELAY_MS);
  }

  console.error(`  Job ${jobId} did not finish within ${POLL_MAX_RETRIES} poll attempts.`);
  return 'timeout';
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  validateConfig();

  // ManagementClient handles token acquisition and refresh automatically
  // via the OAuth 2.0 Client Credentials grant — no manual fetchManagementToken() needed.
  const management = new ManagementClient({
    domain: DOMAIN,
    clientId: CLIENT_ID,
    clientSecret: CLIENT_SECRET,
  });

  const usersFilePath = path.resolve(__dirname, '..', 'users.json');
  const allUsers = JSON.parse(fs.readFileSync(usersFilePath, 'utf8'));
  console.log(`Loaded ${allUsers.length} users from users.json`);

  const batches = chunk(allUsers, BATCH_SIZE);
  console.log(`Split into ${batches.length} batch(es) of up to ${BATCH_SIZE} users`);

  const activeJobIds = new Set();

  for (let i = 0; i < batches.length; i++) {
    const batch = batches[i];
    console.log(`\nBatch ${i + 1}/${batches.length} (${batch.length} users)`);

    // Wait until there is a free concurrent job slot
    while (true) {
      const active = await getActiveJobCount(management, activeJobIds);
      if (active < MAX_CONCURRENT_JOBS) break;
      console.log(
        `  Active jobs (${active}) at limit (${MAX_CONCURRENT_JOBS}). Waiting 5s before retrying...`
      );
      await sleep(5000);
    }

    // Submit via SDK — handles multipart form encoding internally
    console.log(`  Submitting import job...`);
    const job = await management.jobs.importUsers({
      users: new Blob([JSON.stringify(batch)], { type: 'application/json' }),
      connection_id: CONNECTION_ID,
      upsert: UPSERT,
      send_completion_email: SEND_EMAIL,
    });
    const jobId = job.data.id;
    console.log(`  Job submitted: ${jobId}`);
    activeJobIds.add(jobId);

    await pollUntilDone(management, jobId);
    activeJobIds.delete(jobId);
  }

  console.log('\nMigration complete.');
}

main().catch((err) => {
  console.error('Fatal error:', err.message ?? err);
  process.exit(1);
});
