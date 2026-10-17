import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PasswordRecoveryApiService } from './password-recovery-api.service';

type State = 'idle' | 'submitting' | 'accepted' | 'rate-limited' | 'csrf' | 'unavailable' | 'error';

function normalizedEmail(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '')
    .trim()
    .toLowerCase();
  if (!value) return { required: true };
  const candidate = new FormControl(value);
  return Validators.email(candidate) ?? Validators.maxLength(320)(candidate);
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-forgot-password',
  templateUrl: './forgot-password.component.html',
  styleUrl: './password-recovery.component.scss',
})
export class ForgotPasswordComponent {
  private readonly api = inject(PasswordRecoveryApiService);
  readonly email = new FormControl('', {
    nonNullable: true,
    validators: [normalizedEmail],
  });
  readonly state = signal<State>('idle');

  async submit(): Promise<void> {
    if (this.state() === 'submitting' || this.state() === 'accepted') return;
    if (this.email.invalid) {
      this.email.markAsTouched();
      return;
    }
    this.state.set('submitting');
    try {
      const response = await firstValueFrom(this.api.forgot(this.email.value.trim().toLowerCase()));
      this.state.set(response.status === 202 ? 'accepted' : 'error');
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        this.state.set(
          error.status === 429
            ? 'rate-limited'
            : error.status === 403
              ? 'csrf'
              : error.status === 404 || error.status === 503 || error.status === 0
                ? 'unavailable'
                : 'error',
        );
      } else {
        this.state.set('unavailable');
      }
    } finally {
      // A successful request needs no retained copy of the entered address.
      if (this.state() === 'accepted') this.email.reset('');
    }
  }
}
