import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs';

import { LoadingService } from '../services/loading.service';

/**
 * Global progress bar. A counter is used so concurrent requests do not hide
 * the bar while another request is still in flight.
 */
export const loadingInterceptor: HttpInterceptorFn = (request, next) => {
  const loadingService = inject(LoadingService);
  loadingService.start();

  return next(request).pipe(finalize(() => loadingService.stop()));
};
