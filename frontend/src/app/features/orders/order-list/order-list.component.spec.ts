import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AuthUser } from '../../../core/models/auth.model';
import { Order, PageResponse } from '../../../core/models/order.model';
import { AuthService } from '../../../core/services/auth.service';
import { InstrumentService } from '../../../core/services/instrument.service';
import { NotificationService } from '../../../core/services/notification.service';
import { OrderService } from '../../../core/services/order.service';
import { OrderListComponent } from './order-list.component';

const operator: AuthUser = { id: 2, username: 'operator', role: 'OPERATIONS' };

const submittedOrder: Order = {
  id: 42,
  orderNumber: 'ORD-20260101-0001',
  instrumentId: 'GOLD-001',
  instrumentSymbol: 'GOLD',
  instrumentName: 'Gold',
  orderType: 'BUY',
  quantity: 2,
  price: 6500,
  status: 'SUBMITTED',
  remarks: null,
  rejectionReason: null,
  createdBy: 'operator',
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:01:00Z'
};

const page: PageResponse<Order> = {
  content: [submittedOrder],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true
};

describe('OrderListComponent', () => {
  let fixture: ComponentFixture<OrderListComponent>;
  let component: OrderListComponent;
  let admin: boolean;
  let orderMock: jasmine.SpyObj<OrderService>;
  let instrumentMock: jasmine.SpyObj<InstrumentService>;

  beforeEach(async () => {
    admin = false;
    const authMock = {
      isAdmin: () => admin,
      currentUser: operator
    };

    orderMock = jasmine.createSpyObj<OrderService>('OrderService', [
      'list',
      'submit',
      'approve',
      'reject',
      'cancel'
    ]);
    orderMock.list.and.returnValue(of(page));
    orderMock.approve.and.returnValue(of({ ...submittedOrder, status: 'APPROVED' }));
    orderMock.reject.and.returnValue(
      of({ ...submittedOrder, status: 'REJECTED', rejectionReason: 'Price outside band' })
    );

    instrumentMock = jasmine.createSpyObj<InstrumentService>('InstrumentService', ['list']);
    instrumentMock.list.and.returnValue(of([]));

    const notificationMock = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
      'info',
      'dismiss'
    ]);

    await TestBed.configureTestingModule({
      imports: [OrderListComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authMock },
        { provide: OrderService, useValue: orderMock },
        { provide: InstrumentService, useValue: instrumentMock },
        { provide: NotificationService, useValue: notificationMock }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(OrderListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('requests the first page with the default sort and renders the result', () => {
    expect(orderMock.list).toHaveBeenCalledWith({
      search: '',
      status: '',
      instrumentId: '',
      sort: 'createdAt,desc',
      page: 0,
      size: 10
    });
    expect(component.orders).toEqual([submittedOrder]);
    expect(component.pageInfo?.totalElements).toBe(1);
    expect(component.firstVisibleItem).toBe(1);
    expect(component.lastVisibleItem).toBe(1);
  });

  it('hides approval actions from operations users but allows cancelling their own order', () => {
    expect(component.canSubmit(submittedOrder)).toBeFalse();
    expect(component.canApproveOrReject(submittedOrder)).toBeFalse();
    expect(component.canCancel(submittedOrder)).toBeTrue();
  });

  it('shows approval actions to administrators', () => {
    admin = true;

    expect(component.canApproveOrReject(submittedOrder)).toBeTrue();
  });

  it('approves an order and reloads the current query', () => {
    admin = true;
    spyOn(window, 'confirm').and.returnValue(true);

    component.runAction(submittedOrder, 'approve');

    expect(orderMock.approve).toHaveBeenCalledWith(42);
    expect(orderMock.list).toHaveBeenCalledTimes(2);
  });

  it('requires a rejection reason before calling the API', () => {
    component.openReject(submittedOrder);
    component.submitReject();

    expect(component.rejectSubmitted).toBeTrue();
    expect(orderMock.reject).not.toHaveBeenCalled();

    component.rejectForm.controls.reason.setValue('Price outside band');
    component.submitReject();

    expect(orderMock.reject).toHaveBeenCalledWith(42, 'Price outside band');
    expect(component.showRejectModal).toBeFalse();
  });
});
