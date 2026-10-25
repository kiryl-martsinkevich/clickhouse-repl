import { defineConfig, devices } from "@playwright/test";

// Run by modules/it (PortalE2EIT), which starts prod/DR/qa1 ClickHouse, Postgres, the dev IdP and
// the app, then sets BASE_URL and CONTROL_URL. CHROMIUM_PATH points at a pre-installed Chromium.
export default defineConfig({
  testDir: "e2e",
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {} },
    },
  ],
});
