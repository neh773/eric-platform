import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LogsPage } from "./logsPage";
import { redactFields } from "./statusApi";
import { adminSession, fakeGateway, json, readerSession, sectionById } from "./statusTestKit";

afterEach(() => {
  vi.unstubAllGlobals();
});

const records = [
  {
    ts: "2026-10-27T10:00:00.000Z",
    level: "INFO",
    logger: "org.oip.gateway",
    message: "gateway started",
    fields: { port: 8090, password: "Correct-horse-1", passwordHash: "argon2id-HASHMARKER", apiKey: "oip_SECRETMARKER" },
  },
  { ts: "2026-10-27T10:00:01.000Z", level: "WARN", logger: "org.oip.db", message: "slow query", fields: { millis: 900 } },
];

function gateway(levels: Record<string, string> = {}) {
  const state = { levels: { ...levels } };
  const kit = fakeGateway(({ method, url, body }) => {
    if (url.startsWith("/api/v1/logs?")) return json(200, { records });
    if (url === "/api/v1/logs/levels" && method === "GET") return json(200, { default: "INFO", levels: state.levels });
    if (url === "/api/v1/logs/levels" && method === "PUT") {
      const parsed = JSON.parse(String(body)) as { logger: string; level: string };
      state.levels[parsed.logger] = parsed.level;
      return json(200, parsed);
    }
    return undefined;
  });
  return { ...kit, state };
}

describe("redactFields", () => {
  it("hides values whose keys look like credentials", () => {
    expect(redactFields({ password: "x", tokenId: "y", keyHash: "z", port: 1, nested: { a: 1 } })).toEqual([
      ["password", "[hidden]"],
      ["tokenId", "[hidden]"],
      ["keyHash", "[hidden]"],
      ["port", "1"],
      ["nested", '{"a":1}'],
    ]);
  });
});

describe("logs page", () => {
  it("renders the tail newest first and never shows a secret or hash", async () => {
    const kit = gateway({ "org.oip.http": "DEBUG" });
    render(<LogsPage section={sectionById("logs")} client={kit.client} session={adminSession} />);
    const table = await screen.findByTestId("log-records");
    const rows = table.querySelectorAll("tbody tr");
    expect(rows[0]).toHaveTextContent("slow query");
    expect(rows[1]).toHaveTextContent("gateway started");
    expect(rows[1]).toHaveTextContent("port=8090");
    expect(document.body.textContent).not.toContain("Correct-horse-1");
    expect(document.body.textContent).not.toContain("HASHMARKER");
    expect(document.body.textContent).not.toContain("SECRETMARKER");
    expect(await screen.findByTestId("log-levels")).toHaveTextContent("org.oip.http");
    expect(screen.getByRole("link", { name: "Download log file" })).toHaveAttribute("href", "/api/v1/logs/download");
  });

  it("sends logger, level, and text filters to the API", async () => {
    const kit = gateway();
    render(<LogsPage section={sectionById("logs")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("log-records");
    fireEvent.change(screen.getByLabelText("Logger"), { target: { value: "org.oip.db" } });
    fireEvent.change(screen.getByLabelText("Lowest level"), { target: { value: "WARN" } });
    fireEvent.change(screen.getByLabelText("Text"), { target: { value: "slow" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    await waitFor(() => {
      const last = kit.hits("/api/v1/logs?").at(-1)?.url ?? "";
      expect(last).toContain("logger=org.oip.db");
      expect(last).toContain("minLevel=WARN");
      expect(last).toContain("q=slow");
    });
  });

  it("changes a logger level with the CSRF token and shows it in the level table", async () => {
    const kit = gateway();
    await kit.signIn();
    render(<LogsPage section={sectionById("logs")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("log-levels");
    fireEvent.change(screen.getByLabelText("Logger name"), { target: { value: "org.oip.http" } });
    fireEvent.change(screen.getByLabelText("New level"), { target: { value: "TRACE" } });
    fireEvent.click(screen.getByRole("button", { name: "Set level" }));
    expect(await screen.findByText("org.oip.http now logs at TRACE.")).toBeVisible();
    const put = kit.calls.find((call) => call.method === "PUT");
    expect(put?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(JSON.parse(String(put?.body))).toEqual({ logger: "org.oip.http", level: "TRACE" });
    await waitFor(() => expect(screen.getByTestId("log-levels")).toHaveTextContent("TRACE"));
  });

  it("hides the level change for a session without gateway.write", async () => {
    const kit = gateway();
    render(<LogsPage section={sectionById("logs")} client={kit.client} session={readerSession} />);
    await screen.findByTestId("log-records");
    expect(screen.queryByRole("button", { name: "Set level" })).toBeNull();
    expect(screen.queryByLabelText("Logger name")).toBeNull();
  });
});
