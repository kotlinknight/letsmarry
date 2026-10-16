import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService, LoginResult } from '../../core/auth/auth.service';

const LOGIN_MESSAGES: Record<Exclude<LoginResult, 'success'>, string> = {
  invalid: 'We could not sign you in with those details. Check them and try again.',
  'rate-limited': 'Too many attempts. Please wait a little before trying again.',
  'csrf-invalid': 'Your security session expired. Please try signing in again.',
  'invalid-request': 'Check your email address and password, then try again.',
  disabled: 'Sign-in is not available in this environment yet.',
  unavailable: 'We could not reach the service. Please try again when it is available.',
  unexpected: 'We could not confirm sign-in. Please try again.',
};

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(320)],
    }),
    password: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });
  readonly submitting = signal(false);
  readonly passwordVisible = signal(false);
  readonly message = signal<string | null>(null);
  readonly arrivalMessage =
    this.route.snapshot.queryParamMap.get('reason') === 'unavailable'
      ? 'We could not check your session. Please try again when the service is available.'
      : this.route.snapshot.queryParamMap.get('reason') === 'session'
        ? 'Sign in to continue. Your previous session may have ended.'
        : null;

  togglePassword(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  async submit(): Promise<void> {
    if (this.submitting()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.message.set(null);
    this.submitting.set(true);
    try {
      const result = await this.auth.login(
        this.form.controls.email.value,
        this.form.controls.password.value,
      );
      if (result === 'success') {
        // /account is the only protected destination in this stage. Do not
        // navigate to an untrusted URL from the query string.
        await this.router.navigateByUrl('/account');
      } else {
        this.message.set(LOGIN_MESSAGES[result]);
      }
    } finally {
      this.submitting.set(false);
    }
  }
}
