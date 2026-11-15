import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  Instrument,
  InstrumentRequest,
  UpdateInstrumentRequest
} from '../models/instrument.model';

@Injectable({ providedIn: 'root' })
export class InstrumentService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/instruments`;

  list(search = '', active?: boolean): Observable<Instrument[]> {
    let params = new HttpParams();
    if (search.trim()) {
      params = params.set('search', search.trim());
    }
    if (active !== undefined) {
      params = params.set('active', active);
    }
    return this.http.get<Instrument[]>(this.baseUrl, { params });
  }

  getById(id: string): Observable<Instrument> {
    return this.http.get<Instrument>(`${this.baseUrl}/${encodeURIComponent(id)}`);
  }

  create(request: InstrumentRequest): Observable<Instrument> {
    return this.http.post<Instrument>(this.baseUrl, request);
  }

  update(id: string, request: UpdateInstrumentRequest): Observable<Instrument> {
    return this.http.put<Instrument>(`${this.baseUrl}/${encodeURIComponent(id)}`, request);
  }

  updateStatus(id: string, active: boolean): Observable<Instrument> {
    return this.http.patch<Instrument>(
      `${this.baseUrl}/${encodeURIComponent(id)}/status`,
      { active }
    );
  }
}
