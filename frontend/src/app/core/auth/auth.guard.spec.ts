import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
} from '@angular/router';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function checkAccount() {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/account' } as RouterStateSnapshot),
    ) as Promise<boolean | ReturnType<Router['createUrlTree']>>;
  }

  it('waits for /me before allowing an authenticated route', async () => {
    const decision = checkAccount();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/users/me').flush({
      id: '0199d4ee-26ad-7000-9000-000000000001',
      displayName: 'Anjali',
      email: 'anjali@example.com',
      emailVerified: true,
      status: 'ACTIVE',
    });
    expect(await decision).toBe(true);
  });

  it('redirects an expired session to login without a loop', async () => {
    const decision = checkAccount();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/users/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    expect((await decision).toString()).toBe('/login?next=%2Faccount&reason=session');
  });

  it('does not reveal account data when session validation is unavailable', async () => {
    const decision = checkAccount();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/users/me')
      .error(new ProgressEvent('offline'));
    expect((await decision).toString()).toBe('/login?next=%2Faccount&reason=unavailable');
  });
});
