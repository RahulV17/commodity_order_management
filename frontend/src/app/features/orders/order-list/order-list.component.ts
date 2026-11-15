import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  BehaviorSubject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  finalize,
  of,
  switchMap
} from 'rxjs';

import { Instrument } from '../../../core/models/instrument.model';
import {
  Order,
  OrderStatus,
  OrderType,
  PageResponse
} from '../../../core/models/order.model';
import { AuthService } from '../../../core/services/auth.service';
import { InstrumentService } from '../../../core/services/instrument.service';
import { NotificationService } from '../../../core/services/notification.service';
import { OrderService } from '../../../core/services/order.service';
import { EmptyStateComponent } from '../../../shared/components/empty-state/empty-state.component';
import { ModalComponent } from '../../../shared/components/modal/modal.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { StatusLabelPipe } from '../../../shared/pipes/status-label.pipe';

type SortOption = {
  value: string;
  label: string;
};

type OrderAction = 'submit' | 'approve' | 'cancel';

interface OrderQueryState {
  search: string;
  status: OrderStatus | '';
  instrumentId: string;
  sort: string;
  page: number;
  size: number;
}

@Component({
  selector: 'app-order-list',
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink,
    PageHeaderComponent,
    StatusBadgeComponent,
    EmptyStateComponent,
    ModalComponent,
    StatusLabelPipe
  ],
  templateUrl: './order-list.component.html',
  styleUrl: './order-list.component.css'
})
export class OrderListComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly orderService = inject(OrderService);
  private readonly instrumentService = inject(InstrumentService);
  private readonly authService = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('', { nonNullable: true });
  readonly statusControl = new FormControl<OrderStatus | ''>('', { nonNullable: true });
  readonly instrumentControl = new FormControl('', { nonNullable: true });
  readonly sortControl = new FormControl('createdAt,desc', { nonNullable: true });
  readonly sizeControl = new FormControl(10, { nonNullable: true });

  /**
   * One subject holds the whole server query. Every control patches it and
   * switchMap cancels the in-flight request when a newer query arrives.
   */
  private readonly query$ = new BehaviorSubject<OrderQueryState>({
    search: '',
    status: '',
    instrumentId: '',
    sort: 'createdAt,desc',
    page: 0,
    size: 10
  });

  readonly statuses: OrderStatus[] = [
    'NEW',
    'SUBMITTED',
    'APPROVED',
    'REJECTED',
    'CANCELLED'
  ];

  readonly sortOptions: SortOption[] = [
    { value: 'createdAt,desc', label: 'Newest first' },
    { value: 'createdAt,asc', label: 'Oldest first' },
    { value: 'price,desc', label: 'Price: high to low' },
    { value: 'price,asc', label: 'Price: low to high' },
    { value: 'quantity,desc', label: 'Quantity: high to low' },
    { value: 'orderNumber,asc', label: 'Order number' }
  ];

  readonly pageSizes = [5, 10, 20, 50];

  orders: Order[] = [];
  instruments: Instrument[] = [];
  pageInfo: PageResponse<Order> | null = null;
  loading = true;
  errorMessage = '';

  actionInProgress: number | null = null;

  showRejectModal = false;
  rejecting: Order | null = null;
  rejectSubmitted = false;
  rejectSaving = false;
  rejectError = '';

  readonly rejectForm = this.formBuilder.nonNullable.group({
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(500)]]
  });

  ngOnInit(): void {
    this.watchFilters();
    this.loadQuery();
    this.loadInstruments();
  }

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  get currentUsername(): string {
    return this.authService.currentUser?.username ?? '';
  }

  get pageNumbers(): number[] {
    const totalPages = this.pageInfo?.totalPages ?? 0;
    if (totalPages <= 0) {
      return [];
    }
    const current = this.pageInfo?.page ?? 0;
    const windowSize = 5;
    let start = Math.max(0, current - 2);
    const end = Math.min(totalPages, start + windowSize);
    start = Math.max(0, end - windowSize);
    return Array.from({ length: end - start }, (_value, index) => start + index);
  }

  get firstVisibleItem(): number {
    if (!this.pageInfo || this.pageInfo.totalElements === 0) {
      return 0;
    }
    return this.pageInfo.page * this.pageInfo.size + 1;
  }

  get lastVisibleItem(): number {
    if (!this.pageInfo) {
      return 0;
    }
    return this.pageInfo.page * this.pageInfo.size + this.pageInfo.content.length;
  }

  isOwner(order: Order): boolean {
    return order.createdBy === this.currentUsername;
  }

  canSubmit(order: Order): boolean {
    return order.status === 'NEW' && (this.isAdmin || this.isOwner(order));
  }

  canCancel(order: Order): boolean {
    return (
      (order.status === 'NEW' || order.status === 'SUBMITTED') &&
      (this.isAdmin || this.isOwner(order))
    );
  }

  canApproveOrReject(order: Order): boolean {
    return this.isAdmin && order.status === 'SUBMITTED';
  }

  typeClass(orderType: OrderType): string {
    return orderType === 'BUY' ? 'badge--buy' : 'badge--sell';
  }

  orderValue(order: Order): number {
    return Number(order.quantity) * Number(order.price);
  }

  trackByOrderId(_index: number, order: Order): number {
    return order.id;
  }

  refresh(): void {
    this.query$.next({ ...this.query$.value });
  }

  resetFilters(): void {
    this.searchControl.setValue('');
    this.statusControl.setValue('');
    this.instrumentControl.setValue('');
    this.sortControl.setValue('createdAt,desc');
    this.sizeControl.setValue(10);
    this.patchQuery({ search: '', status: '', instrumentId: '', sort: 'createdAt,desc', page: 0, size: 10 });
  }

  goToPage(page: number): void {
    const totalPages = this.pageInfo?.totalPages ?? 0;
    if (page < 0 || (totalPages > 0 && page >= totalPages)) {
      return;
    }
    this.patchQuery({ page });
  }

  previousPage(): void {
    this.goToPage((this.pageInfo?.page ?? 0) - 1);
  }

  nextPage(): void {
    this.goToPage((this.pageInfo?.page ?? 0) + 1);
  }

  runAction(order: Order, action: OrderAction): void {
    const questions: Record<OrderAction, string> = {
      submit: `Submit ${order.orderNumber} for approval?`,
      approve: `Approve ${order.orderNumber}? This decision cannot be undone.`,
      cancel: `Cancel ${order.orderNumber}? This cannot be undone.`
    };
    if (!window.confirm(questions[action])) {
      return;
    }

    const operation$ =
      action === 'submit'
        ? this.orderService.submit(order.id)
        : action === 'approve'
          ? this.orderService.approve(order.id)
          : this.orderService.cancel(order.id);

    this.actionInProgress = order.id;
    operation$
      .pipe(finalize(() => (this.actionInProgress = null)))
      .subscribe({
        next: (updated) => {
          this.notifications.success(
            `${updated.orderNumber} is now ${updated.status.toLowerCase()}.`
          );
          this.refresh();
        },
        error: (error) => this.reportActionError(error, 'The order could not be updated.')
      });
  }

  openReject(order: Order): void {
    this.rejecting = order;
    this.rejectSubmitted = false;
    this.rejectSaving = false;
    this.rejectError = '';
    this.rejectForm.reset({ reason: '' });
    this.showRejectModal = true;
  }

  closeReject(): void {
    if (!this.rejectSaving) {
      this.showRejectModal = false;
      this.rejecting = null;
    }
  }

  submitReject(): void {
    this.rejectSubmitted = true;
    this.rejectError = '';

    if (!this.rejecting || this.rejectForm.invalid) {
      this.rejectForm.markAllAsTouched();
      return;
    }

    const reason = this.rejectForm.controls.reason.value.trim();
    if (reason.length < 5) {
      return;
    }

    this.rejectSaving = true;
    this.orderService
      .reject(this.rejecting.id, reason)
      .pipe(finalize(() => (this.rejectSaving = false)))
      .subscribe({
        next: (updated) => {
          this.notifications.success(`${updated.orderNumber} was rejected.`);
          this.showRejectModal = false;
          this.rejecting = null;
          this.refresh();
        },
        error: (error) =>
          this.reportActionError(error, 'The order could not be rejected.')
      });
  }

  rejectReasonError(): boolean {
    return this.rejectSubmitted && this.rejectForm.controls.reason.invalid;
  }

  private watchFilters(): void {
    this.searchControl.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((search) => this.patchQuery({ search, page: 0 }));

    this.statusControl.valueChanges
      .pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((status) => this.patchQuery({ status, page: 0 }));

    this.instrumentControl.valueChanges
      .pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((instrumentId) => this.patchQuery({ instrumentId, page: 0 }));

    this.sortControl.valueChanges
      .pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((sort) => this.patchQuery({ sort, page: 0 }));

    this.sizeControl.valueChanges
      .pipe(distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe((size) => this.patchQuery({ size, page: 0 }));
  }

  private loadQuery(): void {
    this.query$
      .pipe(
        switchMap((query) => {
          this.loading = true;
          this.errorMessage = '';
          return this.orderService
            .list({
              search: query.search,
              status: query.status,
              instrumentId: query.instrumentId,
              sort: query.sort,
              page: query.page,
              size: query.size
            })
            .pipe(
              catchError(() => {
                this.errorMessage =
                  'Orders could not be loaded. Check the gateway and order-service.';
                return of(null);
              }),
              finalize(() => (this.loading = false))
            );
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((page) => {
        if (page) {
          this.orders = page.content;
          this.pageInfo = page;
        } else {
          this.orders = [];
          this.pageInfo = null;
        }
      });
  }

  private loadInstruments(): void {
    this.instrumentService
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (instruments) => (this.instruments = instruments),
        // The order list still works without the filter options; the global
        // interceptor already reported connectivity problems.
        error: () => (this.instruments = [])
      });
  }

  private patchQuery(partial: Partial<OrderQueryState>): void {
    const next = { ...this.query$.value, ...partial };
    this.query$.next(next);
  }

  private reportActionError(error: unknown, fallback: string): void {
    const status = (error as { status?: number } | null)?.status;
    // 401/403/0/5xx are already surfaced by the global error interceptor.
    if (status === 400 || status === 404 || status === 409 || status === 422) {
      const message =
        (error as { error?: { message?: string } } | null)?.error?.message ?? fallback;
      this.notifications.error(message);
    }
  }
}
