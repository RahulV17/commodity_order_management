import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, EMPTY, finalize, map, switchMap } from 'rxjs';

import { Instrument } from '../../../core/models/instrument.model';
import { CreateOrderRequest, Order, OrderType } from '../../../core/models/order.model';
import { InstrumentService } from '../../../core/services/instrument.service';
import { NotificationService } from '../../../core/services/notification.service';
import { OrderService } from '../../../core/services/order.service';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { StatusLabelPipe } from '../../../shared/pipes/status-label.pipe';

/**
 * Mirrors the backend @Digits(integer = 15, fraction = 4) rule so the browser
 * catches bad input before the request is sent. `required` and `min` handle
 * empty and non-positive values.
 */
function decimalPlacesValidator(control: AbstractControl): ValidationErrors | null {
  const value = control.value;
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const text = String(value);
  if (!/^\d{1,15}(\.\d{1,4})?$/.test(text)) {
    return { decimalPlaces: true };
  }
  return null;
}

@Component({
  selector: 'app-order-form',
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink,
    PageHeaderComponent,
    StatusBadgeComponent,
    StatusLabelPipe
  ],
  templateUrl: './order-form.component.html',
  styleUrl: './order-form.component.css'
})
export class OrderFormComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly instrumentService = inject(InstrumentService);
  private readonly orderService = inject(OrderService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly orderForm = this.formBuilder.nonNullable.group({
    instrumentId: ['', Validators.required],
    orderType: ['BUY' as OrderType, Validators.required],
    quantity: [1, [Validators.required, Validators.min(0.0001), decimalPlacesValidator]],
    price: [0, [Validators.required, Validators.min(0.0001), decimalPlacesValidator]],
    remarks: ['', [Validators.maxLength(500)]]
  });

  instruments: Instrument[] = [];
  loadingInstruments = true;
  instrumentError = '';

  submitted = false;
  saving = false;
  formError = '';
  createdOrder: Order | null = null;

  ngOnInit(): void {
    this.watchInstrumentSelection();
    this.loadInstruments();
  }

  get selectedInstrument(): Instrument | null {
    const id = this.orderForm.controls.instrumentId.value;
    return this.instruments.find((instrument) => instrument.id === id) ?? null;
  }

  get estimatedValue(): number {
    const value = this.orderForm.getRawValue();
    return Number(value.quantity || 0) * Number(value.price || 0);
  }

  get remarksLength(): number {
    return this.orderForm.controls.remarks.value.length;
  }

  get canCreate(): boolean {
    return !this.loadingInstruments && this.instruments.length > 0;
  }

  setOrderType(orderType: OrderType): void {
    this.orderForm.controls.orderType.setValue(orderType);
    this.orderForm.controls.orderType.markAsTouched();
  }

  fieldError(name: 'instrumentId' | 'quantity' | 'price' | 'remarks'): boolean {
    const control = this.orderForm.controls[name];
    return this.submitted && control.invalid;
  }

  hasDecimalError(name: 'quantity' | 'price'): boolean {
    return Boolean(this.orderForm.controls[name].hasError('decimalPlaces'));
  }

  save(): void {
    this.submitted = true;
    this.formError = '';
    this.createdOrder = null;

    if (this.orderForm.invalid) {
      this.orderForm.markAllAsTouched();
      return;
    }

    const value = this.orderForm.getRawValue();
    const request: CreateOrderRequest = {
      instrumentId: value.instrumentId,
      orderType: value.orderType,
      quantity: Number(value.quantity),
      price: Number(value.price),
      remarks: value.remarks.trim() ? value.remarks.trim() : null
    };

    this.saving = true;
    this.orderService
      .create(request)
      .pipe(
        switchMap((created) => {
          // The order is now persisted as NEW. Remember it so a failed submit
          // can be recovered without creating a duplicate order.
          this.createdOrder = created;
          return this.orderService.submit(created.id).pipe(
            map((submitted) => ({ created, submitted }))
          );
        }),
        catchError((error) => {
          // The error is presented in the form. Complete the stream instead of
          // rethrowing: an unhandled RxJS error would surface as a global error
          // (and in tests as an afterAll failure) after the component handled it.
          this.handleSaveError(error);
          return EMPTY;
        }),
        finalize(() => (this.saving = false))
      )
      .subscribe({
        next: ({ submitted }) => {
          this.notifications.success(
            `${submitted.orderNumber} was created and submitted for approval.`
          );
          void this.router.navigate(['/orders', submitted.id]);
        }
      });
  }

  private watchInstrumentSelection(): void {
    this.orderForm.controls.instrumentId.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((instrumentId) => {
        const instrument = this.instruments.find((item) => item.id === instrumentId);
        if (instrument) {
          // Prefill the price with the instrument's indicative price. The user
          // may still overwrite it; the backend validates the value, not this hint.
          this.orderForm.controls.price.setValue(instrument.indicativePrice);
          this.orderForm.controls.price.markAsTouched();
        }
      });
  }

  private loadInstruments(): void {
    this.instrumentService
      .list('', true)
      .pipe(
        finalize(() => (this.loadingInstruments = false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (instruments) => {
          this.instruments = instruments;
          if (instruments.length === 0) {
            return;
          }

          const requested = this.route.snapshot.queryParamMap.get('instrumentId');
          const requestedInstrument = requested
            ? instruments.find((instrument) => instrument.id === requested)
            : undefined;

          this.orderForm.controls.instrumentId.setValue(
            requestedInstrument?.id ?? instruments[0].id
          );
        },
        error: () => {
          this.instrumentError =
            'Active instruments could not be loaded. The order service validates instruments through Feign, so an order cannot be created until the instrument-service is reachable.';
        }
      });
  }

  private handleSaveError(error: unknown): void {
    const status = (error as { status?: number } | null)?.status;
    const message = (error as { error?: { message?: string } } | null)?.error?.message;

    if (this.createdOrder) {
      this.formError = `Order ${this.createdOrder.orderNumber} was created as NEW, but the automatic submit failed. Open the order from the link below and submit it again.`;
      return;
    }

    if (status === 0) {
      this.formError = 'Cannot reach the API gateway. Check that the backend is running.';
      return;
    }

    this.formError = message ?? 'The order could not be created. Please review the form and retry.';
  }
}
