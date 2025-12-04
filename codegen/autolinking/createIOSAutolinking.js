import { createHybridObjectIntializer } from './ios/createHybridObjectInitializer.js';
import { createPodspecRubyExtension } from './ios/createPodspecRubyExtension.js';
import { createSwiftCxxBridge } from './ios/createSwiftCxxBridge.js';
import { createSwiftUmbrellaHeader } from './ios/createSwiftUmbrellaHeader.js';
export function createIOSAutolinking() {
    const podspecExtension = createPodspecRubyExtension();
    const swiftCxxBridge = createSwiftCxxBridge();
    const swiftUmbrellaHeader = createSwiftUmbrellaHeader();
    const hybridObjectInitializer = createHybridObjectIntializer();
    return {
        platform: 'ios',
        sourceFiles: [
            podspecExtension,
            ...swiftCxxBridge,
            swiftUmbrellaHeader,
            ...hybridObjectInitializer,
        ],
    };
}
