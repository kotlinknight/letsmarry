import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { VerificationApiService } from './verification-api.service';

type VerificationState = 'ready' | 'missing' | 'submitting' | 'success' | 'invalid' | 'error';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  selector: 'app-verify-email',
  templateUrl: './verify-email.component.html',
  styleUrl: './verify-email.component.scss',
})
export class VerifyEmailComponent implements OnInit {
  private readonly api = inject(VerificationApiService);
  private token: string | null = null;
  readonly state = signal<VerificationState>('missing');
  readonly resendState = signal<'idle' | 'submitting' | 'sent' | 'error'>('idle');
  readonly email = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.email],
  });

  ngOnInit(): void {
    const fragment = window.location.hash.slice(1);
    const token = new URLSearchParams(fragment).get('token');
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname + window.location.search,
    );
    if (token) {
      this.token = token;
      this.state.set('ready');
    }
  }

  async verify(): Promise<void> {
    if (!this.token || this.state() === 'submitting') return;
    this.state.set('submitting');
    try {
      await firstValueFrom(this.api.verify(this.token));
      this.state.set('success');
    } catch (error) {
      this.state.set(
        error instanceof HttpErrorResponse && error.status === 400 ? 'invalid' : 'error',
      );
    } finally {
      this.token = null;
    }
  }

  async resend(): Promise<void> {
    if (this.email.invalid || this.resendState() === 'submitting') {
      this.email.markAsTouched();
      return;
    }
    this.resendState.set('submitting');
    try {
      await firstValueFrom(this.api.resend(this.email.value));
      this.resendState.set('sent');
    } catch {
      this.resendState.set('error');
    }
  }
}
