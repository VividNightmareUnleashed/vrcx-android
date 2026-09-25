import { describe, expect, test, vi } from 'vitest';

import { inviteMessageSlots, loginModeConfigs, webApi, wrapInteropForLogin } from '../dialogs-b.js';

// Preview-harness login modes (?login=0 / ?login=saved) and invite message fixtures.
function fakeApi() {
    return { callDotNetMethod: vi.fn(async () => 'default') };
}

const configRead = (key) => ['SQLite', 'ExecuteJson', ['SELECT value FROM configs WHERE key = @key', { '@key': key }]];

describe('harness login modes', () => {
    test('login=0 hides the last user and the saved accounts, so the Login page shows', async () => {
        const api = fakeApi();
        const wrapped = wrapInteropForLogin(api, '0');
        expect(await wrapped.callDotNetMethod(...configRead('config:lastuserloggedin'))).toBe('[]');
        expect(await wrapped.callDotNetMethod(...configRead('config:savedcredentials'))).toBe('[]');
        expect(await wrapped.callDotNetMethod(...configRead('config:vrcx_thememode'))).toBe('default');
        expect(api.callDotNetMethod).toHaveBeenCalledTimes(1);
    });

    test('login=saved lists two saved accounts and keeps changes to them', async () => {
        const wrapped = wrapInteropForLogin(fakeApi(), 'saved');
        const [[json]] = JSON.parse(await wrapped.callDotNetMethod(...configRead('config:savedcredentials')));
        expect(Object.keys(JSON.parse(json))).toHaveLength(2);

        await wrapped.callDotNetMethod('SQLite', 'ExecuteNonQuery', [
            'INSERT OR REPLACE INTO configs (key, value) VALUES (@key, @value)',
            new Map([
                ['@key', 'config:savedcredentials'],
                ['@value', '{}']
            ])
        ]);
        expect(await wrapped.callDotNetMethod(...configRead('config:savedcredentials'))).toBe('[["{}"]]');
        expect(loginModeConfigs('0').get('config:savedcredentials')).toBe('');
    });

    test('auth/user answers 401 and other requests fall through', async () => {
        const api = fakeApi();
        const wrapped = wrapInteropForLogin(api, '0');
        const response = JSON.parse(
            await wrapped.callDotNetMethod('WebApi', 'ExecuteJson', [
                JSON.stringify({ url: 'https://api.vrchat.cloud/api/1/auth/user', method: 'GET' })
            ])
        );
        expect(response.status).toBe(401);
        await wrapped.callDotNetMethod('WebApi', 'ExecuteJson', [
            JSON.stringify({ url: 'https://api.vrchat.cloud/api/1/config', method: 'GET' })
        ]);
        expect(api.callDotNetMethod).toHaveBeenCalledTimes(1);
    });
});

describe('invite message fixtures', () => {
    test('twelve slots with made-up ids, some still cooling down', () => {
        const slots = inviteMessageSlots('message');
        expect(slots).toHaveLength(12);
        expect(slots.map((slot) => slot.slot)).toEqual([...Array(12).keys()]);
        expect(slots.every((slot) => slot.id.startsWith('invm_00000000-'))).toBe(true);
        expect(slots.some((slot) => slot.remainingCooldownMinutes > 0)).toBe(true);
    });

    test('webApi answers the message and short name endpoints only', () => {
        expect(webApi('message/usr_00000000-0000-4000-8000-000000000001/request', new URLSearchParams(), 'GET')).toHaveLength(
            12
        );
        expect(
            webApi('instances/wrld_00000000-0000-4000-8000-00000000a001:12345~region(eu)/shortName', null, 'GET')
        ).toEqual({ shortName: 'prvw1234', secureName: 'prvwsecure' });
        expect(webApi('auth/user', null, 'GET')).toBeUndefined();
    });
});
