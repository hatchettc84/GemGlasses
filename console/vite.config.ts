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
        // Send Host: 127.0.0.1:8787 so the Worker's own origin matches the rewritten Origin below.
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq) => {
            // The browser's Origin is the Vite server; present it as same-origin to the Worker's CSRF guard.
            if (proxyReq.getHeader("origin")) proxyReq.setHeader("origin", "http://127.0.0.1:8787");
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
