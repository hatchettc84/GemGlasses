import { fileURLToPath } from "node:url";
import { defineWorkersConfig, readD1Migrations } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  const migrations = await readD1Migrations(fileURLToPath(new URL("./migrations", import.meta.url)));
  return {
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          wrangler: { configPath: "./wrangler.toml" },
          miniflare: {
            bindings: {
              TEST_MIGRATIONS: migrations,
              ENVIRONMENT: "dev",
              TOKEN_APP_SECRET: "test-secret",
              GEMINI_API_KEY: "test-key",
              BOOTSTRAP_ADMIN_EMAIL: "boss@example.com",
            },
          },
        },
      },
    },
  };
});
