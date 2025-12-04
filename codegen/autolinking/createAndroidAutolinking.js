import { createCMakeExtension } from './android/createCMakeExtension.js';
import { createGradleExtension } from './android/createGradleExtension.js';
import { createHybridObjectIntializer } from './android/createHybridObjectInitializer.js';
export function createAndroidAutolinking(allFiles) {
    const cmakeExtension = createCMakeExtension(allFiles);
    const gradleExtension = createGradleExtension();
    const hybridObjectInitializer = createHybridObjectIntializer();
    return {
        platform: 'android',
        jniHybridRegistrations: [],
        sourceFiles: [cmakeExtension, gradleExtension, ...hybridObjectInitializer],
    };
}
