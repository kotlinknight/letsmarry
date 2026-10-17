import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from '@playwright/test';

const enabled = process.env['MMM_REAL_SERVICES'] === '1';
const mailpit = process.env['MMM_REAL_MAILPIT_URL'];
const postgres = process.env['MMM_REAL_POSTGRES_CONTAINER'];
const redis = process.env['MMM_REAL_REDIS_CONTAINER'];
const database = process.env['MMM_REAL_DB_NAME'];
const databaseUser = process.env['MMM_REAL_DB_USER'];

// Account links contain credentials. Keep them out of Playwright artifacts even on a failed retry.
test.use({ screenshot: 'off', trace: 'off', video: 'off' });

function containerCommand(container: string, ...args: string[]): string {
  const result = spawnSync('docker', ['exec', container, ...args], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error('The isolated service inspection failed.');
  return result.stdout.trim();
}

function sql(query: string): string {
  return containerCommand(
    postgres!,
    'psql',
    '-U',
    databaseUser!,
    '-d',
    database!,
    '-AtF',
    '|',
    '-c',
    query,
  );
}

function redisSessionId(cookieValue: string): string {
  const id = Buffer.from(cookieValue, 'base64').toString('utf8');
  if (!/^[0-9a-f-]{36}$/.test(id)) throw new Error('Unexpected session cookie format.');
  return id;
}

function sessionRedisState(cookieValue: string) {
  const id = redisSessionId(cookieValue);
  const key = `makemymarriage:session:sessions:${id}`;
  const command = (...args: string[]) => containerCommand(redis!, 'redis-cli', ...args);
  const interval = spawnSync('docker', [
    'exec',
    redis!,
    'redis-cli',
    '--raw',
    'HGET',
    key,
    'maxInactiveInterval',
  ]);
  if (interval.status !== 0 || interval.stdout.subarray(0, 4).toString('hex') !== 'aced0005') {
    throw new Error('Could not inspect the isolated Spring Session interval.');
  }
  return {
    id,
    type: command('TYPE', key),
    intervalSeconds: interval.stdout.readInt32BE(interval.stdout.length - 5),
    ttlSeconds: Number(command('TTL', key)),
    shadowExists: command('EXISTS', `makemymarriage:session:sessions:expires:${id}`) === '1',
  };
}

async function mailText(request: APIRequestContext, email: string, marker: string) {
  const inbox = await request.get(`${mailpit}/api/v1/messages`);
  if (!inbox.ok()) return null;
  const messages = (await inbox.json()) as {
    messages: { ID: string; To: { Address: string }[] }[];
  };
  for (const item of messages.messages) {
    if (!item.To.some((recipient) => recipient.Address === email)) continue;
    const detail = await request.get(`${mailpit}/api/v1/message/${item.ID}`);
    if (!detail.ok()) continue;
    const body = ((await detail.json()) as { Text: string }).Text;
    if (body.includes(marker)) return body;
  }
  return null;
}

async function waitForMail(request: APIRequestContext, email: string, marker: string) {
  let body: string | null = null;
  await expect
    .poll(
      async () => {
        body = await mailText(request, email, marker);
        return body !== null;
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  return body!;
}

function accountLink(message: string, route: 'verify-email' | 'reset-password'): string {
  const match = message.match(
    new RegExp(`http:\\/\\/127\\.0\\.0\\.1:4200\\/${route}#token=[A-Za-z0-9_-]{43}`),
  );
  if (!match) throw new Error(`The ${route} message did not contain the expected local link.`);
  return match[0];
}

async function openCredentialLink(page: Page, link: string): Promise<void> {
  // evaluate avoids rendering the credential in Playwright call titles or assertion messages.
  await page.goto('/foundation');
  await page.evaluate((target) => window.location.assign(target), link);
}

async function login(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  const response = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' && candidate.url().endsWith('/api/v1/auth/login'),
  );
  await page.getByRole('button', { name: 'Sign in' }).click();
  return response;
}

async function sessionCookie(context: BrowserContext) {
  return (await context.cookies()).find((cookie) => cookie.name === 'SESSION');
}

test('complete account lifecycle through Angular and real services', async ({
  page,
  context,
  browser,
  request,
}) => {
  test.skip(!enabled, 'Requires the explicitly enabled isolated real-service stack.');
  if (!mailpit || !postgres || !redis || !database || !databaseUser) {
    throw new Error('Isolated real-service test configuration is incomplete.');
  }

  const runId = randomUUID();
  const email = `real-1ec-${runId}@example.com`;
  const displayName = 'Anjali Lifecycle';
  const originalPassword = 'Original lifecycle phrase 123';
  const replacementPassword = 'Replacement lifecycle phrase 456';

  await page.goto('/register');
  await page.getByLabel('Display name').fill(displayName);
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(originalPassword);
  await page.getByLabel('Confirm password').fill(originalPassword);
  const registrationResponse = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' && candidate.url().endsWith('/api/v1/auth/register'),
  );
  await page.getByRole('button', { name: 'Create account' }).click();
  const registration = await registrationResponse;
  expect(registration.status()).toBe(201);
  expect(registration.request().headers()['x-csrf-token']).toBeTruthy();
  expect(registration.request().postDataJSON()).toEqual({
    displayName,
    email,
    password: originalPassword,
  });
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
  expect((await page.request.get('/api/v1/users/me')).status()).toBe(401);

  const registeredId = sql(
    `SELECT id FROM users WHERE email='${email}' AND display_name='${displayName}' AND email_verified_at IS NULL`,
  );
  expect(registeredId).toMatch(/^[0-9a-f-]{36}$/);
  const verificationMessage = await waitForMail(
    request,
    email,
    'Verify your Make My Marriage email address',
  );
  const verificationLink = accountLink(verificationMessage, 'verify-email');
  expect(
    sql(
      `SELECT status || '|' || (encrypted_payload IS NULL) FROM email_outbox WHERE recipient_email='${email}' AND template_code='VERIFY_EMAIL'`,
    ),
  ).toBe('SENT|true');

  await openCredentialLink(page, verificationLink);
  await expect(page).toHaveURL(/\/verify-email$/);
  await expect(page.getByRole('heading', { name: 'Verify your email' })).toBeVisible();
  expect(
    sql(
      `SELECT count(*) FROM email_verification_tokens WHERE user_id='${registeredId}' AND consumed_at IS NULL`,
    ),
  ).toBe('1');
  const verificationResponse = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      candidate.url().endsWith('/api/v1/auth/email/verify'),
  );
  await page.getByRole('button', { name: 'Verify email' }).click();
  expect((await verificationResponse).status()).toBe(204);
  await expect(page.getByRole('status')).toContainText('Your email address is verified.');
  expect((await page.request.get('/api/v1/users/me')).status()).toBe(401);

  const reusePage = await context.newPage();
  await openCredentialLink(reusePage, verificationLink);
  await expect(reusePage).toHaveURL(/\/verify-email$/);
  const verificationReuseResponse = reusePage.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      candidate.url().endsWith('/api/v1/auth/email/verify'),
  );
  await reusePage.getByRole('button', { name: 'Verify email' }).click();
  expect((await verificationReuseResponse).status()).toBe(400);
  await expect(reusePage.getByRole('alert')).toContainText('has already been used');
  await reusePage.close();

  const loginResponse = await login(page, email, originalPassword);
  expect(loginResponse.status()).toBe(200);
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByRole('heading', { name: 'Your planning space' })).toBeVisible();
  await expect(page.getByText(displayName, { exact: true })).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  const originalCookie = await sessionCookie(context);
  expect(originalCookie?.httpOnly).toBe(true);
  expect(originalCookie?.sameSite).toBe('Lax');
  const activeSession = sessionRedisState(originalCookie!.value);
  expect(activeSession.type).toBe('hash');
  expect(activeSession.intervalSeconds).toBe(1800);
  expect(activeSession.shadowExists).toBe(true);
  const principalIndex = `makemymarriage:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:${registeredId}`;
  expect(containerCommand(redis, 'redis-cli', 'SCARD', principalIndex)).toBe('1');
  const restored = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'GET' && candidate.url().endsWith('/api/v1/users/me'),
  );
  await page.reload();
  expect((await restored).status()).toBe(200);
  await expect(page.getByText(email)).toBeVisible();
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);

  const recoveryContext = await browser.newContext();
  try {
    const recoveryPage = await recoveryContext.newPage();
    await recoveryPage.goto('/forgot-password');
    await recoveryPage.getByLabel('Email address').fill(email);
    const forgotResponse = recoveryPage.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().endsWith('/api/v1/auth/password/forgot'),
    );
    await recoveryPage.getByRole('button', { name: 'Send reset link' }).click();
    expect((await forgotResponse).status()).toBe(202);
    await expect(recoveryPage.getByRole('status')).toContainText('If an eligible account exists');

    const resetMessage = await waitForMail(request, email, 'Reset your Make My Marriage password');
    const resetLink = accountLink(resetMessage, 'reset-password');
    expect(
      sql(
        `SELECT count(*) FROM password_reset_tokens WHERE user_id='${registeredId}' AND consumed_at IS NULL`,
      ),
    ).toBe('1');

    const resetPage = await recoveryContext.newPage();
    await openCredentialLink(resetPage, resetLink);
    await expect(resetPage).toHaveURL(/\/reset-password$/);
    expect(
      sql(
        `SELECT count(*) FROM password_reset_tokens WHERE user_id='${registeredId}' AND consumed_at IS NULL`,
      ),
    ).toBe('1');
    await resetPage.getByLabel('New password', { exact: true }).fill(replacementPassword);
    await resetPage.getByLabel('Confirm new password').fill(replacementPassword);
    const resetResponse = resetPage.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().endsWith('/api/v1/auth/password/reset'),
    );
    await resetPage.getByRole('button', { name: 'Change password' }).click();
    expect((await resetResponse).status()).toBe(204);
    await expect(resetPage.getByRole('status')).toContainText('Your password has been changed');
    expect(sql(`SELECT auth_version FROM users WHERE id='${registeredId}'`)).toBe('1');
    expect((await page.request.get('/api/v1/users/me')).status()).toBe(401);
    expect(containerCommand(redis, 'redis-cli', 'SCARD', principalIndex)).toBe('0');
    await page.reload();
    await expect(page).toHaveURL(/\/login\?/);
    await expect(page.getByText(email)).toHaveCount(0);

    const resetReusePage = await recoveryContext.newPage();
    await openCredentialLink(resetReusePage, resetLink);
    await resetReusePage.getByLabel('New password', { exact: true }).fill(replacementPassword);
    await resetReusePage.getByLabel('Confirm new password').fill(replacementPassword);
    const resetReuseResponse = resetReusePage.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().endsWith('/api/v1/auth/password/reset'),
    );
    await resetReusePage.getByRole('button', { name: 'Change password' }).click();
    expect((await resetReuseResponse).status()).toBe(400);
    await expect(resetReusePage.getByRole('alert')).toContainText('has already been used');
  } finally {
    await recoveryContext.close();
  }

  const oldPasswordResponse = await login(page, email, originalPassword);
  expect(oldPasswordResponse.status()).toBe(401);
  await expect(page.getByRole('alert')).toContainText('could not sign you in');
  const newPasswordResponse = await login(page, email, replacementPassword);
  expect(newPasswordResponse.status()).toBe(200);
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByText(email)).toBeVisible();
  await waitForMail(request, email, 'Your Make My Marriage password was changed');
  expect(
    sql(
      `SELECT string_agg(template_code || ':' || status || ':' || (encrypted_payload IS NULL), ',' ORDER BY template_code) FROM email_outbox WHERE recipient_email='${email}'`,
    ),
  ).toBe('PASSWORD_CHANGED:SENT:true,PASSWORD_RESET:SENT:true,VERIFY_EMAIL:SENT:true');

  const finalCookie = await sessionCookie(context);
  expect(finalCookie).toBeTruthy();
  const logoutResponse = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' && candidate.url().endsWith('/api/v1/auth/logout'),
  );
  await page.getByRole('button', { name: 'Sign out' }).click();
  expect((await logoutResponse).status()).toBe(204);
  await expect(page).toHaveURL(/\/login$/);
  expect(await sessionCookie(context)).toBeUndefined();
  const expiredSession = sessionRedisState(finalCookie!.value);
  expect(expiredSession.type).toBe('hash');
  expect(expiredSession.intervalSeconds).toBe(0);
  expect(expiredSession.ttlSeconds).toBeGreaterThan(0);
  expect(expiredSession.ttlSeconds).toBeLessThanOrEqual(300);
  expect(expiredSession.shadowExists).toBe(false);
  const replay = await request.get('http://127.0.0.1:8080/api/v1/users/me', {
    headers: { Cookie: `SESSION=${finalCookie!.value}` },
  });
  expect(replay.status()).toBe(401);
  await page.goto('/account');
  await expect(page).toHaveURL(/\/login\?next=%2Faccount&reason=session$/);
});
