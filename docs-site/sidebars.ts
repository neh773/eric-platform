import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
  manual: [
    {
      type: "category",
      label: "Getting started",
      items: ["getting-started/index", "install-windows/index"],
    },
    {
      type: "category",
      label: "Platform",
      items: ["platform/index", "event-streams/index", "project-settings/index", "script-builder/index", "devices/index", "transaction-groups/index", "alarms/index", "historian/index", "redundancy/index", "charts/index", "plc/index", "plc-windows/index", "pid/index", "library/index", "logic/index", "resources/index", "http-endpoints/index", "deployment/index"],
    },
    {
      type: "category",
      label: "Security",
      items: ["security-levels/index", "identity-providers/index", "service-policies/index", "secret-store/index"],
    },
  ],
};

export default sidebars;
