// Owns the exact breakdown line strings. Tests assert these character-for-character.
// Uses U+2212 MINUS SIGN and U+00F7 DIVISION SIGN per the spec block in §7.4.

const MINUS = '−';

/** Trim trailing zeros: 6.5 -> "6.5", 6 -> "6", 0.5 -> "0.5". */
export function trimNum(n: number): string {
  return Number(n.toFixed(2)).toString();
}

function signed2(n: number): string {
  const abs = Math.abs(n).toFixed(2);
  return n < 0 ? `${MINUS}${abs}` : `+${abs}`;
}

export interface BreakdownInput {
  carbsG: number;
  icr: number;
  bg: number | null;
  targetBg: number;
  isf: number;
  iob: number;
  rawUnits: number;
  units: number;
  roundStep: number;
}

export function buildBreakdown(b: BreakdownInput): string[] {
  const lines: string[] = [];
  if (b.carbsG > 0) {
    lines.push(`Carbs: ${trimNum(b.carbsG)} g ÷ ${trimNum(b.icr)} g/U = ${(b.carbsG / b.icr).toFixed(2)} U`);
  }
  if (b.bg !== null) {
    const corr = (b.bg - b.targetBg) / b.isf;
    lines.push(`Correction: (${trimNum(b.bg)} ${MINUS} ${trimNum(b.targetBg)}) ÷ ${trimNum(b.isf)} = ${signed2(corr)} U`);
  }
  if (b.iob > 0) {
    lines.push(`Insulin on board: ${MINUS}${b.iob.toFixed(2)} U`);
  }
  lines.push(`Subtotal: ${b.rawUnits.toFixed(2)} U`);
  lines.push(`Rounded down to ${trimNum(b.roundStep)} U step → ${trimNum(b.units)} U`);
  return lines;
}
