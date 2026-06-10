import { useStore, type Screen } from '../../state/useStore';

const TABS: { id: Screen; label: string; icon: string }[] = [
  { id: 'home', label: 'Home', icon: '⌂' },
  { id: 'history', label: 'History', icon: '☰' },
  { id: 'insights', label: 'Insights', icon: '◔' },
  { id: 'settings', label: 'Settings', icon: '⚙' },
];

export function TabBar() {
  const screen = useStore((s) => s.screen);
  const setScreen = useStore((s) => s.setScreen);
  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 border-t border-surface2 bg-surface">
      <div className="mx-auto flex max-w-lg">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setScreen(tab.id)}
            className={`flex flex-1 flex-col items-center gap-0.5 py-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] text-xs ${
              screen === tab.id ? 'text-accent' : 'text-muted'
            }`}
          >
            <span className="text-lg leading-none">{tab.icon}</span>
            {tab.label}
          </button>
        ))}
      </div>
    </nav>
  );
}
