import { parsePage, parseView, type HmiProject, type PageDocument, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes } from "@oip/components";
import { c11GalleryPage, c11GalleryView } from "./c11Gallery";
import { c18AreaView, c18GalleryPages, c18GalleryView } from "./c18Gallery";
import { c15CardView, c15GalleryPage, c15GalleryView } from "./c15Gallery";
import { sampleStyleClasses } from "./sampleStyleClasses";

const types = allowedComponentTypes();

function view(input: unknown): ViewDocument {
  return parseView(input, { allowedTypes: types });
}

function page(input: unknown): PageDocument {
  return parsePage(input);
}

const home = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Home", path: "home" },
  spec: {
    params: {},
    width: 640,
    height: 360,
    root: {
      id: "home-root",
      kind: "container",
      name: "Home",
      layout: "flex",
      props: { direction: "column", alignItems: "flex-start" },
      children: [
        {
          id: "home-title",
          kind: "component",
          name: "Title",
          type: "label",
          props: { text: "Home", align: "start" },
          styleClasses: ["heading"],
        },
        {
          id: "go-pumps",
          kind: "component",
          name: "Pumps",
          type: "button",
          props: { text: "Pumps", enabled: true },
          events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }],
        },
        {
          id: "open-detail",
          kind: "component",
          name: "Detail",
          type: "button",
          props: { text: "Detail", enabled: true },
          events: [
            {
              event: "onClick",
              actions: [
                {
                  kind: "popup",
                  mode: "toggle",
                  id: "detail",
                  view: "detail",
                  title: "Area",
                  showClose: true,
                  params: { message: "North" },
                },
              ],
            },
          ],
        },
        {
          id: "meter",
          kind: "component",
          name: "Meter",
          type: "embedded-view",
          props: { path: "meter", params: { unit: " m" }, useDefaultViewWidth: true, useDefaultViewHeight: true },
        },
      ],
    },
  },
});

const pumps = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Pumps", path: "pumps" },
  spec: {
    params: {},
    width: 640,
    height: 200,
    root: {
      id: "pumps-root",
      kind: "container",
      name: "Pumps",
      layout: "flex",
      props: { direction: "column" },
      children: [
        {
          id: "pumps-title",
          kind: "component",
          name: "Pump list",
          type: "label",
          props: { text: "Pump list", align: "start" },
        },
      ],
    },
  },
});

const detail = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Detail", path: "detail" },
  spec: {
    params: { message: { value: "", direction: "input" } },
    width: 320,
    height: 160,
    root: {
      id: "detail-root",
      kind: "container",
      name: "Detail",
      layout: "flex",
      props: { direction: "column" },
      children: [
        {
          id: "detail-message",
          kind: "component",
          name: "Message",
          type: "label",
          props: { text: "", align: "start" },
          bindings: { text: { kind: "property", source: { scope: "param", name: "message" } } },
        },
      ],
    },
  },
});

const meter = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Meter", path: "meter" },
  spec: {
    params: { unit: { value: "", direction: "input" } },
    width: 200,
    height: 64,
    root: {
      id: "meter-root",
      kind: "container",
      name: "Meter",
      layout: "flex",
      props: { direction: "row" },
      children: [
        {
          id: "meter-value",
          kind: "component",
          name: "Level",
          type: "numeric",
          props: { value: 0, decimals: 1, prefix: "", suffix: "", announce: true },
          bindings: {
            value: { kind: "tag", mode: "direct", path: "plant/tank/level" },
            suffix: { kind: "property", source: { scope: "param", name: "unit" } },
          },
        },
      ],
    },
  },
});

const dockNav = view({
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "Dock", path: "dock-nav" },
  spec: {
    params: {},
    width: 180,
    height: 320,
    root: {
      id: "dock-root",
      kind: "container",
      name: "Dock",
      layout: "flex",
      props: { direction: "column" },
      children: [
        {
          id: "dock-label",
          kind: "component",
          name: "Navigation",
          type: "label",
          props: { text: "Navigation", align: "start" },
        },
      ],
    },
  },
});

export const sessionProject: HmiProject = {
  name: "Plant session",
  views: [home, pumps, detail, meter, dockNav, c11GalleryView, c18GalleryView, c18AreaView, c15GalleryView, c15CardView],
  pages: [
    page({
      apiVersion: "oip.hmi/v1",
      kind: "Page",
      metadata: { name: "Home" },
      spec: { url: "/", title: "Home", primaryView: "home", docks: [] },
    }),
    page({
      apiVersion: "oip.hmi/v1",
      kind: "Page",
      metadata: { name: "Pumps" },
      spec: { url: "/pumps", title: "Pumps", primaryView: "pumps", docks: [] },
    }),
    c11GalleryPage,
    ...c18GalleryPages,
    c15GalleryPage,
  ],
  sharedDocks: [
    {
      id: "nav",
      edge: "left",
      view: "dock-nav",
      size: 180,
      display: "visible",
      content: "push",
      handle: "hide",
    },
  ],
  cornerPriority: "top-bottom",
  styleClasses: sampleStyleClasses,
};
