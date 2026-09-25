import { createI18n } from 'vue-i18n';

import { getLocalizedStrings } from '../localization';
import { getAndroidMessages } from '../platform/android/i18n';
import { isAndroid } from '../shared/utils/platform';

const FALLBACK_LOCALE = 'en';

const i18n = createI18n({
    locale: FALLBACK_LOCALE,
    fallbackLocale: FALLBACK_LOCALE,
    legacy: false,
    globalInjection: false,
    missingWarn: false,
    warnHtmlMessage: false,
    fallbackWarn: false
});

async function loadLocalizedStrings(code) {
    const localesToLoad = code === FALLBACK_LOCALE ? [FALLBACK_LOCALE] : [FALLBACK_LOCALE, code];

    for (const locale of localesToLoad) {
        const messages = await getLocalizedStrings(locale);
        i18n.global.setLocaleMessage(locale, messages);
        if (isAndroid) {
            i18n.global.mergeLocaleMessage(locale, getAndroidMessages(locale));
        }
    }
}

async function updateLocalizedStrings() {
    await loadLocalizedStrings(i18n.global.locale.value);
}

/**
 * Translate a single key using a specific locale without switching global UI language.
 *
 * @param {string} locale
 * @param {string} key
 * @param {import('vue-i18n').NamedValue=} params
 * @returns {Promise<string>}
 */
async function tForLocale(locale, key, params = {}) {
    await loadLocalizedStrings(locale);
    return i18n.global.t(key, params, { locale });
}

export { i18n, loadLocalizedStrings, tForLocale, updateLocalizedStrings };
