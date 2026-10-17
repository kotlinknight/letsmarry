import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthApiService, CurrentUser } from '../api/auth-api.service';

export type SessionState =
  | 'unknown'
  | 'loading'
  | 'authenticated'
  | 'unauthenticated'
  | 'unavailable';
export type LoginResult =
  | 'success'
  | 'invalid'
  | 'rate-limited'
  | 'csrf-invalid'
  | 'invalid-request'
  | 'disabled'
  | 'unavailable'
  | 'unexpected';
export type LogoutResult = 'confirmed' | 'csrf-invalid' | 'still-authenticated' | 'unconfirmed';

function isCurrentUser(value: unknown): value is CurrentUser {
  if (typeof value !== 'object' || value === null) return false;
  const user = value as Record<string, unknown>;
  return (
    typeof user['id'] === 'string' &&
    typeof user['displayName'] === 'string' &&
    typeof user['email'] === 'string' &&
    user['emailVerified'] === true &&
    user['status'] === 'ACTIVE'
  );
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApiService);
  private readonly currentState = signal<SessionState>('unknown');
  private readonly currentUser = signal<CurrentUser | null>(null);
  private restoreInFlight: Promise<void> | null = null;
  private sessionEpoch = 0;

  readonly state = this.currentState.asReadonly();
  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentState() === 'authenticated');

  clearAfterPasswordReset(): void {
    // A confirmed reset invalidates this browser's prior login along with every other one.
    this.sessionEpoch += 1;
    this.currentUser.set(null);
    this.currentState.set('unauthenticated');
  }

  async refresh(): Promise<void> {
    if (this.restoreInFlight) return this.restoreInFlight;
    this.currentState.set('loading');
    this.currentUser.set(null);
    const epoch = ++this.sessionEpoch;
    this.restoreInFlight = this.fetchCurrentUser(epoch).finally(() => {
      this.restoreInFlight = null;
    });
    return this.restoreInFlight;
  }

  async login(email: string, password: string): Promise<LoginResult> {
    // The startup /me check must settle before login can establish a new session.
    if (this.restoreInFlight) await this.restoreInFlight;
    this.currentState.set('loading');
    this.currentUser.set(null);
    try {
      const response = await firstValueFrom(this.api.login(email, password));
      if (!isCurrentUser(response?.user)) {
        this.currentState.set('unavailable');
        return 'unexpected';
      }
      this.currentUser.set(response.user);
      this.currentState.set('authenticated');
      return 'success';
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          this.currentState.set('unauthenticated');
          return 'invalid';
        }
        if (error.status === 429) {
          this.currentState.set('unauthenticated');
          return 'rate-limited';
        }
        if (error.status === 403) {
          this.currentState.set('unauthenticated');
          return 'csrf-invalid';
        }
        if (error.status === 422) {
          this.currentState.set('unauthenticated');
          return 'invalid-request';
        }
        if (error.status === 404) {
          this.currentState.set('unavailable');
          return 'disabled';
        }
      }
      this.currentState.set('unavailable');
      return 'unavailable';
    }
  }

  async logout(): Promise<LogoutResult> {
    try {
      const response = await firstValueFrom(this.api.logout());
      if (response.status !== 204) throw new Error('Unexpected logout response');
      this.currentUser.set(null);
      this.currentState.set('unauthenticated');
      return 'confirmed';
    } catch (error) {
      const csrfRejected = error instanceof HttpErrorResponse && error.status === 403;
      // The POST may have reached the server even when its response was lost.
      // Only /me can establish whether this browser still has a live session.
      await this.refresh();
      if (this.currentState() === 'unauthenticated') return 'confirmed';
      if (this.currentState() === 'authenticated') {
        return csrfRejected ? 'csrf-invalid' : 'still-authenticated';
      }
      return 'unconfirmed';
    }
  }

  private async fetchCurrentUser(epoch: number): Promise<void> {
    try {
      const user = await firstValueFrom(this.api.currentUser());
      if (epoch !== this.sessionEpoch) return;
      if (!isCurrentUser(user)) {
        this.currentState.set('unavailable');
        return;
      }
      this.currentUser.set(user);
      this.currentState.set('authenticated');
    } catch (error) {
      if (epoch !== this.sessionEpoch) return;
      this.currentState.set(
        error instanceof HttpErrorResponse && error.status === 401
          ? 'unauthenticated'
          : 'unavailable',
      );
    }
  }
}
