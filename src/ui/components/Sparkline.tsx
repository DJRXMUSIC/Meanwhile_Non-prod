import type { BgReading } from '../../lib/types';

interface Props {
  readings: BgReading[];   // newest first
  width?: number;
  height?: number;
  lowThreshold?: number;
}

export function Sparkline({ readings, width = 320, height = 80, lowThreshold = 70 }: Props) {
  if (readings.length < 2) {
    return <div className="text-sm text-muted">Not enough data for a chart yet.</div>;
  }
  const pts = [...readings].reverse(); // oldest → newest, left → right
  const values = pts.map((r) => r.mgdl);
  const min = Math.min(...values, lowThreshold) - 10;
  const max = Math.max(...values) + 10;
  const x = (i: number) => (i / (pts.length - 1)) * (width - 8) + 4;
  const y = (v: number) => height - 4 - ((v - min) / (max - min)) * (height - 8);
  const path = pts.map((r, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(r.mgdl).toFixed(1)}`).join(' ');
  const lowY = y(lowThreshold);

  return (
    <svg width="100%" viewBox={`0 0 ${width} ${height}`} className="block">
      <line x1="0" y1={lowY} x2={width} y2={lowY} stroke="#E5534B" strokeDasharray="4 4" strokeWidth="1" opacity="0.5" />
      <path d={path} fill="none" stroke="#3FB68B" strokeWidth="2" strokeLinejoin="round" />
      <circle cx={x(pts.length - 1)} cy={y(pts[pts.length - 1].mgdl)} r="3" fill="#3FB68B" />
    </svg>
  );
}
