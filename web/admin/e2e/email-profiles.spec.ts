import { expect, test } from "@playwright/test";
import { createServer, type Server } from "node:net";
import { login, openSection, stamp, withViewer } from "./gateway";

/**
 * GAT-030: an SMTP email profile is configured once on the Email Profiles page and used for a test send. The password
 * reaches the SMTP server but never the page or an API response.
 * Doc source: FEATURES GAT-030.
 */
interface Capture {
  login: string[];
  from: string;
  to: string;
  data: string;
}

function smtpServer(captures: Capture[]): Promise<{ server: Server; port: number }> {
  const decode = (text: string) => Buffer.from(text.trim(), "base64").toString("utf8");
  const server = createServer((socket) => {
    socket.setEncoding("utf8");
    const current: Capture = { login: [], from: "", to: "", data: "" };
    let state: "command" | "user" | "pass" | "data" = "command";
    let buffer = "";
    socket.write("220 e2e smtp\r\n");
    socket.on("data", (chunk: string) => {
      buffer += chunk;
      let index: number;
      while ((index = buffer.indexOf("\r\n")) >= 0) {
        const line = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        if (state === "user") {
          current.login.push(decode(line));
          state = "pass";
          socket.write("334 UGFzc3dvcmQ6\r\n");
        } else if (state === "pass") {
          current.login.push(decode(line));
          state = "command";
          socket.write("235 ok\r\n");
        } else if (state === "data") {
          if (line === ".") {
            captures.push({ ...current });
            state = "command";
            socket.write("250 queued\r\n");
          } else {
            current.data += `${line}\n`;
          }
        } else if (line.startsWith("EHLO")) {
          socket.write("250-localhost\r\n250 AUTH LOGIN\r\n");
        } else if (line === "AUTH LOGIN") {
          state = "user";
          socket.write("334 VXNlcm5hbWU6\r\n");
        } else if (line.startsWith("MAIL FROM:")) {
          current.from = line.slice(line.indexOf("<") + 1, line.indexOf(">"));
          socket.write("250 ok\r\n");
        } else if (line.startsWith("RCPT TO:")) {
          current.to = line.slice(line.indexOf("<") + 1, line.indexOf(">"));
          socket.write("250 ok\r\n");
        } else if (line === "DATA") {
          state = "data";
          socket.write("354 go\r\n");
        } else if (line === "QUIT") {
          socket.write("221 bye\r\n");
          socket.end();
        } else {
          socket.write("250 ok\r\n");
        }
      }
    });
    socket.on("error", () => undefined);
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({ server, port: typeof address === "object" && address ? address.port : 0 });
    });
  });
}

test("a profile is created once, sends a test message, and never shows its password", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const captures: Capture[] = [];
  const { server, port } = await smtpServer(captures);
  const name = stamp("mail");
  const secret = "E2e-mail-secret-77";
  try {
    await login(page);
    await openSection(page, "Email Profiles");
    await page.getByRole("button", { name: "New email profile", exact: true }).click();
    await page.getByLabel("Profile name", { exact: true }).fill(name);
    await page.getByLabel("SMTP host", { exact: true }).fill("127.0.0.1");
    await page.getByLabel("Port", { exact: true }).fill(String(port));
    await page.getByLabel("Connection security", { exact: true }).selectOption("none");
    await page.getByLabel("Username", { exact: true }).fill("mailer");
    await page.getByLabel("Password", { exact: true }).fill(secret);
    await page.getByLabel("Sender address", { exact: true }).fill("plant@example.test");
    await page.getByRole("button", { name: "Save profile", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: `Saved ${name}.` })).toBeVisible();
    const row = page.getByTestId(`email-profile-${name}`);
    await expect(row).toContainText("Password (secret set)");
    await expect(page.locator("body")).not.toContainText(secret);

    await page.getByLabel("Test recipient", { exact: true }).fill("ops@example.test");
    await page.getByLabel("Test profile", { exact: true }).selectOption(name);
    await page.getByRole("button", { name: "Send test message", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: `Test message sent through ${name}.` })).toBeVisible();
    await expect.poll(() => captures.length).toBe(1);
    expect(captures[0]?.login).toEqual(["mailer", secret]);
    expect(captures[0]?.from).toBe("plant@example.test");
    expect(captures[0]?.to).toBe("ops@example.test");

    const api = await page.request.get(`/api/v1/email/profiles/${name}`);
    expect(api.ok()).toBeTruthy();
    expect(await api.text()).not.toContain(secret);
    const config = await page.request.get(`/api/v1/config/alarm/gateway/SmtpProfile/${name}`);
    expect(await config.text()).not.toContain(secret);

    await withViewer(browser, page, async (viewer) => {
      expect((await viewer.request.get("/api/v1/email/profiles")).status()).toBe(403);
    });

    await page.getByRole("button", { name: `Delete ${name}`, exact: true }).click();
    await expect(page.getByTestId(`email-profile-${name}`)).toHaveCount(0);
  } finally {
    server.close();
  }
});
