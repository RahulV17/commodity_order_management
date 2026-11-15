import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  CreateOrderRequest,
  Order,
  OrderDocument,
  OrderQuery,
  PageResponse
} from '../models/order.model';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/orders`;

  list(query: OrderQuery): Observable<PageResponse<Order>> {
    let params = new HttpParams()
      .set('page', String(query.page ?? 0))
      .set('size', String(query.size ?? environment.defaultPageSize))
      .set('sort', query.sort ?? 'createdAt,desc');

    if (query.status) {
      params = params.set('status', query.status);
    }
    if (query.instrumentId) {
      params = params.set('instrumentId', query.instrumentId);
    }
    if (query.search?.trim()) {
      params = params.set('search', query.search.trim());
    }

    return this.http.get<PageResponse<Order>>(this.baseUrl, { params });
  }

  getById(id: number): Observable<Order> {
    return this.http.get<Order>(`${this.baseUrl}/${id}`);
  }

  create(request: CreateOrderRequest): Observable<Order> {
    return this.http.post<Order>(this.baseUrl, request);
  }

  submit(id: number): Observable<Order> {
    return this.http.patch<Order>(`${this.baseUrl}/${id}/submit`, {});
  }

  approve(id: number): Observable<Order> {
    return this.http.patch<Order>(`${this.baseUrl}/${id}/approve`, {});
  }

  reject(id: number, reason: string): Observable<Order> {
    return this.http.patch<Order>(`${this.baseUrl}/${id}/reject`, { reason });
  }

  cancel(id: number): Observable<Order> {
    return this.http.patch<Order>(`${this.baseUrl}/${id}/cancel`, {});
  }

  listDocuments(orderId: number): Observable<OrderDocument[]> {
    return this.http.get<OrderDocument[]>(`${this.baseUrl}/${orderId}/documents`);
  }

  uploadDocument(orderId: number, file: File): Observable<OrderDocument> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<OrderDocument>(`${this.baseUrl}/${orderId}/documents`, formData);
  }

  downloadDocument(orderId: number, documentId: number): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${orderId}/documents/${documentId}/download`, {
      responseType: 'blob'
    });
  }
}
