import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';

describe('application routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideRouter(routes)],
    });
  });

  it('renders the foundation page at the default destination', async () => {
    const harness = await RouterTestingHarness.create('/foundation');

    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Weddings, but make it yours.',
    );
  });

  it('renders the design-token reference route', async () => {
    const harness = await RouterTestingHarness.create('/design-tokens');

    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Tokens that feel joyful',
    );
  });

  it('renders the email-verification route without automatically submitting', async () => {
    const harness = await RouterTestingHarness.create('/verify-email');

    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Verify your email',
    );
  });

  it('renders the registration route without submitting automatically', async () => {
    const harness = await RouterTestingHarness.create('/register');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(
      'Create your account',
    );
    expect(harness.routeNativeElement?.textContent).toContain('Create account');
  });
});
