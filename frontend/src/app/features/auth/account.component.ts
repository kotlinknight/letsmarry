import { ChangeDetectionStrategy, Component, HostListener, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-account',
  templateUrl: './account.component.html',
  styleUrl: './account.component.scss',
})
export class AccountComponent {
  private readonly router = inject(Router);
  readonly auth = inject(AuthService);
  readonly signingOut = signal(false);
  readonly message = signal<string | null>(null);

  @HostListener('window:focus')
  onFocus(): void {
    void this.checkSession();
  }

  async checkSession(): Promise<void> {
    if (this.signingOut()) return;
    await this.auth.refresh();
    if (this.auth.state() === 'unauthenticated') {
      await this.router.navigate(['/login'], {
        queryParams: { reason: 'session', next: '/account' },
      });
    }
  }

  async logout(): Promise<void> {
    if (this.signingOut()) return;
    this.message.set(null);
    this.signingOut.set(true);
    try {
      const result = await this.auth.logout();
      if (result === 'confirmed') {
        await this.router.navigateByUrl('/login');
      } else if (result === 'csrf-invalid') {
        this.message.set('Your security session changed. Please try signing out again.');
      } else if (result === 'still-authenticated') {
        this.message.set('Sign-out was not confirmed. You are still signed in. Please try again.');
      } else {
        this.message.set(
          'We could not confirm sign-out or check your session. Please retry when the service is available.',
        );
      }
    } finally {
      this.signingOut.set(false);
    }
  }
}
