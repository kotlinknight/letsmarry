import { expect, test, type Page } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/users/me', (route) => route.fulfill({ status: 401, body: '{}' }));
});

async function fillRegistration(page: Page) {
  await page.getByLabel('Display name').fill('  Anjali  ');
  await page.getByLabel('Email address').fill('  ANJALI@EXAMPLE.COM  ');
  await page.getByLabel('Password', { exact: true }).fill('A memorable phrase 123');
  await page.getByLabel('Confirm password').fill('A memorable phrase 123');
}

test('registration validates, posts with CSRF, and shows email guidance only after 201', async ({
  page,
}) => {
  let requests = 0;
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'csrf-fixture', headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.route('**/api/v1/auth/register', (route) => {
    requests++;
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-fixture');
    expect(route.request().postDataJSON()).toEqual({
      displayName: 'Anjali',
      email: 'anjali@example.com',
      password: 'A memorable phrase 123',
    });
    return route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '0199d4ee-26ad-7000-9000-000000000001',
        displayName: 'Anjali',
        email: 'anjali@example.com',
        emailVerified: false,
      }),
    });
  });
  await page.goto('/register');
  const submit = page.getByRole('button', { name: 'Create account' });
  await expect(submit).toBeDisabled();
  await page.getByLabel('Display name').fill('Anjali');
  await page.getByLabel('Display name').blur();
  await page.getByLabel('Email address').fill('bad-address');
  await page.getByLabel('Email address').blur();
  await expect(
    page.getByText('Enter a valid email address of no more than 320 characters.'),
  ).toBeVisible();
  await fillRegistration(page);
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  await expect(page.getByRole('status')).toContainText('Mailpit');
  expect(requests).toBe(1);
  await page.getByRole('link', { name: 'Request a new verification link' }).click();
  await expect(page).toHaveURL(/\/verify-email$/);
});

test('duplicate email gives existing-account navigation', async ({ page }) => {
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'csrf-fixture', headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.route('**/api/v1/auth/register', (route) =>
    route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({ code: 'EMAIL_ALREADY_REGISTERED' }),
    }),
  );
  await page.goto('/register');
  await fillRegistration(page);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('alert')).toContainText(
    'An account may already use this email address.',
  );
  await expect(page.getByRole('link', { name: 'recover your password' })).toBeVisible();
  await page.getByRole('link', { name: 'request a verification link' }).click();
  await expect(page).toHaveURL(/\/verify-email$/);
});

test('CSRF rejection waits for a deliberate retry and obtains another token', async ({ page }) => {
  let csrfCalls = 0;
  let registerCalls = 0;
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: `csrf-${++csrfCalls}`, headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.route('**/api/v1/auth/register', (route) => {
    registerCalls++;
    expect(route.request().headers()['x-csrf-token']).toBe(`csrf-${registerCalls}`);
    return route.fulfill(
      registerCalls === 1
        ? {
            status: 403,
            contentType: 'application/problem+json',
            body: JSON.stringify({ code: 'CSRF_INVALID' }),
          }
        : {
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              id: '0199d4ee-26ad-7000-9000-000000000001',
              displayName: 'Anjali',
              email: 'anjali@example.com',
              emailVerified: false,
            }),
          },
    );
  });
  await page.goto('/register');
  await fillRegistration(page);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('alert')).toContainText('Your security session expired.');
  expect(registerCalls).toBe(1);
  expect(csrfCalls).toBe(1);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  expect(registerCalls).toBe(2);
  expect(csrfCalls).toBe(2);
});

test('login and registration link to each other on mobile dark theme', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/login');
  await page.getByRole('link', { name: 'Create an account' }).click();
  await expect(page).toHaveURL(/\/register$/);
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.getByLabel('Password', { exact: true })).toHaveCSS(
    'color',
    'rgb(255, 255, 255)',
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    ),
  ).toBe(false);
  await page
    .getByRole('region', { name: 'Create your account' })
    .getByRole('link', { name: 'Sign in' })
    .click();
  await expect(page).toHaveURL(/\/login$/);
});
