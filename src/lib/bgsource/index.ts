import type { BgReading } from '../types';
import type { DeviceConfig } from '../settings';
import { XdripSource } from './xdrip';
import { NightscoutSource } from './nightscout';
import { ManualSource } from './manual';
import { DemoSource } from './demo';

export interface BgSource {
  fetchLatest(count?: number): Promise<BgReading[]>;  // newest first
  label: string;
}

export function createBgSource(config: DeviceConfig): BgSource {
  const { type, url, apiSecret } = config.bgSource;
  switch (type) {
    case 'xdrip':
      return new XdripSource(url ?? 'http://127.0.0.1:17580', apiSecret);
    case 'nightscout':
      return new NightscoutSource(url ?? '', apiSecret);
    case 'manual':
      return new ManualSource();
    case 'demo':
      return new DemoSource();
  }
}
