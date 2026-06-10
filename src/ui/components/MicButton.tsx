import { useEffect, useRef, useState } from 'react';

// Minimal typings for the vendor-prefixed Web Speech API.
interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start(): void;
  stop(): void;
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
}

function getRecognitionCtor(): (new () => SpeechRecognitionLike) | null {
  const w = window as unknown as Record<string, unknown>;
  return (w.webkitSpeechRecognition as new () => SpeechRecognitionLike) ??
    (w.SpeechRecognition as new () => SpeechRecognitionLike) ?? null;
}

interface Props {
  onTranscript: (text: string) => void; // interim results stream into the input
}

export function MicButton({ onTranscript }: Props) {
  const [listening, setListening] = useState(false);
  const [supported, setSupported] = useState(true);
  const recRef = useRef<SpeechRecognitionLike | null>(null);

  useEffect(() => {
    // Chrome speech is server-backed — hide the mic offline.
    setSupported(getRecognitionCtor() !== null && navigator.onLine);
    const onLine = () => setSupported(getRecognitionCtor() !== null && navigator.onLine);
    window.addEventListener('online', onLine);
    window.addEventListener('offline', onLine);
    return () => {
      window.removeEventListener('online', onLine);
      window.removeEventListener('offline', onLine);
    };
  }, []);

  if (!supported) return null;

  const toggle = () => {
    if (listening) {
      recRef.current?.stop();
      return;
    }
    const Ctor = getRecognitionCtor();
    if (!Ctor) return;
    const rec = new Ctor();
    rec.lang = 'en-US';
    rec.interimResults = true;
    rec.continuous = true;
    rec.onresult = (event) => {
      const text = Array.from({ length: event.results.length }, (_, i) => event.results[i][0].transcript).join('');
      onTranscript(text); // user reviews then sends — never auto-submit
    };
    rec.onend = () => setListening(false);
    rec.onerror = () => setListening(false);
    recRef.current = rec;
    rec.start();
    setListening(true);
  };

  return (
    <button
      onClick={toggle}
      aria-label={listening ? 'Stop listening' : 'Start voice input'}
      className={`flex h-16 w-16 shrink-0 items-center justify-center rounded-full text-2xl transition-colors ${
        listening ? 'animate-pulse bg-red text-white' : 'bg-accent text-black'
      }`}
    >
      {listening ? '◼' : '🎤'}
    </button>
  );
}
