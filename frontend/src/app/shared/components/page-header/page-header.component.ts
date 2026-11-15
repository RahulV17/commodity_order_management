import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-page-header',
  imports: [CommonModule],
  template: `
    <header class="page-header">
      <div>
        <p class="page-header__eyebrow" *ngIf="eyebrow">{{ eyebrow }}</p>
        <h1>{{ title }}</h1>
        <p class="page-header__subtitle" *ngIf="subtitle">{{ subtitle }}</p>
      </div>
      <div class="page-header__actions">
        <ng-content />
      </div>
    </header>
  `,
  styles: [
    `
      .page-header {
        display: flex;
        align-items: flex-end;
        justify-content: space-between;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 1.5rem;
      }
      .page-header__eyebrow {
        margin: 0 0 0.35rem;
        font-size: 0.72rem;
        letter-spacing: 0.12em;
        text-transform: uppercase;
        font-weight: 700;
        color: var(--gold-dark);
      }
      h1 {
        margin: 0;
        font-size: clamp(1.45rem, 2.4vw, 1.9rem);
        letter-spacing: -0.02em;
        color: var(--ink);
      }
      .page-header__subtitle {
        margin: 0.45rem 0 0;
        color: var(--slate);
        max-width: 48rem;
        line-height: 1.55;
      }
      .page-header__actions {
        display: flex;
        gap: 0.6rem;
        flex-wrap: wrap;
      }
    `
  ]
})
export class PageHeaderComponent {
  @Input({ required: true }) title!: string;
  @Input() subtitle = '';
  @Input() eyebrow = '';
}
