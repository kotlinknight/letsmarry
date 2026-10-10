import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';

describe('application routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes)],
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
});
