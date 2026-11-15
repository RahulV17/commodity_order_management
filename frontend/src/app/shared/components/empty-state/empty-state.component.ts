import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty-state">
      <div class="empty-state__mark" aria-hidden="true">{{ mark }}</div>
      <h3>{{ title }}</h3>
      <p>{{ message }}</p>
      <ng-content />
    </div>
  `,
  styles: [
    `
      .empty-state {
        padding: 3rem 1.5rem;
        text-align: center;
        color: var(--slate);
      }
      .empty-state__mark {
        width: 3rem;
        height: 3rem;
        display: grid;
        place-items: center;
        margin: 0 auto 1rem;
        border-radius: 50%;
        background: var(--canvas);
        border: 1px solid var(--line);
        color: var(--gold-dark);
        font-size: 1.25rem;
        font-weight: 700;
      }
      h3 {
        margin: 0 0 0.5rem;
        color: var(--ink);
        font-size: 1.05rem;
      }
      p {
        margin: 0 auto;
        max-width: 34rem;
        line-height: 1.6;
      }
    `
  ]
})
export class EmptyStateComponent {
  @Input() title = 'Nothing here yet';
  @Input() message = '';
  @Input() mark = '◇';
}
