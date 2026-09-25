// Tap routing helpers shared by the touch tooltip (TooltipWrapper) and the touch hover card (docs/DESIGN.md §3.3):
// a tap that lands on something the user can activate belongs to that element, never to the surrounding
// information trigger.

export const INTERACTIVE_SELECTOR = [
    'button',
    'a[href]',
    'input',
    'select',
    'textarea',
    'label',
    '[contenteditable="true"]',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="tab"]',
    '[role="checkbox"]',
    '[role="switch"]',
    '[role="option"]',
    '[data-slot="context-menu-trigger"]'
].join(', ');

/**
 * @param {EventTarget | null} target
 * @returns {Element | null}
 */
function asElement(target) {
    if (!target) return null;
    if (target.nodeType === 1) return /** @type {Element} */ (target);
    return /** @type {Node} */ (target).parentElement ?? null;
}

/**
 * Whether a tap on `target` belongs to something tappable inside `trigger` (a button, a link, a clickable name) rather
 * than to the trigger itself. The trigger element is not checked, so a trigger that is itself a button still counts as
 * the trigger. VRCX marks clickable spans with `cursor-pointer`; `cursor` is inherited, so a pointer cursor on the
 * target that the trigger itself does not have means a clickable element sits between them. Only runs on a tap.
 *
 * @param {EventTarget | null} target
 * @param {Element | null} trigger
 * @returns {boolean}
 */
export function isInteractiveDescendant(target, trigger) {
    const element = asElement(target);
    if (!element || !trigger || element === trigger) return false;
    for (let el = element; el && el !== trigger; el = el.parentElement) {
        if (el.matches(INTERACTIVE_SELECTOR)) return true;
    }
    const view = trigger.ownerDocument?.defaultView;
    if (!view?.getComputedStyle) return false;
    return view.getComputedStyle(element).cursor === 'pointer' && view.getComputedStyle(trigger).cursor !== 'pointer';
}
