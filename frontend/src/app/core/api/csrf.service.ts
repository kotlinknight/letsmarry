import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

export interface CsrfResponse {
  token: string;
  headerName: string;
}

@Injectable({ providedIn: 'root' })
export class CsrfService {
  private readonly http = inject(HttpClient);

  getToken() {
    return this.http.get<CsrfResponse>('/api/v1/auth/csrf', { withCredentials: true });
  }
}
