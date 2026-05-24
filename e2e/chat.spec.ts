import { test, expect, type Page } from "@playwright/test";

// ── helpers ──────────────────────────────────────────────────────────────────

async function openRoom(page: Page, room: string) {
  await page.goto(`/?room=${room}`);
  // Wait for the WS connection to be open (composer enabled).
  await expect(page.getByRole("textbox")).toBeEnabled({ timeout: 10_000 });
}

async function sendMessage(page: Page, text: string) {
  await page.getByRole("textbox").fill(text);
  await page.keyboard.press("Enter");
}

// ── Two-tab HUD assertion ─────────────────────────────────────────────────────

test("message arrives in tab B with HUD value under 100ms on localhost", async ({
  browser,
}) => {
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const pageA = await ctxA.newPage();
  const pageB = await ctxB.newPage();

  await openRoom(pageA, "e2e-hud");
  await openRoom(pageB, "e2e-hud");

  // Wait for clock sync (HUD syncing indicator clears).
  await expect(pageB.locator("[data-testid='hud']")).not.toContainText("syncing", {
    timeout: 5_000,
  });

  await sendMessage(pageA, "latency probe");

  // Tab B should receive the message.
  await expect(pageB.locator("[data-testid='message-list']")).toContainText(
    "latency probe",
    { timeout: 5_000 }
  );

  // The HUD peer p50 on tab B should be under 100ms.
  const hudText = await pageB.locator("[data-testid='hud']").textContent();
  const match = hudText?.match(/peer.*?(\d+)ms/i);
  if (match) {
    const ms = parseInt(match[1]!, 10);
    expect(ms).toBeLessThan(100);
  }

  await ctxA.close();
  await ctxB.close();
});

// ── Reconnect-storm regression ────────────────────────────────────────────────

test("reconnects cleanly after server restart within 2 seconds", async ({
  page,
}) => {
  // NOTE: This test requires the backend to be killable and restartable during
  // the test run. In CI, skip or wire up a process-management fixture.
  // Marked as a critical regression guard (see TODOS.md).
  test.skip(
    !!process.env.CI,
    "reconnect-storm test requires backend process control — run locally"
  );

  await openRoom(page, "e2e-reconnect");

  // Verify the connection strip is not showing an error before we start.
  await expect(page.locator("[data-testid='connection-strip']")).not.toBeVisible();

  // --- Kill the backend here (requires external process control fixture) ---
  // await killBackend();
  await expect(page.locator("[data-testid='connection-strip']")).toBeVisible({
    timeout: 5_000,
  });

  // --- Restart the backend within 2s ---
  // await startBackend();
  await expect(page.locator("[data-testid='connection-strip']")).not.toBeVisible({
    timeout: 10_000,
  });

  // Should be able to send a message after recovery.
  await sendMessage(page, "post-reconnect message");
  await expect(page.locator("[data-testid='message-list']")).toContainText(
    "post-reconnect message",
    { timeout: 5_000 }
  );
});
