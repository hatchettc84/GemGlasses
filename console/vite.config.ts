import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In dev, /api goes to `wrangler dev`. DEV_USER_EMAIL signs you in through the
// Worker's dev-only identity header; production uses Cloudflare Access instead.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8787",
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq) => {
            const email = process.env.DEV_USER_EMAIL;
            if (email) proxyReq.setHeader("X-Dev-User-Email", email);
          });
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
