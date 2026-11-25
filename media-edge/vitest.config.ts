import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // A test secret only; the real one is a Worker secret (README.md).
        bindings: { MEDIA_EDGE_SECRET: "test-secret-for-unit-tests-only-0123456789" },
      },
    }),
  ],
  test: { testTimeout: 20_000 },
});
