import type { StyleClassSet } from "@oip/hmi-runtime";

/** Style classes shared by the studio sample project and the /hmi session project.
 * Colors are theme variables, so each class follows the session theme
 * (-). */
export const sampleStyleClasses: StyleClassSet = {
  heading: { style: { color: "var(--oip-color-accent)", fontSize: 20, fontWeight: 700 } },
  alert: { style: { color: "var(--oip-color-alarm)", fontWeight: 700 } },
};
