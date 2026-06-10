export function Breakdown({ lines }: { lines: string[] }) {
  return (
    <pre className="overflow-x-auto rounded-lg bg-bg p-3 font-mono text-sm leading-6 text-fg">
      {lines.join('\n')}
    </pre>
  );
}
