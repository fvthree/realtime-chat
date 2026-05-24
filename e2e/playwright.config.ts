import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./",
  fullyParallel: false, // WS tests share the same server; run sequentially.
  retries: 1,
  workers: 1,
  reporter: "html",
  use: {
    baseURL: "http://localhost:5173",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  // Start the full stack before running tests.
  webServer: [
    {
      command: "cd ../backend && ./gradlew bootRun --no-daemon -q",
      url: "http://localhost:8080/actuator/health",
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: "cd ../frontend && npm run dev",
      url: "http://localhost:5173",
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
  ],
});
