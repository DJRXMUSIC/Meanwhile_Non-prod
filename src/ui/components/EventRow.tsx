import type {
  AppEvent, BgManualPayload, BolusPayload, ContextPayload, MealPayload,
  NotePayload, OutcomePayload, SettingsChangePayload,
} from '../../lib/types';
import { trimNum } from '../../lib/insulin/format';

const ICONS: Record<string, string> = {
  meal: '🍽', bolus: '💉', bg_manual: '🩸', context: '🏷',
  ai_interaction: '✨', outcome: '📈', settings_change: '⚙', note: '📝',
};

export function summarize(event: AppEvent): string {
  switch (event.type) {
    case 'meal': {
      const p = event.payload as MealPayload;
      const names = p.items.map((i) => i.name).join(', ');
      return `${names || 'Meal'} · ${trimNum(p.carbsG)} g carbs`;
    }
    case 'bolus': {
      const p = event.payload as BolusPayload;
      const suggested = p.suggestedUnits !== undefined ? ` (suggested ${trimNum(p.suggestedUnits)})` : '';
      return `${trimNum(p.units)} U${suggested} · ${p.kind}`;
    }
    case 'bg_manual': {
      const p = event.payload as BgManualPayload;
      return `BG ${p.mgdl} mg/dL (manual)`;
    }
    case 'context': {
      const p = event.payload as ContextPayload;
      return p.tags.join(', ') + (p.note ? ` — ${p.note}` : '');
    }
    case 'ai_interaction': {
      const p = event.payload as { inputText?: string };
      return p.inputText ? `"${p.inputText}"` : 'AI interaction';
    }
    case 'outcome': {
      const p = event.payload as OutcomePayload;
      return `Outcome (${p.dataQuality})${p.hypo4h ? ' · hypo <4h' : ''}`;
    }
    case 'settings_change': {
      const p = event.payload as SettingsChangePayload;
      return `${p.key}: ${String(p.from)} → ${String(p.to)}`;
    }
    case 'note':
      return (event.payload as NotePayload).text;
    default:
      return event.type;
  }
}

interface Props {
  event: AppEvent;
  superseded?: boolean;
  supersededBy?: string;
  onTap?: () => void;
  onLongPress?: () => void;
}

export function EventRow({ event, superseded, onTap, onLongPress }: Props) {
  let pressTimer: ReturnType<typeof setTimeout> | undefined;
  return (
    <button
      onClick={onTap}
      onPointerDown={() => {
        if (onLongPress) pressTimer = setTimeout(onLongPress, 550);
      }}
      onPointerUp={() => clearTimeout(pressTimer)}
      onPointerLeave={() => clearTimeout(pressTimer)}
      className="flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left hover:bg-surface"
    >
      <span className="text-lg">{ICONS[event.type] ?? '•'}</span>
      <span className="w-14 shrink-0 text-xs text-muted">
        {new Date(event.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
      </span>
      <span className={`flex-1 truncate text-sm ${superseded ? 'text-muted line-through' : ''}`}>
        {summarize(event)}
      </span>
      {superseded && <span className="text-xs text-muted">amended</span>}
    </button>
  );
}
