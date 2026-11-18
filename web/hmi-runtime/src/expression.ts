/** A small expression subset for expression bindings and map rows.
 * Property refs, tag refs, arithmetic, comparisons, `if`, `abs`, and `now()` (the current time in epoch milliseconds,
 * which an expression binding's poll interval re-reads, HMP-118).
 * Not the full the reference platform expression language (that lands with the gateway expression engine).
 */

export class ExpressionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ExpressionError";
  }
}

export interface ExpressionScope {
  /** Resolve a `{...}` reference. Tag refs arrive as `tag:path` (provider stripped). */
  resolve(ref: string): unknown;
  /** `translate(term)` and `translate(term, locale)`: the term in the session locale (HMP-125). Without it the term is returned. */
  translate?(term: string, locale?: string): string;
}

const TAG_REF = /\{\[([^\]]+)\]([^}]+)\}/g;

/** Tag paths referenced by an expression. The `[provider]` segment is kept out of the path. */
export function tagPathsInExpression(source: string): string[] {
  const paths = new Set<string>();
  for (const match of source.matchAll(TAG_REF)) {
    const path = match[2]?.trim();
    if (path) paths.add(path);
  }
  return [...paths];
}

type Token =
  | { type: "num"; value: number }
  | { type: "str"; value: string }
  | { type: "ref"; value: string }
  | { type: "id"; value: string }
  | { type: "op"; value: string }
  | { type: "lp" }
  | { type: "rp" }
  | { type: "comma" }
  | { type: "eof" };

export function evaluateExpression(source: string, scope: ExpressionScope): unknown {
  const tokens = tokenize(source);
  let index = 0;
  function peek(): Token {
    return tokens[index] ?? { type: "eof" };
  }
  function take(): Token {
    const token = peek();
    index += 1;
    return token;
  }
  function parseOr(): unknown {
    let left = parseAnd();
    while (isOp("||")) {
      take();
      const right = parseAnd();
      left = truthy(left) || truthy(right);
    }
    return left;
  }
  function parseAnd(): unknown {
    let left = parseCmp();
    while (isOp("&&")) {
      take();
      const right = parseCmp();
      left = truthy(left) && truthy(right);
    }
    return left;
  }
  function parseCmp(): unknown {
    let left = parseSum();
    while (isOp("==") || isOp("!=") || isOp("<") || isOp(">") || isOp("<=") || isOp(">=")) {
      const op = (take() as { value: string }).value;
      const right = parseSum();
      left = compare(op, left, right);
    }
    return left;
  }
  function parseSum(): unknown {
    let left = parseProduct();
    while (isOp("+") || isOp("-")) {
      const op = (take() as { value: string }).value;
      const right = parseProduct();
      left = op === "+" ? add(left, right) : num(left) - num(right);
    }
    return left;
  }
  function parseProduct(): unknown {
    let left = parseUnary();
    while (isOp("*") || isOp("/") || isOp("%")) {
      const op = (take() as { value: string }).value;
      const right = parseUnary();
      const b = num(right);
      if ((op === "/" || op === "%") && b === 0) return null;
      const a = num(left);
      left = op === "*" ? a * b : op === "/" ? a / b : a % b;
    }
    return left;
  }
  function parseUnary(): unknown {
    if (isOp("!") || isOp("-")) {
      const op = (take() as { value: string }).value;
      const value = parseUnary();
      return op === "!" ? !truthy(value) : -num(value);
    }
    return parsePrimary();
  }
  function parsePrimary(): unknown {
    const token = take();
    if (token.type === "num") return token.value;
    if (token.type === "str") return token.value;
    if (token.type === "ref") return scope.resolve(token.value);
    if (token.type === "id") {
      if (token.value === "true") return true;
      if (token.value === "false") return false;
      if (token.value === "null") return null;
      if (token.value === "value") return scope.resolve("value");
      if (peek().type !== "lp") throw new ExpressionError(`unknown name: ${token.value}`);
      take();
      const args: unknown[] = [];
      if (peek().type !== "rp") {
        args.push(parseOr());
        while (peek().type === "comma") {
          take();
          args.push(parseOr());
        }
      }
      if (take().type !== "rp") throw new ExpressionError("expected )");
      return call(token.value, args, scope);
    }
    if (token.type === "lp") {
      const value = parseOr();
      if (take().type !== "rp") throw new ExpressionError("expected )");
      return value;
    }
    throw new ExpressionError("unexpected token in expression");
  }
  function isOp(value: string): boolean {
    const token = peek();
    return token.type === "op" && token.value === value;
  }
  const value = parseOr();
  if (peek().type !== "eof") throw new ExpressionError("unexpected trailing input");
  return value;
}

function call(name: string, args: unknown[], scope: ExpressionScope): unknown {
  if (name === "translate") {
    if (args.length < 1 || args.length > 2) throw new ExpressionError("translate expects 1 or 2 arguments");
    const term = args[0] == null ? "" : String(args[0]);
    const locale = args[1] == null ? undefined : String(args[1]);
    return scope.translate ? scope.translate(term, locale) : term;
  }
  if (name === "if") {
    if (args.length !== 3) throw new ExpressionError("if expects 3 arguments");
    return truthy(args[0]) ? args[1] : args[2];
  }
  if (name === "now") {
    if (args.length !== 0) throw new ExpressionError("now expects no arguments");
    return Date.now();
  }
  if (name === "abs") {
    if (args.length !== 1) throw new ExpressionError("abs expects 1 argument");
    return Math.abs(num(args[0]));
  }
  throw new ExpressionError(`unknown function: ${name}`);
}

function add(left: unknown, right: unknown): unknown {
  if (typeof left === "string" || typeof right === "string") return `${left ?? ""}${right ?? ""}`;
  return num(left) + num(right);
}

function compare(op: string, left: unknown, right: unknown): boolean {
  if (op === "==") return left === right;
  if (op === "!=") return left !== right;
  const a = num(left);
  const b = num(right);
  if (op === "<") return a < b;
  if (op === ">") return a > b;
  if (op === "<=") return a <= b;
  return a >= b;
}

function num(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value))) return Number(value);
  return 0;
}

function truthy(value: unknown): boolean {
  return value !== null && value !== false && value !== 0 && value !== "";
}

function tokenize(source: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < source.length) {
    const ch = source[i]!;
    if (ch === " " || ch === "\n" || ch === "\t" || ch === "\r") {
      i += 1;
      continue;
    }
    if (ch === "(") {
      tokens.push({ type: "lp" });
      i += 1;
      continue;
    }
    if (ch === ")") {
      tokens.push({ type: "rp" });
      i += 1;
      continue;
    }
    if (ch === ",") {
      tokens.push({ type: "comma" });
      i += 1;
      continue;
    }
    if (ch === "{") {
      const end = source.indexOf("}", i + 1);
      if (end < 0) throw new ExpressionError("unclosed reference");
      const body = source.slice(i + 1, end).trim();
      const tag = /^\[([^\]]+)\]([\s\S]+)$/.exec(body);
      tokens.push({ type: "ref", value: tag ? `tag:${tag[2]!.trim()}` : body });
      i = end + 1;
      continue;
    }
    if (ch === "'" || ch === '"') {
      let value = "";
      i += 1;
      while (i < source.length && source[i] !== ch) {
        if (source[i] === "\\" && i + 1 < source.length) {
          value += source[i + 1];
          i += 2;
          continue;
        }
        value += source[i];
        i += 1;
      }
      if (source[i] !== ch) throw new ExpressionError("unclosed string");
      i += 1;
      tokens.push({ type: "str", value });
      continue;
    }
    const op = matchOp(source, i);
    if (op) {
      tokens.push({ type: "op", value: op });
      i += op.length;
      continue;
    }
    if (ch >= "0" && ch <= "9" || (ch === "." && source[i + 1]! >= "0" && source[i + 1]! <= "9")) {
      const start = i;
      i += 1;
      while (i < source.length && /[0-9.]/.test(source[i]!)) i += 1;
      const value = Number(source.slice(start, i));
      if (!Number.isFinite(value)) throw new ExpressionError("bad number");
      tokens.push({ type: "num", value });
      continue;
    }
    if (/[A-Za-z_]/.test(ch)) {
      const start = i;
      i += 1;
      while (i < source.length && /[A-Za-z0-9_]/.test(source[i]!)) i += 1;
      tokens.push({ type: "id", value: source.slice(start, i) });
      continue;
    }
    throw new ExpressionError(`unexpected character: ${ch}`);
  }
  tokens.push({ type: "eof" });
  return tokens;
}

function matchOp(source: string, index: number): string | null {
  const two = source.slice(index, index + 2);
  if (two === "&&" || two === "||" || two === "==" || two === "!=" || two === "<=" || two === ">=") return two;
  const one = source[index];
  if (one === "+" || one === "-" || one === "*" || one === "/" || one === "%" || one === "<" || one === ">" || one === "!") return one;
  return null;
}
