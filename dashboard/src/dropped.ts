// Dropped events card (Q164, ADR-0036). The SDKs count events they dropped and report the counts; the numbers are approximate.
import type { ClientReportCounts } from "../../generated/typescript/models";

/** Sum of the shown rows. `undefined` before the first result. When `truncated` is true this is not the total of the period. */
export function droppedTotal(counts: ClientReportCounts | undefined): number | undefined {
  return counts ? counts.items.reduce((sum, r) => sum + r.count, 0) : undefined;
}
