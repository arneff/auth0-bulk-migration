"""
Auth0 Bulk User Migration — Python

Uses the Auth0 Python Management SDK (auth0-python v4).
GetToken handles the OAuth 2.0 Client Credentials grant; Auth0 wraps
the Management API — no manual token exchange or raw HTTP needed.

Auth0 limits: max 2 concurrent import jobs per tenant.
File limit: 500 KB per import file.

Usage:
    cp .env.example .env   (fill in your values)
    pip install -r requirements.txt
    python migrate.py
"""

import io
import json
import os
import sys
import time
from pathlib import Path

from auth0.authentication import GetToken
from auth0.management import Auth0
from dotenv import load_dotenv

load_dotenv()

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
DOMAIN = os.getenv("AUTH0_DOMAIN")
CLIENT_ID = os.getenv("AUTH0_CLIENT_ID")
CLIENT_SECRET = os.getenv("AUTH0_CLIENT_SECRET")
CONNECTION_ID = os.getenv("AUTH0_CONNECTION_ID")
UPSERT = os.getenv("AUTH0_UPSERT", "false").lower() == "true"
SEND_EMAIL = os.getenv("AUTH0_SEND_EMAIL", "false").lower() == "true"

MAX_CONCURRENT_JOBS = 2       # Auth0 hard limit per tenant
BATCH_SIZE = 10               # Users per file (PoC uses all 10 in one batch)
POLL_INITIAL_DELAY = 2.0      # seconds
POLL_MAX_DELAY = 30.0         # seconds
POLL_BACKOFF_MULTIPLIER = 2.0
POLL_MAX_RETRIES = 20


def validate_config():
    missing = [k for k in ("AUTH0_DOMAIN", "AUTH0_CLIENT_ID", "AUTH0_CLIENT_SECRET", "AUTH0_CONNECTION_ID")
               if not os.getenv(k)]
    if missing:
        print(f"Missing required environment variables: {', '.join(missing)}", file=sys.stderr)
        print("Copy .env.example to .env and fill in your values.", file=sys.stderr)
        sys.exit(1)


def get_management_client() -> Auth0:
    """
    Acquires a Management API access token via the OAuth 2.0 Client
    Credentials grant and returns an Auth0 management client.
    GetToken and Auth0 are from the auth0-python SDK — no raw HTTP needed.
    """
    get_token = GetToken(DOMAIN, CLIENT_ID, client_secret=CLIENT_SECRET)
    token_data = get_token.client_credentials(f"https://{DOMAIN}/api/v2/")
    print(f"Token acquired (expires in {token_data.get('expires_in', '?')}s)")
    return Auth0(DOMAIN, token_data["access_token"])


def chunk(lst, size):
    for i in range(0, len(lst), size):
        yield lst[i:i + size]


# ---------------------------------------------------------------------------
# Active job tracking
# ---------------------------------------------------------------------------

def get_active_job_count(client: Auth0, active_job_ids: set) -> int:
    """Returns count of still-running jobs, pruning finished ones from the set."""
    if not active_job_ids:
        return 0

    finished = set()
    for job_id in list(active_job_ids):
        job = client.jobs.get(job_id)
        if job["status"] not in ("pending", "processing"):
            finished.add(job_id)

    active_job_ids -= finished
    return len(active_job_ids)


# ---------------------------------------------------------------------------
# Polling
# ---------------------------------------------------------------------------

def poll_until_done(client: Auth0, job_id: str) -> str:
    """
    Polls a job until it reaches a terminal state (completed | failed).
    Uses exponential backoff.
    Returns the final status string.
    """
    delay = POLL_INITIAL_DELAY
    print(f"  Polling job {job_id}...")

    for attempt in range(1, POLL_MAX_RETRIES + 1):
        time.sleep(delay)
        job = client.jobs.get(job_id)
        status = job["status"]
        pct = job.get("percentage_done", 0)
        print(f"  [attempt {attempt}] job={job_id} status={status} progress={pct}%")

        if status == "completed":
            summary = job.get("summary", {})
            print(
                f"  Job {job_id} COMPLETED — "
                f"inserted: {summary.get('inserted', 0)}, "
                f"updated: {summary.get('updated', 0)}, "
                f"failed: {summary.get('failed', 0)}"
            )
            if summary.get("failed", 0) > 0:
                print(f"  WARNING: {summary['failed']} record(s) failed. Fetching errors...")
                try:
                    errors = client.jobs.get_failed_job(job_id)
                    for err in errors:
                        print(f"    Error: {json.dumps(err)}", file=sys.stderr)
                except Exception as exc:
                    print(f"    Could not fetch errors: {exc}", file=sys.stderr)
            return "completed"

        if status == "failed":
            print(f"  Job {job_id} FAILED.", file=sys.stderr)
            try:
                errors = client.jobs.get_failed_job(job_id)
                for err in errors:
                    print(f"    Error: {json.dumps(err)}", file=sys.stderr)
            except Exception:
                pass
            return "failed"

        delay = min(delay * POLL_BACKOFF_MULTIPLIER, POLL_MAX_DELAY)

    print(f"  Job {job_id} did not finish within {POLL_MAX_RETRIES} poll attempts.", file=sys.stderr)
    return "timeout"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    validate_config()

    # GetToken handles the OAuth 2.0 Client Credentials grant; Auth0 wraps
    # the Management API — no manual token exchange or raw HTTP needed.
    client = get_management_client()

    users_path = Path(__file__).parent.parent / "users.json"
    all_users = json.loads(users_path.read_text(encoding="utf-8"))
    print(f"Loaded {len(all_users)} users from users.json")

    batches = list(chunk(all_users, BATCH_SIZE))
    print(f"Split into {len(batches)} batch(es) of up to {BATCH_SIZE} users")

    active_job_ids: set = set()

    for i, batch in enumerate(batches):
        print(f"\nBatch {i + 1}/{len(batches)} ({len(batch)} users)")

        # Wait until there is a free concurrent job slot
        while True:
            active = get_active_job_count(client, active_job_ids)
            if active < MAX_CONCURRENT_JOBS:
                break
            print(
                f"  Active jobs ({active}) at limit ({MAX_CONCURRENT_JOBS}). "
                "Waiting 5s before retrying..."
            )
            time.sleep(5)

        # Submit via SDK — handles multipart form encoding internally
        print("  Submitting import job...")
        users_file = io.BytesIO(json.dumps(batch).encode("utf-8"))
        job = client.jobs.import_users(
            connection_id=CONNECTION_ID,
            file_obj=users_file,
            upsert=UPSERT,
            send_completion_email=SEND_EMAIL,
        )
        job_id = job["id"]
        print(f"  Job submitted: {job_id}")
        active_job_ids.add(job_id)

        result = poll_until_done(client, job_id)
        active_job_ids.discard(job_id)

        if result == "failed":
            print("  Batch failed. Continuing to next batch...", file=sys.stderr)

    print("\nMigration complete.")


if __name__ == "__main__":
    main()
