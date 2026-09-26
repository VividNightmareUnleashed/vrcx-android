import { describe, expect, test } from 'vitest';

import { getGameLogMenuActions, hasGameLogMenu } from '../gameLogMenu';

describe('game log row menu actions (phone stand-in for the right-click menus)', () => {
    test('location rows offer the world actions of their instance', () => {
        const entry = { type: 'Location', location: 'wrld_00000000-0000-4000-8000-00000000a001:1~region(eu)' };
        expect(getGameLogMenuActions(entry)).toEqual({
            location: 'wrld_00000000-0000-4000-8000-00000000a001:1~region(eu)',
            copyText: '',
            openUrl: ''
        });
        expect(hasGameLogMenu(entry)).toBe(true);
    });

    test('portal spawns use the instance the portal leads to', () => {
        const entry = {
            type: 'PortalSpawn',
            location: 'wrld_00000000-0000-4000-8000-00000000a001:1',
            instanceId: 'wrld_00000000-0000-4000-8000-00000000a002:2'
        };
        expect(getGameLogMenuActions(entry).location).toBe('wrld_00000000-0000-4000-8000-00000000a002:2');
    });

    test('event and external rows can be copied', () => {
        expect(getGameLogMenuActions({ type: 'Event', data: 'Udon: door opened' })).toEqual({
            location: '',
            copyText: 'Udon: door opened',
            openUrl: ''
        });
        expect(getGameLogMenuActions({ type: 'External', message: 'OSC hello' }).copyText).toBe('OSC hello');
    });

    test('videos can be opened unless they come from a stream player without a link', () => {
        const youtube = { type: 'VideoPlay', videoId: 'YouTube', videoUrl: 'https://example.org/v' };
        expect(getGameLogMenuActions(youtube)).toEqual({
            location: '',
            copyText: 'https://example.org/v',
            openUrl: 'https://example.org/v'
        });
        for (const videoId of ['LSMedia', 'PopcornPalace']) {
            const entry = { type: 'VideoPlay', videoId, videoUrl: 'https://example.org/stream' };
            expect(getGameLogMenuActions(entry).openUrl).toBe('');
            expect(getGameLogMenuActions(entry).copyText).toBe('https://example.org/stream');
        }
    });

    test('string and image loads can be opened and copied', () => {
        for (const type of ['StringLoad', 'ImageLoad']) {
            const entry = { type, resourceUrl: 'https://example.org/data.json' };
            expect(getGameLogMenuActions(entry)).toEqual({
                location: '',
                copyText: 'https://example.org/data.json',
                openUrl: 'https://example.org/data.json'
            });
        }
    });

    test('joins, leaves and empty rows have no menu', () => {
        expect(hasGameLogMenu({ type: 'OnPlayerJoined', displayName: 'Aurora' })).toBe(false);
        expect(hasGameLogMenu({ type: 'OnPlayerLeft', displayName: 'Aurora' })).toBe(false);
        expect(hasGameLogMenu({ type: 'Event', data: '' })).toBe(false);
        expect(hasGameLogMenu(null)).toBe(false);
    });
});
