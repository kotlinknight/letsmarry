import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { AccountComponent } from './account.component';

const user = {
  id: '0199d4ee-26ad-7000-9000-000000000001',
  displayName: 'Anjali',
  email: 'anjali@example.com',
  emailVerified: true,
  status: 'ACTIVE',
};

describe('AccountComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('keeps the page and reports uncertainty when logout fails but /me still succeeds', async () => {
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(user);
    await restore;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    const fixture = TestBed.createComponent(AccountComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(user.email);

    const action = fixture.componentInstance.logout();
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    http.expectOne('/api/v1/auth/logout').error(new ProgressEvent('offline'));
    await Promise.resolve();
    http.expectOne('/api/v1/users/me').flush(user);
    await action;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'You are still signed in',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('hides account data when the server cannot confirm the session', async () => {
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    const restore = auth.refresh();
    http.expectOne('/api/v1/users/me').flush(user);
    await restore;
    const fixture = TestBed.createComponent(AccountComponent);
    fixture.detectChanges();

    const check = fixture.componentInstance.checkSession();
    http.expectOne('/api/v1/users/me').error(new ProgressEvent('offline'));
    await check;
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain(user.email);
    expect(fixture.nativeElement.textContent).toContain('account details are hidden');
  });
});
