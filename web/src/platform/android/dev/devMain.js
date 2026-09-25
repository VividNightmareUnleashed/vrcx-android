// Entry of the dev-only Android preview harness: fake host first, then the real app, then the companion state.
import { installMockBridge, mockCompanionState } from './mockBridge.js';

const params = new URLSearchParams(location.search);
const { companionMode } = installMockBridge({ params });

// The app reads the platform globals while its modules evaluate, so it is imported only after the fake host exists.
await import('../../../app.js');

// Until the platform package feeds the companion store from native events, the harness applies the state itself.
const { useCompanionStore } = await import('../companionStore.js');
useCompanionStore().applyState(mockCompanionState(companionMode));
