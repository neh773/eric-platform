import type { Config } from "@docusaurus/types";
import type * as Preset from "@docusaurus/preset-classic";

const config: Config = {
  title: "ERIC",
  tagline: "Build and run a plant from the screen",
  url: "http://127.0.0.1:3000",
  baseUrl: "/",
  onBrokenLinks: "throw",
  onBrokenAnchors: "throw",
  markdown: { hooks: { onBrokenMarkdownLinks: "throw" } },
  i18n: { defaultLocale: "en", locales: ["en"] },
  presets: [
    [
      "classic",
      {
        docs: {
          sidebarPath: "./sidebars.ts",
          routeBasePath: "manual",
        },
        blog: false,
        theme: { customCss: "./src/css/custom.css" },
      } satisfies Preset.Options,
    ],
  ],
  themeConfig: {
    colorMode: { defaultMode: "dark", disableSwitch: false, respectPrefersColorScheme: false },
    navbar: {
      title: "ERIC",
      items: [{ type: "docSidebar", sidebarId: "manual", position: "left", label: "Manual" }],
    },
    footer: {
      style: "dark",
      copyright: "ERIC. The manual is written for the person at the screen.",
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
