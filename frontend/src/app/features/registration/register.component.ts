import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
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
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { normalizedEmail, passwordPolicy } from '../../shared/validation/account.validators';
import { RegistrationApiService } from './registration-api.service';

type RegistrationState = 'ready' | 'submitting' | 'success' | 'unavailable';

interface FieldError {
  field: string;
  message: string;
}

const displayNamePolicy: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const name = String(control.value ?? '').trim();
  if (!name) return { required: true };
  return name.length <= 150 ? null : { maxlength: true };
};

const passwordsMatch: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const password = control.get('password')?.value;
  const confirmation = control.get('confirmPassword')?.value;
  if (!password || !confirmation) return null;
  return password.normalize('NFC') === confirmation.normalize('NFC')
    ? null
    : { passwordMismatch: true };
};

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-register',
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly api = inject(RegistrationApiService);

  readonly form = new FormGroup(
    {
      displayName: new FormControl('', { nonNullable: true, validators: [displayNamePolicy] }),
      email: new FormControl('', { nonNullable: true, validators: [normalizedEmail] }),
      password: new FormControl('', { nonNullable: true, validators: [passwordPolicy] }),
      confirmPassword: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
    },
    { validators: [passwordsMatch] },
  );
  readonly passwordVisible = signal(false);
  readonly confirmationVisible = signal(false);
  readonly state = signal<RegistrationState>('ready');
  readonly message = signal<string | null>(null);
  readonly duplicateEmail = signal(false);
  readonly uncertainOutcome = signal(false);

  constructor() {
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      this.message.set(null);
      this.duplicateEmail.set(false);
      this.uncertainOutcome.set(false);
    });
  }

  normalizeDisplayName(): void {
    this.form.controls.displayName.setValue(this.form.controls.displayName.value.trim());
  }

  normalizeEmail(): void {
    this.form.controls.email.setValue(this.form.controls.email.value.trim().toLowerCase());
  }

  togglePassword(): void {
    this.passwordVisible.update((visible) => !visible);
  }

  toggleConfirmation(): void {
    this.confirmationVisible.update((visible) => !visible);
  }

  async submit(): Promise<void> {
    if (this.state() !== 'ready') return;
    this.normalizeDisplayName();
    this.normalizeEmail();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalid();
      return;
    }
    this.message.set(null);
    this.state.set('submitting');
    try {
      const response = await firstValueFrom(
        this.api.register(
          this.form.controls.displayName.value,
          this.form.controls.email.value,
          this.form.controls.password.value,
        ),
      );
      if (response.status !== 201 || !response.body?.id || response.body.emailVerified !== false) {
        this.showUncertainOutcome();
        return;
      }
      this.form.controls.password.reset('');
      this.form.controls.confirmPassword.reset('');
      this.passwordVisible.set(false);
      this.confirmationVisible.set(false);
      this.state.set('success');
      this.focus('#register-title');
    } catch (error) {
      this.handleError(error);
    } finally {
      if (this.state() === 'submitting') this.state.set('ready');
    }
  }

  private handleError(error: unknown): void {
    if (!(error instanceof HttpErrorResponse)) {
      this.message.set(
        error instanceof Error && error.message === 'Invalid CSRF bootstrap response'
          ? 'Your security session could not be started. Please try again.'
          : 'We could not confirm whether your account was created. Check your email or request a verification link before trying again.',
      );
      this.uncertainOutcome.set(true);
    } else if (error.status === 422) {
      const errors = (error.error as { errors?: FieldError[] } | null)?.errors;
      let mapped = false;
      if (Array.isArray(errors)) {
        for (const fieldError of errors) {
          if (!fieldError || typeof fieldError.message !== 'string') continue;
          if (
            fieldError.field === 'displayName' ||
            fieldError.field === 'email' ||
            fieldError.field === 'password'
          ) {
            this.form.controls[fieldError.field].setErrors({ server: fieldError.message });
            this.form.controls[fieldError.field].markAsTouched();
            mapped = true;
          }
        }
      }
      this.message.set(
        mapped
          ? 'Please correct the highlighted details and try again.'
          : 'Please check your details and try again.',
      );
      if (mapped) {
        this.focusFirstInvalid();
        return;
      }
    } else if (error.status === 409) {
      this.duplicateEmail.set(true);
      this.message.set('An account may already use this email address.');
    } else if (error.status === 403) {
      const code = (error.error as { code?: string } | null)?.code;
      this.message.set(
        code === 'CSRF_INVALID'
          ? 'Your security session expired. Please choose Create account again to retry with a fresh session.'
          : 'We could not complete registration because this request was rejected. Please try again later.',
      );
    } else if (error.status === 404) {
      this.state.set('unavailable');
    } else if (error.status === 429) {
      this.message.set('Too many requests. Please wait before trying again.');
    } else if (error.status === 0 || error.status >= 500) {
      this.showUncertainOutcome();
    } else {
      this.message.set(
        'We could not complete registration. Please check your details and try again.',
      );
    }
    this.focus('#register-feedback');
  }

  private showUncertainOutcome(): void {
    this.uncertainOutcome.set(true);
    this.message.set(
      'We could not confirm whether your account was created. Check your email or request a verification link before trying again.',
    );
    this.focus('#register-feedback');
  }

  private focusFirstInvalid(): void {
    this.changeDetector.detectChanges();
    this.host.nativeElement.querySelector<HTMLInputElement>('[aria-invalid="true"]')?.focus();
  }

  private focus(selector: string): void {
    this.changeDetector.detectChanges();
    this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus();
  }
}
