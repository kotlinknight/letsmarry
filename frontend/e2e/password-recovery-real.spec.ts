import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { request as httpRequest } from 'node:http';
import { expect, test } from '@playwright/test';

const enabled = process.env['MMM_REAL_SERVICES'] === '1';
const mailpit = process.env['MMM_REAL_MAILPIT_URL'];
const postgres = process.env['MMM_REAL_POSTGRES_CONTAINER'];
const redis = process.env['MMM_REAL_REDIS_CONTAINER'];
const database = process.env['MMM_REAL_DB_NAME'];
const databaseUser = process.env['MMM_REAL_DB_USER'];

function sql(query: string): string {
  const result = spawnSync(
    'docker',
    ['exec', postgres!, 'psql', '-U', databaseUser!, '-d', database!, '-At', '-c', query],
    { encoding: 'utf8' },
  );
  if (result.status !== 0) throw new Error('Isolated PostgreSQL inspection failed.');
  return result.stdout.trim();
}

// Give the destructive direct-IP limiter check its own real loopback source IP.
// This exercises request.getRemoteAddr(), without trusting a spoofable forwarding header
// or consuming the browser journeys' 127.0.0.1 limiter window on repeated runs.
async function fromIsolatedLoopback(
  sourceAddress: string,
  method: string,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<{ status: number; headers: import('node:http').IncomingHttpHeaders; body: string }> {
  return new Promise((resolve, reject) => {
    const outgoing = body === undefined ? undefined : JSON.stringify(body);
    const call = httpRequest(
      {
        hostname: '127.0.0.1',
        port: 8080,
        path,
        method,
        localAddress: sourceAddress,
        headers: {
          ...headers,
          ...(outgoing === undefined
            ? {}
            : {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(outgoing),
              }),
        },
      },
      (response) => {
        const chunks: Buffer[] = [];
        response.on('data', (chunk: Buffer) => chunks.push(chunk));
        response.on('end', () =>
          resolve({
            status: response.statusCode ?? 0,
            headers: response.headers,
            body: Buffer.concat(chunks).toString('utf8'),
          }),
        );
      },
    );
    call.on('error', reject);
    call.end(outgoing);
  });
}

async function mailText(
  request: import('@playwright/test').APIRequestContext,
  email: string,
  marker: string,
) {
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

test('real browser recovery revokes old sessions and permits only the new password', async ({
  page,
  request,
  context,
  browser,
}) => {
  test.skip(!enabled, 'Requires the explicitly enabled isolated real-service stack.');
  if (!mailpit || !postgres || !redis || !database || !databaseUser) {
    throw new Error('Isolated real-service test configuration is incomplete.');
  }

  const email = `real-1dd-${randomUUID()}@example.com`;
  const original = 'Original local phrase 123';
  const replacement = 'Replacement local phrase 456';
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as {
    token: string;
    headerName: string;
  };
  const registration = await page.request.post('/api/v1/auth/register', {
    data: { displayName: 'Anjali Test', email, password: original },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(registration.status()).toBe(201);
  const id = ((await registration.json()) as { id: string }).id;
  expect(sql(`SELECT count(*) FROM users WHERE id='${id}' AND email_verified_at IS NULL`)).toBe(
    '1',
  );

  let verificationMail: string | null = null;
  await expect
    .poll(
      async () => {
        verificationMail = await mailText(
          request,
          email,
          'Verify your Make My Marriage email address',
        );
        return verificationMail !== null;
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  const verificationLink = verificationMail!.match(
    /http:\/\/127\.0\.0\.1:4200\/verify-email#token=[A-Za-z0-9_-]+/,
  )?.[0];
  if (!verificationLink) throw new Error('Verification link was not delivered.');
  await page.goto(verificationLink);
  await expect(page).toHaveURL(/\/verify-email$/);
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByRole('status')).toContainText('verified');

  await page.goto('/login');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(original);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/account$/);
  const oldCookie = (await context.cookies()).find((cookie) => cookie.name === 'SESSION')?.value;
  expect(oldCookie).toBeTruthy();
  expect((await page.request.get('/api/v1/users/me')).status()).toBe(200);

  const secondContext = await browser.newContext();
  try {
    const secondPage = await secondContext.newPage();
    await secondPage.goto('/login');
    await secondPage.getByLabel('Email address').fill(email);
    await secondPage.getByLabel('Password', { exact: true }).fill(original);
    await secondPage.getByRole('button', { name: 'Sign in' }).click();
    await expect(secondPage).toHaveURL(/\/account$/);
    const secondCookie = (await secondContext.cookies()).find(
      (cookie) => cookie.name === 'SESSION',
    )?.value;
    expect(secondCookie).toBeTruthy();

    await page.goto('/forgot-password');
    await page.getByLabel('Email address').fill(email);
    await page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(page.getByRole('status')).toContainText('If an eligible account exists');

    let resetMail: string | null = null;
    await expect
      .poll(
        async () => {
          resetMail = await mailText(request, email, 'Reset your Make My Marriage password');
          return resetMail !== null;
        },
        { timeout: 45_000 },
      )
      .toBe(true);
    const link = resetMail!.match(
      /http:\/\/127\.0\.0\.1:4200\/reset-password#token=([A-Za-z0-9_-]+)/,
    );
    if (!link) throw new Error('Reset link was not delivered.');
    expect(
      sql(
        `SELECT count(*) FROM password_reset_tokens WHERE user_id='${id}' AND consumed_at IS NULL`,
      ),
    ).toBe('1');

    const resetPage = await context.newPage();
    await resetPage.goto(link[0]);
    await expect.poll(async () => resetPage.evaluate(() => window.location.hash === '')).toBe(true);
    await resetPage.getByLabel('New password', { exact: true }).fill(replacement);
    await resetPage.getByLabel('Confirm new password').fill(replacement);
    await resetPage.getByRole('button', { name: 'Change password' }).click();
    await expect(resetPage.getByRole('status')).toContainText('Your password has been changed');
    await expect(resetPage.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
    expect(
      sql(
        `SELECT count(*) FROM password_reset_tokens WHERE user_id='${id}' AND consumed_at IS NOT NULL`,
      ),
    ).toBe('1');
    expect(sql(`SELECT auth_version FROM users WHERE id='${id}'`)).toBe('1');
    expect(
      (
        await request.get('http://127.0.0.1:8080/api/v1/users/me', {
          headers: { Cookie: `SESSION=${oldCookie}` },
        })
      ).status(),
    ).toBe(401);
    expect(
      (
        await request.get('http://127.0.0.1:8080/api/v1/users/me', {
          headers: { Cookie: `SESSION=${secondCookie}` },
        })
      ).status(),
    ).toBe(401);
    await secondPage.reload();
    await expect(secondPage).toHaveURL(/\/login\?/);
    await expect(secondPage.getByText(email)).toHaveCount(0);

    const reusePage = await context.newPage();
    await reusePage.goto(link[0]);
    await expect.poll(async () => reusePage.evaluate(() => window.location.hash === '')).toBe(true);
    await reusePage.getByLabel('New password', { exact: true }).fill(replacement);
    await reusePage.getByLabel('Confirm new password').fill(replacement);
    await reusePage.getByRole('button', { name: 'Change password' }).click();
    await expect(reusePage.getByRole('alert')).toContainText('has already been used');

    await page.goto('/login');
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(original);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText('could not sign you in');
    await page.getByLabel('Password', { exact: true }).fill(replacement);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByText(email)).toBeVisible();

    await expect
      .poll(
        async () =>
          (await mailText(request, email, 'Your Make My Marriage password was changed')) !== null,
        { timeout: 45_000 },
      )
      .toBe(true);
    expect(await resetPage.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([
      0, 0,
    ]);
  } finally {
    await secondContext.close();
  }
});

test('real recovery rejects missing CSRF, expired tokens, and direct-IP excess', async ({
  page,
  request,
}) => {
  test.skip(!enabled, 'Requires the explicitly enabled isolated real-service stack.');
  if (!mailpit || !postgres || !redis || !database || !databaseUser) {
    throw new Error('Isolated real-service test configuration is incomplete.');
  }
  const email = `real-1dd-negative-${randomUUID()}@example.com`;
  const password = 'Only for isolated testing 123';
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as {
    token: string;
    headerName: string;
  };
  const registration = await page.request.post('/api/v1/auth/register', {
    data: { displayName: 'Recovery Test', email, password },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(registration.status()).toBe(201);
  const id = ((await registration.json()) as { id: string }).id;
  let verificationMail: string | null = null;
  await expect
    .poll(
      async () => {
        verificationMail = await mailText(
          request,
          email,
          'Verify your Make My Marriage email address',
        );
        return verificationMail !== null;
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  const verify = verificationMail!.match(/verify-email#token=([A-Za-z0-9_-]+)/)?.[1];
  if (!verify) throw new Error('Verification link was not delivered.');
  expect(
    (
      await page.request.post('/api/v1/auth/email/verify', {
        data: { token: verify },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(204);
  expect(
    (
      await page.request.post('/api/v1/auth/password/forgot', {
        data: { email },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(202);
  let resetMail: string | null = null;
  await expect
    .poll(
      async () => {
        resetMail = await mailText(request, email, 'Reset your Make My Marriage password');
        return resetMail !== null;
      },
      { timeout: 45_000 },
    )
    .toBe(true);
  const token = resetMail!.match(/reset-password#token=([A-Za-z0-9_-]+)/)?.[1];
  if (!token) throw new Error('Reset link was not delivered.');
  const body = { token, newPassword: 'A different valid phrase 456' };
  expect((await page.request.post('/api/v1/auth/password/reset', { data: body })).status()).toBe(
    403,
  );
  expect(
    sql(`SELECT count(*) FROM password_reset_tokens WHERE user_id='${id}' AND consumed_at IS NULL`),
  ).toBe('1');

  // Only the named isolated test database is changed; preserve normal developer data.
  sql(
    `UPDATE password_reset_tokens SET created_at=now()-interval '31 minutes', expires_at=now()-interval '1 minute' WHERE user_id='${id}' AND consumed_at IS NULL`,
  );
  const expired = await page.request.post('/api/v1/auth/password/reset', {
    data: body,
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(expired.status()).toBe(400);
  expect(((await expired.json()) as { code: string }).code).toBe('PASSWORD_RESET_TOKEN_INVALID');

  const allocation = spawnSync(
    'docker',
    ['exec', redis, 'redis-cli', 'INCR', 'e2e:recovery:reset-source-sequence'],
    { encoding: 'utf8' },
  );
  const sequence = Number(allocation.stdout.trim());
  if (
    allocation.status !== 0 ||
    !Number.isSafeInteger(sequence) ||
    sequence < 1 ||
    sequence > 0xfeffff
  ) {
    throw new Error('Could not allocate an isolated loopback address from test Redis.');
  }
  const sourceAddress = `127.${1 + (sequence >>> 16)}.${(sequence >>> 8) & 255}.${sequence & 255}`;
  const isolatedCsrfResponse = await fromIsolatedLoopback(
    sourceAddress,
    'GET',
    '/api/v1/auth/csrf',
  );
  expect(isolatedCsrfResponse.status).toBe(200);
  const isolatedCsrf = JSON.parse(isolatedCsrfResponse.body) as {
    token: string;
    headerName: string;
  };
  const isolatedCookie = isolatedCsrfResponse.headers['set-cookie']
    ?.find((cookie) => cookie.startsWith('SESSION='))
    ?.split(';')[0];
  expect(isolatedCookie).toBeTruthy();
  let limited = false;
  for (let attempt = 0; attempt < 25; attempt++) {
    const response = await fromIsolatedLoopback(
      sourceAddress,
      'POST',
      '/api/v1/auth/password/reset',
      { token: 'a'.repeat(43), newPassword: password },
      { Cookie: isolatedCookie!, [isolatedCsrf.headerName]: isolatedCsrf.token },
    );
    if (response.status === 429) {
      limited = true;
      expect(attempt).toBe(20);
      expect((JSON.parse(response.body) as { code: string }).code).toBe(
        'PASSWORD_RECOVERY_RATE_LIMITED',
      );
      break;
    }
    expect(response.status).toBe(400);
  }
  expect(limited).toBe(true);
});
