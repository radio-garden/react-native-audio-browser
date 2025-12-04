import { NitroConfig } from '../../config/NitroConfig.js';
import { getHybridObjectName } from '../getHybridObjectName.js';
export function createJNIHybridObjectRegistration({ hybridObjectName, jniClassName, }) {
    const { JHybridTSpec } = getHybridObjectName(hybridObjectName);
    const jniNamespace = NitroConfig.current.getAndroidPackage('c++/jni', jniClassName);
    return {
        requiredImports: [
            { name: `${JHybridTSpec}.hpp`, language: 'c++', space: 'user' },
            {
                name: 'NitroModules/DefaultConstructableObject.hpp',
                language: 'c++',
                space: 'system',
            },
        ],
        cppCode: `
HybridObjectRegistry::registerHybridObjectConstructor(
  "${hybridObjectName}",
  []() -> std::shared_ptr<HybridObject> {
    static DefaultConstructableObject<${JHybridTSpec}::javaobject> object("${jniNamespace}");
    auto instance = object.create();
    return instance->cthis()->shared();
  }
);
      `.trim(),
    };
}
