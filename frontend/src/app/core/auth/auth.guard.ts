import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.refresh();
  if (auth.isAuthenticated()) return true;
  return router.createUrlTree(['/login'], {
    queryParams: {
      next: state.url,
      reason: auth.state() === 'unavailable' ? 'unavailable' : 'session',
    },
  });
};
