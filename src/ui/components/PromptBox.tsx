import { useState } from 'react';
import { useStore } from '../../state/useStore';
import { MicButton } from './MicButton';

export function PromptBox() {
  const submit = useStore((s) => s.submit);
  const openManualSheet = useStore((s) => s.openManualSheet);
  const phase = useStore((s) => s.phase);
  const [text, setText] = useState('');
  const [voiceUsed, setVoiceUsed] = useState(false);

  const busy = phase === 'snapshotting' || phase === 'extracting';

  const send = () => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    void submit(trimmed, voiceUsed ? 'voice' : 'text');
    setText('');
    setVoiceUsed(false);
  };

  const chip = (label: string, action: () => void) => (
    <button key={label} onClick={action} className="rounded-full bg-surface2 px-3 py-1.5 text-sm text-muted">
      {label}
    </button>
  );

  return (
    <div className="px-4 pt-4">
      <div className="flex items-center gap-3">
        <div className="flex flex-1 items-center rounded-2xl bg-surface ring-2 ring-accent/60">
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && send()}
            placeholder="What's happening?"
            className="flex-1 bg-transparent px-4 py-4 text-lg outline-none placeholder:text-muted"
          />
          {text.trim() && (
            <button onClick={send} disabled={busy} className="pr-4 text-accent disabled:opacity-50">
              {busy ? '…' : 'Send'}
            </button>
          )}
        </div>
        <MicButton
          onTranscript={(t) => {
            setText(t);
            setVoiceUsed(true);
          }}
        />
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {chip('Correction check', () => void submit('correction check', 'text'))}
        {chip('Feeling low', () => void submit('feeling low', 'text'))}
        {chip('Exercise soon', () => void submit('exercise soon', 'text'))}
        {chip('Manual entry', () => openManualSheet())}
        <button onClick={() => openManualSheet()} aria-label="Manual entry" className="rounded-full bg-surface2 px-3 py-1.5 text-sm text-muted">
          123
        </button>
      </div>
      {busy && <div className="shimmer mt-3 text-sm text-muted">Working on it…</div>}
    </div>
  );
}
