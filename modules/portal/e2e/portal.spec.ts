import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

// PLAN M6 exit criteria. CONTROL_URL is the test harness: it creates DR replication backlog on demand.
const control = process.env.CONTROL_URL ?? "http://localhost:9999";
const cob = "2026-10-01";

async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByRole("button", { name: `Sign in as ${user}` }).click();
  await expect(page.getByText(user, { exact: false }).first()).toBeVisible();
}

/** With SCREENSHOTS=<dir>, saves the pages shown in the README. */
async function shot(page: Page, name: string) {
  if (process.env.SCREENSHOTS) await page.screenshot({ path: `${process.env.SCREENSHOTS}/${name}.png`, fullPage: true });
}

async function nav(page: Page, name: string) {
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name, exact: true }).click();
}

async function accessible(page: Page, what: string) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(results.violations.map((v) => `${what}: ${v.id} ${v.help} (${v.nodes.map((n) => n.target.join(" ")).join(", ")})`)).toEqual([]);
}

async function fillRequest(page: Page) {
  await nav(page, "New request");
  await expect(page.getByRole("heading", { name: "New copy request" })).toBeVisible();
  await page.getByLabel("Dataset", { exact: true }).selectOption("trading-eod");
  await page.getByLabel("COB date").fill(cob);
  await page.getByRole("button", { name: "Add date" }).click();
  await page.getByLabel("Target environment").selectOption("qa1");
}

test.describe.serial("ad-hoc copy through the portal", () => {
  test("a QA user copies trading-eod for a COB date; it waits for DR, then succeeds", async ({ page, request }) => {
    await signIn(page, "alice");
    await fillRequest(page);
    await accessible(page, "new request");

    await page.getByRole("button", { name: "Estimate" }).click();
    const estimate = page.getByRole("region", { name: "Estimate" });
    await expect(estimate.getByRole("cell", { name: "trading.trades" })).toBeVisible();
    await expect(estimate.getByRole("cell", { name: "crm.customers" })).toBeVisible();
    await expect(estimate.getByText("has not been copied to qa1")).toBeVisible();
    await accessible(page, "estimate");
    await shot(page, "portal-estimate");

    // The load generator: DR falls behind production, so the guard holds the copy.
    expect((await request.post(`${control}/lag/start`)).ok()).toBeTruthy();
    await expect(page.getByRole("status").filter({ hasText: "DR cluster is busy" })).toBeVisible({ timeout: 60_000 });
    await page.getByRole("button", { name: "Submit request" }).click();
    await expect(page.getByRole("heading", { name: /^Job / })).toBeVisible();
    await expect(page.getByText("WAITING FOR SOURCE").first()).toBeVisible({ timeout: 60_000 });
    await accessible(page, "job waiting");
    await shot(page, "portal-waiting");

    expect((await request.post(`${control}/lag/stop`)).ok()).toBeTruthy();
    await expect(page.getByRole("progressbar", { name: "Chunks copied", exact: true })).toBeVisible({ timeout: 120_000 });
    await expect(page.getByText("SUCCEEDED").first()).toBeVisible({ timeout: 180_000 });
    await accessible(page, "job succeeded");
    await shot(page, "portal-succeeded");

    await nav(page, "Jobs");
    await expect(page.getByRole("cell", { name: "trading-eod" }).first()).toBeVisible();
    await accessible(page, "jobs");
  });

  test("a second user replacing the same slice within 24 h must tick the confirmation", async ({ page }) => {
    await signIn(page, "bob");
    await fillRequest(page);
    await page.getByRole("button", { name: "Estimate" }).click();
    const estimate = page.getByRole("region", { name: "Estimate" });
    await expect(estimate.getByText(/was last replaced by\s+alice/).first()).toBeVisible();
    const submit = page.getByRole("button", { name: "Submit request" });
    await expect(submit).toBeDisabled();
    await accessible(page, "confirmation");
    await shot(page, "portal-confirmation");
    await page.getByLabel("I understand this replaces data others copied recently").check();
    await expect(submit).toBeEnabled();
    await submit.click();
    await expect(page.getByRole("heading", { name: /^Job / })).toBeVisible();
    await expect(page.getByText("SUCCEEDED").first()).toBeVisible({ timeout: 180_000 });
  });

  test("history, guard, continuous and operations pages are accessible", async ({ page }) => {
    await signIn(page, "root");
    await nav(page, "Target history");
    await expect(page.getByRole("cell", { name: "bob" }).first()).toBeVisible();
    await accessible(page, "history");
    await nav(page, "Source guard");
    await expect(page.getByText("Cluster level")).toBeVisible();
    await accessible(page, "guard");
    await nav(page, "Continuous");
    await expect(page.getByRole("heading", { name: "Continuous replication" })).toBeVisible();
    await accessible(page, "continuous");
    await nav(page, "Operations");
    await expect(page.getByRole("heading", { name: "Global pause" })).toBeVisible();
    await accessible(page, "operations");
  });
});
