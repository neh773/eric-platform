import { describe, expect, it } from "vitest";
import { enumList, indexProblemErrors, validateAgainstSchema, type JsonSchema } from "./schema";

const greeting: JsonSchema = {
  type: "object",
  required: ["greeting"],
  properties: {
    greeting: { type: "string", minLength: 1, maxLength: 200 },
    note: { type: "string", pattern: "^[a-z]+$" },
    count: { type: "integer", minimum: 0, maximum: 10 },
    tags: { type: "array", items: { type: "string", minLength: 1 } },
    nested: {
      type: "object",
      required: ["city"],
      properties: { city: { type: "string", minLength: 1 } },
    },
  },
};

describe("validateAgainstSchema", () => {
  it("rejects an empty or over-long greeting and accepts a short one", () => {
    expect(validateAgainstSchema(greeting, { greeting: "" }).map((error) => error.message)).toContain("At least 1 character");
    expect(validateAgainstSchema(greeting, { greeting: "x".repeat(201) }).map((error) => error.message)).toContain("At most 200 characters");
    expect(validateAgainstSchema(greeting, { greeting: "Hello" })).toEqual([]);
  });

  it("checks pattern, integers, arrays, and nested objects", () => {
    const errors = validateAgainstSchema(greeting, {
      greeting: "Hi",
      note: "No1",
      count: 1.5,
      tags: [""],
      nested: {},
    });
    const byPath = Object.fromEntries(errors.map((error) => [error.path, error.message]));
    expect(byPath.note).toMatch(/pattern/);
    expect(byPath.count).toMatch(/integer/);
    expect(byPath["tags.0"]).toMatch(/At least 1/);
    expect(byPath["nested.city"]).toBe("Required");
  });
});

describe("enumList", () => {
  it("reads the string enum from an array property", () => {
    expect(enumList(greeting, "tags")).toEqual([]);
    expect(
      enumList(
        {
          type: "object",
          properties: { scopes: { type: "array", items: { type: "string", enum: ["gateway.read", "audit.read"] } } },
        },
        "scopes",
      ),
    ).toEqual(["gateway.read", "audit.read"]);
  });
});

describe("indexProblemErrors", () => {
  it("maps validator messages onto field paths", () => {
    expect(
      indexProblemErrors([
        "$.greeting: must be at least 1 characters long",
        "$.nested: required property 'city' not found",
        "$.tags[0]: must be at least 1 characters long",
      ]),
    ).toEqual({
      greeting: "must be at least 1 characters long",
      "nested.city": "Required",
      "tags.0": "must be at least 1 characters long",
    });
  });
});
