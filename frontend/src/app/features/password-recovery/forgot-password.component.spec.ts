import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ForgotPasswordComponent } from './forgot-password.component';

describe('ForgotPasswordComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('requires a valid email without contacting the API', async () => {
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
    fixture.detectChanges();
    await fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('#recovery-email').getAttribute('aria-invalid'),
    ).toBe('true');
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address.');
    TestBed.inject(HttpTestingController).expectNone('/api/v1/auth/csrf');
    fixture.componentInstance.email.setValue('   ');
    expect(fixture.componentInstance.email.invalid).toBe(true);
  });

  it('sends normalized email with CSRF and shows only generic acceptance', async () => {
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
    fixture.componentInstance.email.setValue('  ANJALI@example.com  ');
    const action = fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type="submit"]').disabled).toBe(true);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    const request = http.expectOne('/api/v1/auth/password/forgot');
    expect(request.request.body).toEqual({ email: 'anjali@example.com' });
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('csrf');
    expect(request.request.withCredentials).toBe(true);
    request.flush(null, { status: 202, statusText: 'Accepted' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain(
      'If an eligible account exists',
    );
    expect(fixture.componentInstance.email.value).toBe('');
    expect(fixture.nativeElement.textContent).not.toContain('anjali@example.com');
  });

  it.each([
    [429, 'Too many requests'],
    [403, 'security session expired'],
    [503, 'temporarily unavailable'],
    [0, 'temporarily unavailable'],
  ])('shows safe feedback for HTTP %i', async (status, text) => {
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
    fixture.componentInstance.email.setValue('anjali@example.com');
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/password/forgot').flush(null, { status, statusText: 'Failure' });
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(text);
  });

  it('does not submit when CSRF bootstrap fails', async () => {
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
    fixture.componentInstance.email.setValue('anjali@example.com');
    const action = fixture.componentInstance.submit();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').error(new ProgressEvent('offline'));
    await action;
    http.expectNone('/api/v1/auth/password/forgot');
    expect(fixture.componentInstance.state()).toBe('unavailable');
  });
});
