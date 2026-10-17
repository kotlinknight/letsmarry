import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { VerifyEmailComponent } from './verify-email.component';

describe('VerifyEmailComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    window.history.replaceState(null, '', '/');
  });

  it('removes the fragment and waits for an explicit confirmation', async () => {
    window.history.replaceState(null, '', '/verify-email#token=private-token');
    const fixture = TestBed.createComponent(VerifyEmailComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);

    expect(window.location.hash).toBe('');
    expect(fixture.componentInstance.state()).toBe('ready');
    http.expectNone('/api/v1/auth/csrf');

    const action = fixture.componentInstance.verify();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    const verification = http.expectOne('/api/v1/auth/email/verify');
    expect(verification.request.body).toEqual({ token: 'private-token' });
    expect(verification.request.headers.get('X-CSRF-TOKEN')).toBe('csrf');
    expect(verification.request.withCredentials).toBe(true);
    verification.flush(null, { status: 204, statusText: 'No Content' });
    await action;
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('success');
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('requests a replacement with a generic success state', async () => {
    const fixture = TestBed.createComponent(VerifyEmailComponent);
    fixture.detectChanges();
    fixture.componentInstance.email.setValue('person@example.com');
    const action = fixture.componentInstance.resend();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    const resend = http.expectOne('/api/v1/auth/email/verification-requests');
    expect(resend.request.body).toEqual({ email: 'person@example.com' });
    resend.flush(null, { status: 202, statusText: 'Accepted' });
    await action;
    expect(fixture.componentInstance.resendState()).toBe('sent');
  });
});
