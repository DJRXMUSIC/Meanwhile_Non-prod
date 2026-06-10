import { useEffect, useState } from 'react';
import { useStore, type CardState } from '../../state/useStore';
import { useSettings } from '../../lib/settings';
import { trimNum } from '../../lib/insulin/format';
import { Banner } from './Banner';
import { Breakdown } from './Breakdown';
import { ConfirmSheet } from './ConfirmSheet';

function FlagBanners({ card }: { card: CardState }) {
  const settings = useSettings();
  const flags = card.calc.flags;
  return (
    <div className="space-y-2">
      {flags.includes('stale_bg') && card.snapshot.bgAgeMin !== null && (
        <Banner tone="warn" text={`BG is ${card.snapshot.bgAgeMin} min old`} />
      )}
      {flags.includes('recent_bolus') && card.snapshot.recentBolusMinAgo !== null && (
        <Banner
          tone="info"
          text={`You logged a bolus ${Math.round(card.snapshot.recentBolusMinAgo)} min ago — already counted in IOB (${card.snapshot.iob.toFixed(1)} U)`}
        />
      )}
      {flags.includes('low_confidence') && (
        <Banner tone="warn" text="Estimate — tap any item to adjust" />
      )}
      {flags.includes('clarification') && card.extraction?.clarificationNeeded && (
        <Banner tone="info" text={`❓ ${card.extraction.clarificationNeeded}`} />
      )}
      {flags.includes('high_carb_parse') && (
        <Banner tone="warn" text={`Parsed ${card.carbsG} g carbs (> ${settings.highCarbWarn} g) — verify the meal`} />
      )}
    </div>
  );
}

function ItemChips({ card }: { card: CardState }) {
  const editCarbs = useStore((s) => s.editCarbs);
  const [editing, setEditing] = useState<number | 'total' | null>(null);
  const [value, setValue] = useState('');

  const startEdit = (index: number | 'total', current: number) => {
    setEditing(index);
    setValue(String(current));
  };
  const commit = () => {
    const n = Number(value);
    if (Number.isFinite(n) && n >= 0) {
      editCarbs(editing === 'total' ? null : (editing as number), n);
    }
    setEditing(null);
  };

  if (card.items.length === 0 && card.carbsG === 0) return null;

  return (
    <div className="flex flex-wrap items-center gap-2">
      {card.items.map((item, i) =>
        editing === i ? (
          <span key={i} className="flex items-center gap-1 rounded-full bg-surface2 px-3 py-1.5 text-sm">
            {item.name}
            <input
              autoFocus
              type="number"
              inputMode="decimal"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onBlur={commit}
              onKeyDown={(e) => e.key === 'Enter' && commit()}
              className="w-14 rounded bg-bg px-1 text-accent outline-none"
            />
            g
          </span>
        ) : (
          <button
            key={i}
            onClick={() => startEdit(i, item.carbsG)}
            className={`rounded-full bg-surface2 px-3 py-1.5 text-sm ${item.confidence < 0.5 ? 'ring-1 ring-amber' : ''}`}
          >
            {item.name} · {trimNum(item.carbsG)}g ✎
          </button>
        ),
      )}
      {editing === 'total' ? (
        <span className="flex items-center gap-1 rounded-full bg-surface2 px-3 py-1.5 text-sm font-semibold">
          Total
          <input
            autoFocus
            type="number"
            inputMode="decimal"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onBlur={commit}
            onKeyDown={(e) => e.key === 'Enter' && commit()}
            className="w-14 rounded bg-bg px-1 text-accent outline-none"
          />
          g
        </span>
      ) : (
        <button onClick={() => startEdit('total', card.carbsG)} className="rounded-full px-3 py-1.5 text-sm font-semibold text-accent">
          Total {trimNum(card.carbsG)}g ✎
        </button>
      )}
    </div>
  );
}

function RationaleBlock({ card }: { card: CardState }) {
  const refreshRationale = useStore((s) => s.refreshRationale);
  if (card.rationaleFailed) {
    return <p className="text-sm text-muted">Explanation unavailable.</p>;
  }
  if (!card.rationale) {
    return (
      <div className="space-y-2">
        <div className="shimmer h-3 w-full rounded bg-surface2" />
        <div className="shimmer h-3 w-4/5 rounded bg-surface2" />
        <div className="shimmer h-3 w-3/5 rounded bg-surface2" />
      </div>
    );
  }
  return (
    <div>
      <p className="text-sm leading-6">{card.rationale.rationale}</p>
      {card.rationaleStale && (
        <button onClick={() => void refreshRationale()} className="mt-1 text-xs text-amber">
          ⟳ Based on original numbers — tap to regenerate
        </button>
      )}
      {card.rationale.cautions.length > 0 && (
        <ul className="mt-2 space-y-1">
          {card.rationale.cautions.map((c) => (
            <li key={c} className="text-sm text-amber">⚠ {c}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function ResultCard() {
  const card = useStore((s) => s.card);
  const phase = useStore((s) => s.phase);
  const requestLog = useStore((s) => s.requestLog);
  const confirmLog = useStore((s) => s.confirmLog);
  const cancelConfirm = useStore((s) => s.cancelConfirm);
  const confirmSheet = useStore((s) => s.confirmSheet);
  const dismissCard = useStore((s) => s.dismissCard);
  const setLogAlsoStatedDose = useStore((s) => s.setLogAlsoStatedDose);

  const [doseInput, setDoseInput] = useState('');
  const [showLowMath, setShowLowMath] = useState(false);

  const suggested = card?.calc.units ?? 0;
  useEffect(() => {
    setDoseInput(String(suggested));
    setShowLowMath(false);
  }, [suggested, card?.inputText]);

  if (!card) return null;

  const isQa = card.answer !== null;
  if (isQa) {
    return (
      <section className="mx-4 mt-4 rounded-2xl bg-surface p-4">
        <p className="text-sm text-muted">“{card.inputText}”</p>
        <p className="mt-2 leading-6">{card.answer}</p>
        <button onClick={dismissCard} className="mt-3 text-sm text-muted">Dismiss</button>
      </section>
    );
  }

  const lowBg = card.calc.flags.includes('low_bg');
  const actualUnits = Number(doseInput) || 0;
  const isMeal = card.carbsG > 0;
  const logLabel = lowBg
    ? `Log ${card.calc.rescueCarbsG ?? 15} g rescue`
    : actualUnits > 0
      ? `Log ${trimNum(actualUnits)} U`
      : isMeal
        ? 'Log meal — no insulin'
        : 'Log';

  return (
    <section className="mx-4 mt-4 space-y-3 rounded-2xl bg-surface p-4">
      <h2 className={`text-2xl font-bold leading-tight ${lowBg ? 'text-red' : ''}`}>{card.calc.headline}</h2>

      <FlagBanners card={card} />

      {lowBg ? (
        <div>
          <button onClick={() => setShowLowMath((v) => !v)} className="text-sm text-muted underline">
            {showLowMath ? 'Hide dose math' : 'Show dose math anyway'}
          </button>
          {showLowMath && <div className="mt-2"><Breakdown lines={card.calc.breakdown} /></div>}
        </div>
      ) : (
        <Breakdown lines={card.calc.breakdown} />
      )}

      <ItemChips card={card} />

      <RationaleBlock card={card} />

      {card.extraction?.userStatedDoseUnits != null && card.extraction.userStatedDoseUnits > 0 && (
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={card.logAlsoStatedDose}
            onChange={(e) => setLogAlsoStatedDose(e.target.checked)}
          />
          Also log the {trimNum(card.extraction.userStatedDoseUnits)} U you mentioned
        </label>
      )}

      {!lowBg && (
        <label className="flex items-center gap-2 text-sm text-muted">
          Dose to log:
          <input
            type="number"
            inputMode="decimal"
            step="0.5"
            value={doseInput}
            onChange={(e) => setDoseInput(e.target.value)}
            className="w-20 rounded-lg bg-surface2 px-2 py-1.5 text-fg outline-none"
          />
          U {actualUnits !== card.calc.units && <span className="text-amber">(suggested {trimNum(card.calc.units)})</span>}
        </label>
      )}

      <div className="flex gap-3 pt-1">
        <button onClick={dismissCard} className="rounded-xl px-4 py-3 text-muted">Dismiss</button>
        <button
          onClick={() => requestLog(lowBg ? 0 : actualUnits)}
          disabled={phase === 'logging'}
          className="flex-1 rounded-xl bg-accent py-3 font-semibold text-black disabled:opacity-50"
        >
          {phase === 'logging' ? 'Logging…' : logLabel}
        </button>
      </div>

      {confirmSheet && (
        <ConfirmSheet
          reasons={confirmSheet.reasons}
          onConfirm={() => void confirmLog(lowBg ? 0 : actualUnits)}
          onCancel={cancelConfirm}
        />
      )}
    </section>
  );
}
