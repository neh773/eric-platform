import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { mediaSource } from "./media";
import { readString } from "./read";

/**
 * Inline frame (HMP-024): shows an external web page inside the view. Written from the public component page; the
 * code and wording are our own.
 * doc source: FEATURES HMP-024
 *
 * The page is untrusted, so the frame is always sandboxed:
 * - `sandbox` lists the permissions the page gets, and starts as `allow-scripts allow-forms`. Only the tokens in
 *   [FRAME_PERMISSIONS] are kept. `allow-same-origin` is never kept: with it, a page from this origin (or a script
 *   in a page that shares it) could reach into the HMI session, so a framed page always runs as its own opaque origin
 *   and has no script access to the parent. `allow-top-navigation` and its variants are never kept either, so the
 *   page cannot send the session to another address.
 * - The address goes through the HMI's one URL filter ([mediaSource]): a path on this origin or an http(s) URL.
 *   `javascript:`, `data:`, `blob:` and every other scheme show an empty placeholder.
 * - No camera, microphone, location or other browser feature is delegated (`allow` is empty), and no referrer is sent.
 * The gateway's Content-Security-Policy (`frame-src 'self' http: https:`) is what lets the browser load the frame;
 * a page that sends `X-Frame-Options` or `frame-ancestors` against framing still refuses to show (DIFFERENCES 722).
 */

export const inlineFrameSchema: JsonSchema = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  type: "object",
  title: "Inline frame",
  additionalProperties: false,
  properties: {
    source: { type: "string", title: "Page (path or http(s) URL)", default: "" },
    title: { type: "string", title: "Accessible title", default: "Embedded page" },
    sandbox: { type: "string", title: "Sandbox permissions (space separated)", default: "allow-scripts allow-forms" },
  },
};

/** The sandbox permissions a frame may be given. Everything else, `allow-same-origin` included, is dropped. */
export const FRAME_PERMISSIONS: readonly string[] = [
  "allow-scripts",
  "allow-forms",
  "allow-popups",
  "allow-popups-to-escape-sandbox",
  "allow-modals",
  "allow-downloads",
  "allow-presentation",
];

/** The `sandbox` attribute value for [requested]: the allowed tokens, once each, in the order given. May be empty. */
export function frameSandbox(requested: unknown): string {
  const text = typeof requested === "string" ? requested : "allow-scripts allow-forms";
  const kept: string[] = [];
  for (const token of text.split(/\s+/)) {
    if (FRAME_PERMISSIONS.includes(token) && !kept.includes(token)) kept.push(token);
  }
  return kept.join(" ");
}

export function InlineFrame({ props }: HmiComponentProps) {
  const source = mediaSource(props.source);
  const title = readString(props, "title", "Embedded page");
  if (!source) {
    return (
      <div className="oip-inline-frame" role="document" aria-label={title} data-empty="true">
        <span className="oip-media-empty">No page</span>
      </div>
    );
  }
  const sandbox = frameSandbox(props.sandbox);
  return (
    <div className="oip-inline-frame" data-source={source}>
      <iframe key={`${source}|${sandbox}`} title={title} src={source} sandbox={sandbox} referrerPolicy="no-referrer" allow="" loading="lazy" />
    </div>
  );
}
