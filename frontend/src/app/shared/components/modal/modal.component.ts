import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-modal',
  imports: [CommonModule],
  template: `
    <div class="modal-backdrop" (click)="onBackdrop()">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="title"
        (click)="$event.stopPropagation()"
      >
        <header class="modal__header">
          <div>
            <h2>{{ title }}</h2>
            <p *ngIf="subtitle">{{ subtitle }}</p>
          </div>
          <button type="button" class="icon-button" aria-label="Close" (click)="closed.emit()">
            ×
          </button>
        </header>
        <div class="modal__body">
          <ng-content />
        </div>
      </section>
    </div>
  `,
  styles: [
    `
      .modal-backdrop {
        position: fixed;
        inset: 0;
        z-index: 80;
        background: rgba(8, 20, 38, 0.58);
        backdrop-filter: blur(2px);
        display: grid;
        place-items: center;
        padding: 1rem;
      }
      .modal {
        width: min(38rem, 100%);
        max-height: 90vh;
        overflow: auto;
        background: #fff;
        border-radius: 16px;
        box-shadow: 0 28px 80px rgba(5, 16, 32, 0.35);
        animation: rise 160ms ease-out;
      }
      .modal__header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 1rem;
        padding: 1.4rem 1.5rem 0;
      }
      .modal__header h2 {
        margin: 0;
        color: var(--ink);
        font-size: 1.2rem;
      }
      .modal__header p {
        margin: 0.4rem 0 0;
        color: var(--slate);
        font-size: 0.9rem;
        line-height: 1.5;
      }
      .modal__body {
        padding: 1.3rem 1.5rem 1.5rem;
      }
      @keyframes rise {
        from {
          opacity: 0;
          transform: translateY(12px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }
    `
  ]
})
export class ModalComponent {
  @Input({ required: true }) title!: string;
  @Input() subtitle = '';
  @Input() closeOnBackdrop = true;
  @Output() closed = new EventEmitter<void>();

  onBackdrop(): void {
    if (this.closeOnBackdrop) {
      this.closed.emit();
    }
  }
}
