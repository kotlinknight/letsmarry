import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { expect, test } from '@playwright/test';

const enabled = process.env['MMM_REAL_SERVICES'] === '1';
const postgres = process.env['MMM_REAL_POSTGRES_CONTAINER'];
const redis = process.env['MMM_REAL_REDIS_CONTAINER'];
const database = process.env['MMM_REAL_DB_NAME'];
const databaseUser = process.env['MMM_REAL_DB_USER'];
const mailpit = process.env['MMM_REAL_MAILPIT_URL'];

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

function sessionRedisState(id: string) {
  // Spring Session's cookie serializer Base64-encodes the Redis session ID.
  const redisId = Buffer.from(id, 'base64').toString('utf8');
  if (!/^[0-9a-f-]{36}$/.test(redisId)) throw new Error('Unexpected session cookie format.');
  const key = `makemymarriage:session:sessions:${redisId}`;
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
    type: command('TYPE', key),
    intervalSeconds: interval.stdout.readInt32BE(interval.stdout.length - 5),
    ttlSeconds: Number(command('TTL', key)),
    shadowExists: command('EXISTS', `makemymarriage:session:sessions:expires:${redisId}`) === '1',
    securityContextStored: command('HEXISTS', key, 'sessionAttr:SPRING_SECURITY_CONTEXT') === '1',
  };
}

test('real registration, Mailpit verification, login, restoration, and logout', async ({
  page,
  request,
  context,
}) => {
  test.skip(!enabled, 'Requires the explicitly enabled isolated real-service stack.');
  if (!postgres || !redis || !database || !databaseUser || !mailpit) {
    throw new Error('The isolated real-service test configuration is incomplete.');
  }

  const email = `real-1cd-${randomUUID()}@example.com`;
  const password = 'OnlyForLocalIntegration123!';
  const csrfResponse = await page.request.get('/api/v1/auth/csrf');
  expect(csrfResponse.status()).toBe(200);
  expect(csrfResponse.headers()['cache-control']).toContain('no-store');
  const csrf = (await csrfResponse.json()) as { token: string; headerName: string };
  expect(csrf.headerName).toBe('X-CSRF-TOKEN');
  const anonymousCookie = (await context.cookies()).find((cookie) => cookie.name === 'SESSION');
  expect(anonymousCookie?.httpOnly).toBe(true);
  expect(anonymousCookie?.sameSite).toBe('Lax');
  expect(anonymousCookie?.secure).toBe(false); // The local HTTP profile only.

  const registration = await page.request.post('/api/v1/auth/register', {
    data: { displayName: 'Anjali Test', email, password },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(registration.status()).toBe(201);
  const registered = (await registration.json()) as Record<string, unknown>;
  expect(registered).toMatchObject({ displayName: 'Anjali Test', email, emailVerified: false });
  expect(registered['id']).toEqual(expect.any(String));
  expect(registered).not.toHaveProperty('password');
  expect(registered).not.toHaveProperty('token');

  const id = registered['id'] as string;
  expect(id).toMatch(/^[0-9a-f-]{36}$/);
  const persisted = sql(`
    SELECT
      (SELECT count(*) FROM users WHERE id = '${id}' AND email = '${email}' AND email_verified_at IS NULL),
      (SELECT count(*) FROM email_verification_tokens WHERE user_id = '${id}' AND octet_length(token_hash) = 32 AND consumed_at IS NULL),
      (SELECT count(*) FROM email_outbox WHERE recipient_email = '${email}' AND template_code = 'VERIFY_EMAIL'),
      (SELECT count(*) FROM email_outbox WHERE recipient_email = '${email}' AND status = 'PENDING' AND encrypted_payload IS NOT NULL);
  `).split('|');
  expect(persisted.slice(0, 3)).toEqual(['1', '1', '1']);
  // A fast worker can deliver before this inspection; its terminal row then has a purged payload.
  if (persisted[3] !== '1') {
    expect(sql(`SELECT status FROM email_outbox WHERE recipient_email = '${email}'`)).toBe('SENT');
  }

  const unverifiedLogin = await page.request.post('/api/v1/auth/login', {
    data: { email, password },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(unverifiedLogin.status()).toBe(401);

  let message = '';
  await expect
    .poll(
      async () => {
        const response = await request.get(`${mailpit}/api/v1/messages`);
        if (!response.ok()) return false;
        const inbox = (await response.json()) as {
          messages: { ID: string; To: { Address: string }[] }[];
        };
        const delivered = inbox.messages.find((item) =>
          item.To.some((recipient) => recipient.Address === email),
        );
        if (!delivered) return false;
        const details = await request.get(`${mailpit}/api/v1/message/${delivered.ID}`);
        if (!details.ok()) return false;
        message = ((await details.json()) as { Text: string }).Text;
        return message.includes('Verify your Make My Marriage email address');
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  expect(message).toContain('24 hours');
  const link = message.match(/http:\/\/127\.0\.0\.1:4200\/verify-email#token=([A-Za-z0-9_-]+)/);
  if (!link) throw new Error('The Mailpit message did not contain a verification link.');
  const token = link[1];
  const tokenHash = createHash('sha256').update(Buffer.from(token, 'base64url')).digest('hex');
  expect(
    sql(`SELECT encode(token_hash, 'hex') FROM email_verification_tokens WHERE user_id = '${id}'`),
  ).toBe(tokenHash);
  expect(sql(`SELECT status FROM email_outbox WHERE recipient_email = '${email}'`)).toBe('SENT');
  expect(
    sql(
      `SELECT count(*) FROM email_outbox WHERE recipient_email = '${email}' AND encrypted_payload IS NULL`,
    ),
  ).toBe('1');

  await page.goto(link[0]);
  await expect(page.getByRole('heading', { name: 'Verify your email' })).toBeVisible();
  await expect(page).toHaveURL(/\/verify-email$/);
  await expect(page.getByRole('button', { name: 'Verify email' })).toBeVisible();
  expect(
    sql(
      `SELECT count(*) FROM email_verification_tokens WHERE user_id = '${id}' AND consumed_at IS NULL`,
    ),
  ).toBe('1');
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByRole('status')).toContainText('Your email address is verified.');
  expect(
    sql(
      `SELECT count(*) FROM users WHERE id = '${id}' AND status = 'ACTIVE' AND email_verified_at IS NOT NULL`,
    ),
  ).toBe('1');
  expect(
    sql(
      `SELECT count(*) FROM email_verification_tokens WHERE user_id = '${id}' AND consumed_at IS NOT NULL`,
    ),
  ).toBe('1');

  const freshCsrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as {
    token: string;
    headerName: string;
  };
  const reuse = await page.request.post('/api/v1/auth/email/verify', {
    data: { token },
    headers: { [freshCsrf.headerName]: freshCsrf.token },
  });
  expect(reuse.status()).toBe(400);

  await page.goto('/login');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByRole('heading', { name: 'Your planning space' })).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  const authenticatedCookie = (await context.cookies()).find((cookie) => cookie.name === 'SESSION');
  expect(authenticatedCookie?.httpOnly).toBe(true);
  expect(authenticatedCookie?.value).not.toBe(anonymousCookie?.value);
  const activeSession = sessionRedisState(authenticatedCookie!.value);
  expect(activeSession.type).toBe('hash');
  expect(activeSession.intervalSeconds).toBe(1800);
  expect(activeSession.shadowExists).toBe(true);
  expect(activeSession.securityContextStored).toBe(true);
  const principalIndex = `makemymarriage:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:${id}`;
  expect(containerCommand(redis!, 'redis-cli', 'SCARD', principalIndex)).toBe('1');
  // Session fixation protection removes the pre-login anonymous session.
  expect(
    containerCommand(
      redis!,
      'redis-cli',
      'EXISTS',
      `makemymarriage:session:sessions:${Buffer.from(anonymousCookie!.value, 'base64').toString('utf8')}`,
    ),
  ).toBe('0');
  const me = await page.request.get('/api/v1/users/me');
  expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({ id, email, emailVerified: true, status: 'ACTIVE' });
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);

  await page.reload();
  await expect(page.getByText(email)).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  const expiredSession = sessionRedisState(authenticatedCookie!.value);
  // RedisIndexedSessionRepository retains a short-lived hash for destruction events.
  // It is expired server-side even while the hash (including serialized attributes) exists.
  expect(expiredSession.type).toBe('hash');
  expect(expiredSession.intervalSeconds).toBe(0);
  expect(expiredSession.ttlSeconds).toBeGreaterThan(0);
  expect(expiredSession.ttlSeconds).toBeLessThanOrEqual(300);
  expect(expiredSession.shadowExists).toBe(false);
  expect(expiredSession.securityContextStored).toBe(true);
  expect(containerCommand(redis!, 'redis-cli', 'SCARD', principalIndex)).toBe('0');
  expect((await context.cookies()).find((cookie) => cookie.name === 'SESSION')).toBeUndefined();
  const oldSession = await request.get('http://127.0.0.1:8080/api/v1/users/me', {
    headers: { Cookie: `SESSION=${authenticatedCookie!.value}` },
  });
  expect(oldSession.status()).toBe(401);
  await page.goto('/account');
  await expect(page).toHaveURL(/\/login\?next=%2Faccount&reason=session$/);
});
