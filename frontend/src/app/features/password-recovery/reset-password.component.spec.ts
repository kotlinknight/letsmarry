import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ResetPasswordComponent, passwordPolicy } from './reset-password.component';

const token = 'a'.repeat(43);
const password = 'A memorable phrase 123';

describe('ResetPasswordComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    window.history.replaceState(null, '', '/');
  });

  function create(fragment = `#token=${token}`) {
    window.history.replaceState(null, '', `/reset-password${fragment}`);
    const fixture = TestBed.createComponent(ResetPasswordComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('strips the token immediately without submitting, including on invalid links', () => {
    const valid = create();
    expect(window.location.hash).toBe('');
    expect(valid.componentInstance.state()).toBe('ready');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
    const invalid = create('#token=bad');
    expect(window.location.hash).toBe('');
    expect(invalid.componentInstance.state()).toBe('missing');
    expect(invalid.nativeElement.textContent).toContain('Request another reset link');
  });

  it('strips a new fragment received while the reset route is already open', () => {
    const fixture = create();
    window.history.replaceState(null, '', `/reset-password#token=${token}`);
    fixture.componentInstance.onHashChange();
    expect(window.location.hash).toBe('');
    expect(fixture.componentInstance.state()).toBe('ready');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
  });

  it('uses the backend Unicode/NFC length and control-character policy', () => {
    const fixture = create();
    const field = fixture.componentInstance.form.controls.newPassword;
    field.setValue('e\u0301'.repeat(15));
    expect(field.valid).toBe(true);
    field.setValue('😀'.repeat(15));
    expect(field.valid).toBe(true);
    field.setValue('short');
    expect(field.hasError('passwordPolicy')).toBe(true);
    field.setValue('a'.repeat(129));
    expect(field.hasError('passwordPolicy')).toBe(true);
    field.setValue('long enough\u0001secret');
    expect(field.hasError('passwordPolicy')).toBe(true);
    expect(passwordPolicy(field)).toEqual({ passwordPolicy: true });
  });

  it('requires matching passwords and can show or hide both fields', async () => {
    const fixture = create();
    fixture.componentInstance.form.setValue({
      newPassword: password,
      confirmPassword: 'different',
    });
    await fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Passwords must match.');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
    fixture.nativeElement.querySelector('[aria-label="Show new password"]').click();
    fixture.nativeElement.querySelector('[aria-label="Show confirmation"]').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#new-password').type).toBe('text');
    expect(fixture.nativeElement.querySelector('#confirm-password').type).toBe('text');
  });

  it('submits only on user action with CSRF, clears secrets on 204, and offers sign-in', async () => {
    const fixture = create();
    fixture.componentInstance.form.setValue({ newPassword: password, confirmPassword: password });
    const action = fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type="submit"]').disabled).toBe(true);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    const reset = http.expectOne('/api/v1/auth/password/reset');
    expect(reset.request.body).toEqual({ token, newPassword: password });
    expect(reset.request.headers.get('X-CSRF-TOKEN')).toBe('csrf');
    expect(reset.request.withCredentials).toBe(true);
    reset.flush(null, { status: 204, statusText: 'No Content' });
    await action;
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('success');
    expect(fixture.componentInstance.form.controls.newPassword.value).toBe('');
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
    await fixture.componentInstance.submit();
    http.expectNone('/api/v1/auth/csrf');
  });

  it('clears a previously displayed account only after a confirmed reset', async () => {
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush({
      id: '0199d4ee-26ad-7000-9000-000000000001',
      displayName: 'Anjali',
      email: 'anjali@example.com',
      emailVerified: true,
      status: 'ACTIVE',
    });
    await restore;
    expect(auth.isAuthenticated()).toBe(true);
    const fixture = create();
    fixture.componentInstance.form.setValue({ newPassword: password, confirmPassword: password });
    const action = fixture.componentInstance.submit();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http
      .expectOne('/api/v1/auth/password/reset')
      .flush(null, { status: 204, statusText: 'No Content' });
    await action;
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.user()).toBeNull();
  });

  it('treats an invalid or reused token as terminal', async () => {
    const fixture = create();
    fixture.componentInstance.form.setValue({ newPassword: password, confirmPassword: password });
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http
      .expectOne('/api/v1/auth/password/reset')
      .flush({ code: 'PASSWORD_RESET_TOKEN_INVALID' }, { status: 400, statusText: 'Bad Request' });
    await action;
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('invalid');
    expect(fixture.nativeElement.querySelector('a[href="/forgot-password"]')).not.toBeNull();
    await fixture.componentInstance.submit();
    http.expectNone('/api/v1/auth/csrf');
  });

  it.each([
    [403, 'security session expired'],
    [429, 'Too many attempts'],
    [503, 'temporarily unavailable'],
    [0, 'temporarily unavailable'],
  ])('preserves a manual retry path for HTTP %i', async (status, text) => {
    const fixture = create();
    fixture.componentInstance.form.setValue({ newPassword: password, confirmPassword: password });
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/password/reset').flush(null, { status, statusText: 'Failure' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(text);
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).not.toBeNull();
  });
});
