import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RegisterComponent } from './register.component';

describe('RegisterComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function create() {
    const fixture = TestBed.createComponent(RegisterComponent);
    fixture.detectChanges();
    return fixture;
  }

  function fillValid(fixture: ReturnType<typeof create>) {
    fixture.componentInstance.form.setValue({
      displayName: '  Anjali  ',
      email: '  ANJALI@EXAMPLE.COM  ',
      password: 'A memorable phrase 123',
      confirmPassword: 'A memorable phrase 123',
    });
    fixture.detectChanges();
  }

  function bootstrap() {
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/csrf');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBe(true);
    req.flush({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' });
  }

  function registrationRequest() {
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBe(true);
    expect(req.request.headers.get('X-CSRF-TOKEN')).toBe('csrf-token');
    expect(req.request.body).toEqual({
      displayName: 'Anjali',
      email: 'anjali@example.com',
      password: 'A memorable phrase 123',
    });
    return req;
  }

  it('starts invalid and disabled without requesting CSRF', () => {
    const fixture = create();
    expect(fixture.nativeElement.querySelector('.submit-button').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
  });

  it('marks and focuses invalid fields without a request', async () => {
    const fixture = create();
    await fixture.componentInstance.submit();
    expect(fixture.nativeElement.querySelector('#register-name')).toBe(document.activeElement);
    expect(fixture.nativeElement.textContent).toContain('Enter your display name.');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
  });

  it('preserves shared Unicode password and confirmation rules', () => {
    const fixture = create();
    const { displayName, email, password, confirmPassword } =
      fixture.componentInstance.form.controls;
    displayName.setValue('  Anjali  ');
    fixture.componentInstance.normalizeDisplayName();
    expect(displayName.value).toBe('Anjali');
    displayName.setValue('a'.repeat(151));
    expect(displayName.hasError('maxlength')).toBe(true);
    email.setValue('  AnJaLi@Example.com  ');
    fixture.componentInstance.normalizeEmail();
    expect(email.value).toBe('anjali@example.com');
    email.setValue('not-an-email');
    expect(email.invalid).toBe(true);
    password.setValue('e\u0301'.repeat(15));
    confirmPassword.setValue('é'.repeat(15));
    expect(fixture.componentInstance.form.hasError('passwordMismatch')).toBe(false);
    password.setValue('😀'.repeat(15));
    expect(password.valid).toBe(true);
    password.setValue(' short phrase 123 ');
    expect(password.value).toBe(' short phrase 123 ');
    password.setValue('short');
    expect(password.invalid).toBe(true);
    password.setValue('a'.repeat(129));
    expect(password.invalid).toBe(true);
    password.setValue('long enough\u0001secret');
    expect(password.invalid).toBe(true);
  });

  it('toggles both password fields without submitting', () => {
    const fixture = create();
    const buttons = fixture.nativeElement.querySelectorAll('.password-toggle');
    buttons[0].click();
    buttons[1].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#register-password').type).toBe('text');
    expect(fixture.nativeElement.querySelector('#register-confirm-password').type).toBe('text');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
  });

  it('submits once with CSRF, then shows 201 success and clears passwords without login', async () => {
    const fixture = create();
    fillValid(fixture);
    const first = fixture.componentInstance.submit();
    const second = fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.submit-button').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Creating account…');
    bootstrap();
    registrationRequest().flush(
      {
        id: '0199d4ee-26ad-7000-9000-000000000001',
        displayName: 'Anjali',
        email: 'anjali@example.com',
        emailVerified: false,
      },
      { status: 201, statusText: 'Created' },
    );
    await Promise.all([first, second]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Check your email');
    expect(fixture.nativeElement.textContent).toContain('Mailpit');
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/verify-email"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="http://127.0.0.1:8025"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.componentInstance.form.controls.password.value).toBe('');
    expect(fixture.componentInstance.form.controls.confirmPassword.value).toBe('');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/login');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/users/me');
  });

  it('maps backend 422 field errors without clearing the form', async () => {
    const fixture = create();
    fillValid(fixture);
    const action = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush(
      {
        code: 'VALIDATION_ERROR',
        errors: [{ field: 'email', message: 'Address is invalid.' }],
      },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#register-email-error').textContent).toContain(
      'Address is invalid.',
    );
    expect(fixture.nativeElement.querySelector('#register-email')).toBe(document.activeElement);
    expect(fixture.componentInstance.form.controls.password.value).toBe('A memorable phrase 123');
  });

  it.each([
    [409, { code: 'EMAIL_ALREADY_REGISTERED' }, 'An account may already use this email address.'],
    [403, { code: 'CSRF_INVALID' }, 'Your security session expired.'],
    [403, { code: 'ACCESS_DENIED' }, 'this request was rejected.'],
    [429, { code: 'RATE_LIMITED' }, 'Too many requests.'],
    [503, { code: 'UNAVAILABLE' }, 'could not confirm whether your account was created.'],
    [0, null, 'could not confirm whether your account was created.'],
  ])('handles HTTP %i without claiming success', async (status, body, expected) => {
    const fixture = create();
    fillValid(fixture);
    const action = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush(body, { status, statusText: 'Failure' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(expected);
    expect(fixture.nativeElement.textContent).not.toContain('Your account was created.');
    expect(fixture.componentInstance.form.controls.password.value).toBe('A memorable phrase 123');
    if (status === 409) {
      expect(fixture.nativeElement.querySelector('a[href="/forgot-password"]')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('a[href="/verify-email"]')).not.toBeNull();
    }
  });

  it('retries rejected CSRF only after a deliberate second submission', async () => {
    const fixture = create();
    fillValid(fixture);
    const first = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush({ code: 'CSRF_INVALID' }, { status: 403, statusText: 'Forbidden' });
    await first;
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
    const second = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush(null, { status: 409, statusText: 'Conflict' });
    await second;
  });

  it('shows unavailability when the backend gate returns 404', async () => {
    const fixture = create();
    fillValid(fixture);
    const action = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush(null, { status: 404, statusText: 'Not Found' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Registration unavailable');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('does not show success for an unexpected 200 response', async () => {
    const fixture = create();
    fillValid(fixture);
    const action = fixture.componentInstance.submit();
    bootstrap();
    registrationRequest().flush(null, { status: 200, statusText: 'OK' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Create your account');
    expect(fixture.nativeElement.textContent).toContain(
      'could not confirm whether your account was created.',
    );
  });
});
