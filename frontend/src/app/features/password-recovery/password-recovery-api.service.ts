import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { switchMap } from 'rxjs';
import { CsrfService } from '../../core/api/csrf.service';

// Request and response shapes mirror docs/api/openapi.yaml. No generated client is installed yet.
@Injectable({ providedIn: 'root' })
export class PasswordRecoveryApiService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  forgot(email: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token, headerName }) =>
          this.http.post<void>(
            '/api/v1/auth/password/forgot',
            { email },
            { headers: { [headerName]: token }, withCredentials: true, observe: 'response' },
          ),
        ),
      );
  }

  reset(token: string, newPassword: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token: csrfToken, headerName }) =>
          this.http.post<void>(
            '/api/v1/auth/password/reset',
            { token, newPassword },
            { headers: { [headerName]: csrfToken }, withCredentials: true, observe: 'response' },
          ),
        ),
      );
  }
}
