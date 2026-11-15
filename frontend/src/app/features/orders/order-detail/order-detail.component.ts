import {
  Component,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  ViewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  catchError,
  EMPTY,
  finalize,
  map,
  of,
  switchMap
} from 'rxjs';

import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { OrderService } from '../../../core/services/order.service';
import {
  Order,
  OrderDocument,
  OrderStatus
} from '../../../core/models/order.model';
import { ModalComponent } from '../../../shared/components/modal/modal.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { StatusLabelPipe } from '../../../shared/pipes/status-label.pipe';

type OrderAction = 'submit' | 'approve' | 'cancel';

type StageState = 'done' | 'current' | 'todo';

interface TimelineStage {
  status: OrderStatus;
  title: string;
  description: string;
  time: string | null;
  state: StageState;
}

const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;
const ALLOWED_DOCUMENT_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg', 'docx', 'xlsx'];
const MIME_BY_EXTENSION: Record<string, string> = {
  pdf: 'application/pdf',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
};

@Component({
  selector: 'app-order-detail',
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink,
    PageHeaderComponent,
    StatusBadgeComponent,
    ModalComponent,
    StatusLabelPipe
  ],
  templateUrl: './order-detail.component.html',
  styleUrl: './order-detail.component.css'
})
export class OrderDetailComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly orderService = inject(OrderService);
  private readonly authService = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  @ViewChild('fileInput') private fileInput?: ElementRef<HTMLInputElement>;

  order: Order | null = null;
  documents: OrderDocument[] = [];
  loading = true;
  errorMessage = '';
  documentsError = false;
  documentsLoading = false;

  actionInProgress = false;

  selectedFile: File | null = null;
  uploading = false;
  documentError = '';
  downloadingId: number | null = null;

  showRejectModal = false;
  rejectSubmitted = false;
  rejectSaving = false;
  rejectError = '';

  readonly rejectForm = this.formBuilder.nonNullable.group({
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(500)]]
  });

  ngOnInit(): void {
    this.route.paramMap
      .pipe(
        map((params) => Number(params.get('id'))),
        switchMap((id) => {
          if (!Number.isInteger(id) || id <= 0) {
            this.loading = false;
            this.errorMessage = 'The order id in the address is not valid.';
            return EMPTY;
          }
          return this.loadOrder(id);
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((result) => {
        if (result) {
          this.order = result.order;
          this.documents = result.documents;
        }
      });
  }

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  get isOwner(): boolean {
    return this.order?.createdBy === this.authService.currentUser?.username;
  }

  get canSubmit(): boolean {
    return this.order?.status === 'NEW' && (this.isAdmin || this.isOwner);
  }

  get canCancel(): boolean {
    return (
      (this.order?.status === 'NEW' || this.order?.status === 'SUBMITTED') &&
      (this.isAdmin || this.isOwner)
    );
  }

  get canApproveOrReject(): boolean {
    return this.isAdmin && this.order?.status === 'SUBMITTED';
  }

  get totalValue(): number {
    if (!this.order) {
      return 0;
    }
    return Number(this.order.quantity) * Number(this.order.price);
  }

  /**
   * The schema stores the current status and two timestamps, not a per-step
   * audit trail. The timeline therefore shows the path implied by the current
   * state and says so, instead of inventing transition timestamps.
   */
  get timeline(): TimelineStage[] {
    if (!this.order) {
      return [];
    }

    const status = this.order.status;
    const createdAt = this.order.createdAt;
    const updatedAt = this.order.updatedAt;

    const base: TimelineStage[] = [
      {
        status: 'NEW',
        title: 'Created',
        description: `Created by ${this.order.createdBy}. Feign validated the instrument, PostgreSQL stored the order and a Kafka event was published.`,
        time: createdAt,
        state: 'done'
      }
    ];

    if (status === 'NEW') {
      base[0].state = 'current';
      return base;
    }

    if (status === 'CANCELLED') {
      base[0].state = 'done';
      base.push({
        status: 'CANCELLED',
        title: 'Cancelled',
        description:
          'Cancelled before a decision. The API allows cancellation from NEW or SUBMITTED; this build stores the current state, not which one it left.',
        time: updatedAt,
        state: 'current'
      });
      return base;
    }

    base.push({
      status: 'SUBMITTED',
      title: 'Submitted for approval',
      description:
        'Moved to SUBMITTED by its owner or an administrator. An administrator can now approve or reject it.',
      time: status === 'SUBMITTED' ? updatedAt : null,
      state: status === 'SUBMITTED' ? 'current' : 'done'
    });

    if (status === 'SUBMITTED') {
      return base;
    }

    base.push({
      status,
      title: status === 'APPROVED' ? 'Approved' : 'Rejected',
      description:
        status === 'APPROVED'
          ? 'Approved by an administrator. This is a terminal state.'
          : 'Rejected by an administrator. This is a terminal state; the reason is shown on the order.',
      time: updatedAt,
      state: 'current'
    });
    return base;
  }

  runAction(action: OrderAction): void {
    if (!this.order) {
      return;
    }

    const questions: Record<OrderAction, string> = {
      submit: `Submit ${this.order.orderNumber} for approval?`,
      approve: `Approve ${this.order.orderNumber}? This decision cannot be undone.`,
      cancel: `Cancel ${this.order.orderNumber}? This cannot be undone.`
    };
    if (!window.confirm(questions[action])) {
      return;
    }

    const operation$ =
      action === 'submit'
        ? this.orderService.submit(this.order.id)
        : action === 'approve'
          ? this.orderService.approve(this.order.id)
          : this.orderService.cancel(this.order.id);

    this.actionInProgress = true;
    operation$
      .pipe(finalize(() => (this.actionInProgress = false)))
      .subscribe({
        next: (updated) => {
          this.order = updated;
          this.notifications.success(
            `${updated.orderNumber} is now ${updated.status.toLowerCase()}.`
          );
        },
        error: (error) => this.reportActionError(error, 'The order could not be updated.')
      });
  }

  openReject(): void {
    this.rejectSubmitted = false;
    this.rejectSaving = false;
    this.rejectError = '';
    this.rejectForm.reset({ reason: '' });
    this.showRejectModal = true;
  }

  closeReject(): void {
    if (!this.rejectSaving) {
      this.showRejectModal = false;
    }
  }

  submitReject(): void {
    this.rejectSubmitted = true;
    this.rejectError = '';
    if (!this.order || this.rejectForm.invalid) {
      this.rejectForm.markAllAsTouched();
      return;
    }

    const reason = this.rejectForm.controls.reason.value.trim();
    if (reason.length < 5) {
      return;
    }

    this.rejectSaving = true;
    this.orderService
      .reject(this.order.id, reason)
      .pipe(finalize(() => (this.rejectSaving = false)))
      .subscribe({
        next: (updated) => {
          this.order = updated;
          this.showRejectModal = false;
          this.notifications.success(`${updated.orderNumber} was rejected.`);
        },
        error: (error) =>
          this.reportActionError(error, 'The order could not be rejected.')
      });
  }

  rejectReasonError(): boolean {
    return this.rejectSubmitted && this.rejectForm.controls.reason.invalid;
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.documentError = '';
    this.selectedFile = null;

    if (!file) {
      return;
    }

    const extension = this.extensionOf(file.name);
    if (!ALLOWED_DOCUMENT_EXTENSIONS.includes(extension)) {
      this.documentError = 'Unsupported file type. Allowed: PDF, PNG, JPEG, DOCX, XLSX.';
      input.value = '';
      return;
    }
    if (file.size === 0) {
      this.documentError = 'The selected file is empty.';
      input.value = '';
      return;
    }
    if (file.size > MAX_DOCUMENT_BYTES) {
      this.documentError = 'The file exceeds the 5 MB limit.';
      input.value = '';
      return;
    }

    this.selectedFile = file;
  }

  uploadDocument(): void {
    if (!this.order || !this.selectedFile) {
      return;
    }

    const file = this.normalizeContentType(this.selectedFile);
    this.uploading = true;
    this.documentError = '';
    this.orderService
      .uploadDocument(this.order.id, file)
      .pipe(finalize(() => (this.uploading = false)))
      .subscribe({
        next: (document) => {
          this.documents = [document, ...this.documents];
          this.selectedFile = null;
          this.documentsError = false;
          if (this.fileInput) {
            this.fileInput.nativeElement.value = '';
          }
          this.notifications.success(`${document.fileName} uploaded.`);
        },
        error: (error) => {
          this.documentError = this.errorMessageOf(
            error,
            'The document could not be uploaded.'
          );
        }
      });
  }

  downloadDocument(document: OrderDocument): void {
    if (!this.order) {
      return;
    }

    this.downloadingId = document.id;
    this.orderService
      .downloadDocument(this.order.id, document.id)
      .pipe(finalize(() => (this.downloadingId = null)))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const anchor = window.document.createElement('a');
          anchor.href = url;
          anchor.download = document.fileName;
          anchor.click();
          URL.revokeObjectURL(url);
        },
        error: () => this.notifications.error('The document could not be downloaded.')
      });
  }

  reloadDocuments(): void {
    if (!this.order) {
      return;
    }
    this.documentsLoading = true;
    this.documentsError = false;
    this.orderService
      .listDocuments(this.order.id)
      .pipe(finalize(() => (this.documentsLoading = false)))
      .subscribe({
        next: (documents) => (this.documents = documents),
        error: () => (this.documentsError = true)
      });
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  private loadOrder(id: number) {
    this.loading = true;
    this.errorMessage = '';
    this.documentsError = false;

    return this.orderService.getById(id).pipe(
      switchMap((order) =>
        this.orderService.listDocuments(order.id).pipe(
          map((documents) => ({ order, documents })),
          catchError(() => {
            this.documentsError = true;
            return of({ order, documents: [] as OrderDocument[] });
          })
        )
      ),
      catchError((error) => {
        this.errorMessage = this.errorMessageOf(
          error,
          'The order could not be loaded. It may not exist, or your role may not own it.'
        );
        return of(null);
      }),
      finalize(() => (this.loading = false))
    );
  }

  private extensionOf(fileName: string): string {
    return fileName.split('.').pop()?.toLowerCase() ?? '';
  }

  /**
   * Browsers occasionally report an empty MIME type for DOCX/XLSX files. Fill
   * it from the extension so the server's allowlist can evaluate the upload.
   */
  private normalizeContentType(file: File): File {
    if (file.type) {
      return file;
    }
    const mime = MIME_BY_EXTENSION[this.extensionOf(file.name)] ?? 'application/octet-stream';
    return new File([file], file.name, { type: mime });
  }

  private reportActionError(error: unknown, fallback: string): void {
    const status = (error as { status?: number } | null)?.status;
    // 401/403/0/5xx are already surfaced by the global error interceptor.
    if (status === 400 || status === 404 || status === 409 || status === 422) {
      this.notifications.error(this.errorMessageOf(error, fallback));
    }
  }

  private errorMessageOf(error: unknown, fallback: string): string {
    return (error as { error?: { message?: string } } | null)?.error?.message ?? fallback;
  }
}
