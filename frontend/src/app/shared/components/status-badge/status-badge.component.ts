import { Component, Input } from '@angular/core';

import { StatusLabelPipe } from '../../pipes/status-label.pipe';

@Component({
  selector: 'app-status-badge',
  imports: [StatusLabelPipe],
  template: `
    <span class="badge" [class]="'badge badge--' + status.toLowerCase()">
      {{ status | statusLabel }}
    </span>
  `,
  styles: [':host { display: inline-flex; }']
})
export class StatusBadgeComponent {
  @Input({ required: true }) status!: string;
}
