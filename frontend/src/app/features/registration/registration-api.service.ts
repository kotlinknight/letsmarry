import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { switchMap } from 'rxjs';
import { CsrfService } from '../../core/api/csrf.service';

// Mirrors the implemented registration operation in docs/api/openapi.yaml.
export interface RegistrationResponse {
  id: string;
  displayName: string;
  email: string;
  emailVerified: false;
}

@Injectable({ providedIn: 'root' })
export class RegistrationApiService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  register(displayName: string, email: string, password: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token, headerName }) =>
          this.http.post<RegistrationResponse>(
            '/api/v1/auth/register',
            { displayName, email, password },
            { headers: { [headerName]: token }, withCredentials: true, observe: 'response' },
          ),
        ),
      );
  }
}
