/**
 * The `payload` an event script gets in `event.payload`: plain JSON copied from the DOM event while it is handled.
 * Every event carries its `type` (`click`, `change`, ...). Pointer and mouse events add the pointer position in the
 * page (`clientX`, `clientY`, `pageX`, `pageY`, `screenX`, `screenY`), relative to the component (`x`, `y`), the
 * buttons and modifier keys, and `pointerType` when the browser reports one. Keyboard events add `key` and `code`.
 */
export type EventPayload = Record<string, string | number | boolean>;

interface DomLike {
  type?: unknown;
  currentTarget?: unknown;
  [key: string]: unknown;
}

const POINTER_FIELDS = ["clientX", "clientY", "pageX", "pageY", "screenX", "screenY", "button", "buttons"] as const;
const MODIFIERS = ["altKey", "ctrlKey", "shiftKey", "metaKey"] as const;

export function eventPayload(event: unknown): EventPayload | undefined {
  if (!event || typeof event !== "object") return undefined;
  const dom = event as DomLike;
  if (typeof dom.type !== "string" || dom.type.length === 0) return undefined;
  const payload: EventPayload = { type: dom.type };
  if (typeof dom.clientX === "number" && typeof dom.clientY === "number") {
    for (const field of POINTER_FIELDS) {
      const value = dom[field];
      if (typeof value === "number" && Number.isFinite(value)) payload[field] = round(value);
    }
    const rect = boundsOf(dom.currentTarget);
    if (rect) {
      payload.x = round(dom.clientX - rect.left);
      payload.y = round(dom.clientY - rect.top);
    }
    if (typeof dom.pointerType === "string" && dom.pointerType) payload.pointerType = dom.pointerType;
  }
  if (typeof dom.key === "string") payload.key = dom.key;
  if (typeof dom.code === "string") payload.code = dom.code;
  for (const field of MODIFIERS) {
    if (typeof dom[field] === "boolean") payload[field] = dom[field] as boolean;
  }
  return payload;
}

function boundsOf(target: unknown): { left: number; top: number } | null {
  if (!target || typeof (target as Element).getBoundingClientRect !== "function") return null;
  const rect = (target as Element).getBoundingClientRect();
  return { left: rect.left, top: rect.top };
}

/** Sub-pixel positions are kept to two decimals. */
function round(value: number): number {
  return Math.round(value * 100) / 100;
}
