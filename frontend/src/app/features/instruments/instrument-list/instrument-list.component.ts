import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BehaviorSubject, catchError, combineLatest, debounceTime, distinctUntilChanged, finalize, of, startWith, switchMap } from 'rxjs';

import {
  Instrument,
  InstrumentCategory,
  InstrumentRequest,
  UpdateInstrumentRequest
} from '../../../core/models/instrument.model';
import { AuthService } from '../../../core/services/auth.service';
import { InstrumentService } from '../../../core/services/instrument.service';
import { NotificationService } from '../../../core/services/notification.service';
import { EmptyStateComponent } from '../../../shared/components/empty-state/empty-state.component';
import { ModalComponent } from '../../../shared/components/modal/modal.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { StatusLabelPipe } from '../../../shared/pipes/status-label.pipe';

type ActiveFilter = 'all' | 'active' | 'inactive';

@Component({
  selector: 'app-instrument-list',
  imports: [
    CommonModule,
    ReactiveFormsModule,
    PageHeaderComponent,
    StatusBadgeComponent,
    EmptyStateComponent,
    ModalComponent,
    StatusLabelPipe
  ],
  templateUrl: './instrument-list.component.html',
  styleUrl: './instrument-list.component.css'
})
export class InstrumentListComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly instrumentService = inject(InstrumentService);
  private readonly authService = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly searchControl = new FormControl('', { nonNullable: true });
  readonly filterControl = new FormControl<ActiveFilter>('all', { nonNullable: true });
  private readonly reload$ = new BehaviorSubject<void>(undefined);

  instruments: Instrument[] = [];
  loading = true;
  errorMessage = '';

  showForm = false;
  editing: Instrument | null = null;
  submitted = false;
  saving = false;
  formError = '';

  readonly categories: InstrumentCategory[] = [
    'PRECIOUS_METAL',
    'BASE_METAL',
    'ENERGY',
    'AGRICULTURE'
  ];

  readonly instrumentForm = this.formBuilder.nonNullable.group({
    id: ['', [Validators.required, Validators.pattern(/^[A-Z0-9][A-Z0-9-]{2,31}$/)]],
    symbol: ['', [Validators.required, Validators.pattern(/^[A-Z0-9]{2,16}$/)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    category: ['PRECIOUS_METAL' as InstrumentCategory, Validators.required],
    indicativePrice: [0, [Validators.required, Validators.min(0.0001)]]
  });

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  ngOnInit(): void {
    combineLatest([
      this.searchControl.valueChanges.pipe(
        startWith(''),
        debounceTime(300),
        distinctUntilChanged()
      ),
      this.filterControl.valueChanges.pipe(startWith('all')),
      this.reload$
    ])
      .pipe(
        switchMap(([search, filter]) => {
          this.loading = true;
          this.errorMessage = '';
          const active = filter === 'all' ? undefined : filter === 'active';
          return this.instrumentService.list(search, active).pipe(
            catchError(() => {
              this.errorMessage =
                'Instruments could not be loaded. Check the gateway and instrument-service.';
              return of([] as Instrument[]);
            }),
            finalize(() => (this.loading = false))
          );
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((instruments) => (this.instruments = instruments));
  }

  openCreate(): void {
    this.editing = null;
    this.submitted = false;
    this.formError = '';
    this.instrumentForm.reset({
      id: '',
      symbol: '',
      name: '',
      category: 'PRECIOUS_METAL',
      indicativePrice: 0
    });
    this.instrumentForm.controls.id.enable();
    this.showForm = true;
  }

  openEdit(instrument: Instrument): void {
    this.editing = instrument;
    this.submitted = false;
    this.formError = '';
    this.instrumentForm.reset({
      id: instrument.id,
      symbol: instrument.symbol,
      name: instrument.name,
      category: instrument.category,
      indicativePrice: instrument.indicativePrice
    });
    this.instrumentForm.controls.id.disable();
    this.showForm = true;
  }

  closeForm(): void {
    if (!this.saving) {
      this.showForm = false;
    }
  }

  save(): void {
    this.submitted = true;
    this.formError = '';
    if (this.instrumentForm.invalid) {
      this.instrumentForm.markAllAsTouched();
      return;
    }

    const value = this.instrumentForm.getRawValue();
    const request: InstrumentRequest = {
      id: value.id,
      symbol: value.symbol.toUpperCase(),
      name: value.name.trim(),
      category: value.category,
      indicativePrice: Number(value.indicativePrice),
      active: true
    };
    const updateRequest: UpdateInstrumentRequest = {
      symbol: request.symbol,
      name: request.name,
      category: request.category,
      indicativePrice: request.indicativePrice
    };

    this.saving = true;
    const operation$ = this.editing
      ? this.instrumentService.update(this.editing.id, updateRequest)
      : this.instrumentService.create(request);

    operation$.pipe(finalize(() => (this.saving = false))).subscribe({
      next: (saved) => {
        this.notifications.success(
          this.editing
            ? `${saved.symbol} updated successfully.`
            : `${saved.symbol} created successfully.`
        );
        this.showForm = false;
        this.reload$.next();
      },
      error: (error) => {
        this.formError = error?.error?.message ?? 'The instrument could not be saved.';
      }
    });
  }

  toggleStatus(instrument: Instrument): void {
    const nextState = !instrument.active;
    const action = nextState ? 'activate' : 'deactivate';
    if (!window.confirm(`Are you sure you want to ${action} ${instrument.symbol}?`)) {
      return;
    }

    this.instrumentService.updateStatus(instrument.id, nextState).subscribe({
      next: (saved) => {
        this.notifications.success(`${saved.symbol} is now ${saved.active ? 'active' : 'inactive'}.`);
        this.reload$.next();
      },
      error: (error) =>
        this.notifications.error(error?.error?.message ?? 'The status could not be changed.')
    });
  }

  refresh(): void {
    this.reload$.next();
  }

  fieldError(name: 'id' | 'symbol' | 'name' | 'indicativePrice'): boolean {
    const control = this.instrumentForm.controls[name];
    return this.submitted && control.invalid;
  }
}
