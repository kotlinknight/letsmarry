import { Routes } from '@angular/router';

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
    ],
  },
  {
    path: '**',
    redirectTo: 'foundation',
  },
];
