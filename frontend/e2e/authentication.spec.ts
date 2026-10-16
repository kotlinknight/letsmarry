import { expect, test, type Page } from '@playwright/test';

const user = {
  id: '0199d4ee-26ad-7000-9000-000000000001',
  displayName: 'Anjali',
  email: 'anjali@example.com',
  emailVerified: true,
  status: 'ACTIVE',
};

async function mockAuthentication(page: Page) {
  let authenticated = false;
  let csrfCalls = 0;
  await page.route('**/api/v1/auth/csrf', (route) => {
    csrfCalls += 1;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: `csrf-${csrfCalls}`, headerName: 'X-CSRF-TOKEN' }),
    });
  });
  await page.route('**/api/v1/auth/login', (route) => {
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-1');
    expect(route.request().postDataJSON()).toEqual({
      email: user.email,
      password: 'test-password',
    });
    authenticated = true;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ user }),
    });
  });
  await page.route('**/api/v1/users/me', (route) =>
    route.fulfill({
      status: authenticated ? 200 : 401,
      contentType: 'application/json',
      body: JSON.stringify(authenticated ? user : { code: 'AUTHENTICATION_REQUIRED' }),
    }),
  );
  await page.route('**/api/v1/auth/logout', (route) => {
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-2');
    authenticated = false;
    return route.fulfill({ status: 204 });
  });
  return { expire: () => (authenticated = false), csrfCalls: () => csrfCalls };
}

test('protected navigation, login, refresh, logout, and expired session', async ({ page }) => {
  const fixture = await mockAuthentication(page);
  await page.goto('/account');
  await expect(page).toHaveURL(/\/login\?next=%2Faccount&reason=session$/);
  await expect(page.getByText('Sign in to continue.')).toBeVisible();

  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByRole('heading', { name: 'Your planning space' })).toBeVisible();
  await expect(page.getByText(user.email)).toBeVisible();
  await expect(page.getByText('Verified')).toBeVisible();

  await page.reload();
  await expect(page.getByText(user.email)).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(fixture.csrfCalls()).toBe(2);

  await page.goto('/account');
  await expect(page).toHaveURL(/\/login\?next=%2Faccount&reason=session$/);
  await expect(page.getByText(user.email)).toHaveCount(0);
});

test('active account view clears stale details when the session expires', async ({ page }) => {
  const fixture = await mockAuthentication(page);
  await page.goto('/login');
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/account$/);
  fixture.expire();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page).toHaveURL(/\/login\?/);
  expect(new URL(page.url()).searchParams.get('reason')).toBe('session');
  expect(new URL(page.url()).searchParams.get('next')).toBe('/account');
  await expect(page.getByText(user.email)).toHaveCount(0);
});

test('mobile login remains usable in both themes with visible validation', async ({ page }) => {
  await page.route('**/api/v1/users/me', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Enter a valid email address.')).toBeVisible();
  await expect(page.getByText('Enter your password.')).toBeVisible();
  await page.getByRole('button', { name: 'Show password' }).click();
  await expect(page.getByLabel('Password', { exact: true })).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
  expect(overflow).toBe(false);
});
