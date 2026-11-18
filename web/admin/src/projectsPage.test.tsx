import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ProjectsPage, projectNameFromFile } from "./projectsPage";
import { adminSession, fakeGateway, json, readerSession, sectionById } from "./statusTestKit";

afterEach(() => {
  vi.unstubAllGlobals();
});

function gateway(projects: string[]) {
  return fakeGateway(({ method, url }) => {
    if (url === "/api/v1/projects") return json(200, { projects });
    if (method === "POST" && url.startsWith("/api/v1/projects/")) {
      const match = /\/api\/v1\/projects\/([^/]+)\/import\?policy=(\w+)/.exec(url);
      if (!match) return json(422, { detail: "policy is required (skip or overwrite)" });
      const policy = match[2]!;
      if (!projects.includes(match[1]!)) projects.push(match[1]!);
      return json(200, {
        project: match[1],
        policy,
        created: 1,
        overwritten: policy === "overwrite" ? 2 : 0,
        skipped: policy === "skip" ? 2 : 0,
      });
    }
    return undefined;
  });
}

describe("projectNameFromFile", () => {
  it("suggests the project name from an export file name", () => {
    expect(projectNameFromFile("project-plant-a.zip")).toBe("plant-a");
    expect(projectNameFromFile("line2.ZIP")).toBe("line2");
    expect(projectNameFromFile("Not Valid.zip")).toBe("");
  });
});

describe("projects page", () => {
  it("lists projects with export links", async () => {
    const kit = gateway(["plant"]);
    render(<ProjectsPage section={sectionById("projects")} client={kit.client} session={adminSession} />);
    const link = await screen.findByRole("link", { name: "Export plant" });
    expect(link).toHaveAttribute("href", "/api/v1/projects/plant/export");
    expect(link).toHaveAttribute("download", "project-plant.zip");
  });

  it.each(["skip", "overwrite"] as const)("imports an archive with the %s policy", async (policy) => {
    const kit = gateway(["plant"]);
    await kit.signIn();
    render(<ProjectsPage section={sectionById("projects")} client={kit.client} session={adminSession} />);
    await screen.findByRole("link", { name: "Export plant" });
    const archive = new File([new Uint8Array([0x50, 0x4b, 3, 4])], "project-plant.zip", { type: "application/zip" });
    fireEvent.change(screen.getByLabelText("Project archive"), { target: { files: [archive] } });
    expect(screen.getByLabelText("Import into project")).toHaveValue("plant");
    fireEvent.change(screen.getByLabelText("Import into project"), { target: { value: "plant-copy" } });
    fireEvent.click(screen.getByRole("radio", { name: new RegExp(`\\(${policy}\\)`) }));
    fireEvent.click(screen.getByRole("button", { name: "Import" }));
    const result = await screen.findByTestId("import-result");
    expect(result).toHaveTextContent(`Imported into plant-copy (${policy})`);
    const post = kit.calls.find((call) => call.method === "POST");
    expect(post?.url).toBe(`/api/v1/projects/plant-copy/import?policy=${policy}`);
    expect(post?.headers.get("X-CSRF-Token")).toBe("csrf-1");
    expect(post?.headers.get("Content-Type")).toBe("application/zip");
    expect(new Uint8Array(post?.body as ArrayBuffer)[0]).toBe(0x50);
    await waitFor(() => expect(screen.getByRole("link", { name: "Export plant-copy" })).toBeVisible());
  });

  it("rejects an invalid target name before calling the API", async () => {
    const kit = gateway([]);
    await kit.signIn();
    render(<ProjectsPage section={sectionById("projects")} client={kit.client} session={adminSession} />);
    await screen.findByText("No projects yet.");
    const archive = new File([new Uint8Array([1])], "upload.zip", { type: "application/zip" });
    fireEvent.change(screen.getByLabelText("Project archive"), { target: { files: [archive] } });
    fireEvent.change(screen.getByLabelText("Import into project"), { target: { value: "Bad Name" } });
    fireEvent.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("lowercase");
    expect(kit.calls.some((call) => call.method === "POST")).toBe(false);
  });

  it("hides the import form for a session without gateway.write", async () => {
    const kit = gateway(["plant"]);
    render(<ProjectsPage section={sectionById("projects")} client={kit.client} session={readerSession} />);
    await screen.findByRole("link", { name: "Export plant" });
    expect(screen.queryByRole("form", { name: "Import project" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Import" })).toBeNull();
  });
});
