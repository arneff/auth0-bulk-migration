package com.acme.auth0;

import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.filter.UsersImportOptions;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.json.mgmt.jobs.Job;
import com.auth0.json.mgmt.jobs.JobSummary;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auth0 Bulk User Migration — Java
 *
 * Uses the Auth0 Java Management SDK (auth0 v2).
 * AuthAPI handles the OAuth 2.0 Client Credentials grant to acquire a
 * short-lived Management API token — no manual token exchange needed.
 *
 * Auth0 limits: max 2 concurrent import jobs per tenant.
 * File limit: 500 KB per import file.
 *
 * Usage:
 *   cp .env.example .env   (fill in your values)
 *   mvn compile exec:java -Dexec.mainClass="com.acme.auth0.BulkMigrate"
 */
public class BulkMigrate {

    // ---------------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------------
    private static final int MAX_CONCURRENT_JOBS = 2;
    private static final int BATCH_SIZE = 10;
    private static final long POLL_INITIAL_DELAY_MS = 2_000;
    private static final long POLL_MAX_DELAY_MS = 30_000;
    private static final int POLL_BACKOFF_MULTIPLIER = 2;
    private static final int POLL_MAX_RETRIES = 20;

    private final String domain;
    private final String clientId;
    private final String clientSecret;
    private final String connectionId;
    private final boolean upsert;
    private final boolean sendEmail;

    private final Gson gson = new Gson();

    public BulkMigrate(Dotenv env) {
        this.domain       = env.get("AUTH0_DOMAIN");
        this.clientId     = env.get("AUTH0_CLIENT_ID");
        this.clientSecret = env.get("AUTH0_CLIENT_SECRET");
        this.connectionId = env.get("AUTH0_CONNECTION_ID");
        this.upsert       = Boolean.parseBoolean(env.get("AUTH0_UPSERT",      "false"));
        this.sendEmail    = Boolean.parseBoolean(env.get("AUTH0_SEND_EMAIL",   "false"));
    }

    // ---------------------------------------------------------------------------
    // M2M token exchange (Client Credentials flow)
    // ---------------------------------------------------------------------------

    /**
     * Acquires a short-lived Management API access token via the
     * OAuth 2.0 Client Credentials grant using the Auth0 SDK AuthAPI,
     * then returns a ManagementAPI client ready to use.
     */
    private ManagementAPI buildManagementClient() throws Auth0Exception {
        System.out.println("Fetching Management API token via client credentials...");
        AuthAPI authAPI = new AuthAPI(domain, clientId, clientSecret);
        TokenHolder holder = authAPI
                .requestToken("https://" + domain + "/api/v2/")
                .execute()
                .getBody();
        System.out.println("Token acquired (expires in " + holder.getExpiresIn() + "s)");
        return ManagementAPI.newBuilder(domain, holder.getAccessToken()).build();
    }

    // ---------------------------------------------------------------------------
    // Auth0 SDK calls
    // ---------------------------------------------------------------------------

    /**
     * Submits a batch of users as a new import job.
     * Writes the batch to a temp file (SDK importUsers requires a File),
     * submits, then deletes the temp file.  Returns the job ID.
     */
    private String submitImportJob(ManagementAPI mgmt, JsonArray users)
            throws Auth0Exception, IOException {
        Path tempFile = Files.createTempFile("auth0-import-", ".json");
        try {
            Files.writeString(tempFile, gson.toJson(users), StandardCharsets.UTF_8);
            UsersImportOptions options = new UsersImportOptions()
                    .withUpsert(upsert)
                    .withSendCompletionEmail(sendEmail);
            Job job = mgmt.jobs()
                    .importUsers(connectionId, tempFile.toFile(), options)
                    .execute()
                    .getBody();
            return job.getId();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Returns the number of still-active jobs, pruning finished ones from the set.
     */
    private int getActiveJobCount(ManagementAPI mgmt, Set<String> activeJobIds)
            throws Auth0Exception {
        Set<String> finished = new HashSet<>();
        for (String jobId : activeJobIds) {
            Job job = mgmt.jobs().get(jobId).execute().getBody();
            String status = job.getStatus();
            if (!"pending".equals(status) && !"processing".equals(status)) {
                finished.add(jobId);
            }
        }
        activeJobIds.removeAll(finished);
        return activeJobIds.size();
    }

    // ---------------------------------------------------------------------------
    // Polling
    // ---------------------------------------------------------------------------

    /**
     * Polls a job until it reaches a terminal state (completed | failed).
     * Uses exponential backoff to avoid hammering the API.
     * Returns the final status string.
     */
    private String pollUntilDone(ManagementAPI mgmt, String jobId)
            throws Auth0Exception, InterruptedException {
        long delay = POLL_INITIAL_DELAY_MS;
        System.out.println("  Polling job " + jobId + "...");

        for (int attempt = 1; attempt <= POLL_MAX_RETRIES; attempt++) {
            Thread.sleep(delay);

            Job job = mgmt.jobs().get(jobId).execute().getBody();
            String status = job.getStatus();
            int pct = job.getPercentageDone() != null ? job.getPercentageDone() : 0;
            System.out.printf("  [attempt %d] job=%s status=%s progress=%d%%%n",
                    attempt, jobId, status, pct);

            if ("completed".equals(status)) {
                JobSummary summary = job.getSummary();
                int inserted = summary != null && summary.getInserted() != null ? summary.getInserted() : 0;
                int updated  = summary != null && summary.getUpdated()  != null ? summary.getUpdated()  : 0;
                int failed   = summary != null && summary.getFailed()   != null ? summary.getFailed()   : 0;
                System.out.printf("  Job %s COMPLETED — inserted: %d, updated: %d, failed: %d%n",
                        jobId, inserted, updated, failed);
                if (failed > 0) {
                    System.err.println("  WARNING: " + failed + " record(s) failed. Fetching errors...");
                    try {
                        List<?> errors = mgmt.jobs().getErrorDetails(jobId).execute().getBody();
                        if (errors != null) {
                            errors.forEach(e -> System.err.println("    Error: " + gson.toJson(e)));
                        }
                    } catch (Exception ignored) {}
                }
                return "completed";
            }

            if ("failed".equals(status)) {
                System.err.println("  Job " + jobId + " FAILED.");
                try {
                    List<?> errors = mgmt.jobs().getErrorDetails(jobId).execute().getBody();
                    if (errors != null) {
                        errors.forEach(e -> System.err.println("    Error: " + gson.toJson(e)));
                    }
                } catch (Exception ignored) {}
                return "failed";
            }

            delay = Math.min(delay * POLL_BACKOFF_MULTIPLIER, POLL_MAX_DELAY_MS);
        }

        System.err.println("  Job " + jobId + " did not finish within " + POLL_MAX_RETRIES + " poll attempts.");
        return "timeout";
    }

    // ---------------------------------------------------------------------------
    // Batching
    // ---------------------------------------------------------------------------

    private static List<JsonArray> splitIntoBatches(JsonArray users, int batchSize) {
        List<JsonArray> batches = new ArrayList<>();
        JsonArray current = new JsonArray();
        for (JsonElement user : users) {
            current.add(user);
            if (current.size() == batchSize) {
                batches.add(current);
                current = new JsonArray();
            }
        }
        if (current.size() > 0) batches.add(current);
        return batches;
    }

    // ---------------------------------------------------------------------------
    // Main migration loop
    // ---------------------------------------------------------------------------

    public void run(Path usersFilePath) throws Exception {
        // AuthAPI handles the OAuth 2.0 Client Credentials grant —
        // no manual token exchange or raw HTTP needed.
        ManagementAPI mgmt = buildManagementClient();

        String usersJson = Files.readString(usersFilePath);
        JsonArray allUsers = gson.fromJson(usersJson, JsonArray.class);
        System.out.println("Loaded " + allUsers.size() + " users from users.json");

        List<JsonArray> batches = splitIntoBatches(allUsers, BATCH_SIZE);
        System.out.println("Split into " + batches.size() + " batch(es) of up to " + BATCH_SIZE + " users");

        Set<String> activeJobIds = new HashSet<>();

        for (int i = 0; i < batches.size(); i++) {
            JsonArray batch = batches.get(i);
            System.out.printf("%nBatch %d/%d (%d users)%n", i + 1, batches.size(), batch.size());

            // Wait until there is a free concurrent job slot
            while (true) {
                int active = getActiveJobCount(mgmt, activeJobIds);
                if (active < MAX_CONCURRENT_JOBS) break;
                System.out.printf("  Active jobs (%d) at limit (%d). Waiting 5s before retrying...%n",
                        active, MAX_CONCURRENT_JOBS);
                Thread.sleep(5_000);
            }

            System.out.println("  Submitting import job...");
            String jobId = submitImportJob(mgmt, batch);
            System.out.println("  Job submitted: " + jobId);
            activeJobIds.add(jobId);

            String result = pollUntilDone(mgmt, jobId);
            activeJobIds.remove(jobId);

            if ("failed".equals(result)) {
                System.err.println("  Batch failed. Continuing to next batch...");
            }
        }

        System.out.println("\nMigration complete.");
    }

    // ---------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // Load .env from the working directory (project root, one level up from java/)
        Dotenv env = Dotenv.configure()
                .directory(".")
                .ignoreIfMissing()
                .load();

        String[] required = {"AUTH0_DOMAIN", "AUTH0_CLIENT_ID", "AUTH0_CLIENT_SECRET", "AUTH0_CONNECTION_ID"};
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (env.get(key, null) == null) missing.add(key);
        }
        if (!missing.isEmpty()) {
            System.err.println("Missing required environment variables: " + String.join(", ", missing));
            System.err.println("Copy .env.example to .env and fill in your values.");
            System.exit(1);
        }

        Path usersFile = Paths.get("users.json");
        if (!Files.exists(usersFile)) {
            System.err.println("users.json not found. Run from the project root directory.");
            System.exit(1);
        }

        new BulkMigrate(env).run(usersFile);
    }
}
