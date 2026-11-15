import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';

import { NotificationService } from '../../../core/services/notification.service';

@Component({
  selector: 'app-toast-container',
  imports: [CommonModule],
  template: `
    <div class="toast-stack" aria-live="polite">
      <article
        class="toast"
        *ngFor="let toast of notificationService.toasts$ | async"
        [class]="'toast toast--' + toast.type"
      >
        <span class="toast__icon" aria-hidden="true">
          {{ toast.type === 'success' ? '✓' : toast.type === 'error' ? '!' : 'i' }}
        </span>
        <p>{{ toast.message }}</p>
        <button
          type="button"
          class="toast__close"
          aria-label="Dismiss notification"
          (click)="notificationService.dismiss(toast.id)"
        >
          ×
        </button>
      </article>
    </div>
  `,
  styles: [
    `
      .toast-stack {
        position: fixed;
        right: 1.25rem;
        bottom: 1.25rem;
        z-index: 130;
        display: flex;
        flex-direction: column;
        gap: 0.65rem;
        width: min(24rem, calc(100vw - 2rem));
      }
      .toast {
        display: grid;
        grid-template-columns: auto 1fr auto;
        gap: 0.7rem;
        align-items: start;
        padding: 0.85rem 0.95rem;
        border-radius: 12px;
        background: #fff;
        border: 1px solid var(--line);
        box-shadow: 0 18px 48px rgba(10, 24, 44, 0.18);
        animation: toast-in 160ms ease-out;
      }
      .toast p {
        margin: 0;
        font-size: 0.88rem;
        line-height: 1.45;
        color: var(--ink);
      }
      .toast__icon {
        width: 1.35rem;
        height: 1.35rem;
        border-radius: 50%;
        display: grid;
        place-items: center;
        font-size: 0.75rem;
        font-weight: 800;
        color: #fff;
        margin-top: 0.05rem;
      }
      .toast--success .toast__icon {
        background: var(--green);
      }
      .toast--error .toast__icon {
        background: var(--red);
      }
      .toast--info .toast__icon {
        background: var(--blue);
      }
      .toast--success {
        border-left: 4px solid var(--green);
      }
      .toast--error {
        border-left: 4px solid var(--red);
      }
      .toast--info {
        border-left: 4px solid var(--blue);
      }
      .toast__close {
        border: 0;
        background: transparent;
        font-size: 1.1rem;
        line-height: 1;
        color: var(--slate);
        cursor: pointer;
        padding: 0.1rem;
      }
      @keyframes toast-in {
        from {
          opacity: 0;
          transform: translateY(8px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }
    `
  ]
})
export class ToastContainerComponent {
  readonly notificationService = inject(NotificationService);
}
