import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { switchMap } from 'rxjs';
import { CsrfService } from '../../core/api/csrf.service';

@Injectable({ providedIn: 'root' })
export class VerificationApiService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  verify(token: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token: csrfToken, headerName }) =>
          this.http.post<void>(
            '/api/v1/auth/email/verify',
            { token },
            { headers: { [headerName]: csrfToken }, withCredentials: true },
          ),
        ),
      );
  }

  resend(email: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token: csrfToken, headerName }) =>
          this.http.post<void>(
            '/api/v1/auth/email/verification-requests',
            { email },
            { headers: { [headerName]: csrfToken }, withCredentials: true },
          ),
        ),
      );
  }
}
