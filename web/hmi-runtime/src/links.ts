/**
 * The one URL filter of the HMI (review-4 F3): the `link` component, the menus and the navigation action's `url` mode
 * all go through [linkTarget]. Only a page path on this origin and an `http(s)://` URL are followed; `javascript:`,
 * `data:`, `vbscript:` and every other scheme are refused, at view parse time and again when the action runs.
 */

export type LinkTarget = { kind: "page"; path: string } | { kind: "external"; url: string } | { kind: "blocked" };

/** Browsers drop tabs and newlines anywhere in a URL and read a backslash as `/`, so `/\t/evil.example` would
 * open another site. Targets containing control characters, whitespace or a backslash are refused.
 */
function unsafeUrl(url: string): boolean {
  for (let index = 0; index < url.length; index += 1) {
    const code = url.charCodeAt(index);
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return /[\s\\]/.test(url);
}

/** Page paths (`/pumps`) stay in the session. http(s) URLs leave it. Any other scheme is not followed. */
export function linkTarget(value: string): LinkTarget {
  const url = value.trim();
  if (url === "" || unsafeUrl(url)) return { kind: "blocked" };
  if (url.startsWith("/") && !url.startsWith("//")) {
    // A page path must resolve on the current origin.
    try {
      if (new URL(url, "http://session.invalid").origin !== "http://session.invalid") return { kind: "blocked" };
    } catch {
      return { kind: "blocked" };
    }
    return { kind: "page", path: url };
  }
  if (/^https?:\/\//i.test(url)) return { kind: "external", url };
  return { kind: "blocked" };
}
