interface Props {
  reasons: string[];
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmSheet({ reasons, onConfirm, onCancel }: Props) {
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60" onClick={onCancel}>
      <div
        className="w-full max-w-lg rounded-t-2xl bg-surface p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-semibold text-amber">Double-check this one</h2>
        <ul className="mt-3 space-y-2 text-sm">
          {reasons.map((r) => (
            <li key={r} className="rounded-lg bg-amber/10 p-3 text-amber">{r}</li>
          ))}
        </ul>
        <div className="mt-4 flex gap-3">
          <button onClick={onCancel} className="flex-1 rounded-xl bg-surface2 py-3 text-fg">
            Go back
          </button>
          <button onClick={onConfirm} className="flex-1 rounded-xl bg-accent py-3 font-semibold text-black">
            Confirm & log
          </button>
        </div>
      </div>
    </div>
  );
}
