import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { switchMap } from 'rxjs';
import { CsrfService } from './csrf.service';

// These DTOs mirror docs/api/openapi.yaml. Client generation is deferred until a
// supported generator is added to the locked frontend toolchain.
export interface CurrentUser {
  id: string;
  displayName: string;
  email: string;
  emailVerified: true;
  status: 'ACTIVE';
}

export interface LoginResponse {
  user: CurrentUser;
}

@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(CsrfService);

  login(email: string, password: string) {
    return this.csrf
      .getToken()
      .pipe(
        switchMap(({ token, headerName }) =>
          this.http.post<LoginResponse>(
            '/api/v1/auth/login',
            { email, password },
            { headers: { [headerName]: token }, withCredentials: true },
          ),
        ),
      );
  }

  currentUser() {
    return this.http.get<CurrentUser>('/api/v1/users/me', { withCredentials: true });
  }

  logout() {
    return this.csrf.getToken().pipe(
      switchMap(({ token, headerName }) =>
        this.http.post<void>('/api/v1/auth/logout', null, {
          headers: { [headerName]: token },
          withCredentials: true,
          observe: 'response',
        }),
      ),
    );
  }
}
