import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { ApiError } from '../models/api-error.model';
import { NotificationService } from '../services/notification.service';
import { TokenStorageService } from '../services/token-storage.service';

/**
 * Central handling for common HTTP failures. Validation errors (400/422) are
 * left to the feature component so they can be displayed next to the field.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const tokenStorage = inject(TokenStorageService);
  const notificationService = inject(NotificationService);
  const router = inject(Router);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      const apiError = error.error as ApiError | null;
      const isLoginRequest = request.url.includes('/auth/login');

      if (error.status === 401 && !isLoginRequest) {
        tokenStorage.clear();
        notificationService.error('Your session expired. Please sign in again.');
        void router.navigate(['/login']);
      } else if (error.status === 403) {
        notificationService.error(
          apiError?.message ?? 'Your role is not allowed to perform this operation.'
        );
      } else if (error.status === 0) {
        notificationService.error(
          'Cannot reach the API gateway. Check that the backend is running on port 8080.'
        );
      } else if (error.status >= 500) {
        notificationService.error(
          apiError?.message ?? 'The server could not process the request. Please retry.'
        );
      }

      return throwError(() => error);
    })
  );
};
