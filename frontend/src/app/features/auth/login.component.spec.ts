import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows accessible required-field errors and does not submit invalid data', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    await fixture.componentInstance.submit();
    fixture.detectChanges();

    const email = fixture.nativeElement.querySelector('#login-email') as HTMLInputElement;
    const password = fixture.nativeElement.querySelector('#login-password') as HTMLInputElement;
    expect(email.getAttribute('aria-invalid')).toBe('true');
    expect(password.getAttribute('aria-describedby')).toBe('login-password-error');
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address.');
    expect(fixture.nativeElement.textContent).toContain('Enter your password.');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
  });

  it('submits once, disables the button, and shows generic failed-login feedback', async () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: 'anjali@example.com', password: 'wrong' });
    const action = fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.submit-button').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Signing in…');

    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'We could not sign you in with those details.',
    );
    expect(fixture.nativeElement.querySelector('.submit-button').disabled).toBe(false);
  });

  it('navigates to the protected account page after a safe login response', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: 'anjali@example.com', password: 'private' });
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush({
      user: {
        id: '0199d4ee-26ad-7000-9000-000000000001',
        displayName: 'Anjali',
        email: 'anjali@example.com',
        emailVerified: true,
        status: 'ACTIVE',
      },
    });
    await action;
    expect(navigate).toHaveBeenCalledWith('/account');
  });

  it.each([
    [429, 'Too many attempts.'],
    [403, 'Your security session expired.'],
    [422, 'Check your email address and password'],
    [404, 'Sign-in is not available'],
    [503, 'We could not reach the service.'],
  ])('shows useful feedback for login HTTP %i', async (status, expected) => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.form.setValue({ email: 'anjali@example.com', password: 'private' });
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/login').flush(null, { status, statusText: 'Failure' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(expected);
  });

  it('offers a working password visibility toggle', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('.password-toggle') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#login-password').type).toBe('text');
    expect(button.getAttribute('aria-pressed')).toBe('true');
  });
});
