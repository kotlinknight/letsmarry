import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./layout/app-shell/app-shell.component').then((module) => module.AppShellComponent),
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'foundation',
      },
      {
        path: 'foundation',
        title: 'Foundation | Make My Marriage',
        loadComponent: () =>
          import('./pages/foundation-overview/foundation-overview.component').then(
            (module) => module.FoundationOverviewComponent,
          ),
      },
      {
        path: 'design-tokens',
        title: 'Design tokens | Make My Marriage',
        loadComponent: () =>
          import('./pages/design-tokens/design-tokens.component').then(
            (module) => module.DesignTokensComponent,
          ),
      },
      {
        path: 'verify-email',
        title: 'Verify email | Make My Marriage',
        loadComponent: () =>
          import('./features/email-verification/verify-email.component').then(
            (module) => module.VerifyEmailComponent,
          ),
      },
      {
        path: 'login',
        title: 'Sign in | Make My Marriage',
        loadComponent: () =>
          import('./features/auth/login.component').then((module) => module.LoginComponent),
      },
      {
        path: 'forgot-password',
        title: 'Forgot password | Make My Marriage',
        loadComponent: () =>
          import('./features/password-recovery/forgot-password.component').then(
            (module) => module.ForgotPasswordComponent,
          ),
      },
      {
        path: 'reset-password',
        title: 'Reset password | Make My Marriage',
        loadComponent: () =>
          import('./features/password-recovery/reset-password.component').then(
            (module) => module.ResetPasswordComponent,
          ),
      },
      {
        path: 'account',
        title: 'Your account | Make My Marriage',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/auth/account.component').then((module) => module.AccountComponent),
      },
    ],
  },
  {
    path: '**',
    redirectTo: 'foundation',
  },
];
