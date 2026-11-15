import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { Instrument } from '../../../core/models/instrument.model';
import { Order } from '../../../core/models/order.model';
import { InstrumentService } from '../../../core/services/instrument.service';
import { NotificationService } from '../../../core/services/notification.service';
import { OrderService } from '../../../core/services/order.service';
import { OrderFormComponent } from './order-form.component';

const gold: Instrument = {
  id: 'GOLD-001',
  symbol: 'GOLD',
  name: 'Gold',
  category: 'PRECIOUS_METAL',
  indicativePrice: 6500.5,
  active: true,
  activityCount: 2,
  lastOrderAt: null,
  createdAt: null,
  updatedAt: null
};

const createdOrder: Order = {
  id: 42,
  orderNumber: 'ORD-20260101-0001',
  instrumentId: 'GOLD-001',
  instrumentSymbol: 'GOLD',
  instrumentName: 'Gold',
  orderType: 'BUY',
  quantity: 2,
  price: 6500,
  status: 'NEW',
  remarks: 'Client request',
  rejectionReason: null,
  createdBy: 'operator',
  createdAt: '2026-01-01T10:00:00Z',
  updatedAt: '2026-01-01T10:00:00Z'
};

describe('OrderFormComponent', () => {
  let fixture: ComponentFixture<OrderFormComponent>;
  let component: OrderFormComponent;
  let router: Router;
  let instrumentMock: jasmine.SpyObj<InstrumentService>;
  let orderMock: jasmine.SpyObj<OrderService>;
  let notificationMock: jasmine.SpyObj<NotificationService>;

  beforeEach(async () => {
    instrumentMock = jasmine.createSpyObj<InstrumentService>('InstrumentService', ['list']);
    instrumentMock.list.and.returnValue(of([gold]));

    orderMock = jasmine.createSpyObj<OrderService>('OrderService', ['create', 'submit']);
    orderMock.create.and.returnValue(of(createdOrder));
    orderMock.submit.and.returnValue(of({ ...createdOrder, status: 'SUBMITTED' }));

    notificationMock = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
      'info',
      'dismiss'
    ]);

    await TestBed.configureTestingModule({
      imports: [OrderFormComponent],
      providers: [
        provideRouter([]),
        { provide: InstrumentService, useValue: instrumentMock },
        { provide: OrderService, useValue: orderMock },
        { provide: NotificationService, useValue: notificationMock }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(OrderFormComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
  });

  it('loads active instruments and prefills the indicative price', () => {
    expect(instrumentMock.list).toHaveBeenCalledWith('', true);
    expect(component.orderForm.controls.instrumentId.value).toBe('GOLD-001');
    expect(component.orderForm.controls.price.value).toBe(6500.5);
    expect(component.selectedInstrument).toEqual(gold);
  });

  it('rejects non-positive and over-precise quantities', () => {
    const quantity = component.orderForm.controls.quantity;

    quantity.setValue(0);
    expect(quantity.hasError('min')).toBeTrue();

    quantity.setValue(1.12345);
    expect(quantity.hasError('decimalPlaces')).toBeTrue();

    quantity.setValue(1.1234);
    expect(quantity.hasError('decimalPlaces')).toBeFalse();
    expect(quantity.valid).toBeTrue();
  });

  it('creates the order, submits it, and navigates to the detail page', () => {
    component.orderForm.patchValue({ quantity: 2, price: 6500, remarks: '  Client request  ' });

    component.save();

    expect(orderMock.create).toHaveBeenCalledWith({
      instrumentId: 'GOLD-001',
      orderType: 'BUY',
      quantity: 2,
      price: 6500,
      remarks: 'Client request'
    });
    expect(orderMock.submit).toHaveBeenCalledWith(42);
    expect(router.navigate).toHaveBeenCalledWith(['/orders', 42]);
    expect(component.formError).toBe('');
  });

  it('keeps the created order recoverable when the automatic submit fails', () => {
    orderMock.submit.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Invalid state transition' } }))
    );
    component.orderForm.patchValue({ quantity: 2, price: 6500 });

    component.save();

    expect(component.createdOrder?.id).toBe(42);
    expect(component.formError).toContain('created as NEW');
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
