import react from "@vitejs/plugin-react";
import path from "node:path";
import { defineConfig } from "vitest/config";

/**
 * Vitest config (§14) — `@vitejs/plugin-react` works unchanged under Next.js per
 * the PDR's own note ("the App Router doesn't require `next/jest`"). The `@/*`
 * alias mirrors `tsconfig.json`'s `paths` so `src/`/`app/` imports resolve the
 * same way under test as they do under `next build`.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "."),
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    exclude: ["node_modules", ".next", "e2e"],
  },
});
