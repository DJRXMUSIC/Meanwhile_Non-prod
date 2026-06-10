import type { BgReading } from '../types';
import type { BgSource } from './index';
import { parseSgvRows } from './xdrip';

export class NightscoutSource implements BgSource {
  label = 'Nightscout';
  constructor(
    private url: string,
    private token?: string,
  ) {}

  async fetchLatest(count = 24): Promise<BgReading[]> {
    if (!this.url) throw new Error('Nightscout URL not configured');
    const tokenParam = this.token ? `&token=${encodeURIComponent(this.token)}` : '';
    const res = await fetch(
      `${this.url.replace(/\/$/, '')}/api/v1/entries/sgv.json?count=${count}${tokenParam}`,
    );
    if (!res.ok) throw new Error(`Nightscout responded ${res.status}`);
    return parseSgvRows(await res.json(), 'nightscout');
  }
}
