import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EmailProfilesPage } from "./emailProfilesPage";
import { ADMIN_SECTIONS } from "./sections";
import { adminSession, fakeGateway, json, readerSession, sectionById, viewerSession } from "./statusTestKit";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const plant = {
  name: "plant", enabled: true, hostname: "smtp.plant.test", port: 587, useSsl: false, startTls: true, username: "mailer",
  passwordSet: true, authType: "password", fromAddress: "plant@example.test", timeoutMillis: 10000, oauthTokenUrl: "",
  oauthClientId: "", clientSecretSet: false, refreshTokenSet: false, oauthScope: "", version: 1,
};

describe("email profiles page", () => {
  it("is a services section", () => {
    expect(ADMIN_SECTIONS.find((item) => item.id === "email-profiles")?.group).toBe("services");
  });

  it("lists profiles without any secret and says whether one is set", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/email/profiles" ? json(200, { items: [plant] }) : undefined));
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    const row = await screen.findByTestId("email-profile-plant");
    expect(row).toHaveTextContent("smtp.plant.test:587");
    expect(row).toHaveTextContent("STARTTLS");
    expect(row).toHaveTextContent("Password (secret set)");
    expect(row).toHaveTextContent("plant@example.test");
  });

  it("saves a new profile with the password in the request and not in the page", async () => {
    let saved: { url: string; body: string } | null = null;
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === "/api/v1/email/profiles" && method === "GET") return json(200, { items: [] });
      if (url === "/api/v1/email/profiles/relay" && method === "PUT") {
        saved = { url, body: String(body) };
        return json(201, { ...plant, name: "relay", hostname: "smtp.relay.test" });
      }
      return undefined;
    });
    await kit.signIn();
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "New email profile" }));
    fireEvent.change(screen.getByLabelText("Profile name"), { target: { value: "relay" } });
    fireEvent.change(screen.getByLabelText("SMTP host"), { target: { value: "smtp.relay.test" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "mailer" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "Typed-secret-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(saved).not.toBeNull());
    const body = JSON.parse(saved!.body);
    expect(body).toMatchObject({ hostname: "smtp.relay.test", port: 587, startTls: true, useSsl: false, username: "mailer", password: "Typed-secret-1", authType: "password" });
    expect(await screen.findByRole("status")).toHaveTextContent("Saved relay.");
    expect(document.body.textContent).not.toContain("Typed-secret-1");
  });

  it("leaves an untouched secret out of the update so the stored one stays", async () => {
    let saved = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === "/api/v1/email/profiles" && method === "GET") return json(200, { items: [plant] });
      if (url === "/api/v1/email/profiles/plant" && method === "PUT") {
        saved = String(body);
        return json(200, plant);
      }
      return undefined;
    });
    await kit.signIn();
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit plant" }));
    expect(screen.getByLabelText("Password")).toHaveAttribute("placeholder", "Set. Leave empty to keep it.");
    fireEvent.change(screen.getByLabelText("Sender address"), { target: { value: "ops@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(saved).not.toBe(""));
    expect(JSON.parse(saved)).not.toHaveProperty("password");
    expect(JSON.parse(saved).fromAddress).toBe("ops@example.test");
  });

  it("shows the OAuth2 fields and sends the client secret only when typed", async () => {
    let saved = "";
    const kit = fakeGateway(({ url, method, body }) => {
      if (url === "/api/v1/email/profiles" && method === "GET") return json(200, { items: [] });
      if (url === "/api/v1/email/profiles/graph" && method === "PUT") {
        saved = String(body);
        return json(201, { ...plant, name: "graph", authType: "oauth2" });
      }
      return undefined;
    });
    await kit.signIn();
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    fireEvent.click(await screen.findByRole("button", { name: "New email profile" }));
    fireEvent.change(screen.getByLabelText("Profile name"), { target: { value: "graph" } });
    fireEvent.change(screen.getByLabelText("SMTP host"), { target: { value: "smtp.mail.test" } });
    fireEvent.change(screen.getByLabelText("Login method"), { target: { value: "oauth2" } });
    expect(screen.queryByLabelText("Password")).toBeNull();
    fireEvent.change(screen.getByLabelText("Token URL"), { target: { value: "https://tokens.example.test/token" } });
    fireEvent.change(screen.getByLabelText("Client id"), { target: { value: "client-1" } });
    fireEvent.change(screen.getByLabelText("Client secret"), { target: { value: "Cs-typed-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Save profile" }));
    await waitFor(() => expect(saved).not.toBe(""));
    expect(JSON.parse(saved)).toMatchObject({ authType: "oauth2", oauthTokenUrl: "https://tokens.example.test/token", oauthClientId: "client-1", oauthClientSecret: "Cs-typed-2" });
    expect(JSON.parse(saved)).not.toHaveProperty("oauthRefreshToken");
  });

  it("sends a test message and reports a refusal", async () => {
    const kit = fakeGateway(({ url, method }) => {
      if (url === "/api/v1/email/profiles" && method === "GET") return json(200, { items: [plant] });
      if (url === "/api/v1/email/profiles/plant/test") return json(502, { title: "Bad Gateway", status: 502, detail: "smtp expected 235 but got 535" });
      return undefined;
    });
    await kit.signIn();
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={adminSession} />);
    await screen.findByTestId("email-profile-plant");
    fireEvent.change(screen.getByLabelText("Test recipient"), { target: { value: "ops@example.test" } });
    fireEvent.click(screen.getByRole("button", { name: "Send test message" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("smtp expected 235 but got 535");
  });

  it("gives a reader the list but no edit controls, and a viewer nothing", async () => {
    const kit = fakeGateway(({ url }) => (url === "/api/v1/email/profiles" ? json(200, { items: [plant] }) : undefined));
    const { unmount } = render(<EmailProfilesPage section={sectionById("email-profiles")} client={kit.client} session={readerSession} />);
    expect(await screen.findByTestId("email-profile-plant")).toBeVisible();
    expect(screen.queryByRole("button", { name: "New email profile" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Edit plant" })).toBeNull();
    unmount();
    const denied = fakeGateway(() => json(403, { title: "Forbidden", status: 403, detail: "gateway.read denied" }));
    render(<EmailProfilesPage section={sectionById("email-profiles")} client={denied.client} session={viewerSession} />);
    expect(await screen.findByTestId("email-no-read")).toBeVisible();
    expect(denied.hits("/api/v1/email/profiles")).toHaveLength(0);
  });
});
