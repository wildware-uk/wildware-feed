/**
 * Byte ranges and If-None-Match, as the server answers them (server `media/Serve.kt`): one range of
 * `bytes=a-b`, `bytes=a-` or `bytes=-n`; anything else is the whole file.
 */
export type Range = { kind: "whole" } | { kind: "span"; start: number; end: number } | { kind: "unsatisfiable" };

const pattern = /^bytes=(\d*)-(\d*)$/;

/** The header when it is one range this edge understands, else null (the whole file). */
export function singleRange(header: string | null): string | null {
  if (header === null) return null;
  const m = pattern.exec(header.trim());
  if (!m || (m[1] === "" && m[2] === "")) return null;
  return header.trim();
}

export function range(header: string | null, size: number): Range {
  if (header === null) return { kind: "whole" };
  const m = pattern.exec(header.trim());
  if (!m) return { kind: "whole" };
  const startText = m[1] ?? "";
  const endText = m[2] ?? "";
  if (startText === "" && endText === "") return { kind: "whole" };
  if (size === 0) return { kind: "unsatisfiable" };
  if (startText === "") {
    const n = Number(endText);
    if (n === 0) return { kind: "unsatisfiable" };
    return { kind: "span", start: Math.max(0, size - n), end: size - 1 };
  }
  const start = Number(startText);
  if (start >= size) return { kind: "unsatisfiable" };
  const end = endText === "" ? size - 1 : Math.min(Number(endText), size - 1);
  if (end < start) return { kind: "unsatisfiable" };
  return { kind: "span", start, end };
}

/** `*`, or any listed tag (W/ dropped) equal to [etag]. */
export function notModified(header: string | null, etag: string): boolean {
  if (header === null) return false;
  const trimmed = header.trim();
  if (trimmed === "*") return true;
  return trimmed.split(",").some((t) => t.trim().replace(/^W\//, "") === etag);
}
