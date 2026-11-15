import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

export const adminGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const notifications = inject(NotificationService);

  if (authService.isAdmin()) {
    return true;
  }

  notifications.error('The dashboard is available to administrators only.');
  void router.navigate(['/orders']);
  return false;
};
