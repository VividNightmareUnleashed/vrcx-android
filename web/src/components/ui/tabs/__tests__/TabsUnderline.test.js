// TabsUnderline's tab strip classes: the sticky and background props merge (cn) instead of fighting over the
// background, and headerClass is merged last.
import { afterEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';

import { i18n } from '../../../../plugins/i18n';
import TabsUnderline from '../TabsUnderline.vue';

const items = [
    { value: 'info', label: 'Info' },
    { value: 'groups', label: 'Groups' }
];

function headerClasses(props = {}) {
    const wrapper = mount(TabsUnderline, {
        props: { items, ...props },
        global: { plugins: [i18n] },
        attachTo: document.body
    });
    // The header holds the scroll arrows and the tab viewport.
    const classes = wrapper.find('.tabs-underline-viewport').element.parentElement.className.split(/\s+/);
    wrapper.unmount();
    return classes;
}

describe('TabsUnderline header', () => {
    afterEach(() => {
        document.body.innerHTML = '';
    });

    it('keeps the PC card look with the background prop alone', () => {
        const classes = headerClasses({ background: true });
        expect(classes).toEqual(expect.arrayContaining(['rounded-xl', 'overflow-hidden', 'bg-(--profile-card)']));
        expect(classes).not.toContain('sticky');
        expect(classes).not.toContain('bg-background');
    });

    it('gives a sticky card strip an opaque base with the card tint layered over it', () => {
        const classes = headerClasses({ background: true, sticky: true });
        expect(classes).toEqual(
            expect.arrayContaining([
                'sticky',
                'top-0',
                'z-10',
                'bg-background',
                'bg-[image:linear-gradient(var(--profile-card),var(--profile-card))]',
                'rounded-xl'
            ])
        );
        // Two background colours on one element: one of them would lose.
        expect(classes).not.toContain('bg-(--profile-card)');
    });

    it('keeps a plain sticky strip on the page background', () => {
        const classes = headerClasses({ sticky: true });
        expect(classes).toEqual(expect.arrayContaining(['sticky', 'top-0', 'z-10', 'bg-background']));
    });

    it('merges headerClass last, so it can move the sticky offset and the stacking order', () => {
        const classes = headerClasses({ sticky: true, background: true, headerClass: '-top-3 z-40' });
        expect(classes).toEqual(expect.arrayContaining(['sticky', '-top-3', 'z-40']));
        expect(classes).not.toContain('top-0');
        expect(classes).not.toContain('z-10');
    });
});
