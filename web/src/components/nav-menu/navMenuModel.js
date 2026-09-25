// The mounted NavMenu publishes its model here so the phone dock (docs/DESIGN.md §2.1) shows the same entries,
// active state, notification dots and actions without loading the nav config a second time.
import { shallowRef } from 'vue';

const current = shallowRef(null);

/**
 * @param {object} model
 * @returns {() => void} Unpublish
 */
export function publishNavMenuModel(model) {
    current.value = model;
    return () => {
        if (current.value === model) {
            current.value = null;
        }
    };
}

/**
 * @returns {import('vue').ShallowRef<object | null>} The model of the mounted NavMenu (null before it mounts)
 */
export function useNavMenuModel() {
    return current;
}
