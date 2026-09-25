import { createRequire } from 'node:module';
import { describe, expect, test } from 'vitest';

// The build script is CommonJS; load it with Node's own require so its `require.main` guard keeps main() from
// running.
const require = createRequire(import.meta.url);
const {
    createAndroidEntries,
    createThirdPartyNoticeText,
    listCsprojFiles,
    parseGradleDependencies
} = require('../../../../build-scripts/generate-third-party-licenses.js');
const androidLibraries = require('../../../../build-scripts/licenses/android-libraries.json');

const gradle = `
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
    // implementation("commented:out:1.0")
}
`;

describe('generate-third-party-licenses (Android target)', () => {
    test('reads shipped Gradle dependencies and skips test-only ones', () => {
        expect(parseGradleDependencies(gradle)).toEqual([
            { group: 'androidx.core', artifact: 'core-ktx', version: '1.16.0' },
            { group: 'com.squareup.okhttp3', artifact: 'okhttp', version: '4.12.0' },
            { group: 'org.jetbrains.kotlinx', artifact: 'kotlinx-serialization-json', version: '1.8.0' }
        ]);
        expect(parseGradleDependencies('')).toEqual([]);
    });

    test('takes versions from Gradle when available', () => {
        const entries = createAndroidEntries(androidLibraries, parseGradleDependencies(gradle));
        const core = entries.find((entry) => entry.name === 'AndroidX Core KTX');
        expect(core.version).toBe('1.16.0');
        expect(core.sourceType).toBe('android');
        expect(core.license).toBe('Apache-2.0');
        const scanner = entries.find((entry) => entry.name.startsWith('Google code scanner'));
        expect(scanner.version).toBe('16.1.0');
    });

    test('lists the required Android libraries, all reviewed', () => {
        const entries = createAndroidEntries(androidLibraries);
        const names = entries.map((entry) => entry.name);
        for (const name of ['AndroidX WebKit', 'OkHttp', 'kotlinx.coroutines', 'kotlinx.serialization']) {
            expect(names).toContain(name);
        }
        expect(names.some((name) => name.startsWith('Google code scanner'))).toBe(true);
        expect(entries.filter((entry) => entry.needsReview)).toEqual([]);
        expect(new Set(entries.map((entry) => entry.id)).size).toBe(entries.length);
    });

    test('skips the .NET scan when the Dotnet folder is absent', () => {
        expect(listCsprojFiles('this/folder/does/not/exist')).toEqual([]);
    });

    test('writes an Android section in the notice text', () => {
        const text = createThirdPartyNoticeText('', createAndroidEntries(androidLibraries));
        expect(text).toContain('Android app components');
        expect(text).not.toContain('.NET and native bundled components');
        expect(text).toContain('OkHttp - 4.12.0 (Apache-2.0)');
    });
});
