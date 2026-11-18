import type { ViewDocument } from "@oip/hmi-runtime";
import { parseView } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import type { GatewayClient } from "./gatewayClient";
import { savePageConfig, saveView, type PageConfigSpec } from "./persistence";

/**
 * New-project templates (PRO-005): a project can start blank, with a top header of page links ("web nav"), or with a
 * left menu dock ("menu nav"). A template is a set of views plus a page configuration (pages and shared docks),
 * written through the same config API the Studio saves with, so the project opens at `/hmi/<project>/` with its
 * navigation in place. The layout is our own.
 */

export type ProjectTemplateId = "blank" | "web-nav" | "menu-nav";

export interface ProjectTemplate {
  id: ProjectTemplateId;
  label: string;
  description: string;
}

export const PROJECT_TEMPLATES: readonly ProjectTemplate[] = [
  { id: "blank", label: "Blank", description: "An empty project. Views and pages are added in the Studio." },
  { id: "web-nav", label: "Web nav", description: "A header across the top with a link to each page, and the page content below it." },
  { id: "menu-nav", label: "Menu nav", description: "A menu docked to the left with a link to each page, and the page content beside it." },
];

export function isProjectTemplateId(value: string): value is ProjectTemplateId {
  return PROJECT_TEMPLATES.some((template) => template.id === value);
}

export interface TemplateContent {
  views: ViewDocument[];
  pageConfig: PageConfigSpec | null;
}

const LINKS = [
  { label: "Home", url: "/", view: "home" },
  { label: "Overview", url: "/overview", view: "overview" },
  { label: "Alarms", url: "/alarms", view: "alarms" },
];
const MENU_ITEMS = LINKS.map((link) => `${link.label} = ${link.url}`).join(", ");

function contentView(path: string, title: string): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: title, path },
      spec: {
        params: {},
        width: 800,
        height: 480,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction: "column" },
          children: [{ id: "title", kind: "component", name: "Title", type: "label", props: { text: title } }],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

function navView(path: string, title: string, type: "horizontal-menu" | "menu-tree", direction: "row" | "column"): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: title, path },
      spec: {
        params: {},
        width: direction === "row" ? 800 : 220,
        height: direction === "row" ? 48 : 480,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "flex",
          props: { direction },
          children: [{ id: "menu", kind: "component", name: "Menu", type, props: { label: "Main menu", items: MENU_ITEMS } }],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

/** The views and page configuration of [id]. `blank` has no views and stores no page configuration. */
export function templateContent(id: ProjectTemplateId): TemplateContent {
  if (id === "blank") return { views: [], pageConfig: null };
  const pages = LINKS.map((link) => ({ name: link.label, url: link.url, title: link.label, primaryView: link.view, docks: [] }));
  const content = LINKS.map((link) => contentView(link.view, link.label));
  if (id === "web-nav") {
    return {
      views: [navView("nav-header", "Header", "horizontal-menu", "row"), ...content],
      pageConfig: {
        pages,
        sharedDocks: [{ id: "header", edge: "top", view: "nav-header", size: 48, display: "visible", content: "push", handle: "hide" }],
        cornerPriority: "top-bottom",
      },
    };
  }
  return {
    views: [navView("nav-menu", "Menu", "menu-tree", "column"), ...content],
    pageConfig: {
      pages,
      sharedDocks: [{ id: "menu", edge: "left", view: "nav-menu", size: 220, display: "visible", content: "push", handle: "hide" }],
      cornerPriority: "left-right",
    },
  };
}

/** Write [id]'s views and page configuration into the new project [project]. Blank writes nothing. */
export async function applyProjectTemplate(client: GatewayClient, project: string, id: ProjectTemplateId): Promise<void> {
  const { views, pageConfig } = templateContent(id);
  for (const view of views) await saveView(client, project, view, { name: view.metadata.path, version: 0 });
  if (pageConfig) await savePageConfig(client, project, pageConfig, { name: "page-config", version: 0 });
}
