import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/users/me', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
});

test('verification waits for a click and removes the token fragment', async ({ page }) => {
  let verificationRequests = 0;
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' }),
    }),
  );
  await page.route('**/api/v1/auth/email/verify', (route) => {
    verificationRequests += 1;
    expect(route.request().postDataJSON()).toEqual({ token: 'private-test-token' });
    return route.fulfill({ status: 204 });
  });

  await page.goto('/verify-email#token=private-test-token');
  await expect(page).toHaveURL(/\/verify-email$/);
  await expect(page.getByRole('button', { name: 'Verify email' })).toBeVisible();
  expect(verificationRequests).toBe(0);

  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByRole('status')).toContainText('Your email address is verified.');
  expect(verificationRequests).toBe(1);
  await page.getByRole('link', { name: 'Go to sign in' }).click();
  await expect(page).toHaveURL(/\/login$/);
});
