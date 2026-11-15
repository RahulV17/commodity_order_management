export type InstrumentCategory =
  | 'PRECIOUS_METAL'
  | 'BASE_METAL'
  | 'ENERGY'
  | 'AGRICULTURE';

export interface Instrument {
  id: string;
  symbol: string;
  name: string;
  category: InstrumentCategory;
  indicativePrice: number;
  active: boolean;
  activityCount: number;
  lastOrderAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface InstrumentRequest {
  id: string;
  symbol: string;
  name: string;
  category: InstrumentCategory;
  indicativePrice: number;
  active?: boolean;
}

export interface UpdateInstrumentRequest {
  symbol: string;
  name: string;
  category: InstrumentCategory;
  indicativePrice: number;
}
