// Android-only strings and overrides of upstream strings (for example "right-click" → "long-press").
// Files are named <locale>.<part>.json (en.platform.json, en.shell.json, ...), one per part of the UI. They are deep-merged over the upstream locale messages by plugins/i18n.js when isAndroid.
const modules = import.meta.glob('./*.json', { eager: true, import: 'default' });

function deepMerge(target, source) {
    for (const [key, value] of Object.entries(source ?? {})) {
        if (value && typeof value === 'object' && !Array.isArray(value)) {
            target[key] = deepMerge(target[key] && typeof target[key] === 'object' ? target[key] : {}, value);
        } else {
            target[key] = value;
        }
    }
    return target;
}

/**
 * @param {string} locale
 * @returns {object} merged Android messages for the locale (empty object when none)
 */
export function getAndroidMessages(locale) {
    const merged = {};
    for (const [path, messages] of Object.entries(modules)) {
        const file = path.split('/').pop();
        if (file.startsWith(`${locale}.`)) {
            deepMerge(merged, messages);
        }
    }
    return merged;
}
