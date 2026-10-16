import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/users/me', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
});

test('desktop foundation supports routing and persistent themes', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/foundation$/);
  await expect(page.getByRole('heading', { name: 'Weddings, but make it yours.' })).toBeVisible();

  const documentRoot = page.locator('html');
  const initialTheme = await documentRoot.getAttribute('data-theme');
  const themeButton = page.getByRole('button', { name: /Switch to (dark|light) theme/ });

  await themeButton.click();
  const selectedTheme = initialTheme === 'dark' ? 'light' : 'dark';
  await expect(documentRoot).toHaveAttribute('data-theme', selectedTheme);

  await page.reload();
  await expect(documentRoot).toHaveAttribute('data-theme', selectedTheme);

  await page.getByRole('link', { name: 'Design tokens', exact: true }).click();
  await expect(page).toHaveURL(/\/design-tokens$/);
  await expect(
    page.getByRole('heading', { name: 'Tokens that feel joyful, clear and distinctly ours.' }),
  ).toBeVisible();
});

test('mobile shell exposes keyboard and drawer navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/foundation');

  await page.locator('body').press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();

  const menuButton = page.getByRole('button', { name: 'Open navigation' });
  await page.keyboard.press('Tab');
  await expect(menuButton).toBeFocused();
  await expect(menuButton).toHaveAttribute('aria-expanded', 'false');
  await menuButton.click();
  await expect(menuButton).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#primary-navigation')).toBeVisible();

  await page.getByRole('link', { name: 'Design tokens', exact: true }).click();
  await expect(page).toHaveURL(/\/design-tokens$/);
  await expect(menuButton).toHaveAttribute('aria-expanded', 'false');
});

test('unknown routes recover to the foundation', async ({ page }) => {
  await page.goto('/not-a-real-route');

  await expect(page).toHaveURL(/\/foundation$/);
  await expect(page.getByText('A foundation before features')).toBeVisible();
});
