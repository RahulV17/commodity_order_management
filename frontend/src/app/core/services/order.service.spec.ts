import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { Order } from '../models/order.model';
import { OrderService } from './order.service';

const order: Order = {
  id: 42,
  orderNumber: 'ORD-20260101-0001',
  instrumentId: 'GOLD-001',
  instrumentSymbol: 'GOLD',
  instrumentName: 'Gold',
  orderType: 'BUY',
  quantity: 2,
  price: 6500.5,
  status: 'SUBMITTED',
  remarks: null,
  rejectionReason: null,
  createdBy: 'operator',
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:01:00Z'
};

describe('OrderService', () => {
  let service: OrderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(OrderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sends pagination, sorting and filters as query parameters', () => {
    service
      .list({
        search: 'gold',
        status: 'SUBMITTED',
        instrumentId: 'GOLD-001',
        page: 2,
        size: 20,
        sort: 'price,asc'
      })
      .subscribe();

    const request = httpMock.expectOne(
      (candidate) => candidate.url === '/api/orders' && candidate.method === 'GET'
    );
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('price,asc');
    expect(request.request.params.get('status')).toBe('SUBMITTED');
    expect(request.request.params.get('instrumentId')).toBe('GOLD-001');
    expect(request.request.params.get('search')).toBe('gold');

    request.flush({
      content: [order],
      page: 2,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true
    });
  });

  it('creates an order and submits it with separate lifecycle calls', () => {
    service
      .create({
        instrumentId: 'GOLD-001',
        orderType: 'BUY',
        quantity: 2,
        price: 6500.5,
        remarks: null
      })
      .subscribe();

    const createRequest = httpMock.expectOne('/api/orders');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body.instrumentId).toBe('GOLD-001');
    createRequest.flush({ ...order, status: 'NEW' });

    service.submit(42).subscribe();

    const submitRequest = httpMock.expectOne('/api/orders/42/submit');
    expect(submitRequest.request.method).toBe('PATCH');
    submitRequest.flush(order);
  });

  it('sends a rejection reason and supports upload/download calls', () => {
    service.reject(42, 'Price outside the approved band').subscribe();

    const rejectRequest = httpMock.expectOne('/api/orders/42/reject');
    expect(rejectRequest.request.method).toBe('PATCH');
    expect(rejectRequest.request.body).toEqual({ reason: 'Price outside the approved band' });
    rejectRequest.flush({ ...order, status: 'REJECTED' });

    const file = new File(['demo'], 'invoice.pdf', { type: 'application/pdf' });
    service.uploadDocument(42, file).subscribe();

    const uploadRequest = httpMock.expectOne('/api/orders/42/documents');
    expect(uploadRequest.request.method).toBe('POST');
    expect(uploadRequest.request.body instanceof FormData).toBeTrue();
    expect((uploadRequest.request.body as FormData).get('file')).toBe(file);
    uploadRequest.flush({
      id: 7,
      fileName: 'invoice.pdf',
      contentType: 'application/pdf',
      sizeBytes: 4,
      uploadedBy: 'operator',
      uploadedAt: '2026-01-01T10:05:00Z'
    });

    service.downloadDocument(42, 7).subscribe();
    const downloadRequest = httpMock.expectOne('/api/orders/42/documents/7/download');
    expect(downloadRequest.request.responseType).toBe('blob');
    downloadRequest.flush(new Blob(['demo'], { type: 'application/pdf' }));
  });

  it('propagates HTTP errors to the caller', () => {
    let receivedStatus: number | undefined;

    service.getById(999).subscribe({ error: (error) => (receivedStatus = error.status) });

    httpMock
      .expectOne('/api/orders/999')
      .flush({ message: 'Order not found' }, { status: 404, statusText: 'Not Found' });

    expect(receivedStatus).toBe(404);
  });
});
