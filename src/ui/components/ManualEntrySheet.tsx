import { useEffect, useState } from 'react';
import { useStore } from '../../state/useStore';

export function ManualEntrySheet() {
  const open = useStore((s) => s.manualSheetOpen);
  const prefillNote = useStore((s) => s.manualPrefillNote);
  const close = useStore((s) => s.closeManualSheet);
  const submitManual = useStore((s) => s.submitManual);

  const [carbs, setCarbs] = useState('');
  const [bg, setBg] = useState('');
  const [units, setUnits] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    if (open) setNote(prefillNote);
  }, [open, prefillNote]);

  if (!open) return null;

  const submit = () => {
    const carbsG = Number(carbs) || 0;
    const bgOverride = bg.trim() ? Number(bg) : undefined;
    const unitsTaken = units.trim() ? Number(units) : undefined;
    void submitManual({ carbsG, bgOverride, unitsTaken, note: note.trim() || undefined });
    setCarbs(''); setBg(''); setUnits(''); setNote('');
  };

  const field = (label: string, value: string, set: (v: string) => void, placeholder: string, type = 'number') => (
    <label className="block">
      <span className="text-sm text-muted">{label}</span>
      <input
        type={type}
        inputMode={type === 'number' ? 'decimal' : undefined}
        value={value}
        onChange={(e) => set(e.target.value)}
        placeholder={placeholder}
        className="mt-1 w-full rounded-lg bg-surface2 px-3 py-3 text-fg outline-none"
      />
    </label>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60" onClick={close}>
      <div
        className="w-full max-w-lg rounded-t-2xl bg-surface p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))]"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-semibold">Manual entry</h2>
        <div className="mt-3 space-y-3">
          {field('Carbs (g)', carbs, setCarbs, '45')}
          {field('BG override (mg/dL, optional)', bg, setBg, 'leave blank to use sensor')}
          {field('Insulin already taken (U, optional)', units, setUnits, '0')}
          {field('Note', note, setNote, 'optional', 'text')}
        </div>
        <div className="mt-4 flex gap-3">
          <button onClick={close} className="flex-1 rounded-xl bg-surface2 py-3">Cancel</button>
          <button onClick={submit} className="flex-1 rounded-xl bg-accent py-3 font-semibold text-black">
            Calculate
          </button>
        </div>
      </div>
    </div>
  );
}
