import { Routes } from '@angular/router';

import { adminGuard } from './core/guards/admin.guard';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';
import { MainLayoutComponent } from './layouts/main-layout/main-layout.component';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in | Commodity Order Management',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: '',
    component: MainLayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'orders' },
      {
        path: 'dashboard',
        title: 'Dashboard | Commodity Order Management',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./features/dashboard/dashboard/dashboard.component').then(
            (m) => m.DashboardComponent
          )
      },
      {
        path: 'instruments',
        title: 'Instruments | Commodity Order Management',
        loadComponent: () =>
          import('./features/instruments/instrument-list/instrument-list.component').then(
            (m) => m.InstrumentListComponent
          )
      },
      {
        path: 'orders',
        title: 'Orders | Commodity Order Management',
        loadComponent: () =>
          import('./features/orders/order-list/order-list.component').then(
            (m) => m.OrderListComponent
          )
      },
      {
        path: 'orders/new',
        title: 'Create order | Commodity Order Management',
        loadComponent: () =>
          import('./features/orders/order-form/order-form.component').then(
            (m) => m.OrderFormComponent
          )
      },
      {
        path: 'orders/:id',
        title: 'Order details | Commodity Order Management',
        loadComponent: () =>
          import('./features/orders/order-detail/order-detail.component').then(
            (m) => m.OrderDetailComponent
          )
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
