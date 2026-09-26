// Context menus on Android stay whole and on screen near the edges (touchPlacement.js): flip before shifting, shift
// on both axes, and keep clear of the edge, the system bars and the keyboard.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';

const platform = vi.hoisted(() => ({ isAndroid: true }));
vi.mock('@/shared/utils/platform', () => ({
    get isAndroid() {
        return platform.isAndroid;
    }
}));

import { ContextMenu, ContextMenuContent, ContextMenuItem, ContextMenuTrigger } from '..';
import { getTouchMenuCollisionPadding, getTouchMenuPositioning, TOUCH_MENU_EDGE_MARGIN } from '../touchPlacement';

function setInsets({ top = 0, right = 0, bottom = 0, left = 0, ime = 0 } = {}) {
    const style = document.documentElement.style;
    style.setProperty('--safe-top', `${top}px`);
    style.setProperty('--safe-right', `${right}px`);
    style.setProperty('--safe-bottom', `${bottom}px`);
    style.setProperty('--safe-left', `${left}px`);
    style.setProperty('--ime-bottom', `${ime}px`);
}

function clearInsets() {
    for (const name of ['--safe-top', '--safe-right', '--safe-bottom', '--safe-left', '--ime-bottom']) {
        document.documentElement.style.removeProperty(name);
    }
}

describe('getTouchMenuCollisionPadding', () => {
    afterEach(clearInsets);

    it('keeps the edge margin without insets', () => {
        const m = TOUCH_MENU_EDGE_MARGIN;
        expect(getTouchMenuCollisionPadding()).toEqual({ top: m, right: m, bottom: m, left: m });
        expect(getTouchMenuCollisionPadding(null)).toEqual({ top: m, right: m, bottom: m, left: m });
    });

    it('adds the system bars, and the keyboard when it is taller than the gesture bar', () => {
        const m = TOUCH_MENU_EDGE_MARGIN;
        setInsets({ top: 24, right: 12, bottom: 16, left: 30 });
        expect(getTouchMenuCollisionPadding()).toEqual({ top: m + 24, right: m + 12, bottom: m + 16, left: m + 30 });

        setInsets({ top: 24, bottom: 16, ime: 300 });
        expect(getTouchMenuCollisionPadding().bottom).toBe(m + 300);
    });

    it('prioritises the position (flip first, then shift on both axes)', () => {
        expect(getTouchMenuPositioning().prioritizePosition).toBe(true);
    });
});

function mountMenu(contentProps = {}) {
    const Host = defineComponent({
        setup: () => () =>
            h(ContextMenu, null, () => [
                h(ContextMenuTrigger, null, () => h('div', { class: 'row' }, 'Row')),
                h(ContextMenuContent, contentProps, () => h(ContextMenuItem, null, () => 'Action'))
            ])
    });
    return mount(Host, { attachTo: document.body });
}

async function openMenu(wrapper) {
    await wrapper.find('.row').trigger('contextmenu', { clientX: 350, clientY: 700 });
    await flushPromises();
    await nextTick();
    return wrapper.findComponent({ name: 'PopperContent' });
}

describe('ContextMenuContent placement', () => {
    beforeEach(() => {
        platform.isAndroid = true;
        setInsets({ top: 24, bottom: 16 });
    });

    afterEach(() => {
        clearInsets();
        document.body.innerHTML = '';
    });

    it('Android: flips first and keeps clear of the edges and the system bars', async () => {
        const wrapper = mountMenu();
        const popper = await openMenu(wrapper);
        expect(popper.exists()).toBe(true);
        expect(popper.props('prioritizePosition')).toBe(true);
        expect(popper.props('avoidCollisions')).toBe(true);
        expect(popper.props('collisionPadding')).toEqual({
            top: TOUCH_MENU_EDGE_MARGIN + 24,
            right: TOUCH_MENU_EDGE_MARGIN,
            bottom: TOUCH_MENU_EDGE_MARGIN + 16,
            left: TOUCH_MENU_EDGE_MARGIN
        });
        wrapper.unmount();
    });

    it('reads the insets again when the menu opens', async () => {
        const wrapper = mountMenu();
        setInsets({ top: 0, bottom: 0, left: 40 });
        const popper = await openMenu(wrapper);
        expect(popper.props('collisionPadding')).toMatchObject({ left: TOUCH_MENU_EDGE_MARGIN + 40, top: 8 });
        wrapper.unmount();
    });

    it('lets a caller override the placement', async () => {
        const wrapper = mountMenu({ prioritizePosition: false, collisionPadding: 2 });
        const popper = await openMenu(wrapper);
        expect(popper.props('prioritizePosition')).toBe(false);
        expect(popper.props('collisionPadding')).toBe(2);
        wrapper.unmount();
    });

    it('desktop builds keep reka defaults', async () => {
        platform.isAndroid = false;
        const wrapper = mountMenu();
        const popper = await openMenu(wrapper);
        expect(popper.props('prioritizePosition')).toBe(false);
        expect(popper.props('collisionPadding')).toBe(0);
        wrapper.unmount();
    });
});
