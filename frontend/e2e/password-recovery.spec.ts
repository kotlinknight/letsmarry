import { expect, test } from '@playwright/test';

const token = 'a'.repeat(43);

test('forgot and reset pages keep account feedback generic and strip link fragments', async ({
  page,
}) => {
  await page.route('**/api/v1/users/me', (route) => route.fulfill({ status: 401, body: '{}' }));
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.route('**/api/v1/auth/password/forgot', (route) => {
    expect(route.request().headers()['x-csrf-token']).toBe('csrf');
    return route.fulfill({ status: 202 });
  });
  await page.goto('/login');
  await page.getByRole('link', { name: 'Forgot password?' }).click();
  await expect(page).toHaveURL(/\/forgot-password$/);
  await page.getByLabel('Email address').fill('user@example.com');
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByRole('status')).toContainText('If an eligible account exists');
  await page.goto(`/reset-password#token=${token}`);
  await expect(page).toHaveURL(/\/reset-password$/);
  await expect(page.getByRole('button', { name: 'Change password' })).toBeVisible();
  await page.getByLabel('New password', { exact: true }).fill('A memorable phrase 123');
  await page.getByLabel('Confirm new password').fill('A memorable phrase 123');
  await page.route('**/api/v1/auth/password/reset', (route) => {
    expect(route.request().postDataJSON()).toEqual({
      token,
      newPassword: 'A memorable phrase 123',
    });
    return route.fulfill({ status: 204 });
  });
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.getByRole('status')).toContainText('Your password has been changed');
  await expect(page.getByRole('link', { name: 'Go to sign in' })).toBeVisible();
  await page.evaluate((value) => {
    window.location.hash = `token=${value}`;
  }, token);
  await expect.poll(async () => page.evaluate(() => window.location.hash === '')).toBe(true);
  await expect(page.evaluate(() => [localStorage.length, sessionStorage.length])).resolves.toEqual([
    0, 0,
  ]);
});

test('recovery is usable on mobile in both themes and reports safe failures', async ({ page }) => {
  await page.route('**/api/v1/users/me', (route) => route.fulfill({ status: 401, body: '{}' }));
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/forgot-password');
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByText('Enter a valid email address.')).toBeVisible();
  await page.getByLabel('Email address').fill('user@example.com');
  await page.route('**/api/v1/auth/password/forgot', (route) => route.fulfill({ status: 429 }));
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByRole('alert')).toContainText('Too many requests');
  await page.route('**/api/v1/auth/password/forgot', (route) => route.abort('failed'));
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByRole('alert')).toContainText('temporarily unavailable');
  await page.goto(`/reset-password#token=${token}`);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page).toHaveURL(/\/reset-password$/);
  await page.getByLabel('New password', { exact: true }).fill('A memorable phrase 123');
  await page.getByLabel('Confirm new password').fill('A memorable phrase 123');
  await page.route('**/api/v1/auth/password/reset', (route) =>
    route.fulfill({
      status: 400,
      contentType: 'application/problem+json',
      body: JSON.stringify({ code: 'PASSWORD_RESET_TOKEN_INVALID' }),
    }),
  );
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.getByRole('alert')).toContainText('invalid, expired, or has already been used');
  await expect(page.getByRole('link', { name: 'Request another reset link' })).toBeVisible();
});
