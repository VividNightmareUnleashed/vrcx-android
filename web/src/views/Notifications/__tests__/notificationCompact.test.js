import { describe, expect, test } from 'vitest';

import {
    getNotificationActionFlags,
    getNotificationMessageLines,
    hasSenderContent,
    isGroupId
} from '../notificationCompact';

describe('notification cell helpers', () => {
    test('group ids', () => {
        expect(isGroupId('grp_00000000-0000-4000-8000-000000000001')).toBe(true);
        expect(isGroupId('usr_00000000-0000-4000-8000-000000000001')).toBe(false);
        expect(isGroupId(undefined)).toBe(false);
    });

    test('user notifications have a sender, group notifications do not', () => {
        expect(hasSenderContent({ senderUserId: 'usr_1', senderUsername: 'Aurora' })).toBe(true);
        expect(hasSenderContent({ senderUsername: 'Aurora' })).toBe(true);
        expect(hasSenderContent({ link: 'user:usr_1', linkText: 'Aurora' })).toBe(true);
        expect(hasSenderContent({ senderUserId: 'grp_1', senderUsername: 'Preview Makers' })).toBe(false);
        expect(hasSenderContent({ type: 'group.announcement' })).toBe(false);
    });

    test('message lines follow the PC cell', () => {
        expect(getNotificationMessageLines({ title: 'Weekly meetup', message: 'Friday' })).toEqual([
            'Weekly meetup, Friday'
        ]);
        expect(getNotificationMessageLines({ title: 'Cobalt booped you!' })).toEqual(['Cobalt booped you!']);
        expect(getNotificationMessageLines({ message: 'Hello' })).toEqual(['Hello']);
        // The generated invite text is replaced by the invite's location.
        expect(
            getNotificationMessageLines({
                message: 'This is a generated invite to Neon Rooftops',
                details: { worldName: 'Neon Rooftops' }
            })
        ).toEqual([]);
        expect(
            getNotificationMessageLines({
                details: { inviteMessage: 'Join us', requestMessage: 'Can I come?', responseMessage: 'Later' }
            })
        ).toEqual(['Join us', 'Can I come?', 'Later']);
        expect(getNotificationMessageLines(null)).toEqual([]);
    });

    test('decline and delete-log buttons follow the PC rules', () => {
        expect(getNotificationActionFlags({ type: 'invite' })).toEqual({ showDecline: true, showDeleteLog: true });
        expect(getNotificationActionFlags({ type: 'friendRequest' })).toEqual({
            showDecline: true,
            showDeleteLog: false
        });
        for (const type of ['message', 'boop', 'inviteResponse', 'group.invite', 'instance.closed']) {
            expect(getNotificationActionFlags({ type }).showDecline).toBe(false);
        }
        expect(getNotificationActionFlags({ type: 'economy.alert', link: 'economy.wallet' }).showDecline).toBe(false);
    });
});
