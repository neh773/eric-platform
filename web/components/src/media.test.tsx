import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";
import { Audio, buildDrawing, Drawing, drawingAttribute, mediaSource, PdfViewer, pdfFrameSource, Video } from "./media";

/**
 * Display palette media: drawing (HMP-031), PDF viewer (HMP-033, HMV-069), audio (HMP-035), video player (HMP-042).
 */

const REFUSED = [
  "javascript:alert(1)",
  " JavaScript:alert(1)",
  "data:application/pdf;base64,JVBERi0=",
  "data:audio/wav;base64,UklGRg==",
  "blob:http://gateway/abc",
  "vbscript:msgbox",
  "file:///etc/passwd",
  "//elsewhere.test/a.pdf",
  "/\t/elsewhere.test/a.pdf",
  "/a\\b.pdf",
  "manual.pdf",
  "",
];

describe("media sources", () => {
  it("allow a path on this origin and http(s) URLs only", () => {
    expect(mediaSource("/system/webdev/plant/manual.pdf")).toBe("/system/webdev/plant/manual.pdf");
    expect(mediaSource("https://media.test/clip.mp4")).toBe("https://media.test/clip.mp4");
    expect(mediaSource("http://camera.test/stream.webm")).toBe("http://camera.test/stream.webm");
    for (const value of REFUSED) expect(mediaSource(value), value).toBeNull();
    expect(mediaSource(42)).toBeNull();
    expect(mediaSource(null)).toBeNull();
  });

  it("registers the four components in the display group with defaults and sizes", () => {
    const registry = componentRegistry();
    const expected = {
      drawing: { width: 160, height: 160 },
      "pdf-viewer": { width: 420, height: 540 },
      audio: { width: 300, height: 56 },
      "video-player": { width: 480, height: 270 },
    };
    for (const [type, position] of Object.entries(expected)) {
      const module = registry.get(type)!;
      expect(module, type).toBeDefined();
      expect(allowedComponentTypes().has(type)).toBe(true);
      expect(module.category).toBe("display");
      expect(module.position).toEqual(position);
      expect(module.schema.additionalProperties).toBe(false);
      expect(module.schema.title).toBe(module.title);
    }
    expect(defaultsFromSchema(registry.get("pdf-viewer")!.schema)).toEqual({ source: "", page: 1, toolbar: true, title: "PDF document" });
    expect(defaultsFromSchema(registry.get("audio")!.schema)).toEqual({
      source: "",
      label: "Audio",
      playing: false,
      controls: true,
      autoplay: false,
      loop: false,
      muted: false,
      volume: 1,
    });
    expect(defaultsFromSchema(registry.get("video-player")!.schema)).toMatchObject({ source: "", poster: "", playing: false, muted: true, fit: "contain" });
    expect(defaultsFromSchema(registry.get("drawing")!.schema)).toMatchObject({ elements: {}, alt: "Drawing", fit: "contain" });
  });
});

describe("drawing", () => {
  it("keeps drawing elements and presentation attributes and renames ids", () => {
    const built = buildDrawing(
      document,
      '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/></linearGradient></defs>' +
        '<rect id="tank" x="1" y="2" width="10" height="5" fill="url(#g)" stroke="#000" stroke-width="2"/><text x="3" y="4">PT-101</text><use href="#tank"/></svg>',
      "p-",
    );
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    const svg = built.svg;
    expect(svg.getAttribute("viewBox")).toBe("0 0 40 20");
    const rect = svg.querySelector("rect")!;
    expect(rect.getAttribute("id")).toBe("p-tank");
    expect(rect.getAttribute("fill")).toBe("url(#p-g)");
    expect(rect.getAttribute("stroke-width")).toBe("2");
    expect(svg.querySelector("linearGradient")!.getAttribute("id")).toBe("p-g");
    expect(svg.querySelector("text")!.textContent).toBe("PT-101");
    expect(svg.querySelector("use")!.getAttribute("href")).toBe("#p-tank");
  });

  it("drops scripts, handlers, styles, foreign content, links, animation and external references", () => {
    const built = buildDrawing(
      document,
      '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" onload="alert(1)">' +
        "<script>alert(1)</script><style>rect{fill:red}</style>" +
        '<rect id="a" onclick="alert(1)" style="fill:red" fill="url(https://elsewhere.test/x.svg#p)" stroke="javascript:alert(1)" width="5" height="5"/>' +
        '<foreignObject><div xmlns="http://www.w3.org/1999/xhtml">x</div></foreignObject>' +
        '<image href="https://elsewhere.test/x.png"/><a href="javascript:alert(1)"><circle r="2"/></a>' +
        '<animate attributeName="href" to="javascript:alert(1)"/><set attributeName="fill" to="red"/>' +
        '<use xlink:href="https://elsewhere.test/x.svg#icon"/><use xlink:href="#a"/></svg>',
      "p-",
    );
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    const html = built.svg.outerHTML;
    for (const fragment of ["script", "style", "onload", "onclick", "foreignObject", "image", "<a", "circle", "animate", "<set", "elsewhere", "javascript"]) {
      expect(html, fragment).not.toContain(fragment);
    }
    const rect = built.svg.querySelector("rect")!;
    expect(rect.getAttribute("width")).toBe("5");
    expect(rect.hasAttribute("fill")).toBe(false);
    const uses = built.svg.querySelectorAll("use");
    expect(uses[0]!.hasAttribute("href")).toBe(false);
    expect(uses[1]!.getAttribute("href")).toBe("#p-a");
  });

  it("refuses markup that is not SVG", () => {
    expect(buildDrawing(document, "<svg><rect></svg>", "p-")).toEqual({ ok: false, reason: "The drawing is not valid SVG" });
    expect(buildDrawing(document, "<html xmlns='http://www.w3.org/1999/xhtml'/>", "p-")).toEqual({ ok: false, reason: "The drawing is not valid SVG" });
    expect(buildDrawing(document, "   ", "p-")).toEqual({ ok: false, reason: "No drawing" });
  });

  it("filters override values the same way as markup", () => {
    expect(drawingAttribute("fill", "#c00", "p-")).toBe("#c00");
    expect(drawingAttribute("height", 42, "p-")).toBe("42");
    expect(drawingAttribute("onclick", "x()", "p-")).toBeNull();
    expect(drawingAttribute("style", "fill:red", "p-")).toBeNull();
    expect(drawingAttribute("fill", "url(javascript:alert(1))", "p-")).toBeNull();
    expect(drawingAttribute("href", "https://elsewhere.test/a.svg#x", "p-")).toBeNull();
    expect(drawingAttribute("fill", { a: 1 }, "p-")).toBeNull();
    expect(drawingAttribute("fill", Number.NaN, "p-")).toBeNull();
  });

  it("animates an element through its overrides, so a binding on elements.<id>.<attribute> moves it", () => {
    const markup = '<svg viewBox="0 0 100 100"><rect id="level" x="0" y="50" width="10" height="50" fill="#3d7ab8"/></svg>';
    const { container, rerender } = render(<Drawing nodeId="d" locale="en-US" onChange={vi.fn()} props={{ svg: markup, alt: "Tank T-1" }} />);
    expect(screen.getByRole("img", { name: "Tank T-1" })).toBeInTheDocument();
    const level = () => container.querySelector("rect")!;
    expect(level().getAttribute("height")).toBe("50");
    expect(level().id).toMatch(/^oipd.*-level$/);
    rerender(<Drawing nodeId="d" locale="en-US" onChange={vi.fn()} props={{ svg: markup, alt: "Tank T-1", elements: { level: { height: 80, y: 20, fill: "#c00", onclick: "x()" } } }} />);
    expect(level().getAttribute("height")).toBe("80");
    expect(level().getAttribute("y")).toBe("20");
    expect(level().getAttribute("fill")).toBe("#c00");
    expect(level().hasAttribute("onclick")).toBe(false);
    expect(container.querySelector("svg")!.getAttribute("preserveAspectRatio")).toBe("xMidYMid meet");
  });

  it("gives two drawings on a page their own ids", () => {
    const markup = '<svg viewBox="0 0 10 10"><rect id="r" width="1" height="1"/></svg>';
    const { container } = render(
      <>
        <Drawing nodeId="a" locale="en-US" onChange={vi.fn()} props={{ svg: markup }} />
        <Drawing nodeId="b" locale="en-US" onChange={vi.fn()} props={{ svg: markup }} />
      </>,
    );
    const ids = Array.from(container.querySelectorAll("rect")).map((rect) => rect.id);
    expect(new Set(ids).size).toBe(2);
  });

  it("shows why an invalid drawing is not drawn", () => {
    render(<Drawing nodeId="d" locale="en-US" onChange={vi.fn()} props={{ svg: "<svg><g></svg>", alt: "Broken" }} />);
    expect(screen.getByRole("img", { name: "Broken" })).toHaveAttribute("data-invalid", "true");
    expect(screen.getByText("The drawing is not valid SVG")).toBeInTheDocument();
  });
});

describe("PDF viewer", () => {
  it("frames the document at the page asked for", () => {
    expect(pdfFrameSource("/docs/manual.pdf", 3, true)).toBe("/docs/manual.pdf#page=3");
    expect(pdfFrameSource("https://media.test/m.pdf#page=9", 0, false)).toBe("https://media.test/m.pdf#page=1&toolbar=0");
    const { rerender } = render(<PdfViewer nodeId="p" locale="en-US" onChange={vi.fn()} props={{ source: "https://media.test/m.pdf", page: 2, title: "Manual" }} />);
    expect(screen.getByTitle("Manual")).toHaveAttribute("src", "https://media.test/m.pdf#page=2");
    expect(screen.getByTitle("Manual")).not.toHaveAttribute("sandbox");
    rerender(<PdfViewer nodeId="p" locale="en-US" onChange={vi.fn()} props={{ source: "https://media.test/m.pdf", page: 5, title: "Manual", toolbar: false }} />);
    expect(screen.getByTitle("Manual")).toHaveAttribute("src", "https://media.test/m.pdf#page=5&toolbar=0");
  });

  it("frames nothing for a refused source", () => {
    for (const source of REFUSED) {
      const { container, unmount } = render(<PdfViewer nodeId="p" locale="en-US" onChange={vi.fn()} props={{ source, title: "Manual" }} />);
      expect(container.querySelector("iframe"), source).toBeNull();
      expect(screen.getByRole("document", { name: "Manual" })).toHaveAttribute("data-empty", "true");
      unmount();
    }
  });
});

describe("audio and video", () => {
  let play: ReturnType<typeof vi.fn>;
  let pause: ReturnType<typeof vi.fn>;
  let paused = true;

  beforeEach(() => {
    paused = true;
    play = vi.fn(function (this: HTMLMediaElement) {
      paused = false;
      this.dispatchEvent(new Event("play"));
      return Promise.resolve();
    });
    pause = vi.fn(function (this: HTMLMediaElement) {
      paused = true;
      this.dispatchEvent(new Event("pause"));
    });
    vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(play as unknown as () => Promise<void>);
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(pause as unknown as () => void);
    vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockImplementation(() => paused);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("plays and pauses from the bound playing property", () => {
    const onChange = vi.fn();
    const { container, rerender } = render(<Audio nodeId="a" locale="en-US" onChange={onChange} props={{ source: "/media/horn.wav", label: "Horn" }} />);
    const audio = container.querySelector("audio")!;
    expect(audio).toHaveAttribute("src", "/media/horn.wav");
    expect(audio).toHaveAttribute("aria-label", "Horn");
    expect(audio).toHaveAttribute("controls");
    expect(play).not.toHaveBeenCalled();
    rerender(<Audio nodeId="a" locale="en-US" onChange={onChange} props={{ source: "/media/horn.wav", label: "Horn", playing: true, volume: 0.25 }} />);
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.volume).toBe(0.25);
    expect(onChange).not.toHaveBeenCalled();
    rerender(<Audio nodeId="a" locale="en-US" onChange={onChange} props={{ source: "/media/horn.wav", label: "Horn", playing: false }} />);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("writes playing back when the operator starts or stops the player, or the clip ends", () => {
    const onChange = vi.fn();
    const { container, rerender } = render(<Audio nodeId="a" locale="en-US" onChange={onChange} props={{ source: "/media/horn.wav", playing: false }} />);
    const audio = container.querySelector("audio")!;
    fireEvent.play(audio);
    expect(onChange).toHaveBeenLastCalledWith("playing", true);
    rerender(<Audio nodeId="a" locale="en-US" onChange={onChange} props={{ source: "/media/horn.wav", playing: true }} />);
    fireEvent.pause(audio);
    expect(onChange).toHaveBeenLastCalledWith("playing", false);
    onChange.mockClear();
    fireEvent.ended(audio);
    expect(onChange).toHaveBeenLastCalledWith("playing", false);
  });

  it("says the player stopped when the browser refuses to start it", async () => {
    play.mockImplementation(() => Promise.reject(new Error("NotAllowedError")));
    const onChange = vi.fn();
    render(<Video nodeId="v" locale="en-US" onChange={onChange} props={{ source: "https://media.test/clip.mp4", playing: true }} />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(onChange).toHaveBeenCalledWith("playing", false);
  });

  it("shows a video with its poster, muted by default", () => {
    const { container } = render(
      <Video nodeId="v" locale="en-US" onChange={vi.fn()} props={{ source: "https://media.test/clip.mp4", poster: "data:image/png;base64,iVBORw0KGgo=", label: "Line camera", fit: "cover" }} />,
    );
    const video = container.querySelector("video")!;
    expect(video).toHaveAttribute("src", "https://media.test/clip.mp4");
    expect(video).toHaveAttribute("poster", "data:image/png;base64,iVBORw0KGgo=");
    expect(video).toHaveAttribute("aria-label", "Line camera");
    expect(video).toHaveAttribute("data-fit", "cover");
    expect(video.muted).toBe(true);
    const refusedPoster = render(<Video nodeId="w" locale="en-US" onChange={vi.fn()} props={{ source: "/v.webm", poster: "javascript:alert(1)" }} />);
    expect(refusedPoster.container.querySelector("video")).not.toHaveAttribute("poster");
  });

  it("loads nothing for a refused source", () => {
    for (const source of REFUSED) {
      const audio = render(<Audio nodeId="a" locale="en-US" onChange={vi.fn()} props={{ source, label: "Horn" }} />);
      expect(audio.container.querySelector("audio"), source).toBeNull();
      expect(screen.getByRole("group", { name: "Horn" })).toHaveAttribute("data-empty", "true");
      audio.unmount();
      const video = render(<Video nodeId="v" locale="en-US" onChange={vi.fn()} props={{ source, label: "Camera", playing: true }} />);
      expect(video.container.querySelector("video"), source).toBeNull();
      video.unmount();
    }
    expect(play).not.toHaveBeenCalled();
  });
});
