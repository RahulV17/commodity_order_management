import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';

import { LoadingService } from '../../../core/services/loading.service';

@Component({
  selector: 'app-loading-bar',
  imports: [CommonModule],
  template: `
    <div class="loading-track" *ngIf="loadingService.loading$ | async" aria-label="Loading">
      <span class="loading-track__bar"></span>
    </div>
  `,
  styles: [
    `
      .loading-track {
        position: fixed;
        inset: 0 0 auto;
        height: 3px;
        z-index: 120;
        overflow: hidden;
        background: rgba(200, 150, 62, 0.18);
      }
      .loading-track__bar {
        display: block;
        height: 100%;
        width: 35%;
        background: linear-gradient(90deg, var(--gold), #f0cf8d);
        animation: slide 1s ease-in-out infinite;
      }
      @keyframes slide {
        0% {
          transform: translateX(-100%);
        }
        100% {
          transform: translateX(320%);
        }
      }
    `
  ]
})
export class LoadingBarComponent {
  readonly loadingService = inject(LoadingService);
}
