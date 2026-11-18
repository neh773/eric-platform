import { describe, expect, it } from "vitest";
import { reducePopups, withViewParams } from "./popups";
import type { PopupAction } from "./types";

/** Defaults from system.screen.openPopup.
 */

const viewport = { width: 800, height: 600 };

function open(partial: Partial<PopupAction> = {}): PopupAction {
  return { kind: "popup", mode: "open", id: "p", view: "detail", ...partial };
}

describe("popup defaults", () => {
  it("matches openPopup: close and drag on, viewport unbound, and the other flags off", () => {
    const [popup] = reducePopups([], open({ position: { left: -40, top: 700, width: 900, height: 100 } }), viewport);
    expect(popup).toMatchObject({
      showClose: true,
      draggable: true,
      resizable: false,
      modal: false,
      backgroundDismissible: false,
      viewportBound: false,
      x: -40,
      y: 700,
      width: 900,
      height: 100,
    });
  });

  it("honors an explicit false close or drag flag and clamps only when viewportBound is true", () => {
    const [plain] = reducePopups([], open({ showClose: false, draggable: false }), viewport);
    expect(plain).toMatchObject({ showClose: false, draggable: false });
    const [bound] = reducePopups([], open({ viewportBound: true, position: { left: -40, top: 700, width: 900, height: 100 } }), viewport);
    expect(bound).toMatchObject({ viewportBound: true, x: 0, y: 500, width: 800, height: 100 });
  });

  it("fills popup ids and string params from the view that opened them", () => {
    const filled = withViewParams(open({ id: "pump-{name}-P1", title: "{name} P1", params: { root: "[default]Stations/{name}/P1", count: 2 } }), { name: "CinderFlat" });
    expect(filled.id).toBe("pump-CinderFlat-P1");
    expect(filled.title).toBe("CinderFlat P1");
    expect(filled.params).toEqual({ root: "[default]Stations/CinderFlat/P1", count: 2 });
  });
});
