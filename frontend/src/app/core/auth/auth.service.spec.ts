import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

const user = {
  id: '0199d4ee-26ad-7000-9000-000000000001',
  displayName: 'Anjali',
  email: 'anjali@example.com',
  emailVerified: true,
  status: 'ACTIVE',
};

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('bootstraps CSRF, logs in with cookies, then restores the session from /me', async () => {
    const login = auth.login('anjali@example.com', 'private-password');
    const csrf = http.expectOne('/api/v1/auth/csrf');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush({ token: 'first-csrf', headerName: 'X-CSRF-TOKEN' });
    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.withCredentials).toBe(true);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('first-csrf');
    expect(request.request.body).toEqual({ email: user.email, password: 'private-password' });
    request.flush({ user });
    expect(await login).toBe('success');
    expect(auth.user()?.displayName).toBe('Anjali');

    const restore = auth.refresh();
    expect(auth.state()).toBe('loading');
    expect(auth.user()).toBeNull();
    const me = http.expectOne('/api/v1/users/me');
    expect(me.request.withCredentials).toBe(true);
    me.flush(user);
    await restore;
    expect(auth.state()).toBe('authenticated');
    expect(auth.user()?.email).toBe(user.email);
  });

  it('waits for a pending startup session check before signing in', async () => {
    const startup = auth.refresh();
    const login = auth.login('anjali@example.com', 'private-password');
    http.expectNone('/api/v1/auth/csrf');
    http.expectOne('/api/v1/users/me').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });
    await startup;
    await Promise.resolve();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush({ user });
    expect(await login).toBe('success');
    expect(auth.user()?.id).toBe(user.id);
  });

  it('uses generic invalid-credentials feedback and clears stale identity on 401', async () => {
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(user);
    await restore;
    const login = auth.login('anjali@example.com', 'wrong');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    expect(await login).toBe('invalid');
    expect(auth.user()).toBeNull();
    expect(auth.state()).toBe('unauthenticated');
  });

  it('separates rate limiting and CSRF rejection without replaying login', async () => {
    const limited = auth.login('anjali@example.com', 'wrong');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush(null, {
      status: 429,
      statusText: 'Too Many Requests',
    });
    expect(await limited).toBe('rate-limited');

    const rejected = auth.login('anjali@example.com', 'private-password');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'fresh', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(await rejected).toBe('csrf-invalid');
    http.expectNone('/api/v1/auth/login');
  });

  it('refuses malformed CSRF and login responses', async () => {
    const badCsrf = auth.login('anjali@example.com', 'private-password');
    http.expectOne('/api/v1/auth/csrf').flush({ token: '', headerName: 'X-CSRF-TOKEN' });
    expect(await badCsrf).toBe('unavailable');
    http.expectNone('/api/v1/auth/login');

    const badLogin = auth.login('anjali@example.com', 'private-password');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush({ user: { ...user, status: 'SUSPENDED' } });
    expect(await badLogin).toBe('unexpected');
    expect(auth.user()).toBeNull();
  });

  it('treats 401 as expired and network failure as unconfirmed during restoration', async () => {
    const expired = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });
    await expired;
    expect(auth.state()).toBe('unauthenticated');

    const unavailable = auth.refresh();
    http.expectOne('/api/v1/users/me').error(new ProgressEvent('offline'));
    await unavailable;
    expect(auth.state()).toBe('unavailable');
    expect(auth.user()).toBeNull();
  });

  it('obtains post-login CSRF, confirms logout, and clears identity', async () => {
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(user);
    await restore;

    const logout = auth.logout();
    http
      .expectOne('/api/v1/auth/csrf')
      .flush({ token: 'post-login-csrf', headerName: 'X-CSRF-TOKEN' });
    const request = http.expectOne('/api/v1/auth/logout');
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('post-login-csrf');
    expect(request.request.withCredentials).toBe(true);
    request.flush(null, { status: 204, statusText: 'No Content' });
    expect(await logout).toBe('confirmed');
    expect(auth.state()).toBe('unauthenticated');
    expect(auth.user()).toBeNull();
  });

  it('checks /me after a failed logout and never claims revocation without proof', async () => {
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(user);
    await restore;

    const failed = auth.logout();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/logout').error(new ProgressEvent('offline'));
    await Promise.resolve();
    http.expectOne('/api/v1/users/me').flush(user);
    expect(await failed).toBe('still-authenticated');
    expect(auth.user()?.id).toBe(user.id);

    const uncertain = auth.logout();
    http.expectOne('/api/v1/auth/csrf').error(new ProgressEvent('offline'));
    await Promise.resolve();
    http.expectOne('/api/v1/users/me').error(new ProgressEvent('offline'));
    expect(await uncertain).toBe('unconfirmed');
    expect(auth.user()).toBeNull();

    const invalidated = auth.logout();
    http.expectOne('/api/v1/auth/csrf').error(new ProgressEvent('offline'));
    await Promise.resolve();
    http.expectOne('/api/v1/users/me').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });
    expect(await invalidated).toBe('confirmed');
  });

  it('preserves the CSRF distinction when logout is rejected but session survives', async () => {
    const failed = auth.logout();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/logout').flush(null, {
      status: 403,
      statusText: 'Forbidden',
    });
    await Promise.resolve();
    http.expectOne('/api/v1/users/me').flush(user);
    expect(await failed).toBe('csrf-invalid');
  });
});
