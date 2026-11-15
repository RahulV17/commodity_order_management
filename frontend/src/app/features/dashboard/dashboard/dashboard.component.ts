import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { DashboardStats } from '../../../core/models/dashboard.model';
import { DashboardService } from '../../../core/services/dashboard.service';
import { EmptyStateComponent } from '../../../shared/components/empty-state/empty-state.component';
import { PageHeaderComponent } from '../../../shared/components/page-header/page-header.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';

@Component({
  selector: 'app-dashboard',
  imports: [
    CommonModule,
    RouterLink,
    PageHeaderComponent,
    StatusBadgeComponent,
    EmptyStateComponent
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);

  stats: DashboardStats | null = null;
  loading = true;
  errorMessage = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.errorMessage = '';
    this.dashboardService
      .getStats()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (stats) => (this.stats = stats),
        error: () => {
          this.errorMessage =
            'Dashboard statistics could not be loaded. Check the gateway and order-service.';
        }
      });
  }

  get statusEntries(): Array<{ status: string; count: number }> {
    if (!this.stats) {
      return [];
    }
    return Object.entries(this.stats.ordersByStatus).map(([status, count]) => ({
      status,
      count: Number(count)
    }));
  }

  get maxStatusCount(): number {
    return Math.max(1, ...this.statusEntries.map((entry) => entry.count));
  }

  barWidth(count: number): number {
    return Math.max(3, Math.round((count / this.maxStatusCount) * 100));
  }

  trackByStatus(_index: number, entry: { status: string }): string {
    return entry.status;
  }
}
