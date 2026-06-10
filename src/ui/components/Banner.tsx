export function Banner({ tone, text }: { tone: 'info' | 'warn' | 'error'; text: string }) {
  const cls =
    tone === 'error'
      ? 'bg-red/20 text-red'
      : tone === 'warn'
        ? 'bg-amber/20 text-amber'
        : 'bg-surface2 text-muted';
  return <div className={`rounded-lg px-3 py-2 text-sm ${cls}`}>{text}</div>;
}
