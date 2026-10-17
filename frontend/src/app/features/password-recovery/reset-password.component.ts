import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { PasswordRecoveryApiService } from './password-recovery-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { passwordPolicy } from '../../shared/validation/account.validators';

export { passwordPolicy } from '../../shared/validation/account.validators';

type ResetState =
  | 'ready'
  | 'missing'
  | 'submitting'
  | 'success'
  | 'invalid'
  | 'csrf'
  | 'rate-limited'
  | 'unavailable'
  | 'error';

const passwordsMatch: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const password = control.get('newPassword')?.value;
  const confirm = control.get('confirmPassword')?.value;
  return typeof password === 'string' && typeof confirm === 'string' && password === confirm
    ? null
    : { passwordMismatch: true };
};

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-reset-password',
  templateUrl: './reset-password.component.html',
  styleUrl: './password-recovery.component.scss',
})
export class ResetPasswordComponent implements OnInit {
  private readonly api = inject(PasswordRecoveryApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private token: string | null = null;
  readonly state = signal<ResetState>('missing');
  readonly passwordVisible = signal(false);
  readonly confirmationVisible = signal(false);
  readonly form = new FormGroup(
    {
      newPassword: new FormControl('', { nonNullable: true, validators: [passwordPolicy] }),
      confirmPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
    },
    { validators: [passwordsMatch] },
  );

  constructor() {
    // The router can restore a fragment after hashchange; scrub it again when navigation settles.
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        if (window.location.hash) this.captureFragment();
      });
  }

  ngOnInit(): void {
    this.captureFragment();
  }

  @HostListener('window:hashchange')
  onHashChange(): void {
    if (window.location.hash) this.captureFragment();
  }

  private captureFragment(): void {
    const fragment = window.location.hash.slice(1);
    const token = new URLSearchParams(fragment).get('token');
    // Strip the entire fragment, even if malformed, before any user interaction.
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname + window.location.search,
    );
    this.token = token && /^[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
    this.form.reset();
    this.state.set(this.token ? 'ready' : 'missing');
  }

  togglePassword(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  toggleConfirmation(): void {
    this.confirmationVisible.update((visible) => !visible);
  }

  async submit(): Promise<void> {
    if (!this.token || this.state() === 'submitting' || this.state() === 'success') return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.state.set('submitting');
    try {
      const response = await firstValueFrom(
        this.api.reset(this.token, this.form.controls.newPassword.value),
      );
      if (response.status !== 204) {
        this.state.set('error');
        return;
      }
      this.token = null;
      this.form.reset();
      this.auth.clearAfterPasswordReset();
      this.state.set('success');
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 400 && error.error?.code === 'PASSWORD_RESET_TOKEN_INVALID') {
          this.token = null;
          this.form.reset();
          this.state.set('invalid');
        } else {
          this.state.set(
            error.status === 403
              ? 'csrf'
              : error.status === 429
                ? 'rate-limited'
                : error.status === 404 || error.status === 503 || error.status === 0
                  ? 'unavailable'
                  : 'error',
          );
        }
      } else {
        this.state.set('unavailable');
      }
    }
  }
}
