import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";

// The public manual is published to GitHub Pages at https://neh773.github.io/eric-platform/.
// Set DOCS_BASE_URL=/ to preview it at the root of a local server.
const baseUrl = process.env.DOCS_BASE_URL ?? "/eric-platform/";
const repo = "https://github.com/neh773/eric-platform";

const config: Config = {
  title: "ERIC",
  tagline: "Build and run a plant from the screen",
  url: "https://neh773.github.io",
  baseUrl,
  trailingSlash: true,
  onBrokenLinks: "throw",
  onBrokenAnchors: "warn",
  markdown: { format: "detect", hooks: { onBrokenMarkdownLinks: "throw" } },
  i18n: { defaultLocale: "en", locales: ["en"] },
  presets: [
    [
      "classic",
      {
        // The user manual. Source: docs/manual, copied by scripts/build_manual_site.py.
        docs: {
          path: "manual-generated",
          routeBasePath: "manual",
          sidebarPath: "./sidebars-manual.ts",
        },
        blog: false,
        theme: { customCss: "./src/css/custom.css" },
      } satisfies Preset.Options,
    ],
  ],
  plugins: [
    [
      "@docusaurus/plugin-content-docs",
      {
        // Shorter topic pages written for this site.
        id: "reference",
        path: "docs",
        routeBasePath: "reference",
        sidebarPath: "./sidebars.ts",
      },
    ],
  ],
  themeConfig: {
    colorMode: { defaultMode: "dark", disableSwitch: false, respectPrefersColorScheme: false },
    announcementBar: {
      id: "beta",
      content:
        "ERIC 0.1.0 is beta software. Use it only with hardware for testing, under safe conditions. It is a hobby project with no guaranteed support.",
      isCloseable: true,
    },
    navbar: {
      title: "ERIC",
      items: [
        { type: "docSidebar", sidebarId: "manual", position: "left", label: "Manual" },
        { type: "docSidebar", sidebarId: "manual", docsPluginId: "reference", position: "left", label: "Reference" },
        { href: "pathname:///downloads/ERIC-Manual-0.1.0-beta.pdf", label: "PDF manual", position: "left" },
        { href: `${repo}/blob/main/CONTRIBUTING.md`, label: "Contributing", position: "right" },
        { href: `${repo}/releases`, label: "Download", position: "right" },
        { href: repo, label: "GitHub", position: "right" },
      ],
    },
    footer: {
      style: "dark",
      links: [
        {
          title: "Manual",
          items: [
            { label: "Start here", to: "/manual/getting-started/" },
            { label: "Safety and disclaimer", to: "/manual/safety/" },
            { label: "PDF manual", href: "pathname:///downloads/ERIC-Manual-0.1.0-beta.pdf" },
          ],
        },
        {
          title: "Project",
          items: [
            { label: "GitHub", href: repo },
            { label: "Contributing", href: `${repo}/blob/main/CONTRIBUTING.md` },
            { label: "Wishlist", to: "/manual/wishlist/" },
          ],
        },
      ],
      copyright: "Copyright (C) 2026 Nick Hepburn. AGPL-3.0-or-later. Beta software, provided as is with no warranty.",
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
